package synvo.billingworkflow;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BillingReportPresentationTests {
    @Test void formatsExactDecimalsWithoutLosingTinyValuesOrUsingBinaryFloatingPoint() {
        assertEquals("USD 271.25", BillingReportPresentation.money(new BigDecimal("271.245459066466110592")));
        assertEquals("−USD 216.82", BillingReportPresentation.money(new BigDecimal("-216.8179967407536")));
        assertEquals("USD 1,000.00", BillingReportPresentation.money(new BigDecimal("999.995")));
        assertEquals("< USD 0.01", BillingReportPresentation.money(new BigDecimal("0.00003375275")));
        assertEquals("< USD 0.01 (negative)", BillingReportPresentation.money(new BigDecimal("-0.0001")));
        assertEquals("USD 0.00", BillingReportPresentation.money(BigDecimal.ZERO));
        assertEquals("Unavailable", BillingReportPresentation.money(null));
        assertEquals("USD 9,007,199,254,740,993.01", BillingReportPresentation.money(new BigDecimal("9007199254740993.005")));
    }
    @Test void caseInsensitiveDisplayGroupsPreserveExactSumAndOriginalFacts() {
        var values = Map.of("Microsoft.CognitiveServices", new BigDecimal("0.1749152"),
                "MICROSOFT.COGNITIVESERVICES", new BigDecimal("0.00003375275"), "Microsoft.Storage", new BigDecimal("20"));
        var display = BillingReportPresentation.rows(values, true, false);
        assertEquals(2, display.size()); assertEquals("Storage", display.getFirst().label());
        assertEquals("AI services", display.getLast().label()); assertEquals("USD 0.17", display.getLast().amount());
        assertEquals(3, values.size());
        assertEquals(0, BillingReportPresentation.grouped(values).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .compareTo(values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)));
        assertEquals("Jun 2026", BillingReportPresentation.month("month:2026-06"));
    }
    @Test void distinguishesMissingInvoiceEvidenceAndUnavailableComparison() {
        var snapshot = new BillingAnalysisPackageTests().snapshot();
        var facts = new BillingReportFacts(new BigDecimal("0.30"), null, null, null,
                Map.of(), Map.of(), Map.of(), Map.of(), snapshot.summary().selected().buckets());
        var report = new BillingWorkflowStore.Report(java.util.UUID.randomUUID(), snapshot.id(), snapshot.range(), snapshot.createdAt(), snapshot.expiresAt(), facts, snapshot.summary(), null, null, null, null);
        var presentation = BillingReportPresentation.of(report);
        assertTrue(presentation.coverage().getFirst().contains("not invoice-reconciled"));
        assertTrue(presentation.highlights().stream().noneMatch(value -> value.contains("decreased") || value.contains("Comparison caution")));
        assertFalse(presentation.highlights().getFirst().contains("averaged"));
        assertFalse(presentation.highlights().getFirst().contains("% of spending"));
    }
    @Test void flagsBaselineConcentrationWithoutInventingCauseOrSavings() throws Exception {
        var snapshot = new BillingAnalysisPackageTests().snapshot();
        var baseline = new synvo.billing.BillingData.Totals(snapshot.summary().selected().buckets(), Map.of(),
                Map.of("month:2026-03", new BigDecimal("70"), "month:2026-04", new BigDecimal("15"), "month:2026-05", new BigDecimal("15")));
        var old = snapshot.summary();
        var source = new synvo.billing.BillingData.Summary(old.currency(), old.basis(), old.mappingVersion(), old.calculationVersion(), old.selected(), baseline,
                old.comparison(), old.partitions(), old.reconciliation(), old.invoices(), old.limitations());
        var facts = new BillingReportFacts(new BigDecimal("60"), new BigDecimal("100"), new BigDecimal("-40"), new BigDecimal("-40"),
                Map.of("Microsoft.Storage", new BigDecimal("30"), "microsoft.network", new BigDecimal("20"), "Microsoft.Compute", new BigDecimal("10")),
                Map.of("month:2026-06", new BigDecimal("20"), "month:2026-07", new BigDecimal("20"), "month:2026-08", new BigDecimal("20")), Map.of(), Map.of(), old.selected().buckets());
        var range = new synvo.billing.BillingData.Range(java.time.YearMonth.of(2026,6), java.time.YearMonth.of(2026,8), true);
        var report = new BillingWorkflowStore.Report(java.util.UUID.randomUUID(), snapshot.id(), range, snapshot.createdAt(), snapshot.expiresAt(), facts, source, null, null, null, null);
        var view = BillingReportPresentation.of(report);
        assertEquals(3, view.baselineMonths().size());
        assertEquals("Jun 2026 – Aug 2026 Azure subscription costs averaged USD 20.00 per month. Storage and Networking accounted for about 83% of spending. The headline 40.00% reduction is heavily influenced by Mar 2026's high cost. Recurring savings have not been established.", view.highlights().getFirst());
        assertTrue(view.highlights().stream().anyMatch(value -> value.contains("Mar 2026 alone contributed USD 70.00") && value.contains("does not establish its cause")));
        assertTrue(view.highlights().stream().anyMatch(value -> value.contains("not proof of recurring savings")));
        assertEquals(3, view.coverage().size());
        byte[] pdf = new BillingReportPdf().render(report);
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document).replaceAll("\\s+", " ");
            assertTrue(text.contains(view.highlights().getFirst()));
        }
        java.nio.file.Files.write(java.nio.file.Path.of("target/billing-management-lead.pdf"), pdf);
    }
}
