package synvo.persistence;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import synvo.TestcontainersConfiguration;
import synvo.billing.BillingData;
import synvo.billing.BillingException;
import synvo.billingworkflow.BillingReportAnalysis;
import synvo.billingworkflow.BillingWorkflowStore;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Import(TestcontainersConfiguration.class)
class BillingWorkflowPersistenceTests {
    @Autowired BillingWorkflowStore store;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private final Instant now = Instant.now();
    private final BillingData.Range range = new BillingData.Range(YearMonth.of(2026, 7), YearMonth.of(2026, 7), true);

    @Test void recentKeepsLatestPublishedReportAfterManyQuestionsAndFailures() {
        String owner = "workflow-history-" + UUID.randomUUID();
        var report = store.claim(owner, "r", UUID.randomUUID().toString(), BillingWorkflowStore.Kind.GENERATION,
                range, null, null, null, now.minusSeconds(200), now.plusSeconds(60)).work();
        try {
            // The history query depends only on safe work metadata, not publication contents.
            jdbc.sql("UPDATE billing_workflow_work SET status='FACTUAL' WHERE id=:id").param("id", report.id()).update();
            for (int index = 0; index < 101; index++) {
                var attempt = store.claim(owner, "r", UUID.randomUUID().toString(), BillingWorkflowStore.Kind.QUESTION,
                        range, report.id(), UUID.randomUUID(), "Synthetic question", now.minusSeconds(150 - index), now.plusSeconds(60)).work();
                store.fail(attempt.id(), "SYNTHETIC_FAILURE");
            }
            var recent = store.recent(owner, "r", now);
            assertEquals(101, recent.size());
            assertEquals(report.id(), recent.getLast().id());
            assertTrue(store.recent("other", "r", now).stream().noneMatch(work -> work.id().equals(report.id())));
            assertTrue(store.recent(owner, "other", now).isEmpty());
            assertTrue(store.recent(owner, "r", now.plusSeconds(61)).isEmpty());
        } finally {
            jdbc.sql("DELETE FROM billing_workflow_work WHERE owner_open_id=:owner").param("owner", owner).update();
        }
    }

    @Test void historyPagesKeepRepeatedPeriodsAndScopeQuestionCursors() {
        String owner = "history-page-" + UUID.randomUUID();
        try {
            for (int index = 0; index < 55; index++) {
                var item = store.claim(owner, "r", "report-" + index, BillingWorkflowStore.Kind.GENERATION, range,
                        null, UUID.randomUUID(), null, now.minusSeconds(200 - index), now.plusSeconds(60)).work();
                jdbc.sql("UPDATE billing_workflow_work SET status='FACTUAL' WHERE id=:id").param("id", item.id()).update();
                jdbc.sql("INSERT INTO billing_workflow_report(work_id,document) VALUES (:id,'{}'::jsonb)").param("id", item.id()).update();
            }
            var first = store.history(owner, "r", null, null, now);
            assertEquals(50, first.size());
            var older = store.history(owner, "r", null, first.getLast().id(), now);
            assertEquals(5, older.size());
            assertTrue(older.stream().noneMatch(item -> first.stream().anyMatch(newer -> newer.id().equals(item.id()))));
            assertTrue(store.history("foreign", "r", null, null, now).isEmpty());
            assertTrue(store.history(owner, "other", null, null, now).isEmpty());
            assertTrue(store.history(owner, "r", null, null, now.plusSeconds(61)).isEmpty());
            assertThrows(BillingException.class, () -> store.history("foreign", "r", null, first.getLast().id(), now));
            UUID reportId = first.getFirst().id();
            for (int index = 0; index < 105; index++) {
                var question = store.claim(owner, "r", "question-" + index, BillingWorkflowStore.Kind.QUESTION, range,
                        reportId, UUID.randomUUID(), "Synthetic " + index, now.minusSeconds(105 - index), now.plusSeconds(60)).work();
                store.fail(question.id(), "SYNTHETIC");
            }
            var questions = store.history(owner, "r", reportId, null, now);
            assertEquals(50, questions.size());
            assertEquals(50, store.history(owner, "r", reportId, questions.getLast().id(), now).size());
            assertEquals(50, store.history(owner, "r", null, null, now).size());
            assertTrue(store.history(owner, "r", older.getFirst().id(), null, now).isEmpty());
            assertThrows(BillingException.class, () -> store.history(owner, "r", older.getFirst().id(), questions.getLast().id(), now));
            assertThrows(BillingException.class, () -> store.history(owner, "r", null, questions.getLast().id(), now));
        } finally { jdbc.sql("DELETE FROM billing_workflow_work WHERE owner_open_id=:owner").param("owner", owner).update(); }
    }

