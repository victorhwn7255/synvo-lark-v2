package synvo.billing;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Owns billing authorization, one durable import and immutable evidence, independently of agent execution. */
public final class BillingInsightsFacade implements AutoCloseable {
    private final BillingStore store;
    private final BillingSource source;
    private final String owner;
    private final String revision;
    private final boolean enabled;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, ImportControl> imports = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("billing-import").daemon().factory());

    public BillingInsightsFacade(BillingStore store, BillingSource source, String owner, String revision, boolean enabled, Clock clock) {
        this.store = store; this.source = source; this.owner = owner; this.revision = revision; this.enabled = enabled; this.clock = clock;
    }

    public boolean available(String caller) {
        authorize(caller); return enabled;
    }

    public void start() { if (enabled) store.recoverAndCleanup(clock.instant()); }
    public void cleanup() { store.cleanup(clock.instant()); }

    public BillingData.Snapshot request(String caller, String sourceId, BillingData.Range range, String idempotencyKey) {
        authorize(caller);
        if (!enabled) throw new BillingException(BillingException.Reason.DISABLED);
        if (!BillingData.SOURCE_ID.equals(sourceId) || range == null || idempotencyKey == null
                || !idempotencyKey.matches("[a-zA-Z0-9_-]{1,100}")) throw new BillingException(BillingException.Reason.INVALID_REQUEST);
        range.validate(clock);
        var claim = store.claim(caller, revision, idempotencyKey, range, clock.instant());
        if (claim.acquired()) {
            imports.put(claim.snapshot().id(), new ImportControl());
            try { worker.execute(() -> run(claim.snapshot())); }
            catch (RuntimeException exception) {
                imports.remove(claim.snapshot().id());
                store.fail(owner, revision, claim.snapshot().id(), BillingException.Reason.INTERRUPTED, clock.instant());
                throw new BillingException(BillingException.Reason.INTERRUPTED);
            }
        }
        return claim.snapshot();
    }

    public BillingData.Snapshot inspect(String caller, UUID id) {
        authorize(caller);
        return store.find(caller, revision, id, clock.instant()).orElseThrow(() -> new BillingException(BillingException.Reason.NOT_FOUND));
    }

    public BillingData.Summary summary(String caller, UUID id) {
        var snapshot = inspect(caller, id);
        if (snapshot.summary() == null) throw new BillingException(BillingException.Reason.NOT_READY);
        return snapshot.summary();
    }

    public List<BillingData.CostRow> evidence(String caller, UUID id, int offset, int limit) {
        summary(caller, id);
        if (offset < 0 || offset > 1_000_000 || limit < 1 || limit > 100) throw new BillingException(BillingException.Reason.INVALID_REQUEST);
        return store.evidence(caller, revision, id, offset, limit, clock.instant());
    }

    /** Streams a published snapshot to application-owned aggregation, never a caller-supplied query. */
    public void visitEvidence(String caller, UUID id, Consumer<BillingData.CostRow> sink) {
        summary(caller, id);
        store.visitEvidence(caller, revision, id, clock.instant(), sink);
    }

    /** Cooperative stop: the durable claim remains held until provider-session closure. */
    public boolean stop(String caller, UUID id) {
        inspect(caller, id);
        ImportControl control = imports.get(id);
        if (control == null) return false;
        synchronized (control) {
            if (control.finished) return false;
            control.stopped = true;
            return true;
        }
    }

    private void authorize(String caller) {
        if (owner == null || owner.isBlank() || !owner.equals(caller)) throw new BillingException(BillingException.Reason.FORBIDDEN);
    }

    private void run(BillingData.Snapshot snapshot) {
        ImportControl control = imports.get(snapshot.id());
        try {
            BillingData.Summary summary;
            try (var session = source.open()) {
            checkStopped(snapshot.id());
            var parts = new ArrayList<BillingData.Partition>();
            var limitations = new LinkedHashSet<String>();
            var selected = new BillingAccounting();
            var baseline = new BillingAccounting();
            var invoiceRefs = new LinkedHashSet<String>();
            for (var month : snapshot.range().selected()) {
                var partition = month(session, snapshot.id(), month);
                parts.add(partition); invoiceRefs.addAll(partition.invoiceIds());
                store.visitStaging(owner, revision, snapshot.id(), partition.dataset(), selected::add);
                notePartition(partition, limitations);
            }
            boolean baselineComplete = snapshot.range().comparison();
            for (var month : snapshot.range().baseline()) {
                try {
                    var partition = month(session, snapshot.id(), month);
                    parts.add(partition);
                    store.visitStaging(owner, revision, snapshot.id(), partition.dataset(), baseline::add);
                    if (!partition.attributionComplete()) baselineComplete = false;
                } catch (BillingException exception) {
                    store.discardDataset(owner, revision, snapshot.id(), "month:" + month);
                    baselineComplete = false;
                    limitations.add("BASELINE_UNAVAILABLE");
                    break;
                }
            }
            var invoices = new ArrayList<BillingData.Invoice>();
            var bridges = new ArrayList<BillingData.Reconciliation>();
            checkStopped(snapshot.id());
            if (invoiceRefs.isEmpty()) limitations.add("INVOICE_UNAVAILABLE");
            else {
                try {
                    LocalDate end = snapshot.range().last().plusMonths(2).atEndOfMonth();
                    if (end.isAfter(LocalDate.now(clock))) end = LocalDate.now(clock);
                    invoices.addAll(session.invoices(invoiceRefs, snapshot.range().first().atDay(1), end));
                    Set<String> found = new LinkedHashSet<>();
                    for (var invoice : invoices) {
                        checkStopped(snapshot.id());
                        found.add(invoice.id());
                        String dataset = "invoice:" + invoice.id();
                        try {
                            var partition = retrieve(snapshot.id(), dataset, sink -> session.invoice(invoice.id(), sink));
                            parts.add(partition);
                            var charges = new BillingAccounting();
                            store.visitStaging(owner, revision, snapshot.id(), dataset, charges::add);
                            bridges.add(BillingAccounting.reconcile(invoice, charges.totals().sourceTotal(), partition.attributionComplete()));
                        } catch (BillingException exception) {
                            store.discardDataset(owner, revision, snapshot.id(), dataset);
                            bridges.add(new BillingData.Reconciliation(invoice.id(), BillingData.ReconciliationStatus.UNAVAILABLE, null, null));
                        }
                    }
                    for (String missing : invoiceRefs) if (!found.contains(missing)) {
                        bridges.add(new BillingData.Reconciliation(missing, BillingData.ReconciliationStatus.UNAVAILABLE, null, null));
                    }
                } catch (BillingException exception) { limitations.add("INVOICE_UNAVAILABLE"); }
            }
            if (bridges.stream().anyMatch(b -> b.status() != BillingData.ReconciliationStatus.MATCHED)) limitations.add("RECONCILIATION_INCOMPLETE");
            var totals = selected.totals();
            if (!totals.completeAttribution()) limitations.add("UNRESOLVED_ATTRIBUTION");
            if (snapshot.range().comparison() && !baselineComplete) limitations.add("BASELINE_UNAVAILABLE");
            // Closed months can still be corrected upstream; completeness is not finality.
            limitations.add("SOURCE_FINALITY_NOT_ESTABLISHED");
            limitations.add("OPTIMIZATION_REQUIRES_UTILIZATION_AND_COMMITMENT_EVIDENCE");
            var prior = baselineComplete ? baseline.totals() : null;
            var comparison = prior != null && totals.completeAttribution()
                    ? BillingAccounting.compare(totals.azureTotal(), prior.azureTotal()) : null;
            summary = new BillingData.Summary("USD", BillingData.BASIS, BillingData.MAPPING_VERSION,
                    BillingData.CALCULATION_VERSION, totals, prior, comparison, parts, bridges, invoices, limitations);
            if (!clock.instant().isBefore(snapshot.createdAt().plusSeconds(1800))) throw new BillingException(BillingException.Reason.LIMIT_EXCEEDED);
            }
            synchronized (control) {
                checkStopped(snapshot.id());
                store.publish(owner, revision, snapshot.id(), summary, summary.invoices(), clock.instant());
                control.finished = true;
            }
        } catch (BillingException exception) {
            store.fail(owner, revision, snapshot.id(), exception.reason(), clock.instant());
        } catch (RuntimeException exception) {
            store.fail(owner, revision, snapshot.id(), BillingException.Reason.STORAGE_FAILURE, clock.instant());
        } finally {
            synchronized (control) { control.finished = true; }
            imports.remove(snapshot.id(), control);
        }
    }

    private static void notePartition(BillingData.Partition partition, Set<String> limitations) {
        if (!partition.attributionComplete()) limitations.add("UNRESOLVED_ATTRIBUTION");
        if (!partition.datesWithinPartition()) limitations.add("CHARGE_DATES_DIFFER_FROM_RETRIEVAL_PERIOD");
    }

    private BillingData.Partition month(BillingSource.Session session, UUID id, YearMonth month) {
        return retrieve(id, "month:" + month, sink -> session.month(month, sink));
    }

    private BillingData.Partition retrieve(UUID id, String dataset, Retrieval retrieval) {
        checkStopped(id);
        var batch = new ArrayList<BillingData.CostRow>();
        long[] count = {0};
        var partition = retrieval.read(row -> {
            checkStopped(id);
            if (!dataset.equals(row.dataset())) throw new BillingException(BillingException.Reason.SOURCE_INVALID);
            batch.add(row); count[0]++;
            if (batch.size() == 250) { store.stage(owner, revision, id, List.copyOf(batch)); batch.clear(); }
        });
        checkStopped(id);
        if (!dataset.equals(partition.dataset()) || partition.parts().stream().mapToLong(BillingData.Part::rows).sum() != count[0]) {
            throw new BillingException(BillingException.Reason.SOURCE_INVALID);
        }
        if (!batch.isEmpty()) store.stage(owner, revision, id, batch);
        return partition;
    }

    private void checkStopped(UUID id) {
        ImportControl control = imports.get(id);
        if (Thread.currentThread().isInterrupted() || (control != null && control.stopped)) {
            throw new BillingException(BillingException.Reason.INTERRUPTED);
        }
    }

    private static final class ImportControl {
        private volatile boolean stopped;
        private boolean finished;
    }

    @FunctionalInterface private interface Retrieval { BillingData.Partition read(Consumer<BillingData.CostRow> sink); }
    @Override public void close() { worker.shutdownNow(); }
}
