package synvo.integration.azurebilling;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import synvo.billing.BillingData;
import synvo.billing.BillingException;
import synvo.billing.BillingSource;
import synvo.configuration.BillingProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;

/** The only module that knows Azure billing endpoints, report jobs and wire schemas. */
public final class AzureBillingSource implements BillingSource {
    private static final String ARM = "https://management.azure.com";
    private static final String BILLING_VERSION = "api-version=2024-04-01";
    private final String scope;
    private final String profile;
    private final ObjectMapper mapper;
    private final AzureHttp http;
    private final Supplier<String> token;
    private final Clock clock;
    private final Consumer<Duration> sleeper;

    public AzureBillingSource(BillingProperties properties, ObjectMapper mapper) {
        this(properties.accountId(), properties.profileId(), mapper, AzureHttp.production(),
                new CertificateIdentity(properties)::token, Clock.systemUTC(), delay -> {
                    try { Thread.sleep(delay); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw failure(BillingException.Reason.INTERRUPTED); }
                });
    }

    AzureBillingSource(String account, String profile, ObjectMapper mapper, AzureHttp http, Supplier<String> token,
            Clock clock, Consumer<Duration> sleeper) {
        this.scope = "/providers/Microsoft.Billing/billingAccounts/" + account + "/billingProfiles/" + profile;
        this.profile = profile;
        this.mapper = mapper.rebuild().enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).build();
        this.http = http; this.token = token; this.clock = clock; this.sleeper = sleeper;
    }

    @Override public Session open() {
        var session = new RetrievalSession();
        var metadata = session.json(URI.create(ARM + scope + "?" + BILLING_VERSION), null, false);
        if (!scope.equalsIgnoreCase(text(metadata, "id")) || !"Active".equalsIgnoreCase(text(metadata.path("properties"), "status"))) throw invalid();
        session.pages(URI.create(ARM + scope + "/billingSubscriptions?" + BILLING_VERSION + "&includeDeleted=true"), item -> {
            var properties = item.path("properties");
            if (!scope.equalsIgnoreCase(text(properties, "billingProfileId"))) throw invalid();
            String id = text(properties, "subscriptionId");
            if (!id.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) throw invalid();
            if (session.inventory.putIfAbsent(id.toLowerCase(Locale.ROOT), text(properties, "status")) != null) throw invalid();
        });
        return session;
    }

    private final class RetrievalSession implements Session {
        private final Instant deadline = clock.instant().plus(Duration.ofMinutes(30));
        private long remainingBytes = 250L * 1024 * 1024;
        private long remainingRows = 1_000_000;
        private final Map<String, String> inventory = new LinkedHashMap<>();

        @Override public BillingData.Partition month(YearMonth month, Consumer<BillingData.CostRow> sink) {
            YearMonth current = YearMonth.now(clock);
            if (!month.isBefore(current) || month.isBefore(current.minusMonths(13))) throw failure(BillingException.Reason.SOURCE_UNAVAILABLE);
            return report("month:" + month, month.atDay(1), month.atEndOfMonth(), null, sink);
        }

        @Override public BillingData.Partition period(LocalDate first, LocalDate through, Consumer<BillingData.CostRow> sink) {
            LocalDate today = LocalDate.now(clock.withZone(java.time.ZoneOffset.UTC));
            if (first == null || through == null || first.getDayOfMonth() != 1 || first.isAfter(through)
                    || !YearMonth.from(first).equals(YearMonth.from(through)) || through.isAfter(today)
                    || first.isBefore(YearMonth.from(today).minusMonths(12).atDay(1))) {
                throw failure(BillingException.Reason.INVALID_REQUEST);
            }
            return report("month:" + YearMonth.from(first), first, through, null, sink);
        }

        @Override public BillingData.Partition invoice(String reference, Consumer<BillingData.CostRow> sink) {
            if (!reference.matches("[A-Za-z0-9_-]{1,128}")) throw invalid();
            return report("invoice:" + reference, null, null, reference, sink);
        }

        private BillingData.Partition report(String dataset, LocalDate first, LocalDate through, String invoice, Consumer<BillingData.CostRow> sink) {
            YearMonth month = first == null ? null : YearMonth.from(first);
            var request = new LinkedHashMap<String, Object>(); request.put("metric", "ActualCost");
            if (first != null) request.put("timePeriod", Map.of("start", first.toString(), "end", through.toString()));
            else request.put("invoiceId", invoice);
            byte[] body = mapper.writeValueAsBytes(request);
            URI url = URI.create(ARM + scope + "/providers/Microsoft.CostManagement/generateCostDetailsReport?api-version=2025-03-01");
            JsonNode result = json(url, body, true);
            if (!"Completed".equalsIgnoreCase(text(result, "status"))) throw invalid();
            var manifest = result.path("manifest");
            var context = manifest.path("requestContext");
            var echoed = context.path("requestBody");
            String echoedScope = text(context, "requestScope");
            if (!matchesManifestScope(echoedScope) || !"ActualCost".equalsIgnoreCase(text(echoed, "metric"))
                    || echoed.hasNonNull("billingPeriod")) throw invalid();
            if (month != null) {
                if (echoed.hasNonNull("invoiceId") || !sameDate(text(echoed.path("timePeriod"), "start"), first)
                        || !sameDate(text(echoed.path("timePeriod"), "end"), through)) throw invalid();
            } else if (!invoice.equals(text(echoed, "invoiceId")) || echoed.hasNonNull("timePeriod")) throw invalid();
            var blobs = manifest.path("blobs");
            if (!blobs.isArray() || blobs.size() > 100 || !manifest.path("blobCount").isIntegralNumber()
                    || blobs.size() != manifest.path("blobCount").intValue()
                    || !manifest.path("compressData").isBoolean() || manifest.path("compressData").booleanValue()
                    || !"Csv".equalsIgnoreCase(text(manifest, "dataFormat"))) throw invalid();
            long expected = positiveLong(manifest, "byteCount");
            if (expected > remainingBytes) throw failure(BillingException.Reason.LIMIT_EXCEEDED);
            long declared = 0;
            for (var blob : blobs) declared = Math.addExact(declared, positiveLong(blob, "byteCount"));
            if (declared != expected) throw invalid();
            var parts = new ArrayList<BillingData.Part>(); var subscriptions = new LinkedHashSet<String>(); var invoices = new LinkedHashSet<String>();
            var links = new HashSet<URI>(); boolean attributed = true; boolean withinDates = true;
            for (int index = 0; index < blobs.size(); index++) {
                var blob = blobs.get(index); URI link = uri(text(blob, "blobLink")); validateBlob(link);
                if (!links.add(link)) throw invalid();
                try (var response = send(link, null, true)) {
                    if (response.status() != 200) throw failure(BillingException.Reason.SOURCE_UNAVAILABLE);
                    if (!response.headers().getOrDefault("Content-Encoding", "identity").equalsIgnoreCase("identity")) throw invalid();
                    var parsed = CostCsv.parse(new BudgetInput(response.body()), positiveLong(blob, "byteCount"), profile, scope, dataset,
                            month, index, row -> { if (--remainingRows < 0) throw failure(BillingException.Reason.LIMIT_EXCEEDED); sink.accept(row); }, mapper);
                    if (blob.hasNonNull("rowCount") && positiveLong(blob, "rowCount") != parsed.part().rows()) throw invalid();
                    parts.add(parsed.part()); subscriptions.addAll(parsed.subscriptions()); invoices.addAll(parsed.invoices());
                    attributed &= parsed.attributed(); withinDates &= parsed.withinDates();
                }
            }
            if (manifest.hasNonNull("rowCount") && positiveLong(manifest, "rowCount") != parts.stream().mapToLong(BillingData.Part::rows).sum()) throw invalid();
            return new BillingData.Partition(dataset, clock.instant(), "cost-details/2025-03-01;billing/2024-04-01",
                    inventory, parts, subscriptions, invoices, attributed, withinDates);
        }

        @Override public List<BillingData.Invoice> invoices(Set<String> references, LocalDate first, LocalDate last) {
            var result = new ArrayList<BillingData.Invoice>(); var found = new HashSet<String>();
            URI url = URI.create(ARM + scope + "/invoices?" + BILLING_VERSION + "&periodStartDate=" + first + "&periodEndDate=" + last);
            pages(url, item -> {
                String id = text(item, "name");
                if (!references.contains(id)) return;
                if (!found.add(id)) throw invalid();
                var properties = item.path("properties");
                if (!scope.equalsIgnoreCase(text(properties, "billingProfileId"))) throw invalid();
                var amounts = new LinkedHashMap<String, BigDecimal>();
                for (String name : List.of("billedAmount", "subTotal", "taxAmount", "creditAmount", "azurePrepaymentApplied", "freeAzureCreditApplied", "totalAmount", "amountDue")) {
                    var amount = properties.path(name);
                    if (!amount.isMissingNode() && !amount.isNull()) {
                        if (!"USD".equals(text(amount, "currency"))) throw failure(BillingException.Reason.UNSUPPORTED_CURRENCY);
                        var value = amount.path("value");
                        if (!value.isNumber()) throw invalid();
                        amounts.put(name, value.decimalValue());
                    }
                }
                boolean ambiguous = properties.hasNonNull("rebillDetails") || properties.hasNonNull("creditForDocumentId")
                        || !explainedTaxTreatment(properties, amounts);
                for (String credit : List.of("creditAmount", "azurePrepaymentApplied", "freeAzureCreditApplied")) {
                    if (amounts.getOrDefault(credit, BigDecimal.ZERO).signum() != 0) ambiguous = true;
                }
                var metadata = new LinkedHashMap<String, String>();
                for (String field : List.of("invoiceDate", "billingProfileId", "creditForDocumentId", "billedDocumentId", "specialTaxationType")) {
                    String value = text(properties, field);
                    if (value.length() > 2048) throw invalid();
                    if (!value.isEmpty()) metadata.put(field, value);
                }
                for (String field : List.of("creditNoteDocumentId", "invoiceDocumentId")) {
                    String value = text(properties.path("rebillDetails"), field);
                    if (value.length() > 2048) throw invalid();
                    if (!value.isEmpty()) metadata.put("rebill." + field, value);
                }
                result.add(new BillingData.Invoice(id, invoiceDate(text(properties, "invoicePeriodStartDate")),
                        invoiceDate(text(properties, "invoicePeriodEndDate")), text(properties, "documentType"), ambiguous, amounts, metadata));
            });
            return List.copyOf(result);
        }

        /** Establish pre-tax charge comparability from invoice fields alone, never from the cost total. */
        private boolean explainedTaxTreatment(JsonNode properties, Map<String, BigDecimal> amounts) {
            if (!properties.hasNonNull("specialTaxationType")) return true;
            if (!Set.of("SubtotalLevel", "InvoiceLevel").contains(text(properties, "specialTaxationType"))) return false;
            if (!amounts.keySet().containsAll(List.of("billedAmount", "taxAmount", "totalAmount",
                    "creditAmount", "azurePrepaymentApplied", "freeAzureCreditApplied"))) return false;
            for (String credit : List.of("creditAmount", "azurePrepaymentApplied", "freeAzureCreditApplied")) {
                if (amounts.get(credit).signum() != 0) return false;
            }
            var charges = amounts.get("billedAmount");
            var tax = amounts.get("taxAmount");
            return tax.signum() >= 0 && charges.add(tax).compareTo(amounts.get("totalAmount")) == 0
                    && (!amounts.containsKey("subTotal") || amounts.get("subTotal").compareTo(charges) == 0);
        }

        private void pages(URI first, Consumer<JsonNode> sink) {
            URI next = first; var seen = new HashSet<URI>();
            var bounds = query(first);
            while (next != null) {
                if (seen.size() >= 100 || !seen.add(next)) throw failure(BillingException.Reason.LIMIT_EXCEEDED);
                validateArm(next);
                if (!next.getPath().equals(first.getPath())) throw invalid();
                var parameters = query(next);
                for (var bound : bounds.entrySet()) {
                    if (!bound.getValue().equals(parameters.get(bound.getKey()))) throw invalid();
                }
                var page = json(next, null, false);
                if (!page.path("value").isArray()) throw invalid();
                for (var item : page.path("value")) sink.accept(item);
                String link = text(page, "nextLink"); next = link.isEmpty() ? null : uri(link);
            }
        }

        private JsonNode json(URI first, byte[] body, boolean report) {
            URI next = first;
            URI operationLocation = null;
            for (int polls = 0; polls < 100; polls++) {
                validateArm(next);
                try (var response = send(next, body, false)) {
                    if (response.status() == 202 && report) {
                        String location = response.headers().get("Location");
                        if (location == null) throw invalid();
                        next = uri(location); validateArm(next); body = null;
                        String operationPrefix = scope.toLowerCase(Locale.ROOT) + "/providers/microsoft.costmanagement/costdetailsoperationresults/";
                        if (!next.getPath().toLowerCase(Locale.ROOT).startsWith(operationPrefix)
                                || !"2025-03-01".equals(query(next).get("api-version"))) throw invalid();
                        if (operationLocation != null && !operationLocation.equals(next)) throw invalid();
                        operationLocation = next;
                        pause(response.headers().getOrDefault("Retry-After", "20"));
                        continue;
                    }
                    if (response.status() != 200) throw failure(BillingException.Reason.SOURCE_UNAVAILABLE);
                    byte[] content = response.body().readNBytes(4 * 1024 * 1024 + 1);
                    if (content.length > 4 * 1024 * 1024) throw failure(BillingException.Reason.LIMIT_EXCEEDED);
                    checkDeadline(); return mapper.readTree(content);
                } catch (IOException exception) { throw failure(BillingException.Reason.SOURCE_UNAVAILABLE); }
                catch (BillingException exception) { throw exception; }
                catch (RuntimeException exception) { throw invalid(); }
            }
            throw failure(BillingException.Reason.LIMIT_EXCEEDED);
        }

        private AzureHttp.Reply send(URI uri, byte[] body, boolean blob) {
            for (int attempt = 0; attempt < 4; attempt++) {
                checkDeadline();
                if (blob) validateBlob(uri); else validateArm(uri);
                var reply = http.send(uri, body == null ? "GET" : "POST", blob ? null : token.get(), body);
                if (reply.status() == 401 || reply.status() == 403) { reply.close(); throw failure(BillingException.Reason.AUTHENTICATION); }
                if (reply.status() == 429 || reply.status() >= 500 && reply.status() <= 599) {
                    String retry = reply.headers().getOrDefault("Retry-After", Integer.toString(1 << attempt));
                    reply.close(); pause(retry); continue;
                }
                return reply;
            }
            throw failure(BillingException.Reason.SOURCE_UNAVAILABLE);
        }

        private void pause(String value) {
            try {
                Duration delay = value.matches("[0-9]{1,6}") ? Duration.ofSeconds(Long.parseLong(value))
                        : Duration.between(clock.instant(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                if (delay.isNegative() || delay.isZero()) delay = Duration.ofSeconds(1);
                if (!clock.instant().plus(delay).isBefore(deadline)) throw failure(BillingException.Reason.LIMIT_EXCEEDED);
                sleeper.accept(delay); checkDeadline();
            } catch (BillingException exception) { throw exception; }
            catch (RuntimeException exception) { throw invalid(); }
        }

        private void checkDeadline() {
            if (Thread.currentThread().isInterrupted()) throw failure(BillingException.Reason.INTERRUPTED);
            if (!clock.instant().isBefore(deadline)) throw failure(BillingException.Reason.LIMIT_EXCEEDED);
        }

        private final class BudgetInput extends FilterInputStream {
            BudgetInput(InputStream stream) { super(stream); }
            @Override public int read() throws IOException { checkDeadline(); int value = in.read(); if (value >= 0) charge(1); return value; }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                checkDeadline(); int count = in.read(bytes, offset, length); if (count > 0) charge(count); return count;
            }
            private void charge(long count) { remainingBytes -= count; if (remainingBytes < 0) throw failure(BillingException.Reason.LIMIT_EXCEEDED); }
        }
    }

    void validateArm(URI uri) {
        if (uri.toASCIIString().length() > 16384) throw invalid();
        if (!"https".equals(uri.getScheme()) || !"management.azure.com".equals(uri.getHost()) || uri.getUserInfo() != null
                || uri.getFragment() != null || uri.getPort() != -1 && uri.getPort() != 443) throw invalid();
        String path = uri.getPath(); String base = scope.toLowerCase(Locale.ROOT);
        if (path == null || !path.equals(uri.normalize().getPath()) || path.contains("%") || path.contains("\\")) throw invalid();
        String lower = path.toLowerCase(Locale.ROOT);
        boolean operation = lower.matches(java.util.regex.Pattern.quote(base) + "/providers/microsoft.costmanagement/costdetailsoperationresults/[a-z0-9-]+");
        if (!lower.equals(base) && !lower.equals(base + "/billingsubscriptions") && !lower.equals(base + "/invoices")
                && !lower.equals(base + "/providers/microsoft.costmanagement/generatecostdetailsreport")
                && !operation) throw invalid();
        String query = uri.getRawQuery(); if (query == null) throw invalid();
        var keys = new HashSet<String>();
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2); String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            boolean opaqueOperationParameter = operation && Set.of("t", "c", "s", "h").contains(key);
            if (parts.length != 2 || !keys.add(key) || !(opaqueOperationParameter
                    || Set.of("api-version", "includeDeleted", "periodStartDate", "periodEndDate", "$skiptoken", "skiptoken", "$skip", "skip").contains(key))) throw invalid();
            if (opaqueOperationParameter) {
                String value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                if (value.isBlank() || value.length() > 4096 || value.chars().anyMatch(Character::isISOControl)) throw invalid();
            }
            if (key.equals("api-version") && !Set.of("2024-04-01", "2025-03-01").contains(parts[1])) throw invalid();
        }
        if (!keys.contains("api-version")) throw invalid();
        if (keys.stream().anyMatch(Set.of("t", "c", "s", "h")::contains) && !keys.containsAll(Set.of("t", "c", "s", "h"))) throw invalid();
    }

    private boolean matchesManifestScope(String value) {
        // Azure's request-context echo may omit the leading slash or append one trailing slash.
        if (value.startsWith("/")) value = value.substring(1);
        if (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return scope.substring(1).equalsIgnoreCase(value);
    }

    static void validateBlob(URI uri) {
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !uri.getHost().matches("[a-z0-9]{3,24}\\.blob\\.core\\.windows\\.net")
                || uri.getUserInfo() != null || uri.getFragment() != null || uri.getPort() != -1 && uri.getPort() != 443) throw invalid();
    }
    static boolean sameDate(String value, LocalDate date) { return value.matches(java.util.regex.Pattern.quote(date.toString()) + "(?:T00:00:00(?:\\.0+)?(?:Z|\\+00:00)?)?"); }
    private static LocalDate invoiceDate(String value) {
        try { return value.contains("T") ? java.time.OffsetDateTime.parse(value).toLocalDate() : LocalDate.parse(value); }
        catch (RuntimeException exception) { throw invalid(); }
    }
    private static long positiveLong(JsonNode node, String key) {
        var value = node.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) throw invalid();
        return value.longValue();
    }
    private static String text(JsonNode node, String key) { var value = node.path(key); return value.isString() ? value.stringValue() : ""; }
    private static URI uri(String value) { try { return URI.create(value); } catch (RuntimeException exception) { throw invalid(); } }
    private static Map<String, String> query(URI uri) {
        var result = new LinkedHashMap<String, String>();
        if (uri.getRawQuery() == null) throw invalid();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2); if (parts.length != 2) throw invalid();
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (result.putIfAbsent(key, URLDecoder.decode(parts[1], StandardCharsets.UTF_8)) != null) throw invalid();
        }
        return result;
    }
    private static BillingException invalid() { return failure(BillingException.Reason.SOURCE_INVALID); }
    private static BillingException failure(BillingException.Reason reason) { return new BillingException(reason); }
}
