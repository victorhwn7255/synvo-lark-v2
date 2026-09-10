package synvo.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

final class BillingAccounting {
    private final Map<BillingData.Bucket, BillingData.Amount> buckets = new EnumMap<>(BillingData.Bucket.class);
    private final Map<String, BigDecimal> services = new LinkedHashMap<>();
    private final Map<String, BigDecimal> months = new LinkedHashMap<>();

    BillingAccounting() {
        for (var bucket : BillingData.Bucket.values()) buckets.put(bucket, new BillingData.Amount(0, BigDecimal.ZERO));
    }

    void add(BillingData.CostRow row) {
        var old = buckets.get(row.bucket());
        buckets.put(row.bucket(), new BillingData.Amount(old.rows() + 1, checkedAdd(old.cost(), row.cost())));
        if (row.bucket() == BillingData.Bucket.AZURE) {
            services.merge(row.service(), row.cost(), BillingAccounting::checkedAdd);
            months.merge(row.dataset(), row.cost(), BillingAccounting::checkedAdd);
        }
    }

    BillingData.Totals totals() {
        var totals = new BillingData.Totals(buckets, services, months);
        checkMoney(totals.sourceTotal());
        return totals;
    }

    static BigDecimal money(String text) {
        try {
            if (text == null || text.length() > 80) throw new NumberFormatException();
            var amount = new BigDecimal(text);
            checkMoney(amount);
            return amount;
        } catch (NumberFormatException exception) { throw new BillingException(BillingException.Reason.SOURCE_INVALID); }
    }

    static void checkMoney(BigDecimal value) {
        if (value == null || value.precision() > 38 || value.scale() > 18
                || value.precision() - value.scale() > 38 || value.scale() < -38) {
            throw new BillingException(BillingException.Reason.LIMIT_EXCEEDED);
        }
    }

    private static BigDecimal checkedAdd(BigDecimal left, BigDecimal right) {
        var result = left.add(right); checkMoney(result); return result;
    }

    static BillingData.Comparison compare(BigDecimal current, BigDecimal baseline) {
        var delta = current.subtract(baseline); checkMoney(delta);
        return new BillingData.Comparison(delta, baseline.signum() <= 0 ? null
                : delta.multiply(BigDecimal.valueOf(100)).divide(baseline, 2, RoundingMode.HALF_UP));
    }

    static BillingData.Reconciliation reconcile(BillingData.Invoice invoice, BigDecimal source, boolean complete) {
        var target = invoice.amounts().get("billedAmount");
        if (!complete || target == null || invoice.ambiguous() || !"Invoice".equals(invoice.documentType())) {
            return new BillingData.Reconciliation(invoice.id(), BillingData.ReconciliationStatus.NOT_COMPARABLE, source, null);
        }
        var residual = target.subtract(source); checkMoney(residual);
        return new BillingData.Reconciliation(invoice.id(), residual.setScale(2, RoundingMode.HALF_UP).signum() == 0
                ? BillingData.ReconciliationStatus.MATCHED : BillingData.ReconciliationStatus.MISMATCH, source, residual);
    }
}
