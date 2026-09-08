package synvo.integration.azurebilling;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import synvo.billing.BillingData;
import synvo.billing.BillingException;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class AzureBillingSourceTests {
    private static final String SCOPE = "/providers/Microsoft.Billing/billingAccounts/account/billingProfiles/profile";
    private static final String ARM = "https://management.azure.com";
    private static final String BLOB = "https://syntheticstore.blob.core.windows.net/data/report.csv?sig=fake-canary";
    private static final String CSV = "billingProfileId,subscriptionId,date,chargeType,costInBillingCurrency,billingCurrency,invoiceId\nprofile,,2026-07-01,RoundingAdjustment,0.001,USD,example\n";
    private final ArrayDeque<AzureHttp.Reply> replies = new ArrayDeque<>();
    private final List<URI> requests = new ArrayList<>();
    private final List<String> tokens = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();

    @Test void dailyOpenMonthAndOldestMonthUseExactBoundedEchoWithoutRelaxingReports() {
        setup();
        replies.add(reply(200, manifest(0).replace("2026-07-01", "2026-09-01").replace("2026-07-31", "2026-09-07"), Map.of()));
        replies.add(reply(200, manifest(0).replace("2026-07-01", "2025-09-01").replace("2026-07-31", "2025-09-30"), Map.of()));
        var session = source().open();
        assertTrue(session.period(LocalDate.of(2026,9,1), LocalDate.of(2026,9,7), row -> fail()).parts().isEmpty());
        assertTrue(session.period(LocalDate.of(2025,9,1), LocalDate.of(2025,9,30), row -> fail()).parts().isEmpty());
        assertTrue(bodies.get(2).contains("2026-09-07"));
        assertFalse(bodies.get(2).contains("invoiceId"));
        assertThrows(BillingException.class, () -> session.month(YearMonth.of(2026,9), row -> fail()));
        for (var period : List.of(new LocalDate[]{LocalDate.of(2026,9,1), LocalDate.of(2026,9,8)},
                new LocalDate[]{LocalDate.of(2026,8,1), LocalDate.of(2026,9,1)},
                new LocalDate[]{LocalDate.of(2025,8,1), LocalDate.of(2025,8,31)})) {
            assertThrows(BillingException.class, () -> session.period(period[0], period[1], row -> fail()));
        }
        assertEquals(4, requests.size());
        replies.add(reply(200, manifest(0).replace("2026-07-01", "2026-09-01").replace("2026-07-31", "2026-09-06"), Map.of()));
        assertEquals(BillingException.Reason.SOURCE_INVALID, assertThrows(BillingException.class,
                () -> session.period(LocalDate.of(2026,9,1), LocalDate.of(2026,9,7), row -> fail())).reason());
    }

    @Test void completeReportStreamsEveryPartAndNeverSendsBearerToBlob() {
        setup();
        replies.add(reply(202, "{}", Map.of("Location", ARM + SCOPE + "/providers/Microsoft.CostManagement/costDetailsOperationResults/abc?api-version=2025-03-01", "Retry-After", "1")));
        replies.add(reply(200, manifest(2), Map.of()));
        replies.add(reply(200, CSV, Map.of())); replies.add(reply(200, CSV, Map.of()));
        var rows = new ArrayList<BillingData.CostRow>();
        var result = source().open().month(YearMonth.of(2026, 7), rows::add);
        assertEquals(2, result.parts().size()); assertEquals(2, rows.size());
        assertTrue(result.attributionComplete()); assertEquals(0, rows.getFirst().part()); assertEquals(1, rows.getLast().part());
        for (int i = 0; i < requests.size(); i++) {
            if (requests.get(i).getHost().endsWith("blob.core.windows.net")) assertNull(tokens.get(i));
            else assertEquals("synthetic-bearer", tokens.get(i));
        }
    }

    @Test void missingPartAndChangedManifestDateCannotComplete() {
        setup(); replies.add(reply(200, manifest(1).replace("2026-07-31", "2026-08-01"), Map.of()));
        var session = source().open();
        assertThrows(BillingException.class, () -> session.month(YearMonth.of(2026, 7), row -> fail("Wrong manifest must not emit rows")));
        assertEquals(3, requests.size());
    }

    @Test void noContentAndRedirectNeverMeanZero() {
        for (int status : new int[]{204, 302}) {
            replies.clear(); setup(); replies.add(reply(status, "", Map.of("Location", "http://127.0.0.1/")));
            var session = source().open();
            assertThrows(BillingException.class, () -> session.month(YearMonth.of(2026, 7), row -> fail()));
        }
    }

    @Test void exactDecimalsSurviveInvoiceJsonAndAmountDueIsIndependent() {
        setup();
        replies.add(reply(200, """
                {"value":[{"name":"example","properties":{"billingProfileId":"%s","invoicePeriodStartDate":"2026-07-01T00:00:00Z",
                "invoicePeriodEndDate":"2026-07-31T00:00:00Z","documentType":"Invoice",
                "billedAmount":{"currency":"USD","value":0.123456789012345678},"amountDue":{"currency":"USD","value":0}}}]}
                """.formatted(SCOPE), Map.of()));
        var invoices = source().open().invoices(Set.of("example"), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 31));
        assertEquals("0.123456789012345678", invoices.getFirst().amounts().get("billedAmount").toPlainString());
        assertEquals(0, invoices.getFirst().amounts().get("amountDue").signum());
    }

    @Test void knownTaxTreatmentRequiresAnIndependentExactInvoiceBridge() {
        String fields = """
                "billedAmount":{"currency":"USD","value":100},"taxAmount":{"currency":"USD","value":9},
                "totalAmount":{"currency":"USD","value":109},"creditAmount":{"currency":"USD","value":0},
                "azurePrepaymentApplied":{"currency":"USD","value":0},"freeAzureCreditApplied":{"currency":"USD","value":0}
                """;
        for (String type : List.of("SubtotalLevel", "InvoiceLevel")) {
            assertFalse(taxInvoice(type, fields, "").ambiguous());
            assertFalse(taxInvoice(type, fields, ",\"subTotal\":{\"currency\":\"USD\",\"value\":100}").ambiguous());
            assertEquals(type, taxInvoice(type, fields, "").metadata().get("specialTaxationType"));
        }
        assertTrue(taxInvoice("Unknown", fields, "").ambiguous());
        assertTrue(taxInvoice("", fields, "").ambiguous());
        assertTrue(taxInvoice("SubtotalLevel", fields.replace("109", "108.99"), "").ambiguous());
        assertTrue(taxInvoice("SubtotalLevel", fields.replace("\"value\":9", "\"value\":-9").replace("109", "91"), "").ambiguous());
        assertTrue(taxInvoice("SubtotalLevel", fields, ",\"subTotal\":{\"currency\":\"USD\",\"value\":99}").ambiguous());
        assertTrue(taxInvoice("SubtotalLevel", fields, ",\"rebillDetails\":{\"invoiceDocumentId\":\"example-prior\"}").ambiguous());
        assertTrue(taxInvoice("SubtotalLevel", fields, ",\"creditForDocumentId\":\"example-prior\"").ambiguous());
        for (String name : List.of("billedAmount", "taxAmount", "totalAmount", "creditAmount", "azurePrepaymentApplied", "freeAzureCreditApplied")) {
            assertTrue(taxInvoice("SubtotalLevel", fields.replace("\"" + name + "\":", "\"ignored\":"), "").ambiguous());
        }
        for (String name : List.of("creditAmount", "azurePrepaymentApplied", "freeAzureCreditApplied")) {
            assertTrue(taxInvoice("SubtotalLevel", fields.replace("\"" + name + "\":{\"currency\":\"USD\",\"value\":0}",
                    "\"" + name + "\":{\"currency\":\"USD\",\"value\":1}"), "").ambiguous());
        }
    }

    private synvo.billing.BillingData.Invoice taxInvoice(String type, String fields, String extra) {
        replies.clear(); setup();
        replies.add(reply(200, """
                {"value":[{"name":"example","properties":{"billingProfileId":"%s","invoicePeriodStartDate":"2026-07-01",
                "invoicePeriodEndDate":"2026-07-31","documentType":"Invoice","specialTaxationType":"%s",%s%s}}]}
                """.formatted(SCOPE, type, fields, extra), Map.of()));
        return source().open().invoices(Set.of("example"), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 31)).getFirst();
    }

    @Test void hostileOriginsPathsAndActualPrivateSocketAddressesFail() throws Exception {
        var source = source();
        for (String value : List.of("http://management.azure.com" + SCOPE, ARM + SCOPE + "/../other?api-version=2024-04-01",
                ARM + SCOPE + "evil?api-version=2024-04-01", "https://management.azure.com.evil/", ARM + SCOPE + "?api-version=2024-04-01&scope=other")) {
            assertThrows(BillingException.class, () -> source.validateArm(URI.create(value)));
        }
        for (String value : List.of("http://syntheticstore.blob.core.windows.net/data", "https://syntheticstore.blob.core.windows.net.evil/data",
                "https://user@syntheticstore.blob.core.windows.net/data", "https://127.0.0.1/data")) {
            assertThrows(BillingException.class, () -> AzureBillingSource.validateBlob(URI.create(value)));
        }
        for (String address : List.of("127.0.0.1", "10.0.0.1", "169.254.169.254", "100.64.0.1", "::1", "fc00::1")) {
            assertThrows(java.io.IOException.class, () -> AzureHttp.requirePublic(InetAddress.getByName(address)));
        }
    }

    @Test void equivalentMidnightDatesAreAcceptedButTimeShiftsAreNot() {
        var date = LocalDate.of(2026, 7, 31);
        assertTrue(AzureBillingSource.sameDate("2026-07-31T00:00:00.000Z", date));
        assertTrue(AzureBillingSource.sameDate("2026-07-31", date));
        assertFalse(AzureBillingSource.sameDate("2026-07-31T01:00:00Z", date));
        assertFalse(AzureBillingSource.sameDate("2026-07-31T00:00:00+08:00", date));
    }

    @Test void documentedManifestScopeWithoutLeadingSlashStillRequiresExactProfile() {
        for (String echo : List.of(SCOPE, SCOPE.substring(1), SCOPE + "/", SCOPE.substring(1) + "/")) {
            replies.clear(); setup(); replies.add(reply(200, manifest(0).replace("\"requestScope\":\"" + SCOPE, "\"requestScope\":\"" + echo), Map.of()));
            assertTrue(source().open().month(YearMonth.of(2026, 7), row -> fail()).parts().isEmpty());
        }
        for (String echo : List.of(SCOPE + "-other", SCOPE + "//", "/" + SCOPE, SCOPE + "/../other")) {
            replies.clear(); setup(); replies.add(reply(200, manifest(0).replace("\"requestScope\":\"" + SCOPE, "\"requestScope\":\"" + echo), Map.of()));
            var session = source().open();
            assertThrows(BillingException.class, () -> session.month(YearMonth.of(2026, 7), row -> fail()));
        }
    }

    @Test void throttlingRetriesAreBoundedAndAnExpiredSignedLinkDoesNotLeakItsToken() {
        setup(); replies.add(reply(429, "private provider message", Map.of("Retry-After", "1")));
        replies.add(reply(200, manifest(1), Map.of()));
        replies.add(reply(403, "fake-canary signed URL expired", Map.of()));
        var session = source().open();
        var error = assertThrows(BillingException.class, () -> session.month(YearMonth.of(2026, 7), row -> fail()));
        assertEquals("AUTHENTICATION", error.getMessage()); assertNull(error.getCause());
        assertEquals(5, requests.size()); assertNull(tokens.getLast());
    }

    @Test void completedEmptyManifestIsZeroButCompressionAndWrongByteCountsFail() {
        setup(); replies.add(reply(200, manifest(0), Map.of()));
        assertTrue(source().open().month(YearMonth.of(2026, 7), row -> fail()).parts().isEmpty());
        replies.clear(); setup(); replies.add(reply(200, manifest(1).replace("\"compressData\":false", "\"compressData\":true"), Map.of()));
        var session = source().open();
        assertThrows(BillingException.class, () -> session.month(YearMonth.of(2026, 7), row -> fail()));
        replies.clear(); setup(); replies.add(reply(200, manifest(1), Map.of())); replies.add(reply(200, CSV + "\n", Map.of()));
        var wrongBytes = source().open();
        assertThrows(BillingException.class, () -> wrongBytes.month(YearMonth.of(2026, 7), row -> {}));
    }

    @Test void paginationCannotChangeTheInventoryCoverageAndRebindingFailsBeforeTls() throws Exception {
        replies.add(reply(200, "{\"id\":\"" + SCOPE + "\",\"properties\":{\"status\":\"Active\"}}", Map.of()));
        replies.add(reply(200, "{\"value\":[],\"nextLink\":\"" + ARM + SCOPE + "/billingSubscriptions?api-version=2024-04-01&includeDeleted=false\"}", Map.of()));
        assertThrows(BillingException.class, () -> source().open());
        try (var listener = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var socket = new java.net.Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort())) {
            assertThrows(java.io.IOException.class, () -> new AzureHttp.PublicSocketFactory().createSocket(socket,
                    "syntheticstore.blob.core.windows.net", 443, false));
        }
    }

    @Test void deniedInventoryAndMissingPagesFailBeforeAnyCostJob() {
        replies.add(reply(200, "{\"id\":\"" + SCOPE + "\",\"properties\":{\"status\":\"Active\"}}", Map.of()));
        replies.add(reply(403, "synthetic confidential provider detail", Map.of()));
        assertEquals(BillingException.Reason.AUTHENTICATION, assertThrows(BillingException.class, () -> source().open()).reason());
        assertEquals(2, requests.size());
        replies.clear(); requests.clear();
        replies.add(reply(200, "{\"id\":\"" + SCOPE + "\",\"properties\":{\"status\":\"Active\"}}", Map.of()));
        replies.add(reply(200, "{\"value\":[],\"nextLink\":\"" + ARM + SCOPE + "/billingSubscriptions?api-version=2024-04-01&includeDeleted=true&$skiptoken=next\"}", Map.of()));
        for (int i = 0; i < 4; i++) replies.add(reply(503, "synthetic failure", Map.of("Retry-After", "1")));
        assertThrows(BillingException.class, () -> source().open());
        assertTrue(requests.stream().noneMatch(uri -> uri.getPath().contains("generateCostDetailsReport")));
    }

    @Test void metadataPartByteAndPollingWaitLimitsFailWithoutDownloadingData() {
        for (String manifest : List.of(manifest(101), manifest(1).replace("\"byteCount\":" + CSV.getBytes(StandardCharsets.UTF_8).length,
                "\"byteCount\":262144001"))) {
            replies.clear(); setup(); replies.add(reply(200, manifest, Map.of()));
            var session = source().open();
            assertThrows(BillingException.class, () -> session.month(YearMonth.of(2026, 7), row -> fail()));
        }
        replies.clear(); setup();
        replies.add(reply(202, "{}", Map.of("Location", ARM + SCOPE + "/providers/Microsoft.CostManagement/costDetailsOperationResults/abc?api-version=2025-03-01", "Retry-After", "1800")));
        var session = source().open();
        assertEquals(BillingException.Reason.LIMIT_EXCEEDED, assertThrows(BillingException.class,
                () -> session.month(YearMonth.of(2026, 7), row -> fail())).reason());
        replies.clear();
        replies.add(reply(200, " ".repeat(4 * 1024 * 1024 + 1), Map.of()));
        assertEquals(BillingException.Reason.LIMIT_EXCEEDED, assertThrows(BillingException.class, () -> source().open()).reason());
    }

    private AzureBillingSource source() {
        return new AzureBillingSource("account", "profile", JsonMapper.builder().build(), (uri, method, bearer, body) -> {
            requests.add(uri); tokens.add(bearer); bodies.add(body == null ? "" : new String(body, StandardCharsets.UTF_8)); return replies.removeFirst();
        }, () -> "synthetic-bearer", Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC), delay -> {});
    }

    @Test void signedOperationParametersAreBoundToTheExactProviderIssuedPollLocation() {
        String poll = ARM + SCOPE + "/providers/Microsoft.CostManagement/costDetailsOperationResults/abc?api-version=2025-03-01&t=synthetic-time&c=synthetic-context&s=synthetic-scope&h=synthetic-signature";
        setup(); replies.add(reply(202, "{}", Map.of("Location", poll, "Retry-After", "1")));
        replies.add(reply(200, manifest(0), Map.of()));
        assertTrue(source().open().month(YearMonth.of(2026, 7), row -> fail()).parts().isEmpty());
        assertEquals(URI.create(poll), requests.getLast());
        replies.clear(); setup();
        replies.add(reply(202, "{}", Map.of("Location", poll, "Retry-After", "1")));
        replies.add(reply(202, "{}", Map.of("Location", poll.replace("synthetic-signature", "changed"), "Retry-After", "1")));
        var session = source().open();
        assertThrows(BillingException.class, () -> session.month(YearMonth.of(2026, 7), row -> fail()));
        assertThrows(BillingException.class, () -> source().validateArm(URI.create(ARM + SCOPE + "?api-version=2024-04-01&t=a&c=b&s=c&h=d")));
        assertThrows(BillingException.class, () -> source().validateArm(URI.create(poll.replace("&h=synthetic-signature", ""))));
    }
    private void setup() {
        replies.add(reply(200, "{\"id\":\"" + SCOPE + "\",\"properties\":{\"status\":\"Active\"}}", Map.of()));
        replies.add(reply(200, "{\"value\":[]}", Map.of()));
    }
    private static AzureHttp.Reply reply(int status, String body, Map<String, String> headers) {
        return new AzureHttp.Reply(status, headers, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), () -> {});
    }
    private static String manifest(int count) {
        int length = CSV.getBytes(StandardCharsets.UTF_8).length;
        var blobs = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < count; index++) blobs.add(Map.of("blobLink", BLOB + "&part=" + index, "byteCount", length));
        return JsonMapper.builder().build().writeValueAsString(Map.of("status", "Completed", "manifest", Map.of(
                "requestContext", Map.of("requestScope", SCOPE, "requestBody", Map.of("metric", "ActualCost", "timePeriod", Map.of("start", "2026-07-01T00:00:00.000Z", "end", "2026-07-31"))),
                "blobCount", count, "byteCount", count * length, "compressData", false, "dataFormat", "Csv", "blobs", blobs)));
    }
}
