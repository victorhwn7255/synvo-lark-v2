package synvo.billingworkflow;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import synvo.billing.BillingData;
import synvo.billing.BillingException;
import synvo.billing.BillingInsightsFacade;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Owns the private, reproducible file package; never fetches Azure or accepts a caller path. */
public final class BillingAnalysisPackage {
    private static final long MAX_BYTES = 512L * 1024 * 1024;
    private static final List<String> INPUT_FILES = List.of("evidence.jsonl", "facts.json", "manifest.json");
    private static final Set<String> DIMENSIONS = Set.of("subscriptionname", "productname", "resourcegroup",
            "resourceid", "resourcelocation", "billingperiodstartdate", "billingperiodenddate",
            "serviceperiodstartdate", "serviceperiodenddate", "sourceCostScale");
    private final BillingInsightsFacade billing;
    private final Path root;
    private final Clock clock;
    private final ObjectMapper mapper;

    public BillingAnalysisPackage(BillingInsightsFacade billing, Path root, Clock clock, ObjectMapper mapper) {
        this.billing = billing; this.root = root; this.clock = clock; this.mapper = mapper;
    }

    public synchronized Prepared prepare(String owner, UUID snapshotId, UUID reportId, UUID attemptId) {
        return prepare(owner, snapshotId, reportId, attemptId, requireSnapshot(owner, snapshotId).expiresAt());
    }
    public synchronized Prepared prepare(String owner, UUID snapshotId, UUID reportId, UUID attemptId, Instant workExpiresAt) {
        var snapshot = requireSnapshot(owner, snapshotId);
        Instant expiresAt = snapshot.expiresAt().isBefore(workExpiresAt) ? snapshot.expiresAt() : workExpiresAt;
        if (!clock.instant().isBefore(expiresAt)) throw failure(BillingException.Reason.NOT_FOUND);
        if (reportId == null || attemptId == null) throw failure(BillingException.Reason.INVALID_REQUEST);
        Path staging = null;
        try {
            requireRoot();
            Path report = directory(root, reportId.toString());
            Path input = report.resolve("input");
            if (Files.exists(input, LinkOption.NOFOLLOW_LINKS)) {
                requireDirectory(input);
                Path manifest = input.resolve("manifest.json");
                if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
                    var previous = mapper.readTree(readFile(manifest, 1024 * 1024));
                    if (!snapshotId.toString().equals(previous.path("snapshotId").stringValue())
                            || !reportId.toString().equals(previous.path("reportId").stringValue())) {
                        throw failure(BillingException.Reason.INVALID_REQUEST);
                    }
                }
            }
            staging = Files.createTempDirectory(report, ".preparing-");
            sharedDirectory(staging);
            var amounts = new Accumulator(snapshot);
            long[] bytes = {0};
            Path evidence = staging.resolve("evidence.jsonl");
            try (var out = Files.newOutputStream(evidence, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)) {
                billing.visitEvidence(owner, snapshotId, row -> {
                    amounts.add(row);
                    write(out, row(row, amounts), bytes);
                });
            }
            var facts = amounts.finish();
            writeFile(staging.resolve("facts.json"), factDocument(facts), bytes);
            var hashes = new LinkedHashMap<String, String>();
            hashes.put("evidence.jsonl", hash(evidence));
            hashes.put("facts.json", hash(staging.resolve("facts.json")));
            var manifest = new LinkedHashMap<String, Object>();
            manifest.put("version", "billing-workspace-v1"); manifest.put("snapshotId", snapshotId.toString());
            manifest.put("reportId", reportId.toString()); manifest.put("scopeRevision", snapshot.revision());
            manifest.put("selectedMonths", snapshot.range().selected().stream().map(Object::toString).toList());
            manifest.put("baselineMonths", snapshot.range().baseline().stream().map(Object::toString).toList());
            manifest.put("currency", snapshot.summary().currency()); manifest.put("basis", snapshot.summary().basis());
            manifest.put("mappingVersion", snapshot.summary().mappingVersion());
            manifest.put("calculationVersion", snapshot.summary().calculationVersion());
            manifest.put("createdAt", snapshot.createdAt().toString()); manifest.put("expiresAt", expiresAt.toString());
            manifest.put("baselineMonthlyCosts", snapshot.summary().baseline() == null ? Map.of() : decimals(snapshot.summary().baseline().months()));
            manifest.put("limitations", snapshot.summary().limitations().stream().sorted().toList());
            manifest.put("rowCount", amounts.rowCount); manifest.put("inputHashes", new TreeMap<>(hashes));
            manifest.put("invoices", snapshot.summary().invoices().stream().map(invoice -> Map.of(
                    "id", invoice.id(), "first", invoice.first().toString(), "last", invoice.last().toString(),
                    "documentType", invoice.documentType(), "ambiguous", invoice.ambiguous(),
                    "amounts", decimals(invoice.amounts()))).toList());
            manifest.put("reconciliation", snapshot.summary().reconciliation().stream().map(bridge -> {
                var value = new LinkedHashMap<String, Object>();
                value.put("invoiceId", bridge.invoiceId()); value.put("status", bridge.status());
                value.put("sourceCharges", decimal(bridge.sourceCharges())); value.put("residual", decimal(bridge.residual()));
                return value;
            }).toList());
            manifest.put("sources", snapshot.summary().partitions().stream().map(part -> Map.of(
                    "dataset", part.dataset(), "role", amounts.role(part.dataset()),
                    "sourceVersion", part.sourceVersion(), "retrievedAt", part.retrievedAt().toString(),
                    "attributionComplete", part.attributionComplete(), "datesWithinPartition", part.datesWithinPartition(),
                    "parts", part.parts())).toList());
            writeFile(staging.resolve("manifest.json"), manifest, bytes);
            hashes.put("manifest.json", hash(staging.resolve("manifest.json")));
            requireSnapshot(owner, snapshotId);
            requireDirectory(report);
            if (Files.exists(input, LinkOption.NOFOLLOW_LINKS)) removeInput(input);
            Files.move(staging, input, StandardCopyOption.ATOMIC_MOVE);
            staging = null;
            Path attempt = directory(directory(report, "attempts"), attemptId.toString());
            directory(attempt, "work"); directory(attempt, "output"); directory(report, "published");
            return new Prepared(owner, snapshotId, reportId, attemptId, snapshot.revision(),
                    expiresAt, Map.copyOf(hashes), facts);
        } catch (JacksonException exception) { throw failure(BillingException.Reason.SOURCE_INVALID); }
        catch (IOException exception) { throw failure(BillingException.Reason.STORAGE_FAILURE); }
        finally {
            if (staging != null) try { removeInput(staging); }
            catch (IOException ignored) { /* An incomplete package is never dispatched. */ }
        }
    }

    public synchronized void verify(String owner, Prepared prepared) {
        if (prepared == null || !prepared.owner().equals(owner)) throw failure(BillingException.Reason.FORBIDDEN);
        var snapshot = requireSnapshot(owner, prepared.snapshotId());
        if (!snapshot.revision().equals(prepared.scopeRevision()) || snapshot.expiresAt().isBefore(prepared.expiresAt())
                || !clock.instant().isBefore(prepared.expiresAt())) {
            throw failure(BillingException.Reason.NOT_FOUND);
        }
        try {
            requireRoot();
            Path report = root.resolve(prepared.reportId().toString());
            requireDirectory(report);
            Path input = report.resolve("input"); requireDirectory(input);
            for (String name : INPUT_FILES) {
                if (!hash(input.resolve(name)).equals(prepared.inputHashes().get(name))) throw failure(BillingException.Reason.SOURCE_INVALID);
            }
        } catch (IOException exception) { throw failure(BillingException.Reason.STORAGE_FAILURE); }
    }

    /** Restores only validated publication copies; the database remains authoritative. */
    public synchronized void publishFiles(String owner, Prepared prepared, BillingWorkflowStore.Report report, byte[] pdf) {
        verify(owner, prepared);
        if (!prepared.reportId().equals(report.id()) || !prepared.snapshotId().equals(report.snapshotId())) throw failure(BillingException.Reason.INVALID_REQUEST);
        Path staging = null;
        try {
            Path folder = root.resolve(prepared.reportId().toString()); requireDirectory(folder);
            staging = Files.createTempDirectory(folder, ".publishing-");
            sharedDirectory(staging);
            var document = new LinkedHashMap<String, Object>();
            document.put("reportId", report.id()); document.put("snapshotId", report.snapshotId());
            document.put("range", report.range()); document.put("expiresAt", report.expiresAt());
            document.put("facts", factDocument(report.facts())); document.put("analysis", report.analysis());
            document.put("analysisFailure", report.analysisFailure()); document.put("limitations", report.source().limitations().stream().sorted().toList());
            writeFile(staging.resolve("report.json"), document, new long[]{0});
            if (pdf != null) {
                if (pdf.length > 10 * 1024 * 1024) throw failure(BillingException.Reason.LIMIT_EXCEEDED);
                Files.write(staging.resolve("report.pdf"), pdf, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS);
            }
            Path target = folder.resolve("published");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) removeTree(target);
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE); staging = null;
        } catch (IOException exception) { throw failure(BillingException.Reason.STORAGE_FAILURE); }
        finally { if (staging != null) try { removeTree(staging); } catch (IOException ignored) { /* Never authoritative. */ } }
    }

    public synchronized void cleanupScratch(UUID reportId, UUID attemptId) {
        try {
            requireRoot(); Path report = root.resolve(reportId.toString());
            if (!Files.exists(report, LinkOption.NOFOLLOW_LINKS)) return;
            requireDirectory(report);
            Path attempts = report.resolve("attempts");
            if (Files.exists(attempts, LinkOption.NOFOLLOW_LINKS)) {
                requireDirectory(attempts);
                Path attempt = attempts.resolve(attemptId.toString());
                if (Files.exists(attempt, LinkOption.NOFOLLOW_LINKS)) removeTree(attempt);
            }
            try (var entries = Files.list(report)) {
                for (Path entry : entries.toList()) if (entry.getFileName().toString().startsWith(".preparing-")
                        || entry.getFileName().toString().startsWith(".publishing-")) removeTree(entry);
            }
        } catch (IOException exception) { throw failure(BillingException.Reason.STORAGE_FAILURE); }
    }

    public synchronized void deleteReport(UUID reportId) {
        try {
            requireRoot(); Path report = root.resolve(reportId.toString());
            if (Files.exists(report, LinkOption.NOFOLLOW_LINKS)) removeTree(report);
        } catch (IOException exception) { throw failure(BillingException.Reason.STORAGE_FAILURE); }
    }

    private void removeTree(Path target) throws IOException {
        if (target.equals(root) || !target.startsWith(root)) throw failure(BillingException.Reason.CONFIGURATION);
        requireDirectory(target.getParent());
        // walk does not follow symbolic links; delete the link, never its destination.
        Files.walkFileTree(target, new java.nio.file.SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file); return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.deleteIfExists(directory); return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private BillingData.Snapshot requireSnapshot(String owner, UUID id) {
        var snapshot = billing.inspect(owner, id);
        if (!owner.equals(snapshot.owner())) throw failure(BillingException.Reason.FORBIDDEN);
        if (!clock.instant().isBefore(snapshot.expiresAt())) throw failure(BillingException.Reason.NOT_FOUND);
        if (snapshot.summary() == null || (snapshot.state() != BillingData.State.READY
                && snapshot.state() != BillingData.State.READY_WITH_LIMITATIONS)) throw failure(BillingException.Reason.NOT_READY);
        return snapshot;
    }

    private void requireRoot() throws IOException {
        if (root == null || !root.isAbsolute() || !root.normalize().equals(root)) throw failure(BillingException.Reason.CONFIGURATION);
        requireDirectory(root);
    }

    private void requireDirectory(Path path) throws IOException {
        if (!path.startsWith(root) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || !path.toRealPath().equals(path)) throw failure(BillingException.Reason.CONFIGURATION);
    }

    private Path directory(Path parent, String name) throws IOException {
        requireDirectory(parent);
        Path child = parent.resolve(name);
        if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(child); sharedDirectory(child);
        }
        requireDirectory(child);
        return child;
    }

    private static void sharedDirectory(Path directory) throws IOException {
        // The deployment gives only backend and runner the shared Billing group.
        // setgid propagates that group; temporary directories otherwise default to 0700.
        Files.setAttribute(directory, "unix:mode", 02770, LinkOption.NOFOLLOW_LINKS);
    }

    private byte[] readFile(Path file, long limit) throws IOException {
        requireDirectory(file.getParent());
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > limit) throw failure(BillingException.Reason.SOURCE_INVALID);
        try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes((int) limit + 1);
            if (bytes.length > limit) throw failure(BillingException.Reason.LIMIT_EXCEEDED);
            return bytes;
        }
    }

    private String hash(Path file) throws IOException {
        requireDirectory(file.getParent());
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw failure(BillingException.Reason.SOURCE_INVALID);
        try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16384]; long count = 0; int read;
            while ((read = in.read(buffer)) != -1) {
                count += read;
                if (count > MAX_BYTES) throw failure(BillingException.Reason.LIMIT_EXCEEDED);
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private void writeFile(Path file, Object document, long[] count) throws IOException {
        try (var out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)) {
            write(out, document, count);
        }
    }

    private void write(OutputStream out, Object document, long[] count) {
        byte[] encoded = (mapper.writeValueAsString(document) + "\n").getBytes(StandardCharsets.UTF_8);
        count[0] += encoded.length;
        if (count[0] > MAX_BYTES) throw failure(BillingException.Reason.LIMIT_EXCEEDED);
        try { out.write(encoded); }
        catch (IOException exception) { throw failure(BillingException.Reason.STORAGE_FAILURE); }
    }

    private void removeInput(Path input) throws IOException {
        requireDirectory(input);
        try (var entries = Files.list(input)) {
            for (Path file : entries.toList()) {
                if (!INPUT_FILES.contains(file.getFileName().toString())
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw failure(BillingException.Reason.SOURCE_INVALID);
            }
        }
        for (String name : INPUT_FILES) Files.deleteIfExists(input.resolve(name));
        Files.delete(input);
    }

    private static Map<String, Object> row(BillingData.CostRow row, Accumulator amounts) {
        var document = new LinkedHashMap<String, Object>();
        document.put("reference", row.dataset() + "/" + row.part() + "/" + row.ordinal());
        document.put("dataset", row.dataset()); document.put("role", amounts.role(row.dataset()));
        document.put("part", row.part()); document.put("ordinal", row.ordinal());
        document.put("sourceHash", amounts.partHashes.get(row.dataset() + "/" + row.part()));
        document.put("date", row.date().toString()); document.put("currency", row.currency());
        document.put("cost", row.cost().toPlainString()); document.put("bucket", row.bucket().name());
        document.put("subscription", row.subscription()); document.put("service", row.service());
        document.put("chargeType", row.chargeType()); document.put("invoiceId", row.invoiceId());
        var dimensions = new TreeMap<String, String>();
        row.dimensions().forEach((key, value) -> { if (DIMENSIONS.contains(key)) dimensions.put(key, value); });
        document.put("dimensions", dimensions);
        return document;
    }

    static Map<String, Object> factDocument(BillingReportFacts facts) {
        var document = new LinkedHashMap<String, Object>();
        document.put("selectedTotal", decimal(facts.selectedTotal())); document.put("baselineTotal", decimal(facts.baselineTotal()));
        document.put("delta", decimal(facts.delta())); document.put("percent", decimal(facts.percent()));
        document.put("services", decimals(facts.services())); document.put("months", decimals(facts.months()));
        document.put("subscriptions", decimals(facts.subscriptions())); document.put("serviceChanges", decimals(facts.serviceChanges()));
        var buckets = new TreeMap<String, Object>();
        facts.buckets().forEach((key, value) -> buckets.put(key.name(), Map.of("rows", value.rows(), "cost", decimal(value.cost()))));
        document.put("buckets", buckets);
        return document;
    }

    private static String decimal(BigDecimal value) { return value == null ? null : value.toPlainString(); }
    private static Map<String, String> decimals(Map<String, BigDecimal> source) {
        var result = new TreeMap<String, String>(); source.forEach((key, value) -> result.put(key, decimal(value))); return result;
    }
    private static BillingException failure(BillingException.Reason reason) { return new BillingException(reason); }

    public record Prepared(String owner, UUID snapshotId, UUID reportId, UUID attemptId, String scopeRevision,
            Instant expiresAt, Map<String, String> inputHashes, BillingReportFacts facts) {
        public Prepared { inputHashes = Map.copyOf(inputHashes); }
        @Override public String toString() { return "PreparedBillingPackage[protected]"; }
    }

    private static final class Accumulator {
        private final BillingData.Snapshot snapshot;
        private final Map<String, Long> expected = new TreeMap<>();
        private final Map<String, Long> observed = new TreeMap<>();
        private final Map<String, String> partHashes = new TreeMap<>();
        private final Map<BillingData.Bucket, BillingData.Amount> buckets = new EnumMap<>(BillingData.Bucket.class);
        private final Map<String, BigDecimal> subscriptions = new TreeMap<>();
        private final Map<String, BigDecimal> services = new TreeMap<>();
        private final Set<String> selected;
        private final Set<String> baseline;
        private long rowCount;

        private Accumulator(BillingData.Snapshot snapshot) {
            this.snapshot = snapshot;
            selected = Set.copyOf(snapshot.range().selected().stream().map(m -> "month:" + m).toList());
            baseline = Set.copyOf(snapshot.range().baseline().stream().map(m -> "month:" + m).toList());
            for (var bucket : BillingData.Bucket.values()) buckets.put(bucket, new BillingData.Amount(0, BigDecimal.ZERO));
            for (var partition : snapshot.summary().partitions()) for (var part : partition.parts()) {
                String key = partition.dataset() + "/" + part.ordinal();
                if (expected.putIfAbsent(key, part.rows()) != null) throw failure(BillingException.Reason.SOURCE_INVALID);
                observed.put(key, 0L); partHashes.put(key, part.sha256());
            }
        }

        private String role(String dataset) {
            if (selected.contains(dataset)) return "selected";
            if (baseline.contains(dataset)) return "baseline";
            if (dataset.startsWith("invoice:")) return "invoice";
            throw failure(BillingException.Reason.SOURCE_INVALID);
        }

        private void add(BillingData.CostRow row) {
            String key = row.dataset() + "/" + row.part();
            if (!expected.containsKey(key) || row.ordinal() > expected.get(key) || row.ordinal() != observed.get(key) + 1
                    || ++rowCount > 1_000_000) throw failure(BillingException.Reason.SOURCE_INVALID);
            observed.merge(key, 1L, Long::sum);
            if ("selected".equals(role(row.dataset()))) {
                var old = buckets.get(row.bucket());
                buckets.put(row.bucket(), new BillingData.Amount(old.rows() + 1, old.cost().add(row.cost())));
                if (row.bucket() == BillingData.Bucket.AZURE) {
                    subscriptions.merge(row.subscription(), row.cost(), BigDecimal::add);
                    services.merge(row.service(), row.cost(), BigDecimal::add);
                }
            }
        }

        private BillingReportFacts finish() {
            if (!expected.equals(observed)) throw failure(BillingException.Reason.SOURCE_INVALID);
            var summary = snapshot.summary();
            for (var bucket : BillingData.Bucket.values()) {
                var actual = buckets.get(bucket); var saved = summary.selected().buckets().get(bucket);
                if (saved == null || actual.rows() != saved.rows() || actual.cost().compareTo(saved.cost()) != 0) throw failure(BillingException.Reason.SOURCE_INVALID);
            }
            if (!decimalsEqual(services, summary.selected().services())) throw failure(BillingException.Reason.SOURCE_INVALID);
            var changes = new TreeMap<String, BigDecimal>();
            if (summary.baseline() != null && summary.comparison() != null) {
                changes.putAll(services);
                summary.baseline().services().forEach((key, value) -> changes.merge(key, value.negate(), BigDecimal::add));
            }
            return new BillingReportFacts(summary.selected().azureTotal(),
                    summary.baseline() == null ? null : summary.baseline().azureTotal(),
                    summary.comparison() == null ? null : summary.comparison().delta(),
                    summary.comparison() == null ? null : summary.comparison().percent(),
                    services, summary.selected().months(), subscriptions, changes, buckets);
        }

        private static boolean decimalsEqual(Map<String, BigDecimal> a, Map<String, BigDecimal> b) {
            return a.keySet().equals(b.keySet()) && a.entrySet().stream().allMatch(e -> e.getValue().compareTo(b.get(e.getKey())) == 0);
        }
    }
}
