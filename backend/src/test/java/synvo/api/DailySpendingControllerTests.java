package synvo.api;

import org.junit.jupiter.api.Test;
import synvo.billingworkflow.DailySpendingView;
import synvo.billing.BillingException;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class DailySpendingControllerTests {
    @Test void independentEndpointsUseSessionIdentityNoStoreAndSafeBusy() throws Exception {
        var daily = mock(DailySpendingView.class);
        var sessions = mock(LarkSessionAccess.class);
        when(sessions.require(any())).thenReturn(new LarkSessionAccess.AuthorizedUser("owner", "Synthetic", null));
        var mvc = standaloneSetup(new DailySpendingController(daily, sessions)).build();
        mvc.perform(get("/api/billing-insights/daily?year=2026&owner=attacker")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(daily).read("owner", 2026);
        when(daily.refresh("owner", "request")).thenThrow(new BillingException(BillingException.Reason.BUSY));
        mvc.perform(post("/api/billing-insights/daily/refresh").contentType("application/json").content("{\"key\":\"request\",\"owner\":\"attacker\",\"scope\":\"other\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("BUSY")).andExpect(header().string("Cache-Control", "no-store"));
        verify(daily).refresh("owner", "request");
        mvc.perform(get("/api/billing-insights/daily?year=bad")).andExpect(status().isBadRequest());
        when(sessions.require(any())).thenThrow(new LarkSessionAccess.UnauthorizedSessionException());
        mvc.perform(get("/api/billing-insights/daily")).andExpect(status().isUnauthorized());
    }
}
