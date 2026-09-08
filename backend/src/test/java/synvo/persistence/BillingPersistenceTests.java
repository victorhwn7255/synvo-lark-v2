package synvo.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import synvo.TestcontainersConfiguration;
import synvo.billing.BillingData;
import synvo.billing.BillingException;
import synvo.billing.BillingStore;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BillingPersistenceTests {
    @Autowired private BillingStore store;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ObjectMapper mapper;
    private final Instant now = Instant.parse("2026-09-07T00:00:00Z");
    private final BillingData.Range range = new BillingData.Range(YearMonth.of(2026, 7), YearMonth.of(2026, 7), false);

    @Test
    void snapshotsPublishAtomicallyRetainIdenticalRowsAndRejectCrossOwnerReads() {
        String key = UUID.randomUUID().toString();
        var claim = store.claim("owner", "revision", key, range, now);
        UUID id = claim.snapshot().id();
        try {
            assertTrue(claim.acquired());
            assertFalse(store.claim("owner", "revision", key, range, now).acquired());
            assertThrows(BillingException.class, () -> store.claim("owner", "revision", key,
                    new BillingData.Range(YearMonth.of(2026, 6), YearMonth.of(2026, 7), false), now));
            store.stage("owner", "revision", id, List.of(row(1), row(2)));
            assertThrows(BillingException.class, () -> store.visitEvidence("owner", "revision", id, now, ignored -> fail("Staging must not stream")));
            assertTrue(store.evidence("owner", "revision", id, 0, 100, now).isEmpty());
            assertThrows(BillingException.class, () -> store.stage("wrong-owner", "revision", id, List.of(row(3))));
            store.publish("owner", "revision", id, summary(), List.of(), now);
            assertEquals(2, store.evidence("owner", "revision", id, 0, 100, now).size());
            var streamed = new java.util.ArrayList<BillingData.CostRow>();
            store.visitEvidence("owner", "revision", id, now, streamed::add);
            assertEquals(2, streamed.size());
            assertThrows(BillingException.class, () -> store.visitEvidence("other", "revision", id, now, ignored -> fail("Foreign evidence must not stream")));
            assertThrows(BillingException.class, () -> store.visitEvidence("owner", "revoked", id, now, ignored -> fail("Revoked evidence must not stream")));
            assertThrows(BillingException.class, () -> store.visitEvidence("owner", "revision", id, Instant.parse("2027-11-01T00:00:00Z"), ignored -> fail("Expired evidence must not stream")));
            assertTrue(store.find("other", "revision", id, now).isEmpty());
            assertTrue(store.find("owner", "revoked", id, now).isEmpty());
            assertTrue(store.evidence("other", "revision", id, 0, 100, now).isEmpty());
            assertEquals("USD", store.find("owner", "revision", id, now).orElseThrow().summary().currency());
            store.fail("owner", "revision", id, BillingException.Reason.SOURCE_INVALID, now);
            assertEquals(BillingData.State.READY, store.find("owner", "revision", id, now).orElseThrow().state());
            assertTrue(store.find("owner", "revision", id, Instant.parse("2027-11-01T00:00:00Z")).isEmpty());
        } finally { store.fail("owner", "revision", id, BillingException.Reason.INTERRUPTED, now); }
    }

    @Test
    void concurrentClaimsHaveOneWinnerAndFailureRemovesStaging() {
        var futures = List.of(CompletableFuture.supplyAsync(this::attempt), CompletableFuture.supplyAsync(this::attempt));
        var claims = futures.stream().map(CompletableFuture::join).filter(java.util.Objects::nonNull).toList();
        assertEquals(1, claims.size());
        var id = claims.getFirst().snapshot().id();
        store.stage("owner", "revision", id, List.of(row(1)));
        store.fail("owner", "revision", id, BillingException.Reason.SOURCE_INVALID, now);
        assertEquals(BillingData.State.FAILED, store.find("owner", "revision", id, now).orElseThrow().state());
        assertTrue(store.evidence("owner", "revision", id, 0, 100, now).isEmpty());
        store.cleanup(now.plusSeconds(91L * 86400));
        assertTrue(store.find("owner", "revision", id, now).isEmpty());
    }

    private BillingStore.Claim attempt() {
        try { return store.claim("owner", "revision", UUID.randomUUID().toString(), range, now); }
        catch (BillingException exception) { assertEquals(BillingException.Reason.BUSY, exception.reason()); return null; }
    }

    @Test
    void restartRecoversOrphansAndAnExistingWorkerLeaseCannotBeStolen() {
        var first = new JdbcBillingStore(jdbc, transactions, mapper, dataSource);
        var second = new JdbcBillingStore(jdbc, transactions, mapper, dataSource);
        UUID id = null;
        try {
            first.recoverAndCleanup(now);
            id = first.claim("owner", "revision", UUID.randomUUID().toString(), range, now).snapshot().id();
            first.stage("owner", "revision", id, List.of(row(1)));
            var rejected = assertThrows(BillingException.class, () -> second.recoverAndCleanup(now));
            assertEquals(BillingException.Reason.BUSY, rejected.reason());
            first.close();
            second.recoverAndCleanup(now);
            assertEquals(BillingData.State.INTERRUPTED, second.find("owner", "revision", id, now).orElseThrow().state());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM billing_cost_evidence WHERE snapshot_id=?", Integer.class, id));
        } finally {
            if (id != null) store.fail("owner", "revision", id, BillingException.Reason.INTERRUPTED, now);
            first.close(); second.close();
        }
    }

    @Test
    void restoredExpiredSnapshotsArePhysicallyRemovedWhileActiveClaimsSurviveCleanup() {
        jdbc.update("""
                INSERT INTO billing_snapshot(id,owner_id,scope_revision,idempotency_key,first_month,last_month,comparison,state,created_at,expires_at,failure)
                SELECT gen_random_uuid(),'restore-batch-owner','revision',gen_random_uuid()::text,'2026-07-01','2026-07-01',false,
                'FAILED','2026-09-01T00:00:00Z','2026-12-01T00:00:00Z','SOURCE_INVALID' FROM generate_series(1,205)
                """);
        UUID expired = store.claim("retention-owner", "revision", UUID.randomUUID().toString(), range, now).snapshot().id();
        store.stage("retention-owner", "revision", expired, List.of(row(1)));
        store.publish("retention-owner", "revision", expired, summary(), List.of(), now);
        Instant later = Instant.parse("2027-11-07T00:00:00Z");
        UUID active = store.claim("retention-owner", "revision", UUID.randomUUID().toString(), range, later).snapshot().id();
        try {
            store.cleanup(later);
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM billing_snapshot WHERE owner_id='restore-batch-owner'", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM billing_cost_evidence WHERE snapshot_id=?", Integer.class, expired));
            assertTrue(store.find("retention-owner", "revision", expired, now).isEmpty());
            assertEquals(BillingData.State.RUNNING, store.find("retention-owner", "revision", active, later).orElseThrow().state());
        } finally { store.fail("retention-owner", "revision", active, BillingException.Reason.INTERRUPTED, later); }
    }

    private static BillingData.CostRow row(long ordinal) {
        return new BillingData.CostRow("month:2026-07", 0, ordinal, LocalDate.of(2026, 7, 1), "USD", new BigDecimal("1.234567890123456789"),
                BillingData.Bucket.PROFILE_ADJUSTMENT, "", "Unallocated", "RoundingAdjustment", "sample-invoice", Map.of());
    }

    private static BillingData.Summary summary() {
        var buckets = new EnumMap<BillingData.Bucket, BillingData.Amount>(BillingData.Bucket.class);
        for (var bucket : BillingData.Bucket.values()) buckets.put(bucket, new BillingData.Amount(0, BigDecimal.ZERO));
        return new BillingData.Summary("USD", BillingData.BASIS, BillingData.MAPPING_VERSION, BillingData.CALCULATION_VERSION,
                new BillingData.Totals(buckets, Map.of(), Map.of()), null, null, List.of(), List.of(), List.of(), Set.of());
    }
}
