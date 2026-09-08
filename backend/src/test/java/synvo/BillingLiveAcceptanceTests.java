package synvo;

import java.time.YearMonth;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import synvo.billing.BillingData;
import synvo.billing.BillingInsightsFacade;
import synvo.billing.BillingSource;
import synvo.billing.BillingException;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.junit.jupiter.api.Assertions.*;

/** Deliberate opt-in only. No company credentials, billing values or raw evidence in test reports. */
@EnabledIfEnvironmentVariable(named = "SYNVO_BILLING_LIVE_TEST", matches = "approved-june-july-2026")
@SpringBootTest(properties = {"synvo.billing.enabled=true", "synvo.lark.enabled=false", "synvo.codex.enabled=false",
        "synvo.model.enabled=false", "logging.level.root=ERROR"})
class BillingLiveAcceptanceTests {
    @Autowired private BillingInsightsFacade facade;
    @MockitoSpyBean private BillingSource source;

    @BeforeEach void safeSourceDiagnostics() {
        org.mockito.Mockito.doAnswer(invocation -> {
            BillingSource.Session real;
            try { real = (BillingSource.Session) invocation.callRealMethod(); }
            catch (BillingException error) { diagnostic("open", error); throw error; }
            return new BillingSource.Session() {
                @Override public BillingData.Partition month(YearMonth month, java.util.function.Consumer<BillingData.CostRow> sink) {
                    try { return real.month(month, sink); }
                    catch (BillingException error) { diagnostic("month", error); throw error; }
                }
                @Override public java.util.List<BillingData.Invoice> invoices(java.util.Set<String> ids, java.time.LocalDate first, java.time.LocalDate last) {
                    try { return real.invoices(ids, first, last); }
                    catch (BillingException error) { diagnostic("invoice-metadata", error); throw error; }
                }
                @Override public BillingData.Partition invoice(String id, java.util.function.Consumer<BillingData.CostRow> sink) {
                    try { return real.invoice(id, sink); }
                    catch (BillingException error) { diagnostic("invoice-charges", error); throw error; }
                }
                @Override public void close() { real.close(); }
            };
        }).when(source).open();
    }

    private static void diagnostic(String stage, BillingException error) {
        System.out.println("BILLING_DIAGNOSTIC stage=" + stage + " reason=" + error.reason());
        java.util.Arrays.stream(error.getStackTrace()).filter(frame -> frame.getClassName().startsWith("synvo.integration.azurebilling"))
                .limit(5).forEach(frame -> System.out.println("BILLING_DIAGNOSTIC source=" + frame.getFileName() + ":" + frame.getLineNumber()));
    }

    @DynamicPropertySource static void preflight(DynamicPropertyRegistry registry) {
        if (!"true".equals(System.getenv("SYNVO_BILLING_LIVE_STORAGE_VERIFIED"))) {
            throw new IllegalStateException("Live storage and backup verification required before context creation");
        }
        String url = required("SPRING_DATASOURCE_URL");
        String password = required("SPRING_DATASOURCE_PASSWORD");
        if (password.length() < 20 || password.equals("synvo_local") || !url.startsWith("jdbc:postgresql://127.0.0.1:")) {
            throw new IllegalStateException("Use the approved isolated local database with a non-default password");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.username", () -> required("SPRING_DATASOURCE_USERNAME"));
        registry.add("synvo.lark.pilot-open-id", () -> required("SYNVO_LARK_PILOT_OPEN_ID"));
    }

    @Test void authorizedJulyCostsJuneBaselineAndInvoiceBridgePass() throws Exception {
        String owner = required("SYNVO_LARK_PILOT_OPEN_ID");
        var request = facade.request(owner, BillingData.SOURCE_ID,
                new BillingData.Range(YearMonth.of(2026, 7), YearMonth.of(2026, 7), true), UUID.randomUUID().toString());
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(31);
        BillingData.Snapshot result = request;
        while (result.state() == BillingData.State.RUNNING && System.nanoTime() < deadline) {
            Thread.sleep(1000); result = facade.inspect(owner, request.id());
        }
        assertTrue(result.state() == BillingData.State.READY || result.state() == BillingData.State.READY_WITH_LIMITATIONS,
                "Live import did not publish validated evidence; inspect only its safe failure category locally");
        var summary = facade.summary(owner, request.id());
        assertTrue(summary.selected().completeAttribution(), "Selected attribution incomplete");
        assertNotNull(summary.comparison(), "Live baseline comparison unavailable");
        assertTrue(summary.reconciliation().stream().anyMatch(bridge -> bridge.status() == BillingData.ReconciliationStatus.MATCHED),
                "No independently matched invoice charge total; Phase 2 live gate remains open");
        assertFalse(summary.reconciliation().stream().anyMatch(bridge -> bridge.status() == BillingData.ReconciliationStatus.MISMATCH),
                "Unexplained invoice residual remains");
        System.out.println("Billing live gate: selected coverage, baseline and matching invoice verified; financial values withheld.");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Required live configuration missing: " + name);
        return value;
    }
}
