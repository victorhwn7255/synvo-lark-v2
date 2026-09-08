package synvo.billingworkflow;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import synvo.TestcontainersConfiguration;
import synvo.agent.*;
import synvo.billing.BillingException;
import synvo.workspaceagent.WorkspaceAgentFacade;
import synvo.workspaceagent.WorkspaceAgentEngine.RunMode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest @ActiveProfiles("test") @Import(TestcontainersConfiguration.class)
class BillingWorkflowFacadeTests {
    @Autowired BillingWorkflowStore store;
    @TempDir Path root;
    private final BillingAnalysisPackageTests fixture = new BillingAnalysisPackageTests();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Instant now = Instant.parse("2026-09-08T00:00:00Z");

    @Test void persistsValidatedReportPdfAndBoundFollowupWithoutRefetchThenCleansExpiredFilesAndTask() throws Exception {
        var source = fixture.source();
        when(source.available("owner")).thenReturn(true);
        when(source.request(any(), any(), any(), any())).thenReturn(fixture.snapshot());
        when(source.summary("owner", fixture.snapshot().id())).thenReturn(fixture.snapshot().summary());
        var tasks = mock(WorkspaceAgentFacade.class);
        var task = task();
        when(tasks.createWorkflowTask(any(), any(), any())).thenReturn(task);
        when(tasks.workflowTask("owner", task.taskId())).thenReturn(task);
        var coordinator = mock(ConversationRunCoordinator.class);
        when(coordinator.run(any(ConversationRequest.class), any(), any())).thenAnswer(call -> {
            ConversationRequest request = call.getArgument(0);
            assertEquals("Run the authorized workflow analysis.", request.content());
            String prompt = request.workspaceInput().text();
            assertTrue(prompt.contains("input/{manifest.json,facts.json,evidence.jsonl}"));
            var statement = new BillingReportAnalysis.Statement("Cost is [[fact:/selectedTotal]].", List.of("fact:/selectedTotal"), List.of(new BillingReportAnalysis.Claim("/selectedTotal", "0.30")));
            String text;
            if (prompt.contains("Return one statement JSON")) {
                assertTrue(prompt.contains("at most 12000 characters total"));
                assertFalse(prompt.contains("32000 characters"));
                assertTrue(prompt.contains("12 references total"));
                text = mapper.writeValueAsString(statement);
            }
            else {
                var id = UUID.fromString(prompt.split("Report ID: ")[1].substring(0,36));
                text = mapper.writeValueAsString(new BillingReportAnalysis.Draft(id, fixture.snapshot().id(), statement, List.of(statement), List.of(), List.of()));
            }
            return new ConversationResult(task.conversationId(), UUID.randomUUID(), null, ConversationResult.Outcome.DIRECT_ANSWER, ConversationResult.Status.COMPLETED, text, List.of(), false);
        });
        var packages = new BillingAnalysisPackage(source, root.toRealPath(), Clock.fixed(now, ZoneOffset.UTC), mapper);
        try (var facade = facade(source, packages, tasks, coordinator, now)) {
            var work = facade.generate("owner", fixture.snapshot().range(), UUID.randomUUID().toString(), true);
            assertEquals(BillingWorkflowStore.State.COMPLETE, await(facade, work.id()).state());
            assertEquals("0.30", facade.reportView("owner", work.id()).facts().get("selectedTotal"));
            assertEquals("Cost is USD 0.30.", facade.reportView("owner", work.id()).analysis().executiveSummary().text());
            assertTrue(facade.pdf("owner", work.id()).length > 500);
            assertEquals(facade.work("owner", work.id()).expiresAt(), facade.reportView("owner", work.id()).expiresAt());
            assertThrows(BillingException.class, () -> facade.report("other", work.id()));
            var question = facade.ask("owner", work.id(), "What did we spend?", UUID.randomUUID().toString());
            assertEquals(BillingWorkflowStore.State.COMPLETE, await(facade, question.id()).state());
            assertEquals("Cost is USD 0.30.", facade.answerView("owner", question.id()).text());
            assertTrue(facade.savedAnalyses("owner", null).items().stream().anyMatch(item -> item.id().equals(work.id())));
            assertEquals(question.id(), facade.questions("owner", work.id(), null).items().getFirst().id());
            byte[] savedPdf = store.pdf("owner", "revision", work.id(), now).orElseThrow();
            var savedReport = facade.report("owner", work.id());
            var tooManyRefs = new BillingReportAnalysis.Statement("Concise summary.", java.util.Collections.nCopies(13, "fact:/selectedTotal"), List.of());
            when(coordinator.run(any(ConversationRequest.class), any(), any())).thenReturn(new ConversationResult(task.conversationId(), UUID.randomUUID(), null,
                    ConversationResult.Outcome.DIRECT_ANSWER, ConversationResult.Status.COMPLETED, mapper.writeValueAsString(tooManyRefs), List.of(), false));
            var failedQuestion = facade.ask("owner", work.id(), "Show evidence", UUID.randomUUID().toString());
            var failed = await(facade, failedQuestion.id());
            assertEquals(BillingWorkflowStore.State.FAILED, failed.state());
            assertEquals("ANSWER_TOO_MANY_REFERENCES", failed.failure());
            assertNull(facade.answer("owner", failed.id()));
            assertEquals(savedReport, facade.report("owner", work.id()));
            assertArrayEquals(savedPdf, store.pdf("owner", "revision", work.id(), now).orElseThrow());
            verify(coordinator, times(3)).run(any(ConversationRequest.class), any(), any());
            assertThrows(BillingException.class, () -> facade.questions("other", work.id(), null));
            verify(source, times(1)).request(any(), any(), any(), any());
            try (var expired = facade(source, packages, tasks, coordinator, now.plusSeconds(4000))) { expired.cleanup(); }
            assertFalse(java.nio.file.Files.exists(root.resolve(work.id().toString())));
            verify(tasks).deleteWorkflowTask("owner", task.taskId());
        }
    }

