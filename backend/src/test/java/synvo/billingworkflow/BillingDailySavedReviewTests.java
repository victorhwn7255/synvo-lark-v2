package synvo.billingworkflow;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import synvo.billing.BillingData;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in read of an operator-selected existing local report, with no provider calls or file export. */
class BillingDailySavedReviewTests {
    @Test @EnabledIfSystemProperty(named = "billing.daily.review", matches = "[0-9a-f-]{36}")
    void reconcilesTheExistingSavedSnapshotWithoutExportingEnterpriseRows() throws Exception {
        UUID id = UUID.fromString(System.getProperty("billing.daily.review"));
        var mapper = new ObjectMapper();
        var report = mapper.readValue(query("SELECT document::text FROM billing_workflow_report WHERE work_id='" + id + "'"), BillingWorkflowStore.Report.class);
        var rows = query("SELECT evidence::text FROM billing_cost_evidence WHERE snapshot_id='" + report.snapshotId() + "' ORDER BY dataset,part,ordinal");
        var view = BillingDailyCosts.calculate(report, null, LocalDate.now(ZoneOffset.UTC), sink -> rows.lines().forEach(line -> sink.accept(mapper.readValue(line, BillingData.CostRow.class))));
        assertEquals(0, report.facts().selectedTotal().compareTo(new java.math.BigDecimal(view.selectedTotal().exact())));
        assertEquals(0, report.facts().baselineTotal().compareTo(new java.math.BigDecimal(view.comparisonTotal().exact())));
        assertEquals(view.recordedDays(), view.days().stream().filter(day -> day.amount() != null).count());
        assertTrue(view.allRecordedDays() > 0); assertTrue(mapper.writeValueAsString(view).length() < 1024 * 1024);
        System.out.println("Saved daily projection: reconciliation passed; recorded days=" + view.allRecordedDays() + "; spillover rows=" + view.spilloverRows());
        System.out.println("Distribution scale: bands=" + view.bands().size() + "; visible-year positive days per band=" + view.bands().stream()
                .map(band -> view.days().stream().filter(day -> day.level() == band.level()).count()).toList());
    }
    private String query(String sql) throws Exception {
        var process = new ProcessBuilder("docker", "compose", "-f", "compose.yaml", "-f", "compose.codex.yaml", "-f", "compose.billing.yaml", "-f", "compose.billing-workflow.yaml",
                "exec", "-T", "postgres", "psql", "-U", "synvo", "-d", "synvo", "-v", "ON_ERROR_STOP=1", "-Atc", sql)
                .directory(Path.of("..").toRealPath().toFile()).start();
        String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), "Local read-only query failed");
        return result;
    }
}
