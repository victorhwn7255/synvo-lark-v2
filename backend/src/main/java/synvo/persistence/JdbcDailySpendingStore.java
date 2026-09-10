package synvo.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import synvo.billing.BillingData;
import synvo.billing.BillingException;
import synvo.billing.DailySpendingStore;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcDailySpendingStore implements DailySpendingStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    public JdbcDailySpendingStore(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper mapper) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager); this.mapper = mapper;
    }
    @Override public Claim claim(String owner, String revision, String key, YearMonth historyStart, Instant now) {
        return tx(() -> {
            lock();
            var existing = jdbc.query("SELECT * FROM billing_daily_run WHERE owner_id=? AND scope_revision=? AND idempotency_key=? AND expires_at>?",
                    this::run, owner, revision, key, utc(now));
            if (!existing.isEmpty()) return new Claim(existing.getFirst(), false);
            var active = jdbc.query("SELECT * FROM billing_daily_run WHERE state='RUNNING'", this::run);
            if (!active.isEmpty()) {
                Boolean same = jdbc.queryForObject("SELECT owner_id=? AND scope_revision=? FROM billing_daily_run WHERE id=?", Boolean.class, owner, revision, active.getFirst().id());
                if (Boolean.TRUE.equals(same)) return new Claim(active.getFirst(), false);
                throw failure(BillingException.Reason.BUSY);
            }
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM billing_snapshot WHERE state='RUNNING')", Boolean.class))) throw failure(BillingException.Reason.BUSY);
            cleanup(now);
            var partitions = partitions(owner, revision, historyStart, now);
            var plan = new ArrayList<Period>();
            LocalDate today = now.atOffset(ZoneOffset.UTC).toLocalDate();
            YearMonth current = YearMonth.from(today);
            for (YearMonth month = first(historyStart, now); !month.isAfter(current); month = month.plusMonths(1)) {
                LocalDate from = month.atDay(1), through = month.equals(current) ? today : month.atEndOfMonth();
                var saved = partitions.stream().filter(p -> p.period().first().equals(from)).findFirst();
                if (!month.isBefore(current.minusMonths(1)) || saved.isEmpty() || saved.get().period().through().isBefore(through)
                        || !saved.get().retrievedAt().isAfter(now.minusSeconds(30L * 86400))) plan.add(new Period(from, through));
            }
            if (plan.isEmpty() || plan.size() > 13) throw failure(BillingException.Reason.INVALID_REQUEST);
            if (jdbc.queryForObject("SELECT count(*) FROM billing_daily_run", Long.class) >= 1000) throw failure(BillingException.Reason.LIMIT_EXCEEDED);
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO billing_daily_run(id,owner_id,scope_revision,idempotency_key,state,started_at,expires_at,plan) VALUES(?,?,?,?,'RUNNING',?,?,?::jsonb)",
                    id, owner, revision, key, utc(now), utc(now.plusSeconds(90L * 86400)), mapper.writeValueAsString(plan));
            return new Claim(new Run(id, "RUNNING", now, null, List.copyOf(plan), 0, 0, null), true);
        });
    }
    @Override public UUID stagePartition(String owner, String revision, UUID run, Period period, Instant now) {
        return tx(() -> {
            var active = requireRunning(owner, revision, run, now);
            if (active.attempted() >= active.plan().size() || !active.plan().get(active.attempted()).equals(period)) throw failure(BillingException.Reason.INVALID_REQUEST);
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM billing_daily_partition WHERE run_id=? AND NOT published)", Boolean.class, run))) throw failure(BillingException.Reason.NOT_READY);
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO billing_daily_partition(id,run_id,owner_id,scope_revision,first_date,through_date) VALUES(?,?,?,?,?,?)",
                    id, run, owner, revision, period.first(), period.through());
            return id;
        });
    }
    @Override public void stageRows(String owner, String revision, UUID run, UUID version, List<BillingData.CostRow> rows, Instant now) {
        tx(() -> {
            requireRunning(owner, revision, run, now);
            if (rows.size() > 250) throw failure(BillingException.Reason.LIMIT_EXCEEDED);
            LocalDate first = staged(run, version);
            for (var row : rows) {
                if (!row.dataset().equals("month:" + YearMonth.from(first))) throw failure(BillingException.Reason.SOURCE_INVALID);
                jdbc.update("INSERT INTO billing_daily_row(version_id,part,ordinal,evidence) VALUES(?,?,?,?::jsonb)", version, row.part(), row.ordinal(), mapper.writeValueAsString(row));
            }
            return null;
        });
    }
    @Override public void publish(String owner, String revision, UUID run, UUID version, BillingData.Partition evidence, Instant now) {
        tx(() -> {
            lock();
            requireRunning(owner, revision, run, now);
            LocalDate first = staged(run, version);
            if (first.isBefore(YearMonth.from(now.atOffset(ZoneOffset.UTC)).minusMonths(12).atDay(1))
                    || !evidence.dataset().equals("month:" + YearMonth.from(first)) || evidence.retrievedAt().isAfter(now)
                    || evidence.parts().size() > 100) throw failure(BillingException.Reason.SOURCE_INVALID);
            long expected = 0;
            for (int i = 0; i < evidence.parts().size(); i++) {
                var part = evidence.parts().get(i);
                if (part.ordinal() != i || part.rows() < 0 || part.bytes() < 0 || part.sha256() == null || !part.sha256().matches("[a-f0-9]{64}")) throw failure(BillingException.Reason.SOURCE_INVALID);
                long count = jdbc.queryForObject("SELECT count(*) FROM billing_daily_row WHERE version_id=? AND part=?", Long.class, version, i);
                if (count != part.rows()) throw failure(BillingException.Reason.SOURCE_INVALID);
                expected += count;
            }
            if (expected != jdbc.queryForObject("SELECT count(*) FROM billing_daily_row WHERE version_id=?", Long.class, version)) throw failure(BillingException.Reason.SOURCE_INVALID);
            jdbc.update("DELETE FROM billing_daily_partition WHERE owner_id=? AND scope_revision=? AND first_date=? AND published", owner, revision, first);
            jdbc.update("UPDATE billing_daily_partition SET published=true,retrieved_at=?,row_count=?,source_version=?,parts=?::jsonb WHERE id=?",
                    utc(evidence.retrievedAt()), expected, evidence.sourceVersion(), mapper.writeValueAsString(evidence.parts()), version);
            jdbc.update("UPDATE billing_daily_run SET completed=completed+1,attempted=attempted+1 WHERE id=?", run);
            return null;
        });
    }
    @Override public void skip(String owner, String revision, UUID run, UUID version, BillingException.Reason reason, Instant now) {
        tx(() -> {
            requireRunning(owner, revision, run, now);
            staged(run, version);
            jdbc.update("DELETE FROM billing_daily_partition WHERE id=? AND NOT published", version);
            jdbc.update("UPDATE billing_daily_run SET attempted=attempted+1,failure=? WHERE id=?", reason.name(), run);
            return null;
        });
    }
    @Override public void finish(String owner, String revision, UUID id, BillingException.Reason reason, Instant now) {
        tx(() -> {
            lock();
            var runs = jdbc.query("SELECT * FROM billing_daily_run WHERE id=? AND owner_id=? AND scope_revision=? AND state='RUNNING' FOR UPDATE", this::run, id, owner, revision);
            if (runs.isEmpty()) return null;
            var run = runs.getFirst();
            if (reason == null && run.attempted() != run.plan().size()) throw failure(BillingException.Reason.NOT_READY);
            BillingException.Reason outcome = reason == null ? run.failure() : reason;
            String state = outcome == null ? "COMPLETE" : outcome == BillingException.Reason.INTERRUPTED ? "INTERRUPTED" : run.completed() > 0 ? "PARTIAL" : "FAILED";
            jdbc.update("UPDATE billing_daily_run SET state=?,failure=?,finished_at=? WHERE id=?", state, outcome == null ? null : outcome.name(), utc(now), id);
            jdbc.update("DELETE FROM billing_daily_partition WHERE run_id=? AND NOT published", id);
            return null;
        });
    }
    @Override public Feed read(String owner, String revision, YearMonth historyStart, Instant now, Consumer<BillingData.CostRow> sink) {
        return tx(() -> {
            // Publication takes the same lock: metadata, rows and legend are one revision, not interleaved reads.
            lock();
            var partitions = partitions(owner, revision, historyStart, now);
            for (var partition : partitions) {
                jdbc.query(connection -> {
                    var statement = connection.prepareStatement("SELECT evidence::text FROM billing_daily_row WHERE version_id=? ORDER BY part,ordinal");
                    statement.setObject(1, partition.version()); statement.setFetchSize(250); statement.setQueryTimeout(300); return statement;
                }, (RowCallbackHandler) row -> sink.accept(mapper.readValue(row.getString(1), BillingData.CostRow.class)));
            }
            var latest = jdbc.query("SELECT * FROM billing_daily_run WHERE owner_id=? AND scope_revision=? AND expires_at>? ORDER BY started_at DESC,id DESC LIMIT 1", this::run, owner, revision, utc(now));
            var success = jdbc.queryForList("SELECT max(finished_at) FROM billing_daily_run WHERE owner_id=? AND scope_revision=? AND state='COMPLETE' AND expires_at>?", java.sql.Timestamp.class, owner, revision, utc(now));
            return new Feed(partitions, latest.isEmpty() ? null : latest.getFirst(), success.getFirst() == null ? null : success.getFirst().toInstant());
        });
    }
    private List<Partition> partitions(String owner, String revision, YearMonth historyStart, Instant now) {
        return jdbc.query("SELECT * FROM billing_daily_partition WHERE owner_id=? AND scope_revision=? AND published AND first_date>=? AND first_date<=? ORDER BY first_date",
                (row, index) -> new Partition(row.getObject("id", UUID.class), new Period(row.getDate("first_date").toLocalDate(), row.getDate("through_date").toLocalDate()),
                        row.getTimestamp("retrieved_at").toInstant(), row.getLong("row_count")), owner, revision, first(historyStart, now).atDay(1), YearMonth.from(now.atOffset(ZoneOffset.UTC)).atDay(1));
    }
    private Run requireRunning(String owner, String revision, UUID id, Instant now) {
        var runs = jdbc.query("SELECT * FROM billing_daily_run WHERE id=? AND owner_id=? AND scope_revision=? AND state='RUNNING' AND expires_at>? FOR UPDATE", this::run, id, owner, revision, utc(now));
        if (runs.isEmpty()) throw failure(BillingException.Reason.NOT_READY);
        if (!now.isBefore(runs.getFirst().startedAt().plusSeconds(1800))) throw failure(BillingException.Reason.LIMIT_EXCEEDED);
        return runs.getFirst();
    }
    private LocalDate staged(UUID run, UUID version) {
        var dates = jdbc.queryForList("SELECT first_date FROM billing_daily_partition WHERE id=? AND run_id=? AND NOT published", LocalDate.class, version, run);
        if (dates.isEmpty()) throw failure(BillingException.Reason.NOT_READY);
        return dates.getFirst();
    }
    private Run run(ResultSet row, int index) throws SQLException {
        var finished = row.getTimestamp("finished_at"); String failure = row.getString("failure");
        return new Run(row.getObject("id", UUID.class), row.getString("state"), row.getTimestamp("started_at").toInstant(), finished == null ? null : finished.toInstant(),
                List.copyOf(Arrays.asList(mapper.readValue(row.getString("plan"), Period[].class))), row.getInt("completed"), row.getInt("attempted"), failure == null ? null : BillingException.Reason.valueOf(failure));
    }
    private void cleanup(Instant now) {
        jdbc.update("DELETE FROM billing_daily_partition WHERE first_date<? AND (published OR run_id NOT IN (SELECT id FROM billing_daily_run WHERE state='RUNNING'))", YearMonth.from(now.atOffset(ZoneOffset.UTC)).minusMonths(12).atDay(1));
        jdbc.update("DELETE FROM billing_daily_run WHERE expires_at<=? AND state<>'RUNNING'", utc(now));
    }
    private void lock() { jdbc.execute("SELECT pg_advisory_xact_lock(73580121)"); }
    private static YearMonth first(YearMonth history, Instant now) {
        YearMonth floor = YearMonth.from(now.atOffset(ZoneOffset.UTC)).minusMonths(12);
        return history.isAfter(floor) ? history : floor;
    }
    private static java.time.OffsetDateTime utc(Instant now) { return now.atOffset(ZoneOffset.UTC); }
    private static BillingException failure(BillingException.Reason reason) { return new BillingException(reason); }
    private <T> T tx(Supplier<T> work) {
        try { return transaction.execute(status -> work.get()); }
        catch (BillingException error) { throw error; }
        catch (RuntimeException error) { throw failure(BillingException.Reason.STORAGE_FAILURE); }
    }
}
