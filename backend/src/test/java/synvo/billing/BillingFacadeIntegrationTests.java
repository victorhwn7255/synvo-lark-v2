package synvo.billing;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import synvo.TestcontainersConfiguration;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BillingFacadeIntegrationTests {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path packageRoot;
    @Autowired private BillingStore store;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC);

    @Test void fullFacadePublishesExactSnapshotAndReplaysAfterFacadeReplacement() throws Exception {
        var session = new SampleSource(false, false);
        UUID id;
        try (var facade = facade(session)) {
            id = facade.request("owner", BillingData.SOURCE_ID, range(true), UUID.randomUUID().toString()).id();
            var snapshot = terminal(facade, id);
            assertEquals(BillingData.State.READY_WITH_LIMITATIONS, snapshot.state());
            assertEquals(0, snapshot.summary().selected().azureTotal().compareTo(new BigDecimal("10")));
            assertEquals(new BigDecimal("100.00"), snapshot.summary().comparison().percent());
            assertEquals(BillingData.ReconciliationStatus.MATCHED, snapshot.summary().reconciliation().getFirst().status());
            assertEquals(1, snapshot.summary().selected().buckets().get(BillingData.Bucket.PROFILE_ADJUSTMENT).rows());
        }
        try (var restored = facade(session)) {
            assertEquals(6, restored.evidence("owner", id, 0, 100).size());
            assertNotNull(restored.summary("owner", id));
            assertThrows(BillingException.class, () -> restored.summary("other", id));
            var packages = new synvo.billingworkflow.BillingAnalysisPackage(restored, packageRoot.toRealPath(), clock, new tools.jackson.databind.ObjectMapper());
            var prepared = packages.prepare("owner", id, UUID.randomUUID(), UUID.randomUUID());
            packages.verify("owner", prepared);
            assertEquals(0, prepared.facts().selectedTotal().compareTo(new BigDecimal("10")));
            assertEquals(0, prepared.facts().baselineTotal().compareTo(new BigDecimal("5")));
        }
    }

    @Test void duplicateRequestDoesNotStartAnotherProviderSessionAndBusyDoesNotQueue() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var opens = new AtomicInteger();
        BillingSource source = () -> {
            opens.incrementAndGet(); entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException(); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new BillingException(BillingException.Reason.INTERRUPTED); }
            return new SampleSource(false, false);
        };
        try (var facade = new BillingInsightsFacade(store, source, "owner", "revision", true, clock)) {
            String key = UUID.randomUUID().toString();
            UUID id = facade.request("owner", BillingData.SOURCE_ID, range(false), key).id();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(id, facade.request("owner", BillingData.SOURCE_ID, range(false), key).id());
                var error = assertThrows(BillingException.class, () -> facade.request("owner", BillingData.SOURCE_ID, range(false), UUID.randomUUID().toString()));
                assertEquals(BillingException.Reason.BUSY, error.reason()); assertEquals(1, opens.get());
            } finally { release.countDown(); terminal(facade, id); }
        }
    }

    @Test void stopHoldsClaimUntilProviderCloseAndCannotPublishALateResult() throws Exception {
        var closing = new CountDownLatch(1); var release = new CountDownLatch(1);
        var source = new SampleSource(false, false) {
            @Override public void close() {
                closing.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
        };
        try (var facade = facade(source)) {
            var id = facade.request("owner", BillingData.SOURCE_ID, range(false), UUID.randomUUID().toString()).id();
            try {
                assertTrue(closing.await(5, TimeUnit.SECONDS));
                assertThrows(BillingException.class, () -> facade.stop("other", id));
                assertTrue(facade.stop("owner", id));
                assertTrue(facade.stop("owner", id));
                assertNull(facade.inspect("owner", id).summary());
                var busy = assertThrows(BillingException.class, () -> facade.request("owner", BillingData.SOURCE_ID,
                        range(false), UUID.randomUUID().toString()));
                assertEquals(BillingException.Reason.BUSY, busy.reason());
            } finally { release.countDown(); }
            var stopped = terminal(facade, id);
            assertEquals(BillingData.State.INTERRUPTED, stopped.state());
            assertEquals(BillingException.Reason.INTERRUPTED, stopped.failure());
            assertNull(stopped.summary());
        }
    }

    @Test void baselineFailurePreservesSelectedCostsWithoutInventingGrowth() throws Exception {
        try (var facade = facade(new SampleSource(true, false))) {
            var id = facade.request("owner", BillingData.SOURCE_ID, range(true), UUID.randomUUID().toString()).id();
            var result = terminal(facade, id);
            assertEquals(BillingData.State.READY_WITH_LIMITATIONS, result.state());
            assertNull(result.summary().comparison()); assertNull(result.summary().baseline());
            assertTrue(result.summary().limitations().contains("BASELINE_UNAVAILABLE"));
        }
    }

    @Test void refreshAndFailedRefreshCannotRewriteEarlierEvidence() throws Exception {
        UUID original;
        try (var facade = facade(new SampleSource(false, false))) {
            original = facade.request("owner", BillingData.SOURCE_ID, range(false), UUID.randomUUID().toString()).id();
            terminal(facade, original);
        }
        try (var facade = facade(new SampleSource(false, false) {
            @Override public BillingData.Partition month(YearMonth month, Consumer<BillingData.CostRow> sink) {
                return partition("month:" + month, "20", sink);
            }
            @Override public List<BillingData.Invoice> invoices(Set<String> refs, LocalDate first, LocalDate last) {
                throw new BillingException(BillingException.Reason.AUTHENTICATION);
            }
        })) {
            UUID refreshed = facade.request("owner", BillingData.SOURCE_ID, range(false), UUID.randomUUID().toString()).id();
            var result = terminal(facade, refreshed);
            assertNotEquals(original, refreshed);
            assertEquals(0, result.summary().selected().azureTotal().compareTo(new BigDecimal("20")));
            assertTrue(result.summary().limitations().contains("INVOICE_UNAVAILABLE"));
            assertTrue(result.summary().limitations().contains("OPTIMIZATION_REQUIRES_UTILIZATION_AND_COMMITMENT_EVIDENCE"));
            assertEquals(0, facade.summary("owner", original).selected().azureTotal().compareTo(new BigDecimal("10")));
        }
        try (var facade = facade(new SampleSource(false, true))) {
            UUID failed = facade.request("owner", BillingData.SOURCE_ID, range(false), UUID.randomUUID().toString()).id();
            assertEquals(BillingData.State.FAILED, terminal(facade, failed).state());
            assertNotNull(facade.summary("owner", original));
        }
    }

    @RepeatedTest(20) void selectedFailureAfterStagingCannotExposePartialCompanyTotal() throws Exception {
        try (var facade = facade(new SampleSource(false, true))) {
            var id = facade.request("owner", BillingData.SOURCE_ID, range(false), UUID.randomUUID().toString()).id();
            var result = terminal(facade, id);
            assertEquals(BillingData.State.FAILED, result.state()); assertNull(result.summary());
            assertThrows(BillingException.class, () -> facade.evidence("owner", id, 0, 100));
        }
    }

    @Test void disabledAndUnauthorizedRequestsNeverReachTheProvider() {
        try (var facade = new BillingInsightsFacade(store, () -> { fail("Provider must not be contacted"); return null; }, "owner", "revision", false, clock)) {
            assertFalse(facade.available("owner"));
            assertThrows(BillingException.class, () -> facade.request("other", BillingData.SOURCE_ID, range(false), "key"));
            assertThrows(BillingException.class, () -> facade.request("owner", BillingData.SOURCE_ID, range(false), "key"));
        }
    }

    private BillingInsightsFacade facade(BillingSource.Session session) { return new BillingInsightsFacade(store, () -> session, "owner", "revision", true, clock); }
    private static BillingData.Range range(boolean compare) { return new BillingData.Range(YearMonth.of(2026, 7), YearMonth.of(2026, 7), compare); }
    private BillingData.Snapshot terminal(BillingInsightsFacade facade, UUID id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            BillingData.Snapshot result;
            try { result = facade.inspect("owner", id); }
            catch (BillingException exception) { throw new AssertionError(diagnostic(id), exception); }
            if (result.state() != BillingData.State.RUNNING) return result;
            Thread.sleep(20);
        }
        throw new AssertionError(diagnostic(id));
    }

    private String diagnostic(UUID id) {
        var threads = new StringBuilder();
        Thread.getAllStackTraces().forEach((thread, frames) -> {
            if (thread.getName().equals("billing-import")) threads.append(java.util.Arrays.toString(frames));
        });
        return "Synthetic billing state=" + jdbc.queryForList("SELECT state,expires_at FROM billing_snapshot WHERE id=?", id)
                + "; worker frames=" + threads;
    }

    private static class SampleSource implements BillingSource.Session {
        private final boolean failBaseline;
        private final boolean failSelected;
        SampleSource(boolean failBaseline, boolean failSelected) { this.failBaseline = failBaseline; this.failSelected = failSelected; }
        @Override public BillingData.Partition month(YearMonth month, Consumer<BillingData.CostRow> sink) {
            if (failBaseline && month.getMonthValue() == 6) throw new BillingException(BillingException.Reason.SOURCE_UNAVAILABLE);
            String dataset = "month:" + month;
            if (failSelected) {
                for (int i = 1; i <= 260; i++) sink.accept(row(dataset, i, "1", BillingData.Bucket.AZURE));
                throw new BillingException(BillingException.Reason.SOURCE_INVALID);
            }
            return partition(dataset, month.getMonthValue() == 6 ? "5" : "10", sink);
        }
        @Override public List<BillingData.Invoice> invoices(Set<String> references, LocalDate first, LocalDate last) {
            return List.of(new BillingData.Invoice("example", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), "Invoice", false,
                    Map.of("billedAmount", new BigDecimal("10.001")), Map.of()));
        }
        @Override public BillingData.Partition invoice(String reference, Consumer<BillingData.CostRow> sink) { return partition("invoice:" + reference, "10", sink); }
        static BillingData.Partition partition(String dataset, String amount, Consumer<BillingData.CostRow> sink) {
            sink.accept(row(dataset, 1, amount, BillingData.Bucket.AZURE));
            sink.accept(row(dataset, 2, "0.001", BillingData.Bucket.PROFILE_ADJUSTMENT));
            return new BillingData.Partition(dataset, Instant.parse("2026-09-07T00:00:00Z"), "synthetic-v1", Map.of(),
                    List.of(new BillingData.Part(0, "a".repeat(64), 100, 2)), Set.of("00000000-0000-4000-8000-000000000001"), Set.of("example"), true, true);
        }
        private static BillingData.CostRow row(String dataset, long ordinal, String cost, BillingData.Bucket bucket) {
            return new BillingData.CostRow(dataset, 0, ordinal, LocalDate.of(2026, 7, 1), "USD", new BigDecimal(cost), bucket,
                    bucket == BillingData.Bucket.AZURE ? "00000000-0000-4000-8000-000000000001" : "", "Compute", "Usage", "example", Map.of());
        }
    }
}
