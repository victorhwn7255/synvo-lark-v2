package synvo.billing;

import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Authorized, user-triggered daily ingestion, with no report or agent dependency. */
public final class DailySpendingFacade implements AutoCloseable {
    private final DailySpendingStore store;
    private final BillingSource source;
    private final String owner;
    private final String revision;
    private final boolean enabled;
    private final YearMonth historyStart;
    private final Clock clock;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("billing-daily").daemon().factory());

    public DailySpendingFacade(DailySpendingStore store, BillingSource source, String owner, String revision,
            boolean enabled, YearMonth historyStart, Clock clock) {
        this.store = store; this.source = source; this.owner = owner; this.revision = revision + "/daily/" + BillingData.MAPPING_VERSION;
        this.enabled = enabled; this.historyStart = historyStart; this.clock = clock;
    }
    public DailySpendingStore.Run refresh(String caller, String key) {
        authorize(caller);
        if (key == null || !key.matches("[a-zA-Z0-9_-]{1,100}")) throw new BillingException(BillingException.Reason.INVALID_REQUEST);
        var claim = store.claim(owner, revision, key, historyStart, clock.instant());
        if (claim.acquired()) {
            try { worker.execute(() -> retrieve(claim.run())); }
            catch (RuntimeException error) {
                store.finish(owner, revision, claim.run().id(), BillingException.Reason.INTERRUPTED, clock.instant());
                throw new BillingException(BillingException.Reason.INTERRUPTED);
            }
        }
        return claim.run();
    }
    public DailySpendingStore.Feed read(String caller, Consumer<BillingData.CostRow> sink) {
        authorize(caller);
        return store.read(owner, revision, historyStart, clock.instant(), sink);
    }
    private void authorize(String caller) {
        if (owner == null || owner.isBlank() || !owner.equals(caller)) throw new BillingException(BillingException.Reason.FORBIDDEN);
        if (!enabled) throw new BillingException(BillingException.Reason.DISABLED);
    }
    private void retrieve(DailySpendingStore.Run run) {
        BillingException.Reason failure = null;
        try (var session = source.open()) {
            long[] totalRows = {0};
            long totalBytes = 0;
            for (var period : run.plan()) {
                check(run);
                var version = store.stagePartition(owner, revision, run.id(), period, clock.instant());
                try {
                    var batch = new ArrayList<BillingData.CostRow>(250);
                    long[] count = {0};
                    var partition = session.period(period.first(), period.through(), row -> {
                        check(run);
                        if (!row.dataset().equals("month:" + YearMonth.from(period.first()))) throw new BillingException(BillingException.Reason.SOURCE_INVALID);
                        if (++totalRows[0] > 1_000_000) throw new BillingException(BillingException.Reason.LIMIT_EXCEEDED);
                        count[0]++;
                        batch.add(row);
                        if (batch.size() == 250) { store.stageRows(owner, revision, run.id(), version, batch, clock.instant()); batch.clear(); }
                    });
                    if (!partition.dataset().equals("month:" + YearMonth.from(period.first())) || partition.parts().size() > 100
                            || count[0] != partition.parts().stream().mapToLong(BillingData.Part::rows).sum()) {
                        throw new BillingException(BillingException.Reason.SOURCE_INVALID);
                    }
                    totalBytes += partition.parts().stream().mapToLong(BillingData.Part::bytes).sum();
                    if (totalBytes > 250L * 1024 * 1024) throw new BillingException(BillingException.Reason.LIMIT_EXCEEDED);
                    if (!batch.isEmpty()) store.stageRows(owner, revision, run.id(), version, batch, clock.instant());
                    check(run);
                    store.publish(owner, revision, run.id(), version, partition, clock.instant());
                } catch (BillingException error) {
                    if (error.reason() != BillingException.Reason.SOURCE_UNAVAILABLE && error.reason() != BillingException.Reason.SOURCE_INVALID
                            && error.reason() != BillingException.Reason.UNSUPPORTED_CURRENCY && error.reason() != BillingException.Reason.UNSUPPORTED_PRECISION) throw error;
                    // An unavailable old month remains a gap; it must not prevent fetching accessible newer history.
                    store.skip(owner, revision, run.id(), version, error.reason(), clock.instant());
                }
            }
        } catch (BillingException error) { failure = error.reason(); }
        catch (RuntimeException error) { failure = BillingException.Reason.SOURCE_UNAVAILABLE; }
        store.finish(owner, revision, run.id(), failure, clock.instant());
    }
    private void check(DailySpendingStore.Run run) {
        if (Thread.currentThread().isInterrupted()) throw new BillingException(BillingException.Reason.INTERRUPTED);
        if (!clock.instant().isBefore(run.startedAt().plus(Duration.ofMinutes(30)))) throw new BillingException(BillingException.Reason.LIMIT_EXCEEDED);
    }
    @Override public void close() { worker.shutdownNow(); }
}
