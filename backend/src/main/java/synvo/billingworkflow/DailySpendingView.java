package synvo.billingworkflow;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import synvo.billing.DailySpendingFacade;
import synvo.billing.DailySpendingStore;

/** Calendar presentation owns source-date aggregation, never provider retrieval or report identity. */
public final class DailySpendingView {
    public record Refresh(UUID id, String state, int completed, int total, String failure) { }
    public record Coverage(String month, String through, Instant retrievedAt) { }
    public record View(String revision, DailyCostAggregation.Calendar calendar, Instant expiresAt,
            Instant lastAttempt, Instant lastSuccess, Instant retrievedLast, String observedThrough,
            String coveredThrough, List<Coverage> coverage, List<String> missingMonths,
            List<String> staleMonths, boolean provisional, Refresh refresh) { }
    private final DailySpendingFacade daily;
    private final YearMonth historyStart;
    private final Clock clock;
    public DailySpendingView(DailySpendingFacade daily, YearMonth historyStart, Clock clock) {
        this.daily = daily; this.historyStart = historyStart; this.clock = clock;
    }
    public View read(String owner, Integer year) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        var totals = new DailyCostAggregation();
        var feed = daily.read(owner, row -> totals.add(row, true));
        YearMonth current = YearMonth.from(today);
        YearMonth start = historyStart.isAfter(current.minusMonths(12)) ? historyStart : current.minusMonths(12);
        var coverage = new ArrayList<Coverage>();
        var missing = new ArrayList<String>();
        var stale = new ArrayList<String>();
        String through = null;
        boolean gap = false;
        for (YearMonth month = start; !month.isAfter(current); month = month.plusMonths(1)) {
            totals.years.add(month.getYear());
            YearMonth selected = month;
            var partition = feed.partitions().stream().filter(p -> YearMonth.from(p.period().first()).equals(selected)).findFirst();
            if (partition.isEmpty()) { missing.add(month.toString()); gap = true; }
            else {
                var p = partition.get();
                coverage.add(new Coverage(month.toString(), p.period().through().toString(), p.retrievedAt()));
                if (!gap) through = p.period().through().toString();
                if (p.period().through().isBefore(month.equals(current) ? today : month.atEndOfMonth())) gap = true;
                if (p.period().through().isBefore(month.equals(current) ? today : month.atEndOfMonth())
                        || p.retrievedAt().isBefore(clock.instant().minusSeconds((month.isBefore(current.minusMonths(1)) ? 30L : 1L) * 86400))) stale.add(month.toString());
            }
        }
        var calendar = totals.finish(year == null ? today.getYear() : year, today, start.atDay(1), today);
        String observed = totals.dates.keySet().stream().filter(d -> !d.isAfter(today)).max(LocalDate::compareTo).map(LocalDate::toString).orElse(null);
        Instant retrieved = feed.partitions().stream().map(DailySpendingStore.Partition::retrievedAt).max(Instant::compareTo).orElse(null);
        // Version identity changes on every atomic partition replacement, including valid empty partitions.
        String identity = feed.partitions().stream().map(p -> p.version().toString()).collect(java.util.stream.Collectors.joining("/"));
        String revision = UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return new View(revision, calendar, current.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                feed.latest() == null ? null : feed.latest().startedAt(), feed.lastSuccess(), retrieved, observed, through,
                List.copyOf(coverage), List.copyOf(missing), List.copyOf(stale), totals.dates.keySet().stream().anyMatch(d -> YearMonth.from(d).equals(current)), refresh(feed.latest()));
    }
    public Refresh refresh(String owner, String key) { return refresh(daily.refresh(owner, key)); }
    private static Refresh refresh(DailySpendingStore.Run run) {
        return run == null ? null : new Refresh(run.id(), run.state(), run.completed(), run.plan().size(), run.failure() == null ? null : run.failure().name());
    }
}