    @Test void legacyReportAndPdfCannotAdvertiseSourceExpiryBeyondWorkAccess() throws Exception {
        var source = fixture.source();
        var work = store.claim("owner", "revision", UUID.randomUUID().toString(), BillingWorkflowStore.Kind.GENERATION,
                fixture.snapshot().range(), null, fixture.snapshot().id(), null, now, now.plusSeconds(120)).work();
        var packages = new BillingAnalysisPackage(source, root.toRealPath(), Clock.fixed(now, ZoneOffset.UTC), mapper);
        var prepared = packages.prepare("owner", fixture.snapshot().id(), work.id(), work.id());
        var legacy = new BillingWorkflowStore.Report(work.id(), fixture.snapshot().id(), fixture.snapshot().range(), now,
                fixture.snapshot().expiresAt(), prepared.facts(), fixture.snapshot().summary(), null, null, null, null);
        assertTrue(store.publish(work.id(), legacy, new byte[]{1}, now));
        try (var facade = facade(source, packages, mock(WorkspaceAgentFacade.class), mock(ConversationRunCoordinator.class), now)) {
            assertEquals(work.expiresAt(), facade.report("owner", work.id()).expiresAt());
            var daily = facade.dailyCosts("owner", work.id(), 2026);
            assertEquals("0.30", daily.selectedTotal().exact());
            assertEquals("0.10", daily.comparisonTotal().exact());
            assertEquals(2, daily.recordedDays());
            assertEquals(work.expiresAt(), daily.expiresAt());
            assertThrows(BillingException.class, () -> facade.dailyCosts("other", work.id(), 2026));
            assertThrows(BillingException.class, () -> facade.dailyCosts("owner", work.id(), 2022));
            try (var document = org.apache.pdfbox.Loader.loadPDF(facade.pdf("owner", work.id()))) {
                String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
                assertTrue(text.contains("08 Sep 2026, 00:02 UTC"));
            }
            verify(source, never()).request(any(), any(), any(), any());
            assertTrue(facade.savedAnalyses("owner", null).items().stream().anyMatch(item -> item.id().equals(work.id())));
            assertThrows(BillingException.class, () -> facade.savedAnalyses("other", null));
            when(source.inspect("owner", fixture.snapshot().id())).thenThrow(new BillingException(BillingException.Reason.NOT_FOUND));
            assertTrue(facade.savedAnalyses("owner", null).items().stream().noneMatch(item -> item.id().equals(work.id())));
            assertThrows(BillingException.class, () -> facade.questions("owner", work.id(), null));
            assertThrows(BillingException.class, () -> facade.dailyCosts("owner", work.id(), 2026));
        }
    }

