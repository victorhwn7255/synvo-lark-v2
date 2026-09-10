package synvo.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import synvo.TestcontainersConfiguration;
import synvo.billing.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Import(TestcontainersConfiguration.class)
class DailySpendingPersistenceTests {
    @Autowired DailySpendingStore daily;
    @Autowired BillingStore reports;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired tools.jackson.databind.ObjectMapper mapper;
    private final Instant now = Instant.parse("2026-09-08T00:00:00Z");
    private final YearMonth start = YearMonth.of(2025, 9);

    @Test void restartDiscardsUnpublishedRowsPreservesCoverageAndResumesHoles() {
        String owner = UUID.randomUUID().toString();
        var firstWorker = new JdbcBillingStore(jdbc, transactions, mapper, dataSource);
        var secondWorker = new JdbcBillingStore(jdbc, transactions, mapper, dataSource);
        UUID runId = null;
        try {
            firstWorker.recoverAndCleanup(now);
            var run = daily.claim(owner, "v1", "crash", start, now).run(); runId = run.id();
            var firstVersion = daily.stagePartition(owner, "v1", run.id(), run.plan().getFirst(), now);
            daily.publish(owner, "v1", run.id(), firstVersion, evidence(0), now);
            var secondVersion = daily.stagePartition(owner, "v1", run.id(), run.plan().get(1), now);
            assertEquals(BillingException.Reason.BUSY, assertThrows(BillingException.class, () -> secondWorker.recoverAndCleanup(now)).reason());
            firstWorker.close(); secondWorker.recoverAndCleanup(now);
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM billing_daily_partition WHERE id=?", Integer.class, secondVersion));
            var feed = daily.read(owner, "v1", start, now, ignored -> fail());
            assertEquals("INTERRUPTED", feed.latest().state()); assertEquals(1, feed.partitions().size());
            var resume = daily.claim(owner, "v1", "resume", start, now.plusSeconds(1)).run();
            assertEquals(12, resume.plan().size());
            daily.finish(owner, "v1", resume.id(), BillingException.Reason.INTERRUPTED, now);
        } finally {
            if (runId != null) daily.finish(owner, "v1", runId, BillingException.Reason.INTERRUPTED, now);
            firstWorker.close(); secondWorker.close();
        }
    }

    @Test void unavailableOldPartitionDoesNotBlockNewerCoverageAndReportClaimsBlockDaily() {
        String owner = UUID.randomUUID().toString();
        var report = reports.claim(owner, "v1", "report", new BillingData.Range(start, start, false), now);
        try { assertEquals(BillingException.Reason.BUSY, assertThrows(BillingException.class, () -> daily.claim(owner, "v1", "daily", start, now)).reason()); }
        finally { reports.fail(owner, "v1", report.snapshot().id(), BillingException.Reason.INTERRUPTED, now); }
        var run = daily.claim(owner, "v1", "daily", YearMonth.of(2026,8), now).run();
        try {
            var failed = daily.stagePartition(owner, "v1", run.id(), run.plan().getFirst(), now);
            assertThrows(BillingException.class, () -> daily.stageRows("other", "v1", run.id(), failed, List.of(), now));
            daily.skip(owner, "v1", run.id(), failed, BillingException.Reason.SOURCE_UNAVAILABLE, now);
            var good = daily.stagePartition(owner, "v1", run.id(), run.plan().getLast(), now);
            daily.publish(owner, "v1", run.id(), good, new BillingData.Partition("month:2026-09", now, "test", Map.of(), List.of(), Set.of(), Set.of(), true, true), now);
            daily.finish(owner, "v1", run.id(), null, now);
            var feed = daily.read(owner, "v1", start, now, ignored -> fail());
            assertEquals("PARTIAL", feed.latest().state()); assertEquals(1, feed.latest().completed()); assertEquals(2, feed.latest().attempted());
            assertEquals(LocalDate.of(2026,9,1), feed.partitions().getFirst().period().first());
        } finally { daily.finish(owner, "v1", run.id(), BillingException.Reason.INTERRUPTED, now); }
    }

