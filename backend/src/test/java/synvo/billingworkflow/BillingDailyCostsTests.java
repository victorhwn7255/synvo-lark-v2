package synvo.billingworkflow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import synvo.billing.BillingData;
import synvo.billing.BillingException;
import static org.junit.jupiter.api.Assertions.*;

class BillingDailyCostsTests {
    private BillingData.CostRow row(String dataset, String date, String value, String service, BillingData.Bucket bucket) {
        return new BillingData.CostRow(dataset, 0, 1, LocalDate.parse(date), "USD", new BigDecimal(value), bucket,
                "00000000-0000-0000-0000-000000000001", service, "Usage", "", Map.of());
    }
    private BillingData.CostRow row(String dataset, String date, String value) { return row(dataset, date, value, "Microsoft.Storage", BillingData.Bucket.AZURE); }
    private BillingWorkflowStore.Report report(List<BillingData.CostRow> rows, boolean baseline, String selectedMonth) {
        var snapshot = new BillingAnalysisPackageTests().snapshot();
        var month = YearMonth.parse(selectedMonth);
        var selected = rows.stream().filter(r -> r.dataset().equals("month:" + month) && r.bucket() == BillingData.Bucket.AZURE).map(BillingData.CostRow::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
        var prior = rows.stream().filter(r -> r.dataset().equals("month:" + month.minusMonths(1)) && r.bucket() == BillingData.Bucket.AZURE).map(BillingData.CostRow::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
        var parts = (baseline ? List.of("month:" + month, "month:" + month.minusMonths(1)) : List.of("month:" + month)).stream()
                .map(name -> new BillingData.Partition(name, snapshot.createdAt(), "v1", Map.of(), List.of(), Set.of(), Set.of(), false, false)).toList();
        var totals = new BillingData.Totals(Map.of(BillingData.Bucket.AZURE, new BillingData.Amount(0, selected)), Map.of(), Map.of());
        var source = new BillingData.Summary("USD", BillingData.BASIS, "v1", "v1", totals, null, null, parts, List.of(), List.of(), Set.of());
        var facts = new BillingReportFacts(selected, baseline ? prior : null, null, null, Map.of(), Map.of(), Map.of(), Map.of(), totals.buckets());
        return new BillingWorkflowStore.Report(UUID.randomUUID(), snapshot.id(), new BillingData.Range(month, month, true), snapshot.createdAt(), snapshot.expiresAt(), facts, source, null, null, null, null);
    }
    @Test void exactRowsRolesRefundsZeroAndInvoiceOverlap() {
        var rows = List.of(row("month:2026-07", "2026-07-01", "0.10"), row("month:2026-07", "2026-07-01", "0.10"),
                row("month:2026-07", "2026-07-01", "0.20", "MICROSOFT.STORAGE", BillingData.Bucket.AZURE),
                row("month:2026-06", "2026-07-01", "0.30"), row("invoice:overlap", "2026-07-01", "1000"),
                row("month:2026-07", "2026-07-02", "-2"), row("month:2026-07", "2026-07-03", "1"), row("month:2026-07", "2026-07-03", "-1"),
                row("month:2026-07", "2026-07-01", "-0.01", "Other", BillingData.Bucket.PROFILE_ADJUSTMENT));
        var view = BillingDailyCosts.calculate(report(rows, true, "2026-07"), null, LocalDate.of(2026,9,8), rows::forEach);
        assertEquals(365, view.days().size()); assertEquals(3, view.recordedDays());
        var first = view.days().stream().filter(d -> d.date().equals("2026-07-01")).findFirst().orElseThrow();
        assertEquals("0.70", first.amount().exact()); assertEquals("0.40", first.selected().exact()); assertEquals("0.30", first.comparison().exact());
        assertEquals(4, first.rows()); assertEquals(1, first.services().size()); assertEquals("Storage", first.services().getFirst().label());
        assertEquals("-1.60", view.selectedTotal().exact()); assertEquals("-0.01", view.excluded().getFirst().amount().exact()); assertEquals(1, view.spilloverRows());
        assertEquals("NEGATIVE", view.days().get(182).state()); assertEquals("ZERO", view.days().get(183).state());
        assertEquals("MISSING", view.days().getFirst().state()); assertNull(view.days().getFirst().amount());
        assertEquals("FUTURE", view.days().getLast().state());
    }
    @Test void leapYearSpilloversAndStablePreciseBands() {
        var rows = List.of(row("month:2024-02", "2024-02-29", "0.0001"), row("month:2024-01", "2023-12-31", "0.0004"));
        var report = report(rows, true, "2024-02");
        var leap = BillingDailyCosts.calculate(report, 2024, LocalDate.of(2024,3,1), rows::forEach);
        var prior = BillingDailyCosts.calculate(report, 2023, LocalDate.of(2024,3,1), rows::forEach);
        assertEquals(366, leap.days().size()); assertEquals("2024-02-29", leap.days().get(59).date());
        assertEquals(leap.bands(), prior.bands()); assertEquals("> USD 0.00 – ≤ USD 0.0001", leap.bands().getFirst().label());
        assertEquals("0.0004", leap.outsideYearTotal().exact()); assertEquals(1, leap.days().get(59).level());
        assertThrows(BillingException.class, () -> BillingDailyCosts.calculate(report, 2022, LocalDate.of(2024,3,1), rows::forEach));
    }
    @Test void incompleteNegativeOnlyAndFutureRecordsAreNotFabricatedCoverage() {
        var rows = List.of(row("month:2026-07", "2027-01-01", "-1"));
        var view = BillingDailyCosts.calculate(report(rows, false, "2026-07"), 2027, LocalDate.of(2026,9,8), rows::forEach);
        assertTrue(view.bands().isEmpty()); assertEquals("FUTURE_RECORDED", view.days().getFirst().state());
        assertTrue(view.limitations().stream().anyMatch(s -> s.contains("incomplete")));
        assertTrue(view.limitations().stream().anyMatch(s -> s.contains("Future-dated")));
        var empty = BillingDailyCosts.calculate(report(List.of(), false, "2026-07"), null, LocalDate.of(2026,9,8), sink -> {});
        assertEquals(0, empty.recordedDays()); assertTrue(empty.bands().isEmpty()); assertNull(empty.days().getFirst().amount());
    }
    @Test void topFiveOtherConservesSignedCostAndMismatchedFactsFailClosed() {
        var rows = java.util.stream.IntStream.rangeClosed(1, 8).mapToObj(i -> row("month:2026-07", "2026-07-01", Integer.toString(i == 8 ? -8 : i), "Service " + i, BillingData.Bucket.AZURE)).toList();
        var report = report(rows, false, "2026-07");
        var view = BillingDailyCosts.calculate(report, null, LocalDate.of(2026,9,8), rows::forEach);
        var day = view.days().stream().filter(d -> d.amount() != null).findFirst().orElseThrow();
        assertEquals(6, day.services().size()); assertEquals("Other", day.services().getLast().label());
        assertEquals(0, new BigDecimal(day.amount().exact()).compareTo(day.services().stream().map(s -> new BigDecimal(s.amount().exact())).reduce(BigDecimal.ZERO, BigDecimal::add)));
        assertThrows(BillingException.class, () -> BillingDailyCosts.calculate(report, null, LocalDate.of(2026,9,8), sink -> {}));
    }
    @Test void quintilesSpreadOrdinaryCostsDespiteOutliersWithoutChangingTotals() {
        var rows = java.util.stream.IntStream.rangeClosed(1, 10).mapToObj(i -> row("month:2026-07", String.format("2026-07-%02d", i), i == 10 ? "500" : Integer.toString(i))).toList();
        var view = BillingDailyCosts.calculate(report(rows, false, "2026-07"), null, LocalDate.of(2026,9,8), rows::forEach);
        assertEquals(List.of("2.00", "4.00", "6.00", "8.00", "500.00"), view.bands().stream().map(BillingDailyCosts.Band::upper).toList());
        assertEquals(List.of(1,1,2,2,3,3,4,4,5,5), view.days().stream().filter(d -> d.amount() != null).map(BillingDailyCosts.Day::level).toList());
        assertEquals("545", view.selectedTotal().exact());
        assertEquals("daily-recorded-v2", view.calculationVersion());
    }
    @Test void tiesAndSubCentNoiseDoNotManufactureShades() {
        var rows = java.util.stream.IntStream.rangeClosed(1, 10).mapToObj(i -> row("month:2026-07", String.format("2026-07-%02d", i), i % 2 == 0 ? "2.991" : "2.992")).toList();
        var view = BillingDailyCosts.calculate(report(rows, false, "2026-07"), null, LocalDate.of(2026,9,8), rows::forEach);
        assertEquals(1, view.bands().size()); assertEquals("3.00", view.bands().getFirst().upper());
        assertTrue(view.days().stream().filter(d -> d.amount() != null).allMatch(d -> d.level() == 1));
        assertEquals("29.915", view.selectedTotal().exact());
    }
}
