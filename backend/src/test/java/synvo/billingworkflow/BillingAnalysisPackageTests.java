package synvo.billingworkflow;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import synvo.billing.BillingData;
import synvo.billing.BillingException;
import synvo.billing.BillingInsightsFacade;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BillingAnalysisPackageTests {
    @TempDir Path directory;
    private final Instant now = Instant.parse("2026-09-08T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void writesExactVersionedPackageAndDifferentAttemptsWithoutFetchingAzure() throws Exception {
        var source = source();
        var packages = new BillingAnalysisPackage(source, directory.toRealPath(), clock, mapper);
        UUID report = UUID.randomUUID();
        var first = packages.prepare("owner", snapshot().id(), report, UUID.randomUUID());
        var second = packages.prepare("owner", snapshot().id(), report, UUID.randomUUID());
        assertNotEquals(first.attemptId(), second.attemptId());
        assertEquals(first.inputHashes(), second.inputHashes());
        assertEquals(new BigDecimal("0.30"), first.facts().selectedTotal());
        assertEquals(new BigDecimal("0.10"), first.facts().baselineTotal());
        assertEquals(new BigDecimal("0.20"), first.facts().delta());
        assertEquals(new BigDecimal("200.00"), first.facts().percent());
        assertEquals(new BigDecimal("0.30"), first.facts().subscriptions().values().iterator().next());
        var input = directory.resolve(report.toString()).resolve("input");
        assertTrue(Files.readString(input.resolve("evidence.jsonl")).contains("\"cost\":\"0.10\""));
        assertTrue(Files.readString(input.resolve("facts.json")).contains("\"selectedTotal\":\"0.30\""));
        assertFalse(Files.readString(input.resolve("evidence.jsonl")).contains("DO_NOT_EXPORT"));
        assertTrue(mapper.readTree(Files.readString(input.resolve("manifest.json"))).path("rowCount").asLong() == 4);
        packages.verify("owner", first);
        packages.cleanupScratch(report, first.attemptId());
        assertFalse(Files.exists(directory.resolve(report + "/attempts/" + first.attemptId())));
        assertTrue(Files.isDirectory(directory.resolve(report + "/attempts/" + second.attemptId() + "/work")));
        assertTrue(Files.getPosixFilePermissions(input).contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ));
        verify(source, never()).request(any(), any(), any(), any());
    }

    @Test void refusesChangedInputUntilExplicitPreparationRebuildsTheSameSnapshot() throws Exception {
        var packages = new BillingAnalysisPackage(source(), directory.toRealPath(), clock, mapper);
        var prepared = packages.prepare("owner", snapshot().id(), UUID.randomUUID(), UUID.randomUUID());
        Files.writeString(directory.resolve(prepared.reportId().toString()).resolve("input/facts.json"), "{}");
        assertThrows(BillingException.class, () -> packages.verify("owner", prepared));
        var rebuilt = packages.prepare("owner", snapshot().id(), prepared.reportId(), UUID.randomUUID());
        assertEquals(prepared.inputHashes(), rebuilt.inputHashes());
        packages.verify("owner", rebuilt);
    }

    @Test void packageExpiresWithWorkEvenWhenSourceLivesLonger() throws Exception {
        var packages = new BillingAnalysisPackage(source(), directory.toRealPath(), clock, mapper);
        var prepared = packages.prepare("owner", snapshot().id(), UUID.randomUUID(), UUID.randomUUID(), now.plusSeconds(120));
        assertEquals(now.plusSeconds(120), prepared.expiresAt());
        assertTrue(Files.readString(directory.resolve(prepared.reportId() + "/input/manifest.json")).contains(now.plusSeconds(120).toString()));
        packages.verify("owner", prepared);
        var later = new BillingAnalysisPackage(source(), directory.toRealPath(), Clock.fixed(now.plusSeconds(121), ZoneOffset.UTC), mapper);
        assertThrows(BillingException.class, () -> later.verify("owner", prepared));
    }

    @Test void rejectsForeignOwnerExpiredEvidenceAndSymlinkedReportDirectory() throws Exception {
        var source = source();
        var packages = new BillingAnalysisPackage(source, directory.toRealPath(), clock, mapper);
        assertThrows(BillingException.class, () -> packages.prepare("other", snapshot().id(), UUID.randomUUID(), UUID.randomUUID()));
        var expired = new BillingAnalysisPackage(source, directory.toRealPath(), Clock.fixed(now.plusSeconds(4000), ZoneOffset.UTC), mapper);
        assertThrows(BillingException.class, () -> expired.prepare("owner", snapshot().id(), UUID.randomUUID(), UUID.randomUUID()));
        Path outside = Files.createTempDirectory("billing-synthetic-outside-");
        try {
            UUID report = UUID.randomUUID();
            Files.createSymbolicLink(directory.resolve(report.toString()), outside);
            assertThrows(BillingException.class, () -> packages.prepare("owner", snapshot().id(), report, UUID.randomUUID()));
            try (var children = Files.list(outside)) { assertEquals(0, children.count()); }
        } finally { Files.delete(outside); }
    }

    @Test void keepsSeparateReportsAndRejectsAChangedReportSnapshotBinding() throws Exception {
        var packages = new BillingAnalysisPackage(source(), directory.toRealPath(), clock, mapper);
        var first = packages.prepare("owner", snapshot().id(), UUID.randomUUID(), UUID.randomUUID());
        var second = packages.prepare("owner", snapshot().id(), UUID.randomUUID(), UUID.randomUUID());
        assertNotEquals(first.reportId(), second.reportId());
        packages.verify("owner", first);
        packages.verify("owner", second);
        assertThrows(BillingException.class, () -> packages.prepare("owner", UUID.randomUUID(), first.reportId(), UUID.randomUUID()));
    }

    BillingInsightsFacade source() {
        var source = mock(BillingInsightsFacade.class);
        when(source.inspect(anyString(), any(UUID.class))).thenAnswer(call -> {
            if (!"owner".equals(call.getArgument(0)) || !snapshot().id().equals(call.getArgument(1))) {
                throw new BillingException(BillingException.Reason.FORBIDDEN);
            }
            return snapshot();
        });
        doAnswer(call -> {
            java.util.function.Consumer<BillingData.CostRow> sink = call.getArgument(2);
            rows().forEach(sink);
            return null;
        }).when(source).visitEvidence(eq("owner"), eq(snapshot().id()), any());
        return source;
    }

    private List<BillingData.CostRow> rows() {
        return List.of(row("month:2026-07", 1, "0.10"), row("month:2026-07", 2, "0.20"),
                row("month:2026-06", 1, "0.10"), row("invoice:synthetic", 1, "0.30"));
    }

    private BillingData.CostRow row(String dataset, long ordinal, String cost) {
        return new BillingData.CostRow(dataset, 0, ordinal, dataset.startsWith("month:") ? YearMonth.parse(dataset.substring(6)).atDay(1) : LocalDate.of(2026, 7, 1), "USD",
                new BigDecimal(cost), BillingData.Bucket.AZURE, "00000000-0000-0000-0000-000000000001",
                "Synthetic service", "Usage", "synthetic", Map.of("resourceGroup", "Synthetic", "unknown", "DO_NOT_EXPORT"));
    }

    BillingData.Snapshot snapshot() {
        var buckets = new java.util.EnumMap<BillingData.Bucket, BillingData.Amount>(BillingData.Bucket.class);
        for (var bucket : BillingData.Bucket.values()) buckets.put(bucket, new BillingData.Amount(0, BigDecimal.ZERO));
        buckets.put(BillingData.Bucket.AZURE, new BillingData.Amount(2, new BigDecimal("0.30")));
        var totals = new BillingData.Totals(buckets, Map.of("Synthetic service", new BigDecimal("0.30")), Map.of("2026-07", new BigDecimal("0.30")));
        var priorBuckets = new java.util.EnumMap<>(buckets);
        priorBuckets.put(BillingData.Bucket.AZURE, new BillingData.Amount(1, new BigDecimal("0.10")));
        var prior = new BillingData.Totals(priorBuckets, Map.of("Synthetic service", new BigDecimal("0.10")), Map.of("2026-06", new BigDecimal("0.10")));
        var parts = List.of(
                partition("month:2026-07", 2), partition("month:2026-06", 1), partition("invoice:synthetic", 1));
        var summary = new BillingData.Summary("USD", BillingData.BASIS, BillingData.MAPPING_VERSION, BillingData.CALCULATION_VERSION,
                totals, prior, new BillingData.Comparison(new BigDecimal("0.20"), new BigDecimal("200.00")),
                parts, List.of(), List.of(), Set.of("SOURCE_FINALITY_NOT_ESTABLISHED"));
        return new BillingData.Snapshot(UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "owner", "revision", "synthetic", new BillingData.Range(YearMonth.of(2026, 7), YearMonth.of(2026, 7), true),
                BillingData.State.READY_WITH_LIMITATIONS, now.minusSeconds(60), now.plusSeconds(3600), summary, null);
    }

    private BillingData.Partition partition(String dataset, long count) {
        return new BillingData.Partition(dataset, now.minusSeconds(60), "synthetic-v1", Map.of(),
                List.of(new BillingData.Part(0, "0".repeat(64), 100, count)), Set.of(), Set.of(), true, true);
    }
}