    @Test void durablePlanReplacementGapsAndSharedClaim() {
        String owner = UUID.randomUUID().toString();
        var run = daily.claim(owner, "v1", "first", start, now).run();
        try {
            assertEquals(13, run.plan().size());
            assertEquals(LocalDate.of(2025, 9, 1), run.plan().getFirst().first());
            assertEquals(LocalDate.of(2026, 9, 8), run.plan().getLast().through());
            assertFalse(daily.claim(owner, "v1", "first", start, now).acquired());
            assertEquals(run.id(), daily.claim(owner, "v1", "second-click", start, now).run().id());
            assertEquals(BillingException.Reason.BUSY, assertThrows(BillingException.class,
                    () -> reports.claim(owner, "v1", "report", new BillingData.Range(start, start, false), now)).reason());
            var version = daily.stagePartition(owner, "v1", run.id(), run.plan().getFirst(), now);
            daily.stageRows(owner, "v1", run.id(), version, List.of(row(1, "2.01"), row(2, "2.01")), now);
            assertTrue(read(owner).isEmpty());
            daily.publish(owner, "v1", run.id(), version, evidence(2), now);
            assertEquals(2, read(owner).size());
            assertTrue(read("other").isEmpty());
            daily.read(owner, "revoked", start, now, ignored -> fail("Revoked data"));
            daily.finish(owner, "v1", run.id(), BillingException.Reason.SOURCE_UNAVAILABLE, now);
            var feed = daily.read(owner, "v1", start, now, ignored -> { });
            assertEquals("PARTIAL", feed.latest().state());
            assertNull(feed.lastSuccess());
            var resumed = daily.claim(owner, "v1", "resume", start, now.plusSeconds(1)).run();
            assertEquals(12, resumed.plan().size());
            assertEquals(LocalDate.of(2025, 10, 1), resumed.plan().getFirst().first());
            daily.finish(owner, "v1", resumed.id(), BillingException.Reason.INTERRUPTED, now);
            var repair = daily.claim(owner, "v1", "repair", start, now.plusSeconds(31L * 86400)).run();
            // The active retention window drops Sep 2025 in October; it cannot leak via a later read.
            assertEquals(LocalDate.of(2025, 10, 1), repair.plan().getFirst().first());
            daily.read(owner, "v1", start, now.plusSeconds(31L * 86400), ignored -> fail("Expired partition"));
            daily.finish(owner, "v1", repair.id(), BillingException.Reason.INTERRUPTED, now.plusSeconds(31L * 86400));
        } finally { daily.finish(owner, "v1", run.id(), BillingException.Reason.INTERRUPTED, now); }
    }

    @Test void recentMonthsReplaceRatherThanAppendAndEmptyMonthIsCovered() {
        String owner = UUID.randomUUID().toString();
        YearMonth recent = YearMonth.of(2026, 9);
        var run = daily.claim(owner, "v1", "initial", recent, now).run();
        try {
            var period = run.plan().getFirst();
            var version = daily.stagePartition(owner, "v1", run.id(), period, now);
            var row = new BillingData.CostRow("month:2026-09", 0, 1, LocalDate.of(2026, 8, 30), "USD", BigDecimal.ONE,
                    BillingData.Bucket.AZURE, "11111111-1111-1111-1111-111111111111", "Storage", "Usage", "", Map.of());
            daily.stageRows(owner, "v1", run.id(), version, List.of(row), now);
            daily.publish(owner, "v1", run.id(), version, new BillingData.Partition("month:2026-09", now, "test", Map.of(),
                    List.of(new BillingData.Part(0, "a".repeat(64), 10, 1)), Set.of(), Set.of(), true, false), now);
            daily.finish(owner, "v1", run.id(), null, now);
            var replacement = daily.claim(owner, "v1", "replacement", recent, now.plusSeconds(1)).run();
            assertEquals(1, replacement.plan().size());
            var empty = daily.stagePartition(owner, "v1", replacement.id(), period, now);
            assertEquals(1, read(owner).size());
            daily.publish(owner, "v1", replacement.id(), empty, new BillingData.Partition("month:2026-09", now, "test", Map.of(), List.of(), Set.of(), Set.of(), true, true), now);
            daily.finish(owner, "v1", replacement.id(), null, now);
            assertTrue(read(owner).isEmpty());
            var feed = daily.read(owner, "v1", recent, now, ignored -> { });
            assertEquals(1, feed.partitions().size());
            assertEquals(period.through(), feed.partitions().getFirst().period().through());
            assertNotNull(feed.lastSuccess());
        } finally { daily.finish(owner, "v1", run.id(), BillingException.Reason.INTERRUPTED, now); }
    }

    private List<BillingData.CostRow> read(String owner) {
        var rows = new ArrayList<BillingData.CostRow>();
        daily.read(owner, "v1", start, now, rows::add); return rows;
    }
    private BillingData.CostRow row(long ordinal, String cost) {
        return new BillingData.CostRow("month:2025-09", 0, ordinal, LocalDate.of(2025, 9, 2), "USD", new BigDecimal(cost),
                BillingData.Bucket.AZURE, "11111111-1111-1111-1111-111111111111", "Storage", "Usage", "", Map.of());
    }
    private BillingData.Partition evidence(long count) {
        return new BillingData.Partition("month:2025-09", now, "test", Map.of(), List.of(new BillingData.Part(0, "a".repeat(64), 10, count)), Set.of(), Set.of(), true, true);
    }
}
