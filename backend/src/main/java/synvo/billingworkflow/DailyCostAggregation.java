package synvo.billingworkflow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import synvo.billing.BillingData;
import synvo.billing.BillingException;

import synvo.billingworkflow.BillingDailyCosts.*;

/** Exact date/service arithmetic shared by immutable reports and the independent feed. */
final class DailyCostAggregation {
    record Calendar(int year, List<Integer> years, List<Day> days, List<Band> bands, int recordedDays,
            int allRecordedDays, long spilloverRows, Amount selectedTotal, Amount comparisonTotal,
            Amount visibleTotal, Amount outsideYearTotal, List<Service> excluded) { }
    final Map<LocalDate, Daily> dates = new TreeMap<>();
    final Map<String, BigDecimal> excluded = new TreeMap<>();
    final Set<Integer> years = new TreeSet<>();
    BigDecimal selected = BigDecimal.ZERO, comparison = BigDecimal.ZERO;
    long spillovers;
    private static Amount amount(BigDecimal value) { return new Amount(value.toPlainString(), BillingReportPresentation.money(value)); }
    private static BillingException invalid() { return new BillingException(BillingException.Reason.SOURCE_INVALID); }
    private static final class Daily {
        BigDecimal selected = BigDecimal.ZERO, comparison = BigDecimal.ZERO;
        long rows;
        final Map<String, BigDecimal> services = new HashMap<>();
        BigDecimal total() { return selected.add(comparison); }
    }
    void add(BillingData.CostRow row, boolean isSelected) {
        if (row.dataset().startsWith("invoice:")) return;
        if (!row.currency().equals("USD")) throw invalid();
        if (row.bucket() != BillingData.Bucket.AZURE) {
            excluded.merge(row.bucket().name(), row.cost(), BigDecimal::add); return;
        }
        if (row.date().getYear() < 1 || row.date().getYear() > 9999) throw invalid();
        years.add(row.date().getYear());
        if (!YearMonth.from(row.date()).toString().equals(row.dataset().substring(6))) spillovers++;
        var day = dates.computeIfAbsent(row.date(), ignored -> new Daily());
        day.rows++;
        if (isSelected) { day.selected = day.selected.add(row.cost()); selected = selected.add(row.cost()); }
        else { day.comparison = day.comparison.add(row.cost()); comparison = comparison.add(row.cost()); }
        day.services.merge(row.service(), row.cost(), BigDecimal::add);
        if (dates.size() > 1096 || years.size() > 4) throw new BillingException(BillingException.Reason.LIMIT_EXCEEDED);
    }
    Calendar finish(int year, LocalDate today, LocalDate selectedFirst, LocalDate selectedLast) {
        if (year < 1 || year > 9999 || !years.contains(year)) throw new BillingException(BillingException.Reason.INVALID_REQUEST);
        var positive = dates.values().stream().map(Daily::total).filter(value -> value.signum() > 0).sorted().toList();
        var bands = new ArrayList<Band>();
        BigDecimal previous = BigDecimal.ZERO;
        for (int i = 1; !positive.isEmpty() && i <= 5; i++) {
            BigDecimal value = positive.get((positive.size() * i + 4) / 5 - 1);
            // Round only visual thresholds; ties and sub-cent noise must not manufacture extra shades.
            BigDecimal upper = value.setScale(Math.max(2, 2 - value.precision() + value.scale()), java.math.RoundingMode.CEILING);
            if (upper.compareTo(previous) <= 0) continue;
            bands.add(new Band(bands.size() + 1, upper.toPlainString(), "> " + bandMoney(previous) + " – ≤ " + bandMoney(upper)));
            previous = upper;
        }
        var days = new ArrayList<Day>();
        BigDecimal visible = BigDecimal.ZERO;
        int count = 0;
        LocalDate first = LocalDate.of(year, 1, 1);
        for (int i = 0; i < first.lengthOfYear(); i++) {
            LocalDate date = first.plusDays(i); Daily day = dates.get(date);
            boolean inSelected = !date.isBefore(selectedFirst) && !date.isAfter(selectedLast);
            if (day == null) {
                days.add(new Day(date.toString(), date.isAfter(today) ? "FUTURE" : "MISSING", 0, inSelected, 0, null, null, null, List.of()));
                continue;
            }
            count++; visible = visible.add(day.total());
            int level = 0;
            if (day.total().signum() > 0) {
                level = 1;
                while (level < bands.size() && day.total().compareTo(new BigDecimal(bands.get(level - 1).upper())) > 0) level++;
            }
            String state = date.isAfter(today) ? "FUTURE_RECORDED" : day.total().signum() < 0 ? "NEGATIVE" : day.total().signum() == 0 ? "ZERO" : "RECORDED";
            days.add(new Day(date.toString(), state, level, inSelected, day.rows, amount(day.total()), amount(day.selected), amount(day.comparison), services(day.services)));
        }
        return new Calendar(year, List.copyOf(years), List.copyOf(days), List.copyOf(bands), count, dates.size(), spillovers,
                amount(selected), amount(comparison), amount(visible), amount(selected.add(comparison).subtract(visible)),
                excluded.entrySet().stream().map(e -> new Service(e.getKey().replace('_', ' ').toLowerCase(java.util.Locale.ROOT), amount(e.getValue()))).toList());
    }
    private static String bandMoney(BigDecimal value) {
        return value.signum() > 0 && value.compareTo(new BigDecimal("0.01")) < 0 ? "USD " + value.stripTrailingZeros().toPlainString() : BillingReportPresentation.money(value);
    }
    private static List<Service> services(Map<String, BigDecimal> values) {
        var entries = BillingReportPresentation.grouped(values).entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, BigDecimal>, BigDecimal>comparing(e -> e.getValue().abs()).reversed().thenComparing(Map.Entry.comparingByKey())).toList();
        var result = new ArrayList<Service>();
        for (var entry : entries.subList(0, Math.min(5, entries.size()))) result.add(new Service(BillingReportPresentation.label(entry.getKey()), amount(entry.getValue())));
        if (entries.size() > 5) result.add(new Service("Other", amount(entries.subList(5, entries.size()).stream().map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add))));
        return List.copyOf(result);
    }
}
