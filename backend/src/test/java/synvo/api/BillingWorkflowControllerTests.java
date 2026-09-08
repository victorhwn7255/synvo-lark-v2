package synvo.api;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import synvo.billing.BillingData;
import synvo.billing.BillingException;
import synvo.billingworkflow.BillingWorkflowFacade;
import synvo.billingworkflow.BillingWorkflowStore;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class BillingWorkflowControllerTests {
    private final BillingWorkflowFacade facade = mock(BillingWorkflowFacade.class);
    private MockMvc mvc;
    private final UUID id = UUID.randomUUID();
    @BeforeEach void setup() {
        var session = mock(LarkSessionAccess.class);
        when(session.require(any())).thenReturn(new LarkSessionAccess.AuthorizedUser("owner", "Synthetic", null));
        mvc = standaloneSetup(new BillingWorkflowController(facade, session)).build();
    }
    @Test void usesSessionIdentityAndNoStoreWithoutExposingInternalBindings() throws Exception {
        var work = work(); when(facade.recent("owner")).thenReturn(List.of(work)); when(facade.available("owner")).thenReturn(true);
        mvc.perform(get("/api/billing-insights")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.reportId").value(id.toString())).andExpect(jsonPath("$.works[0].owner").doesNotExist())
                .andExpect(jsonPath("$.works[0].taskId").doesNotExist()).andExpect(jsonPath("$.works[0].snapshotId").doesNotExist());
        when(facade.generate(eq("owner"), any(), eq("request-1"), eq(true))).thenReturn(work);
        mvc.perform(post("/api/billing-insights/generations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"first\":\"2026-07\",\"last\":\"2026-08\",\"key\":\"request-1\",\"workspaceWrite\":true,\"owner\":\"attacker\"}"))
                .andExpect(status().isOk());
        verify(facade).generate("owner", new BillingData.Range(YearMonth.of(2026,7), YearMonth.of(2026,8), true), "request-1", true);
    }
    @Test void pdfEvidenceAndErrorsStayOwnerScopedAndPrivate() throws Exception {
        when(facade.pdf("owner", id)).thenReturn(new byte[]{37,80,68,70});
        mvc.perform(get("/api/billing-insights/reports/{id}/pdf", id)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(content().contentType(MediaType.APPLICATION_PDF));
        when(facade.evidenceView("owner", id, 50, 50)).thenThrow(new BillingException(BillingException.Reason.FORBIDDEN));
        mvc.perform(get("/api/billing-insights/reports/{id}/evidence?offset=50", id)).andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.error").value("FORBIDDEN"));
        mvc.perform(post("/api/billing-insights/generations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"first\":\"invalid\",\"last\":\"2026-08\",\"key\":\"request\",\"workspaceWrite\":true}"))
                .andExpect(status().isBadRequest());
    }
    @Test void historyAndQuestionsUseSessionScopeBoundedPagesAndNoStore() throws Exception {
        UUID before = UUID.randomUUID();
        when(facade.savedAnalyses("owner", before)).thenReturn(new BillingWorkflowFacade.Page<>(List.of(
                new BillingWorkflowFacade.SavedAnalysis(id, "2026-07", "2026-08", Instant.parse("2026-09-08T00:00:00Z"), BillingWorkflowStore.State.COMPLETE)), null));
        mvc.perform(get("/api/billing-insights/reports").param("before", before.toString()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].id").value(id.toString())).andExpect(jsonPath("$.items[0].owner").doesNotExist());
        when(facade.questions("owner", id, null)).thenReturn(new BillingWorkflowFacade.Page<>(List.of(work()), null));
        mvc.perform(get("/api/billing-insights/reports/{id}/questions", id)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.items[0].taskId").doesNotExist());
        mvc.perform(get("/api/billing-insights/reports?before=invalid")).andExpect(status().isBadRequest());
        verify(facade).savedAnalyses("owner", before); verify(facade).questions("owner", id, null);
    }
    @Test void dailyCostsUseSessionAndRejectInvalidOrUnavailableYear() throws Exception {
        mvc.perform(get("/api/billing-insights/reports/{id}/daily-costs", id).param("owner", "attacker"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(facade).dailyCosts("owner", id, null);
        when(facade.dailyCosts("owner", id, 2022)).thenThrow(new BillingException(BillingException.Reason.INVALID_REQUEST));
        mvc.perform(get("/api/billing-insights/reports/{id}/daily-costs?year=2022", id)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/billing-insights/reports/{id}/daily-costs?year=wrong", id)).andExpect(status().isBadRequest());
        when(facade.dailyCosts("owner", id, 2026)).thenThrow(new BillingException(BillingException.Reason.NOT_FOUND));
        mvc.perform(get("/api/billing-insights/reports/{id}/daily-costs?year=2026", id)).andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"));
    }
    private BillingWorkflowStore.Work work() {
        return new BillingWorkflowStore.Work(id,"owner","revision","request-1",BillingWorkflowStore.Kind.GENERATION,
                new BillingData.Range(YearMonth.of(2026,7), YearMonth.of(2026,8), true),null,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                BillingWorkflowStore.State.COMPLETE,null,null,Instant.now(),Instant.now().plusSeconds(1000));
    }
}
