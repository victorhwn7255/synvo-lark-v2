package synvo.billingworkflow;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import synvo.billing.BillingException;
import static org.junit.jupiter.api.Assertions.*;

class BillingReportPdfTests {
    private final synvo.billing.BillingData.Snapshot snapshot = new BillingAnalysisPackageTests().snapshot();

    @Test void rendersExactFactsProvenanceLimitationsAndPagination() throws Exception {
        var facts = facts(Map.of("Synthetic Compute", new BigDecimal("0.30")));
        byte[] bytes = new BillingReportPdf().render(UUID.fromString("00000000-0000-0000-0000-000000000003"), snapshot, facts, null);
        try (var pdf = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("USD 0.30"));
            assertTrue(text.contains("Analysis unavailable"));
            assertTrue(text.contains("SOURCE FINALITY NOT ESTABLISHED"));
            assertTrue(text.contains("Snapshot: " + snapshot.id()));
            assertTrue(text.contains("Invoice reconciliation unavailable"));
            assertTrue(pdf.getNumberOfPages() >= 2);
            assertNull(pdf.getDocumentCatalog().getOpenAction());
            for (var page : pdf.getPages()) assertTrue(page.getAnnotations().isEmpty());
        }
        Files.write(Path.of("target/synthetic-billing-report.pdf"), bytes);
    }

    @Test void longLabelsWrapAndUnsupportedGlyphsFailExplicitly() throws Exception {
        var facts = facts(Map.of("Synthetic-".repeat(40), new BigDecimal("-0.123456")));
        try (var pdf = Loader.loadPDF(new BillingReportPdf().render(UUID.randomUUID(), snapshot, facts, null))) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("−USD 0.12"));
        }
        assertThrows(BillingException.class, () -> new BillingReportPdf().render(UUID.randomUUID(), snapshot,
                facts(Map.of("Unsupported glyph 🦄", BigDecimal.ONE)), null));
    }

    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "billing.review.file", matches = ".+")
    void renderUserAuthorizedSavedReportWithoutAzureOrModelCalls() throws Exception {
        Path input = Path.of(System.getProperty("billing.review.file"));
        var mapper = new tools.jackson.databind.ObjectMapper();
        var report = mapper.readValue(Files.readString(input), BillingWorkflowStore.Report.class);
        byte[] result = new BillingReportPdf().render(report);
        try (var pdf = Loader.loadPDF(result)) {
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("What your team should know"));
            assertTrue(text.contains("Available until:"));
            assertFalse(text.contains("271.245459066466110592"));
        }
        Files.write(input.resolveSibling("readable-billing-insights.pdf"), result);
        Files.writeString(input.resolveSibling("presentation.json"), mapper.writeValueAsString(BillingReportPresentation.of(report)));
    }

    private BillingReportFacts facts(Map<String, BigDecimal> services) {
        return new BillingReportFacts(new BigDecimal("0.30"), new BigDecimal("0.10"), new BigDecimal("0.20"),
                new BigDecimal("200.00"), services, Map.of("month:2026-07", new BigDecimal("0.30")),
                Map.of("00000000-0000-0000-0000-000000000001", new BigDecimal("0.30")),
                Map.of("Synthetic Compute", new BigDecimal("0.20")), snapshot.summary().selected().buckets());
    }
}
