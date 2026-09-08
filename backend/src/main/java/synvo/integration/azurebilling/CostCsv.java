package synvo.integration.azurebilling;

import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import synvo.billing.BillingData;
import synvo.billing.BillingException;
import tools.jackson.databind.ObjectMapper;

final class CostCsv {
    private static final DateTimeFormatter COST_DETAILS_DATE = DateTimeFormatter.ofPattern("M/d/uuuu", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private CostCsv() { }
    record Parsed(BillingData.Part part, Set<String> subscriptions, Set<String> invoices, boolean attributed, boolean withinDates) { }

    static Parsed parse(InputStream stream, long expectedBytes, String profile, String scope, String dataset,
            YearMonth month, int part, Consumer<BillingData.CostRow> sink, ObjectMapper mapper) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var counted = new CountingInput(stream);
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            var reader = new FieldBoundReader(new InputStreamReader(new DigestInputStream(counted, digest), decoder));
            try (var parser = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)) {
                var headers = new HashMap<String, Integer>();
                List<String> names = parser.getHeaderNames();
                if (names.size() > 512) throw limit();
                for (int i = 0; i < names.size(); i++) {
                    String name = names.get(i).toLowerCase(Locale.ROOT);
                    if (name.equals("billingcurrencycode")) name = "billingcurrency";
                    if (headers.putIfAbsent(name, i) != null) throw invalid();
                }
                for (var required : List.of("billingprofileid", "subscriptionid", "date", "chargetype", "costinbillingcurrency", "billingcurrency")) {
                    if (!headers.containsKey(required)) throw invalid();
                }
                var subscriptions = new LinkedHashSet<String>();
                var invoices = new LinkedHashSet<String>();
                boolean attributed = true; boolean withinDates = true; long rows = 0;
                for (var record : parser) {
                    if (++rows > 1_000_000) throw limit();
                    if (record.size() != names.size()) throw invalid();
                    String rowProfile = field(record, headers, "billingprofileid");
                    if (!rowProfile.equalsIgnoreCase(profile) && !rowProfile.equalsIgnoreCase(scope)) throw invalid();
                    String subscription = field(record, headers, "subscriptionid").toLowerCase(Locale.ROOT);
                    if (!subscription.isEmpty() && !subscription.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) throw invalid();
                    String type = field(record, headers, "chargetype");
                    if (type.isBlank()) throw invalid();
                    LocalDate date = sourceDate(field(record, headers, "date"));
                    String text = field(record, headers, "costinbillingcurrency");
                    if (text.isBlank() || text.length() > 80) throw invalid();
                    BigDecimal cost = new BigDecimal(text);
                    int sourceCostScale = cost.scale();
                    if (sourceCostScale > 18) {
                        // Some Azure zero/exact values carry redundant fractional zeros.
                        // Retain their formatting scale, but never round a nonzero digit away.
                        if (sourceCostScale > 38 || cost.stripTrailingZeros().scale() > 18) throw new BillingException(BillingException.Reason.UNSUPPORTED_PRECISION);
                        cost = cost.setScale(18, RoundingMode.UNNECESSARY);
                    }
                    var bucket = type.equals("RoundingAdjustment") ? BillingData.Bucket.PROFILE_ADJUSTMENT
                            : subscription.isEmpty() ? BillingData.Bucket.UNRESOLVED : BillingData.Bucket.AZURE;
                    if (bucket == BillingData.Bucket.UNRESOLVED) attributed = false;
                    if (!subscription.isEmpty()) subscriptions.add(subscription);
                    String invoice = field(record, headers, "invoiceid");
                    if (!invoice.isEmpty()) {
                        if (!invoice.matches("[A-Za-z0-9_-]{1,128}")) throw invalid();
                        invoices.add(invoice);
                    }
                    if (dataset.startsWith("invoice:") && !dataset.equals("invoice:" + invoice)) throw invalid();
                    if (month != null && !YearMonth.from(date).equals(month)) withinDates = false;
                    String service = field(record, headers, "consumedservice");
                    if (service.isBlank()) service = field(record, headers, "metercategory");
                    if (service.isBlank()) service = "Unallocated";
                    var dimensions = new LinkedHashMap<String, String>();
                    if (sourceCostScale > 18) dimensions.put("sourceCostScale", Integer.toString(sourceCostScale));
                    for (String dimension : List.of("billingaccountid", "subscriptionname", "productname", "resourcegroup", "resourceid", "resourcelocation")) {
                        String value = field(record, headers, dimension);
                        if (!value.isBlank()) dimensions.put(dimension, value);
                    }
                    for (String prefix : List.of("billingperiod", "serviceperiod")) {
                        String first = field(record, headers, prefix + "startdate");
                        String last = field(record, headers, prefix + "enddate");
                        if (!first.isEmpty() || !last.isEmpty()) {
                            if (sourceDate(first).isAfter(sourceDate(last))) throw invalid();
                            dimensions.put(prefix + "startdate", first); dimensions.put(prefix + "enddate", last);
                        }
                    }
                    String tags = field(record, headers, "tags");
                    if (!tags.isBlank()) {
                        try {
                            var node = mapper.readTree(tags);
                            if (!node.isObject() || node.size() > 128) throw invalid();
                            for (var entry : node.properties()) if (!entry.getValue().isString()) throw invalid();
                            dimensions.put("tags", mapper.writeValueAsString(node));
                        } catch (RuntimeException exception) { dimensions.put("tagsStatus", "unavailable"); }
                    }
                    sink.accept(new BillingData.CostRow(dataset, part, rows, date, field(record, headers, "billingcurrency"),
                            cost, bucket, subscription, service, type, invoice, dimensions));
                }
                if (counted.count != expectedBytes) throw invalid();
                return new Parsed(new BillingData.Part(part, HexFormat.of().formatHex(digest.digest()), counted.count, rows),
                        Set.copyOf(subscriptions), Set.copyOf(invoices), attributed, withinDates);
            }
        } catch (BillingException exception) { throw exception; }
        catch (IOException | NoSuchAlgorithmException | RuntimeException exception) {
            // Preserve code locations for safe diagnosis, never parser messages, values or causes.
            var failure = invalid(); failure.setStackTrace(exception.getStackTrace()); throw failure;
        }
    }

    private static String field(CSVRecord row, Map<String, Integer> headers, String key) {
        Integer index = headers.get(key); return index == null ? "" : row.get(index);
    }
    private static LocalDate sourceDate(String value) {
        if (value.matches("[0-9]{1,2}/[0-9]{1,2}/[0-9]{4}")) return LocalDate.parse(value, COST_DETAILS_DATE);
        if (value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return LocalDate.parse(value);
        throw invalid();
    }
    private static BillingException invalid() { return new BillingException(BillingException.Reason.SOURCE_INVALID); }
    private static BillingException limit() { return new BillingException(BillingException.Reason.LIMIT_EXCEEDED); }

    private static final class CountingInput extends java.io.FilterInputStream {
        private long count;
        CountingInput(InputStream stream) { super(stream); }
        @Override public int read() throws IOException { int value = in.read(); if (value >= 0) count++; return value; }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = in.read(bytes, offset, length); if (read > 0) count += read; return read;
        }
    }

    /** Enforce field bounds before Commons CSV allocates a whole hostile field. */
    private static final class FieldBoundReader extends FilterReader {
        private boolean quoted;
        private boolean first = true;
        private int fieldBytes;
        private int columns = 1;
        FieldBoundReader(Reader reader) { super(reader); }
        @Override public int read() throws IOException {
            int value = super.read();
            if (first) { first = false; if (value == '\uFEFF') value = super.read(); }
            if (value < 0) return value;
            if (value == '"') quoted = !quoted;
            if (!quoted && (value == ',' || value == '\r' || value == '\n')) {
                fieldBytes = 0;
                if (value == ',') { if (++columns > 512) throw limit(); } else columns = 1;
            } else {
                fieldBytes += value < 128 ? 1 : value < 2048 ? 2 : 3;
                if (fieldBytes > 65536) throw limit();
            }
            return value;
        }
        @Override public int read(char[] chars, int offset, int length) throws IOException {
            if (length == 0) return 0;
            int count = 0;
            while (count < length) { int value = read(); if (value < 0) break; chars[offset + count++] = (char) value; }
            return count == 0 ? -1 : count;
        }
    }
}
