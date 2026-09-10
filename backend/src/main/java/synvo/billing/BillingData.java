package synvo.billing;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.LongStream;

/** Immutable, provider-independent billing evidence. None of these records grants access. */
public final class BillingData {
    private BillingData() { }

    public static final String SOURCE_ID = "primary-azure-billing";
    public static final String BASIS = "actual-cost/billing-received-period";
    public static final String MAPPING_VERSION = "mca-actual-csv-v3";
    public static final String CALCULATION_VERSION = "billing-exact-v1";
    public enum State { RUNNING, READY, READY_WITH_LIMITATIONS, FAILED, INTERRUPTED }
    public enum Bucket { AZURE, PROFILE_ADJUSTMENT, EXCLUDED, UNRESOLVED }
    public enum ReconciliationStatus { MATCHED, MISMATCH, UNAVAILABLE, NOT_COMPARABLE }

    public record Range(YearMonth first, YearMonth last, boolean comparison) {
        public void validate(Clock clock) {
            if (first == null || last == null || first.isAfter(last)
                    || ChronoUnit.MONTHS.between(first, last) > 5
                    || !last.isBefore(YearMonth.now(clock.withZone(ZoneOffset.UTC)))) {
                throw new BillingException(BillingException.Reason.INVALID_REQUEST);
            }
        }
        public List<YearMonth> selected() { return months(first, last); }
        public List<YearMonth> baseline() {
            long count = ChronoUnit.MONTHS.between(first, last) + 1;
            return comparison ? months(first.minusMonths(count), first.minusMonths(1)) : List.of();
        }
        private static List<YearMonth> months(YearMonth first, YearMonth last) {
            return LongStream.rangeClosed(0, ChronoUnit.MONTHS.between(first, last)).mapToObj(first::plusMonths).toList();
        }
    }

    public record CostRow(String dataset, int part, long ordinal, LocalDate date, String currency,
            BigDecimal cost, Bucket bucket, String subscription, String service, String chargeType,
            String invoiceId, Map<String, String> dimensions) {
        public CostRow {
            if (dataset == null || dataset.length() > 160 || part < 0 || ordinal < 1 || date == null
                    || bucket == null || subscription == null || service == null || chargeType == null
                    || invoiceId == null || dimensions == null || dimensions.size() > 32) {
                throw new BillingException(BillingException.Reason.SOURCE_INVALID);
            }
            if (!"USD".equals(currency)) throw new BillingException(BillingException.Reason.UNSUPPORTED_CURRENCY);
            BillingAccounting.checkMoney(cost);
            if (bucket == Bucket.AZURE && !subscription.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
                throw new BillingException(BillingException.Reason.SOURCE_INVALID);
            }
            dimensions = Map.copyOf(dimensions);
        }
        @Override public String toString() { return "CostRow[protected evidence]"; }
    }

    public record Part(int ordinal, String sha256, long bytes, long rows) { }
    public record Partition(String dataset, Instant retrievedAt, String sourceVersion, Map<String, String> inventory, List<Part> parts, Set<String> subscriptions,
            Set<String> invoiceIds, boolean attributionComplete, boolean datesWithinPartition) {
        public Partition {
            inventory = Map.copyOf(inventory); parts = List.copyOf(parts); subscriptions = Set.copyOf(subscriptions); invoiceIds = Set.copyOf(invoiceIds);
        }
    }
    public record Amount(long rows, BigDecimal cost) { }
    public record Totals(Map<Bucket, Amount> buckets, Map<String, BigDecimal> services, Map<String, BigDecimal> months) {
        public Totals { buckets = Map.copyOf(buckets); services = Map.copyOf(services); months = Map.copyOf(months); }
        public BigDecimal azureTotal() { return buckets.get(Bucket.AZURE).cost(); }
        public BigDecimal sourceTotal() { return buckets.values().stream().map(Amount::cost).reduce(BigDecimal.ZERO, BigDecimal::add); }
        public boolean completeAttribution() { return buckets.get(Bucket.UNRESOLVED).rows() == 0; }
    }
    public record Comparison(BigDecimal delta, BigDecimal percent) { }
    public record Invoice(String id, LocalDate first, LocalDate last, String documentType, boolean ambiguous,
            Map<String, BigDecimal> amounts, Map<String, String> metadata) {
        public Invoice {
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}") || first == null || last == null || first.isAfter(last)) {
                throw new BillingException(BillingException.Reason.SOURCE_INVALID);
            }
            amounts = Map.copyOf(amounts); amounts.values().forEach(BillingAccounting::checkMoney); metadata = Map.copyOf(metadata);
        }
        @Override public String toString() { return "Invoice[protected evidence]"; }
    }
    public record Reconciliation(String invoiceId, ReconciliationStatus status, BigDecimal sourceCharges, BigDecimal residual) { }
    public record Summary(String currency, String basis, String mappingVersion, String calculationVersion,
            Totals selected, Totals baseline, Comparison comparison, List<Partition> partitions,
            List<Reconciliation> reconciliation, List<Invoice> invoices, Set<String> limitations) {
        public Summary { partitions = List.copyOf(partitions); reconciliation = List.copyOf(reconciliation); invoices = List.copyOf(invoices); limitations = Set.copyOf(limitations); }
        @Override public String toString() { return "Summary[protected evidence]"; }
    }
    public record Snapshot(UUID id, String owner, String revision, String idempotencyKey, Range range, State state,
            Instant createdAt, Instant expiresAt, Summary summary, BillingException.Reason failure) {
        @Override public String toString() { return "Snapshot[state=" + state + "]"; }
    }
}