    @Test void duplicatePayloadBusyStopPublicationAndExpiryRemainAtomic() {
        String owner = "workflow-" + UUID.randomUUID(), key = UUID.randomUUID().toString();
        var claim = store.claim(owner, "revision", key, BillingWorkflowStore.Kind.QUESTION, range,
                UUID.randomUUID(), UUID.randomUUID(), "What changed?", now, now.plusSeconds(60));
        var work = claim.work();
        try {
            assertTrue(claim.acquired());
            assertFalse(store.claim(owner, "revision", key, work.kind(), range, work.parentReportId(), work.snapshotId(), work.question(), now, work.expiresAt()).acquired());
            assertThrows(BillingException.class, () -> store.claim(owner, "revision", key, work.kind(), range, work.parentReportId(), work.snapshotId(), "Changed payload", now, work.expiresAt()));
            assertThrows(BillingException.class, () -> store.claim(owner, "revision", "another", work.kind(), range, work.parentReportId(), work.snapshotId(), "Another", now, work.expiresAt()));
            assertTrue(store.find("other", "revision", work.id(), now).isEmpty());
            assertTrue(store.find(owner, "other", work.id(), now).isEmpty());
            assertTrue(store.stop(work.id()));
            assertFalse(store.advance(work.id(), BillingWorkflowStore.State.ANALYZING));
            assertFalse(store.answer(work.id(), new BillingReportAnalysis.Statement("Unavailable", List.of(), List.of()), now));
        } finally { store.fail(work.id(), "INTERRUPTED"); }
        assertEquals(BillingWorkflowStore.State.STOPPED, store.find(owner, "revision", work.id(), now).orElseThrow().state());
        assertTrue(store.find(owner, "revision", work.id(), now.plusSeconds(61)).isEmpty());
    }

    @Test void answerRoundTripsAndRecoveryDoesNotReplayUnfinishedWork() {
        String owner = "workflow-" + UUID.randomUUID();
        var work = store.claim(owner, "r", UUID.randomUUID().toString(), BillingWorkflowStore.Kind.QUESTION, range,
                UUID.randomUUID(), UUID.randomUUID(), "Saved question", now, now.plusSeconds(60)).work();
        var answer = new BillingReportAnalysis.Statement("Insufficient utilization evidence.", List.of(), List.of());
        try {
            assertTrue(store.answer(work.id(), answer, now));
            assertFalse(store.answer(work.id(), answer, now));
            assertEquals(answer, store.answer(owner, "r", work.id(), now).orElseThrow());
            assertTrue(store.answer("other", "r", work.id(), now).isEmpty());
            store.clearExpired(work.id());
            assertTrue(store.answer(owner, "r", work.id(), now).isEmpty());
            store.cleanupFinished(work.id());
        } finally { store.fail(work.id(), "TEST_CLEANUP"); }
        var interrupted = store.claim(owner, "r", UUID.randomUUID().toString(), BillingWorkflowStore.Kind.GENERATION,
                range, null, null, null, now, now.plusSeconds(60)).work();
        store.recover();
        assertEquals(BillingWorkflowStore.State.INTERRUPTED, store.find(owner, "r", interrupted.id(), now).orElseThrow().state());
    }
}
