package synvo.billing;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BillingAccountingTests {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC);

    @Test void oneThreeAndSixMonthsIncludeLeapAndYearBoundariesWithoutOverlap() {
        for (int count : new int[]{1, 3, 6}) {
            var first = YearMonth.of(2024, 1);
            var range = new BillingData.Range(first, first.plusMonths(count - 1), true);
            range.validate(CLOCK);
            assertEquals(count, range.selected().size()); assertEquals(count, range.baseline().size());
            assertTrue(java.util.Collections.disjoint(range.selected(), range.baseline()));
        }
        assertEquals(LocalDate.of(2024, 2, 29), YearMonth.of(2024, 2).atEndOfMonth());
        assertThrows(BillingException.class, () -> new BillingData.Range(null, YearMonth.of(2026, 7), false).validate(CLOCK));
        assertThrows(BillingException.class, () -> new BillingData.Range(YearMonth.of(2027, 1), YearMonth.of(2027, 1), false).validate(CLOCK));
    }

    @Test void creditNotesRebillsAndUnknownInvoiceChargesAreNotComparable() {
        for (String type : List.of("CreditNote", "Unknown")) {
            var invoice = new BillingData.Invoice("example", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), type, false,
                    Map.of("billedAmount", BigDecimal.TEN), Map.of());
            assertEquals(BillingData.ReconciliationStatus.NOT_COMPARABLE, BillingAccounting.reconcile(invoice, BigDecimal.TEN, true).status());
        }
        var rebill = new BillingData.Invoice("example", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), "Invoice", true,
                Map.of("billedAmount", BigDecimal.TEN), Map.of());
        assertEquals(BillingData.ReconciliationStatus.NOT_COMPARABLE, BillingAccounting.reconcile(rebill, BigDecimal.TEN, true).status());
    }

    @Test
    void selectedAndBaselineMonthsAreDisjointAndCalendarBased() {
        var range = new BillingData.Range(YearMonth.of(2026, 1), YearMonth.of(2026, 3), true);
        range.validate(CLOCK);
        assertEquals(List.of(YearMonth.of(2025, 10), YearMonth.of(2025, 11), YearMonth.of(2025, 12)), range.baseline());
        assertEquals(3, range.selected().size());
        assertThrows(BillingException.class, () -> new BillingData.Range(YearMonth.of(2026, 9), YearMonth.of(2026, 9), false).validate(CLOCK));
        assertThrows(BillingException.class, () -> new BillingData.Range(YearMonth.of(2026, 1), YearMonth.of(2026, 7), false).validate(CLOCK));
        assertThrows(BillingException.class, () -> new BillingData.Range(YearMonth.of(2026, 3), YearMonth.of(2026, 1), false).validate(CLOCK));
    }

    @Test
    void roundingIsNotSubscriptionSpendAndIdenticalRowsAreNotDeduplicated() {
        var ledger = new BillingAccounting();
        ledger.add(row("0.123456789012345678", BillingData.Bucket.AZURE));
        ledger.add(row("0.123456789012345678", BillingData.Bucket.AZURE));
        ledger.add(row("-0.006913578024691356", BillingData.Bucket.PROFILE_ADJUSTMENT));
        var totals = ledger.totals();
        assertEquals(0, totals.sourceTotal().compareTo(new BigDecimal("0.24")));
        assertEquals(2, totals.buckets().get(BillingData.Bucket.AZURE).rows());
        assertEquals(0, totals.azureTotal().compareTo(new BigDecimal("0.246913578024691356")));
        assertEquals(totals.azureTotal(), totals.services().get("Compute"));
    }

    @Test
    void unknownAttributionIsRetainedEvenWhenItsAmountIsZero() {
        var ledger = new BillingAccounting();
        ledger.add(row("0", BillingData.Bucket.UNRESOLVED));
        assertFalse(ledger.totals().completeAttribution());
        assertEquals(1, ledger.totals().buckets().get(BillingData.Bucket.UNRESOLVED).rows());
    }

    @Test
    void precisionCannotBeSilentlyRoundedAndNegativeBaselineHasNoPercentage() {
        assertThrows(BillingException.class, () -> BillingAccounting.money("0.0000000000000000001"));
        assertThrows(BillingException.class, () -> BillingAccounting.money("1e100"));
        assertThrows(BillingException.class, () -> BillingAccounting.money("NaN"));
        assertNull(BillingAccounting.compare(new BigDecimal("12"), BigDecimal.ZERO).percent());
        assertNull(BillingAccounting.compare(new BigDecimal("12"), new BigDecimal("-1")).percent());
        assertEquals(new BigDecimal("20.00"), BillingAccounting.compare(new BigDecimal("12"), new BigDecimal("10")).percent());
    }

    @Test
    void invoiceUsesChargesNotCurrentAmountDueAndNeverInventsAnAdjustment() {
        var invoice = new BillingData.Invoice("invoice-example", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                "Invoice", false, Map.of("billedAmount", new BigDecimal("10.00"), "amountDue", BigDecimal.ZERO), Map.of());
        assertEquals(BillingData.ReconciliationStatus.MATCHED, BillingAccounting.reconcile(invoice, new BigDecimal("10.0001"), true).status());
        var mismatch = BillingAccounting.reconcile(invoice, new BigDecimal("9.99"), true);
        assertEquals(BillingData.ReconciliationStatus.MISMATCH, mismatch.status());
        assertEquals(new BigDecimal("0.01"), mismatch.residual());
        assertEquals(BillingData.ReconciliationStatus.NOT_COMPARABLE, BillingAccounting.reconcile(invoice, BigDecimal.TEN, false).status());
    }

    static BillingData.CostRow row(String amount, BillingData.Bucket bucket) {
        return new BillingData.CostRow("month:2026-07", 0, 1, LocalDate.of(2026, 7, 1), "USD", new BigDecimal(amount),
                bucket, bucket == BillingData.Bucket.AZURE ? "00000000-0000-4000-8000-000000000001" : "",
                "Compute", "Usage", "invoice-example", Map.of());
    }
}
