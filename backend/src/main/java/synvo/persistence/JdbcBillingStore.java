package synvo.persistence;

import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import synvo.billing.BillingData;
import synvo.billing.BillingException;
import synvo.billing.BillingStore;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcBillingStore implements BillingStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private final DataSource dataSource;
    private Connection workerLease;

    public JdbcBillingStore(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper mapper, DataSource dataSource) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager); this.mapper = mapper; this.dataSource = dataSource;
    }

    @Override public Claim claim(String owner, String revision, String key, BillingData.Range range, Instant now) {
        return tx(() -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(73580121)");
            var existing = jdbc.query("SELECT * FROM billing_snapshot WHERE owner_id=? AND scope_revision=? AND idempotency_key=? AND expires_at>?",
                    this::snapshot, owner, revision, key, utc(now));
            if (!existing.isEmpty()) {
                if (!existing.getFirst().range().equals(range)) throw new BillingException(BillingException.Reason.INVALID_REQUEST);
                return new Claim(existing.getFirst(), false);
            }
            Boolean busy = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM billing_snapshot WHERE state='RUNNING') OR EXISTS(SELECT 1 FROM billing_daily_run WHERE state='RUNNING')", Boolean.class);
            if (Boolean.TRUE.equals(busy)) throw new BillingException(BillingException.Reason.BUSY);
            // Expired keys may be reused only after their evidence has actually been removed.
            jdbc.update("DELETE FROM billing_snapshot WHERE owner_id=? AND scope_revision=? AND idempotency_key=? AND expires_at<=? AND state<>'RUNNING'",
                    owner, revision, key, utc(now));
            UUID id = UUID.randomUUID();
            var expiry = utc(now).plusMonths(13).toInstant();
            jdbc.update("""
                    INSERT INTO billing_snapshot(id,owner_id,scope_revision,idempotency_key,first_month,last_month,comparison,state,created_at,expires_at)
                    VALUES (?,?,?,?,?,?,?,'RUNNING',?,?)
                    """, id, owner, revision, key, range.first().atDay(1), range.last().atDay(1), range.comparison(), utc(now), utc(expiry));
            return new Claim(new BillingData.Snapshot(id, owner, revision, key, range, BillingData.State.RUNNING, now, expiry, null, null), true);
        });
    }

    @Override public Optional<BillingData.Snapshot> find(String owner, String revision, UUID id, Instant now) {
        return safe(() -> jdbc.query("SELECT * FROM billing_snapshot WHERE id=? AND owner_id=? AND scope_revision=? AND expires_at>?",
                this::snapshot, id, owner, revision, utc(now)).stream().findFirst());
    }

    @Override public void stage(String owner, String revision, UUID id, List<BillingData.CostRow> rows) {
        tx(() -> {
            requireRunning(owner, revision, id);
            if (rows.size() > 250) throw new BillingException(BillingException.Reason.LIMIT_EXCEEDED);
            for (var row : rows) jdbc.update("""
                    INSERT INTO billing_cost_evidence(snapshot_id,dataset,part,ordinal,cost,evidence) VALUES(?,?,?,?,?,?::jsonb)
                    """, id, row.dataset(), row.part(), row.ordinal(), row.cost(), mapper.writeValueAsString(row));
            return null;
        });
    }

    @Override public void discardDataset(String owner, String revision, UUID id, String dataset) {
        tx(() -> { requireRunning(owner, revision, id);
            jdbc.update("DELETE FROM billing_cost_evidence WHERE snapshot_id=? AND dataset=?", id, dataset); return null; });
    }

    @Override public void visitStaging(String owner, String revision, UUID id, String dataset, Consumer<BillingData.CostRow> sink) {
        tx(() -> {
            requireRunning(owner, revision, id);
            jdbc.query(connection -> {
                var statement = connection.prepareStatement("SELECT evidence::text FROM billing_cost_evidence WHERE snapshot_id=? AND dataset=? ORDER BY part,ordinal");
                statement.setObject(1, id); statement.setString(2, dataset); statement.setFetchSize(250); return statement;
            }, (RowCallbackHandler) row -> sink.accept(mapper.readValue(row.getString(1), BillingData.CostRow.class)));
            return null;
        });
    }

    @Override public void publish(String owner, String revision, UUID id, BillingData.Summary summary, List<BillingData.Invoice> invoices, Instant now) {
        tx(() -> {
            requireRunning(owner, revision, id);
            for (var invoice : invoices) jdbc.update("INSERT INTO billing_invoice_evidence(snapshot_id,invoice_id,evidence) VALUES(?,?,?::jsonb)",
                    id, invoice.id(), mapper.writeValueAsString(invoice));
            jdbc.update("UPDATE billing_snapshot SET summary=?::jsonb,state=?,expires_at=? WHERE id=? AND owner_id=? AND scope_revision=? AND state='RUNNING'",
                    mapper.writeValueAsString(summary), summary.limitations().isEmpty() ? "READY" : "READY_WITH_LIMITATIONS",
                    utc(now).plusMonths(13), id, owner, revision);
            return null;
        });
    }

    @Override public void fail(String owner, String revision, UUID id, BillingException.Reason reason, Instant now) {
        tx(() -> {
            int updated = jdbc.update("UPDATE billing_snapshot SET state=?,failure=?,expires_at=? WHERE id=? AND owner_id=? AND scope_revision=? AND state='RUNNING'",
                    reason == BillingException.Reason.INTERRUPTED ? "INTERRUPTED" : "FAILED", reason.name(), utc(now).plusDays(90), id, owner, revision);
            if (updated == 1) {
                jdbc.update("DELETE FROM billing_cost_evidence WHERE snapshot_id=?", id);
                jdbc.update("DELETE FROM billing_invoice_evidence WHERE snapshot_id=?", id);
            }
            return null;
        });
    }

    @Override public List<BillingData.CostRow> evidence(String owner, String revision, UUID id, int offset, int limit, Instant now) {
        if (offset < 0 || offset > 1_000_000 || limit < 1 || limit > 100) throw new BillingException(BillingException.Reason.INVALID_REQUEST);
        return safe(() -> jdbc.query("""
                SELECT e.evidence::text FROM billing_cost_evidence e JOIN billing_snapshot s ON s.id=e.snapshot_id
                WHERE s.id=? AND s.owner_id=? AND s.scope_revision=? AND s.expires_at>? AND s.state IN ('READY','READY_WITH_LIMITATIONS')
                ORDER BY e.dataset,e.part,e.ordinal LIMIT ? OFFSET ?
                """, (row, index) -> mapper.readValue(row.getString(1), BillingData.CostRow.class), id, owner, revision, utc(now), limit, offset));
    }

    @Override public void visitEvidence(String owner, String revision, UUID id, Instant now, Consumer<BillingData.CostRow> sink) {
        tx(() -> {
            var snapshot = find(owner, revision, id, now).orElseThrow(() -> new BillingException(BillingException.Reason.NOT_FOUND));
            if (snapshot.summary() == null) throw new BillingException(BillingException.Reason.NOT_READY);
            jdbc.query(connection -> {
                var statement = connection.prepareStatement("""
                        SELECT evidence::text FROM billing_cost_evidence
                        WHERE snapshot_id=? ORDER BY dataset,part,ordinal
                        """);
                statement.setObject(1, id);
                statement.setFetchSize(250);
                statement.setQueryTimeout(300);
                return statement;
            }, (RowCallbackHandler) row -> sink.accept(mapper.readValue(row.getString(1), BillingData.CostRow.class)));
            return null;
        });
    }

    @Override public synchronized void recoverAndCleanup(Instant now) {
        // A session lock prevents a second backend from declaring a live worker interrupted.
        if (workerLease != null) { cleanup(now); return; }
        if (workerLease == null) {
            try {
                workerLease = dataSource.getConnection();
                try (var statement = workerLease.createStatement(); var result = statement.executeQuery("SELECT pg_try_advisory_lock(73580122)")) {
                    result.next();
                    if (!result.getBoolean(1)) { close(); throw new BillingException(BillingException.Reason.BUSY); }
                }
            } catch (SQLException exception) { close(); throw new BillingException(BillingException.Reason.STORAGE_FAILURE); }
        }
        tx(() -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(73580121)");
            jdbc.update("DELETE FROM billing_daily_partition WHERE NOT published");
            jdbc.update("UPDATE billing_daily_run SET state='INTERRUPTED',failure='INTERRUPTED',finished_at=? WHERE state='RUNNING'", utc(now));
            jdbc.update("DELETE FROM billing_cost_evidence WHERE snapshot_id IN (SELECT id FROM billing_snapshot WHERE state='RUNNING')");
            jdbc.update("DELETE FROM billing_invoice_evidence WHERE snapshot_id IN (SELECT id FROM billing_snapshot WHERE state='RUNNING')");
            jdbc.update("UPDATE billing_snapshot SET state='INTERRUPTED',failure='INTERRUPTED',expires_at=? WHERE state='RUNNING'", utc(now).plusDays(90));
            return null;
        });
        cleanup(now);
    }

    @Override public void cleanup(Instant now) {
        tx(() -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(73580121)");
            jdbc.update("DELETE FROM billing_daily_partition WHERE published AND first_date<?", YearMonth.from(utc(now)).minusMonths(12).atDay(1));
            jdbc.update("DELETE FROM billing_daily_run WHERE expires_at<=? AND state<>'RUNNING'", utc(now));
            return null;
        });
        // Drain a restored backlog in short transactions before serving evidence.
        // A pathological backlog fails startup closed rather than retaining it silently.
        for (int batch = 0; batch < 100; batch++) {
            int deleted = tx(() -> jdbc.update("DELETE FROM billing_snapshot WHERE id IN (SELECT id FROM billing_snapshot WHERE expires_at<=? AND state<>'RUNNING' ORDER BY expires_at LIMIT 100)", utc(now)));
            if (deleted < 100) return;
        }
        throw new BillingException(BillingException.Reason.LIMIT_EXCEEDED);
    }

    private void requireRunning(String owner, String revision, UUID id) {
        var states = jdbc.queryForList("SELECT state FROM billing_snapshot WHERE id=? AND owner_id=? AND scope_revision=? FOR UPDATE", String.class, id, owner, revision);
        if (states.isEmpty()) throw new BillingException(BillingException.Reason.NOT_FOUND);
        if (!"RUNNING".equals(states.getFirst())) throw new BillingException(BillingException.Reason.NOT_READY);
    }

    private BillingData.Snapshot snapshot(ResultSet row, int index) throws SQLException {
        String summary = row.getString("summary"); String failure = row.getString("failure");
        return new BillingData.Snapshot(row.getObject("id", UUID.class), row.getString("owner_id"), row.getString("scope_revision"),
                row.getString("idempotency_key"), new BillingData.Range(YearMonth.from(row.getDate("first_month").toLocalDate()),
                YearMonth.from(row.getDate("last_month").toLocalDate()), row.getBoolean("comparison")),
                BillingData.State.valueOf(row.getString("state")), row.getTimestamp("created_at").toInstant(), row.getTimestamp("expires_at").toInstant(),
                summary == null ? null : mapper.readValue(summary, BillingData.Summary.class), failure == null ? null : BillingException.Reason.valueOf(failure));
    }

    private <T> T tx(Supplier<T> work) { return safe(() -> transaction.execute(status -> work.get())); }
    private static <T> T safe(Supplier<T> work) {
        try { return work.get(); }
        catch (BillingException exception) { throw exception; }
        catch (RuntimeException exception) { throw new BillingException(BillingException.Reason.STORAGE_FAILURE); }
    }
    private static OffsetDateTime utc(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }

    @PreDestroy public synchronized void close() {
        if (workerLease != null) {
            Connection lease = workerLease;
            workerLease = null;
            try {
                try (var statement = lease.createStatement()) { statement.execute("SELECT pg_advisory_unlock(73580122)"); }
            } catch (SQLException exception) { /* No provider/SQL detail is safe to log. */ }
            finally {
                try { lease.close(); } catch (SQLException exception) { /* Connection may already be lost. */ }
            }
        }
    }
}
