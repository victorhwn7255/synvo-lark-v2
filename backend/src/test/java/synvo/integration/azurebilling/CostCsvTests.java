package synvo.integration.azurebilling;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import synvo.billing.BillingData;
import synvo.billing.BillingException;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class CostCsvTests {
    private static final String HEADER = "billingProfileId,SubscriptionId,date,chargeType,costInBillingCurrency,BillingCurrency,consumedService,invoiceId\r\n";
    private static final String ROW = "profile,,2026-07-01,RoundingAdjustment,-0.001,USD,Compute,invoice-example\r\n";

    @Test void mixedCaseAndKnownRoundingRetainSourceRowsAndHash() {
        var rows = new ArrayList<BillingData.CostRow>();
        var result = parse("\uFEFF" + HEADER + ROW + ROW, rows);
        assertEquals(2, result.part().rows()); assertEquals(64, result.part().sha256().length());
        assertTrue(result.attributed()); assertEquals(BillingData.Bucket.PROFILE_ADJUSTMENT, rows.getFirst().bucket());
        assertEquals(2, rows.getLast().ordinal());
    }

    @Test void unknownUnattributedChargeIsALimitationNotDroppedMoney() {
        var rows = new ArrayList<BillingData.CostRow>();
        assertFalse(parse(HEADER + ROW.replace("RoundingAdjustment", "UnrecognizedCharge"), rows).attributed());
        assertEquals(BillingData.Bucket.UNRESOLVED, rows.getFirst().bucket());
    }

    @Test void duplicateHeadersWrongProfileCurrencyPrecisionAndOversizedFieldsFail() {
        for (String csv : new String[]{HEADER.replace("consumedService", "BILLINGPROFILEID") + ROW,
                HEADER + ROW.replace("profile,", "other,"), HEADER + ROW.replace("USD", "EUR"),
                HEADER + ROW.replace("-0.001", "1e100"), HEADER + ROW.replace("Compute", "x".repeat(65537)),
                HEADER.replace("costInBillingCurrency", "costInPricingCurrency") + ROW}) {
            assertThrows(BillingException.class, () -> parse(csv, new ArrayList<>()));
        }
    }

    @Test void quotedMultilineCellsDoNotBecomeExtraRowsAndLateDatesAreDisclosed() {
        var rows = new ArrayList<BillingData.CostRow>();
        var result = parse(HEADER + ROW.replace("Compute", "\"Compute,\nOther\"").replace("2026-07-01", "2026-06-30"), rows);
        assertEquals(1, result.part().rows()); assertFalse(result.withinDates());
        assertEquals("Compute,\nOther", rows.getFirst().service());
    }

    @Test void historicalAndNewSubscriptionsRemainAttributedByProfileAndWrongInvoiceFails() {
        String historical = "00000000-0000-4000-8000-000000000001";
        String newlyBilled = "00000000-0000-4000-8000-000000000002";
        var rows = new ArrayList<BillingData.CostRow>();
        var parsed = parse(HEADER + ROW.replace("profile,,", "profile," + historical + ",").replace("RoundingAdjustment", "Usage")
                + ROW.replace("profile,,", "profile," + newlyBilled + ",").replace("RoundingAdjustment", "Usage"), rows);
        assertEquals(java.util.Set.of(historical, newlyBilled), parsed.subscriptions());
        assertTrue(parsed.attributed()); assertTrue(rows.stream().allMatch(row -> row.bucket() == BillingData.Bucket.AZURE));
        byte[] bytes = (HEADER + ROW).getBytes(StandardCharsets.UTF_8);
        assertThrows(BillingException.class, () -> CostCsv.parse(new ByteArrayInputStream(bytes), bytes.length,
                "profile", "/example/profile", "invoice:different", null, 0, row -> fail(), JsonMapper.builder().build()));
    }

    private static CostCsv.Parsed parse(String csv, ArrayList<BillingData.CostRow> rows) {
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        return CostCsv.parse(new ByteArrayInputStream(bytes), bytes.length, "profile", "/example/profile", "month:2026-07",
                YearMonth.of(2026, 7), 0, rows::add, JsonMapper.builder().build());
    }

    @Test void costDetailsMonthFirstDatesAreExplicitStrictAndIndependentOfHostLocale() {
        String header = HEADER.stripTrailing() + ",billingPeriodStartDate,billingPeriodEndDate\r\n";
        String row = ROW.stripTrailing().replace("2026-07-01", "7/1/2026") + ",07/01/2026,07/31/2026\r\n";
        var rows = new ArrayList<BillingData.CostRow>();
        parse(header + row, rows);
        assertEquals(java.time.LocalDate.of(2026, 7, 1), rows.getFirst().date());
        assertEquals("07/31/2026", rows.getFirst().dimensions().get("billingperiodenddate"));
        for (String invalid : new String[]{"31/07/2026", "02/29/2025", "07/32/2026", "2026-07-01T12:00:00"}) {
            var error = assertThrows(BillingException.class, () -> parse(HEADER + ROW.replace("2026-07-01", invalid), new ArrayList<>()));
            assertEquals("SOURCE_INVALID", error.getMessage()); assertNull(error.getCause());
        }
    }

    @Test void redundantFractionalZerosNormalizeExactlyWithoutRoundingRealPrecision() {
        for (String value : new String[]{"0.000000000000000000000000", "0.000000000000000001000000"}) {
            var rows = new ArrayList<BillingData.CostRow>();
            parse(HEADER + ROW.replace("-0.001", value), rows);
            assertEquals(0, new java.math.BigDecimal(value).compareTo(rows.getFirst().cost()));
            assertEquals(18, rows.getFirst().cost().scale());
            assertEquals("24", rows.getFirst().dimensions().get("sourceCostScale"));
        }
        for (String value : new String[]{"0.0000000000000000001", "0E-1000000"}) {
            var error = assertThrows(BillingException.class, () -> parse(HEADER + ROW.replace("-0.001", value), new ArrayList<>()));
            assertEquals(BillingException.Reason.UNSUPPORTED_PRECISION, error.reason());
        }
    }
}
