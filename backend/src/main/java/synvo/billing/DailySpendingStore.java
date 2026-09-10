package synvo.billing;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Independent received-month replacements; reads stream one consistent published revision. */
public interface DailySpendingStore {
    record Period(LocalDate first, LocalDate through) { }
    record Run(UUID id, String state, Instant startedAt, Instant finishedAt, List<Period> plan,
            int completed, int attempted, BillingException.Reason failure) { }
    record Claim(Run run, boolean acquired) { }
    record Partition(UUID version, Period period, Instant retrievedAt, long rows) { }
    record Feed(List<Partition> partitions, Run latest, Instant lastSuccess) { }
    Claim claim(String owner, String revision, String key, YearMonth historyStart, Instant now);
    UUID stagePartition(String owner, String revision, UUID run, Period period, Instant now);
    void stageRows(String owner, String revision, UUID run, UUID version, List<BillingData.CostRow> rows, Instant now);
    void publish(String owner, String revision, UUID run, UUID version, BillingData.Partition evidence, Instant now);
    void skip(String owner, String revision, UUID run, UUID version, BillingException.Reason failure, Instant now);
    void finish(String owner, String revision, UUID run, BillingException.Reason failure, Instant now);
    Feed read(String owner, String revision, YearMonth historyStart, Instant now, Consumer<BillingData.CostRow> sink);
}
