package synvo.billingworkflow;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import synvo.billing.BillingData;
import synvo.billing.BillingException;

/** Bounded, read-time view of one report's evidence; amounts are decimal strings. */
public record BillingDailyCosts(UUID reportId, int year, List<Integer> years, String first, String last,
        String comparisonFirst, String comparisonLast, String currency, String basis, String calculationVersion,
        Instant expiresAt, Instant retrievedFirst, Instant retrievedLast, List<String> limitations,
        List<Day> days, List<Band> bands, int recordedDays, int allRecordedDays, long spilloverRows,
        Amount selectedTotal, Amount comparisonTotal, Amount visibleTotal, Amount outsideYearTotal,
        List<Service> excluded) {
    public record Amount(String exact, String display) { }
    public record Service(String label, Amount amount) { }
    public record Band(int level, String upper, String label) { }
    public record Day(String date, String state, int level, boolean selectedPeriod, long rows,
            Amount amount, Amount selected, Amount comparison, List<Service> services) { }
    @Override public String toString() { return "BillingDailyCosts[protected]"; }

    static BillingDailyCosts calculate(BillingWorkflowStore.Report report, Integer requestedYear, LocalDate today,
            Consumer<Consumer<BillingData.CostRow>> evidence) {
        var aggregate = new Aggregate(report);
        evidence.accept(aggregate::add);
        return aggregate.finish(requestedYear, today);
    }
    private static BillingException invalid() { return new BillingException(BillingException.Reason.SOURCE_INVALID); }
    private static final class Aggregate {
        final BillingWorkflowStore.Report report;
        final Map<String, Boolean> roles = new HashMap<>();
        final DailyCostAggregation totals = new DailyCostAggregation();
        final List<String> limitations = new ArrayList<>();
        Instant retrievedFirst, retrievedLast;
        Aggregate(BillingWorkflowStore.Report report) {
            this.report = report;
            var selectedMonths = report.range().selected();
            var baselineMonths = report.range().baseline();
            selectedMonths.forEach(month -> totals.years.add(month.getYear()));
            baselineMonths.forEach(month -> totals.years.add(month.getYear()));
            for (var partition : report.source().partitions()) {
                if (!partition.dataset().startsWith("month:")) continue;
                YearMonth month = YearMonth.parse(partition.dataset().substring(6));
                if (!selectedMonths.contains(month) && !baselineMonths.contains(month)) throw invalid();
                if (roles.putIfAbsent(partition.dataset(), selectedMonths.contains(month)) != null) throw invalid();
                if (retrievedFirst == null || partition.retrievedAt().isBefore(retrievedFirst)) retrievedFirst = partition.retrievedAt();
                if (retrievedLast == null || partition.retrievedAt().isAfter(retrievedLast)) retrievedLast = partition.retrievedAt();
            }
            limitations.add("Recorded charges are provisional; Azure may still revise them. Missing records are not zero spending.");
            limitations.add("Daily dates describe usage or purchases in saved billing-received periods, not a guaranteed complete consumption history.");
            if (selectedMonths.stream().anyMatch(month -> !roles.containsKey("month:" + month))) throw invalid();
            if (baselineMonths.stream().anyMatch(month -> !roles.containsKey("month:" + month))) limitations.add("Comparison-period evidence is incomplete.");
            if (report.source().partitions().stream().anyMatch(p -> roles.containsKey(p.dataset()) && !p.attributionComplete()))
                limitations.add("Some charges have unresolved attribution; populated days show recorded Azure subtotals only.");
        }
        void add(BillingData.CostRow row) {
            if (row.dataset().startsWith("invoice:")) return;
            Boolean selected = roles.get(row.dataset());
            if (selected == null) throw invalid();
            totals.add(row, selected);
        }
        BillingDailyCosts finish(Integer requestedYear, LocalDate today) {
            if (totals.selected.compareTo(report.facts().selectedTotal()) != 0
                    || report.facts().baselineTotal() != null && totals.comparison.compareTo(report.facts().baselineTotal()) != 0
                    || report.source().baseline() != null && totals.comparison.compareTo(report.source().baseline().azureTotal()) != 0) throw invalid();
            if (totals.spillovers > 0) limitations.add("Some charge dates fall outside their original billing month; selected/comparison membership is preserved.");
            if (totals.dates.keySet().stream().anyMatch(date -> date.isAfter(today))) limitations.add("Future-dated source charges exist; inspect them as a source inconsistency, not confirmed future spending.");
            var c = totals.finish(requestedYear == null ? report.range().last().getYear() : requestedYear, today, report.range().first().atDay(1), report.range().last().atEndOfMonth());
            var baseline = report.range().baseline();
            return new BillingDailyCosts(report.id(), c.year(), c.years(), report.range().first().atDay(1).toString(), report.range().last().atEndOfMonth().toString(),
                    baseline.isEmpty() ? null : baseline.getFirst().atDay(1).toString(), baseline.isEmpty() ? null : baseline.getLast().atEndOfMonth().toString(),
                    report.source().currency(), report.source().basis(), "daily-recorded-v2", report.expiresAt(), retrievedFirst, retrievedLast,
                    List.copyOf(limitations), c.days(), c.bands(), c.recordedDays(), c.allRecordedDays(), c.spilloverRows(),
                    c.selectedTotal(), c.comparisonTotal(), c.visibleTotal(), c.outsideYearTotal(), c.excluded());
        }
    }
}
