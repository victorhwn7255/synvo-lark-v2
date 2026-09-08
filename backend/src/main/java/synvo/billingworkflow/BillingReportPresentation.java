package synvo.billingworkflow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import synvo.billing.BillingData;

/** Readable views of immutable facts. Rounding and display grouping never rewrite source evidence. */
final class BillingReportPresentation {
    private static final Map<String, String> SERVICES = Map.of(
            "microsoft.storage", "Storage", "microsoft.network", "Networking",
            "microsoft.compute", "Compute", "microsoft.recoveryservices", "Backup & recovery",
            "microsoft.cognitiveservices", "AI services");
    private BillingReportPresentation() { }

    static String money(BigDecimal amount) {
        if (amount == null) return "Unavailable";
        if (amount.signum() != 0 && amount.abs().compareTo(new BigDecimal("0.01")) < 0)
            return "< USD 0.01" + (amount.signum() < 0 ? " (negative)" : "");
        var format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return (amount.signum() < 0 ? "−USD " : "USD ") + format.format(amount.abs());
    }
    static String percent(BigDecimal value) { return value == null ? "Unavailable" : value.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%"; }
    static String month(String value) {
        try { return YearMonth.parse(value.replace("month:", "")).format(DateTimeFormatter.ofPattern("MMM uuuu", Locale.ENGLISH)); }
        catch (java.time.DateTimeException invalid) { return value; }
    }
    static String period(BillingData.Range range) { return period(range.selected().stream().map(Object::toString).toList()); }
    private static String period(List<String> values) {
        if (values.isEmpty()) return "Not available";
        return month(values.getFirst()) + (values.size() == 1 ? "" : " – " + month(values.getLast()));
    }
    static String label(String value) {
        return SERVICES.getOrDefault(value.toLowerCase(Locale.ROOT), value);
    }
    static String narrative(String value) {
        for (var entry : SERVICES.entrySet()) value = value.replaceAll("(?i)" + java.util.regex.Pattern.quote(entry.getKey()), java.util.regex.Matcher.quoteReplacement(entry.getValue()));
        return value.replace("Azure-bucket cost", "Azure subscription cost").replace("Azure-bucket", "Azure subscription")
                .replace("the manifest does not establish source finality", "Azure may still revise these charges");
    }
    static Map<String, BigDecimal> grouped(Map<String, BigDecimal> values) {
        var grouped = new TreeMap<String, BigDecimal>(String.CASE_INSENSITIVE_ORDER);
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> grouped.merge(entry.getKey(), entry.getValue(), BigDecimal::add));
        return grouped;
    }
    static List<BillingWorkflowFacade.DisplayRow> rows(Map<String, BigDecimal> values, boolean service, boolean changes) {
        var source = service ? grouped(values) : values;
        Comparator<Map.Entry<String, BigDecimal>> order = Comparator.comparing(entry -> changes ? entry.getValue().abs() : entry.getValue(), Comparator.reverseOrder());
        return source.entrySet().stream().sorted(order.thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> new BillingWorkflowFacade.DisplayRow(service ? label(entry.getKey()) : entry.getKey(), money(entry.getValue()))).toList();
    }
    private static List<BillingWorkflowFacade.DisplayRow> months(Map<String, BigDecimal> values) {
        return new TreeMap<>(values).entrySet().stream().map(entry -> new BillingWorkflowFacade.DisplayRow(month(entry.getKey()), money(entry.getValue()))).toList();
    }
    static BillingWorkflowFacade.Presentation of(BillingWorkflowStore.Report report) {
        var facts = report.facts(); var source = report.source();
        var baseline = source.baseline() == null ? Map.<String, BigDecimal>of() : source.baseline().months();
        var highlights = new ArrayList<String>();
        highlights.add(managementLead(report));
        highlights.add("Azure subscription cost was " + money(facts.selectedTotal()) + " for " + period(report.range()) + ". These costs exclude tax and separately identified billing-profile adjustments.");
        if (facts.months().size() > 1) {
            var range = facts.months().values().stream().sorted().toList();
            highlights.add("Monthly costs ranged from " + money(range.getFirst()) + " to " + money(range.getLast()) + ". Review the monthly figures alongside the period comparison.");
        }
        if (facts.delta() != null && facts.baselineTotal() != null) {
            highlights.add("Compared with " + period(report.range().baseline().stream().map(Object::toString).toList()) + ", costs "
                    + (facts.delta().signum() < 0 ? "decreased by " : facts.delta().signum() > 0 ? "increased by " : "were unchanged: ")
                    + money(facts.delta().abs()) + (facts.percent() == null ? "" : " (" + percent(facts.percent().abs()) + ")") + ". A cost reduction is not proof of recurring savings.");
            baseline.entrySet().stream().max(Map.Entry.comparingByValue()).filter(peak -> baseline.size() > 1
                    && facts.baselineTotal().signum() > 0 && peak.getValue().compareTo(facts.baselineTotal().multiply(new BigDecimal("0.5"))) > 0)
                    .ifPresent(peak -> highlights.add("Comparison caution: " + month(peak.getKey()) + " alone contributed " + money(peak.getValue())
                            + ", more than half of the comparison-period cost. That high month strongly influences the headline change; the data does not establish its cause."));
        }
        var coverage = new ArrayList<String>();
        for (YearMonth selected : report.range().selected()) {
            var invoices = source.invoices().stream().filter(invoice -> !invoice.first().isAfter(selected.atDay(1)) && !invoice.last().isBefore(selected.atEndOfMonth())).toList();
            boolean matched = invoices.stream().anyMatch(invoice -> source.reconciliation().stream().anyMatch(bridge -> bridge.invoiceId().equals(invoice.id()) && bridge.status() == BillingData.ReconciliationStatus.MATCHED));
            coverage.add(month(selected.toString()) + ": " + (matched ? "matched invoice available in saved evidence."
                    : "not invoice-reconciled; no matching reconciled invoice in saved evidence."));
        }
        var buckets = facts.buckets().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> new BillingWorkflowFacade.DisplayRow(
                switch (entry.getKey()) { case AZURE -> "Azure subscriptions"; case PROFILE_ADJUSTMENT -> "Billing-profile adjustments"; case EXCLUDED -> "Excluded charges"; case UNRESOLVED -> "Unresolved attribution"; }
                        + " (" + entry.getValue().rows() + " rows)", money(entry.getValue().cost()))).toList();
        var reconciliation = source.reconciliation().stream().map(bridge -> "Invoice " + bridge.invoiceId() + ": "
                + (bridge.status() == BillingData.ReconciliationStatus.MATCHED ? "matched at currency precision" : bridge.status().name().toLowerCase(Locale.ROOT).replace('_', ' '))
                + "; charges " + money(bridge.sourceCharges()) + "; difference " + money(bridge.residual()) + ".").toList();
        return new BillingWorkflowFacade.Presentation(period(report.range()), period(report.range().baseline().stream().map(Object::toString).toList()),
                money(facts.selectedTotal()), money(facts.baselineTotal()), money(facts.delta()), percent(facts.percent()),
                highlights, coverage, rows(facts.services(), true, false), rows(facts.serviceChanges(), true, true), months(facts.months()), months(baseline),
                rows(facts.subscriptions(), false, false), buckets, reconciliation);
    }

    private static String managementLead(BillingWorkflowStore.Report report) {
        var facts = report.facts();
        String lead = period(report.range()) + " Azure subscription costs were " + money(facts.selectedTotal()) + ".";
        boolean completeMonths = report.range().selected().stream().allMatch(month -> facts.months().containsKey("month:" + month));
        if (completeMonths && report.range().selected().size() > 1) {
            lead = period(report.range()) + " Azure subscription costs averaged "
                    + money(facts.selectedTotal().divide(BigDecimal.valueOf(report.range().selected().size()), 12, RoundingMode.HALF_UP)) + " per month.";
        }
        var services = grouped(facts.services()).entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).toList();
        if (services.size() >= 2 && facts.selectedTotal().signum() > 0 && services.stream().allMatch(entry -> entry.getValue().signum() >= 0)
                && services.stream().map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(facts.selectedTotal()) == 0) {
            var share = services.get(0).getValue().add(services.get(1).getValue()).multiply(new BigDecimal("100"))
                    .divide(facts.selectedTotal(), 0, RoundingMode.HALF_UP);
            lead += " " + label(services.get(0).getKey()) + " and " + label(services.get(1).getKey())
                    + " accounted for about " + share.toPlainString() + "% of spending.";
        }
        var baseline = report.source().baseline();
        if (baseline != null && baseline.months().size() > 1 && facts.baselineTotal() != null && facts.baselineTotal().signum() > 0
                && facts.delta() != null && facts.delta().signum() < 0 && facts.percent() != null) {
            var peak = baseline.months().entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
            if (peak.getValue().compareTo(facts.baselineTotal().multiply(new BigDecimal("0.5"))) > 0)
                lead += " The headline " + percent(facts.percent().abs()) + " reduction is heavily influenced by "
                        + month(peak.getKey()) + "'s high cost.";
        }
        return lead + " Recurring savings have not been established.";
    }
}
