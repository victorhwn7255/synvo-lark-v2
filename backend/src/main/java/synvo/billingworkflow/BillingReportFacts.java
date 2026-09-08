package synvo.billingworkflow;

import java.math.BigDecimal;
import java.util.Map;
import synvo.billing.BillingData;

/** Exact report amounts remain Java values; JSON serialization uses decimal strings. */
public record BillingReportFacts(
        BigDecimal selectedTotal, BigDecimal baselineTotal, BigDecimal delta, BigDecimal percent,
        Map<String, BigDecimal> services, Map<String, BigDecimal> months,
        Map<String, BigDecimal> subscriptions, Map<String, BigDecimal> serviceChanges,
        Map<BillingData.Bucket, BillingData.Amount> buckets) {
    public BillingReportFacts {
        services = Map.copyOf(services); months = Map.copyOf(months);
        subscriptions = Map.copyOf(subscriptions); serviceChanges = Map.copyOf(serviceChanges);
        buckets = Map.copyOf(buckets);
    }
    @Override public String toString() { return "BillingReportFacts[protected]"; }
}