    @Test void invalidAnalysisGivesFactualReportAndExplicitRetryUsesSameSnapshot() throws Exception {
        var source = fixture.source(); when(source.available("owner")).thenReturn(true);
        when(source.request(any(), any(), any(), any())).thenReturn(fixture.snapshot());
        var tasks = mock(WorkspaceAgentFacade.class); when(tasks.createWorkflowTask(any(), any(), any())).thenReturn(task());
        var coordinator = mock(ConversationRunCoordinator.class);
        when(coordinator.run(any(ConversationRequest.class), any(), any())).thenReturn(new ConversationResult(UUID.randomUUID(), UUID.randomUUID(), null, ConversationResult.Outcome.DIRECT_ANSWER, ConversationResult.Status.COMPLETED, "invented invalid draft", List.of(), false));
        var packages = new BillingAnalysisPackage(source, root.toRealPath(), Clock.fixed(now, ZoneOffset.UTC), mapper);
        try (var facade = facade(source, packages, tasks, coordinator, now)) {
            var work = facade.generate("owner", fixture.snapshot().range(), UUID.randomUUID().toString(), true);
            assertEquals(BillingWorkflowStore.State.FACTUAL, await(facade, work.id()).state());
            assertNull(facade.report("owner", work.id()).analysis());
            var retry = facade.retry("owner", work.id(), UUID.randomUUID().toString(), true);
            assertEquals(BillingWorkflowStore.State.FACTUAL, await(facade, retry.id()).state());
            assertNull(work.snapshotId()); // Original accepted view predates source binding.
            assertEquals(facade.report("owner", work.id()).snapshotId(), facade.report("owner", retry.id()).snapshotId());
            verify(source, times(1)).request(any(), any(), any(), any());
        }
    }

    @Test void stopDuringAgentExecutionPreventsLatePublicationAndKeepsGlobalClaimUntilTerminal() throws Exception {
        var source = fixture.source(); when(source.available("owner")).thenReturn(true);
        when(source.request(any(), any(), any(), any())).thenReturn(fixture.snapshot());
        var tasks = mock(WorkspaceAgentFacade.class); when(tasks.createWorkflowTask(any(), any(), any())).thenReturn(task());
        var coordinator = mock(ConversationRunCoordinator.class);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(coordinator.run(any(ConversationRequest.class), any(), any())).thenAnswer(call -> {
            entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS));
            return new ConversationResult(UUID.randomUUID(), UUID.randomUUID(), null, ConversationResult.Outcome.DIRECT_ANSWER, ConversationResult.Status.COMPLETED, "late", List.of(), false);
        });
        var packages = new BillingAnalysisPackage(source, root.toRealPath(), Clock.fixed(now, ZoneOffset.UTC), mapper);
        try (var facade = facade(source, packages, tasks, coordinator, now)) {
            var work = facade.generate("owner", fixture.snapshot().range(), UUID.randomUUID().toString(), true);
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertEquals(BillingWorkflowStore.State.STOPPING, facade.stop("owner", work.id()).state());
                assertThrows(BillingException.class, () -> facade.generate("owner", fixture.snapshot().range(), UUID.randomUUID().toString(), true));
            } finally { release.countDown(); }
            assertEquals(BillingWorkflowStore.State.STOPPED, await(facade, work.id()).state());
            assertThrows(BillingException.class, () -> facade.report("owner", work.id()));
        }
    }
    private BillingWorkflowFacade facade(synvo.billing.BillingInsightsFacade source, BillingAnalysisPackage packages, WorkspaceAgentFacade tasks, ConversationRunCoordinator coordinator, Instant time) {
        return new BillingWorkflowFacade(store, source, packages, new BillingReportAnalysis(mapper), new BillingReportPdf(), tasks, coordinator, "owner", "revision", "billing", true, Clock.fixed(time, ZoneOffset.UTC));
    }
    private BillingWorkflowStore.Work await(BillingWorkflowFacade facade, UUID id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        BillingWorkflowStore.Work work;
        do { work = facade.work("owner", id); if (!work.active()) return work; Thread.sleep(20); } while (System.nanoTime() < deadline);
        fail("Billing worker did not terminate"); return work;
    }
    private WorkspaceAgentFacade.TaskView task() { return new WorkspaceAgentFacade.TaskView(UUID.randomUUID(), UUID.randomUUID(), "Synthetic", "billing", "Billing", RunMode.WORKSPACE_WRITE, false, false, now, now); }
}
