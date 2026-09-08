package synvo.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import synvo.billing.BillingData;
import synvo.billing.BillingException;
import synvo.billingworkflow.BillingReportAnalysis;
import synvo.billingworkflow.BillingWorkflowStore;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcBillingWorkflowStore implements BillingWorkflowStore {
    private static final String ACTIVE = "status IN ('FETCHING','PREPARING','ANALYZING')";
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    public JdbcBillingWorkflowStore(JdbcClient jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    @Override public Claim claim(String owner, String revision, String key, Kind kind, BillingData.Range range,
            UUID parentReportId, UUID snapshotId, String question, Instant now, Instant expiresAt) {
        var existing = byKey(owner, revision, key);
        if (existing.isPresent()) return replay(existing.get(), kind, range, parentReportId, snapshotId, question, now);
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO billing_workflow_work(id,owner_open_id,scope_revision,request_key,kind,
                    first_month,last_month,comparison,parent_report_id,snapshot_id,question,status,created_at,expires_at)
                    VALUES (:id,:owner,:revision,:key,:kind,:first,:last,:comparison,:parent,:snapshot,:question,:status,:now,:expires)
                    """).param("id", id).param("owner", owner).param("revision", revision).param("key", key)
                    .param("kind", kind.name()).param("first", range.first().toString()).param("last", range.last().toString())
                    .param("comparison", range.comparison()).param("parent", parentReportId).param("snapshot", snapshotId)
                    .param("question", question).param("status", snapshotId == null ? "FETCHING" : "PREPARING")
                    .param("now", utc(now)).param("expires", utc(expiresAt)).update();
        } catch (DuplicateKeyException busy) {
            return byKey(owner, revision, key).map(work -> replay(work, kind, range, parentReportId, snapshotId, question, now))
                    .orElseThrow(() -> new BillingException(BillingException.Reason.BUSY));
        }
        return new Claim(find(owner, revision, id, now).orElseThrow(), true);
    }
    private Claim replay(Work work, Kind kind, BillingData.Range range, UUID parent, UUID snapshot, String question, Instant now) {
        if (work.kind() != kind || !work.range().equals(range) || !Objects.equals(work.parentReportId(), parent)
                || (snapshot != null && !snapshot.equals(work.snapshotId())) || !Objects.equals(work.question(), question)
                || !now.isBefore(work.expiresAt())) throw new BillingException(BillingException.Reason.INVALID_REQUEST);
        return new Claim(work, false);
    }
    private Optional<Work> byKey(String owner, String revision, String key) {
        return jdbc.sql("SELECT * FROM billing_workflow_work WHERE owner_open_id=:owner AND scope_revision=:revision AND request_key=:key")
                .param("owner", owner).param("revision", revision).param("key", key).query(JdbcBillingWorkflowStore::work).optional();
    }
    @Override public Optional<Work> find(String owner, String revision, UUID id, Instant now) {
        return jdbc.sql("SELECT * FROM billing_workflow_work WHERE id=:id AND owner_open_id=:owner AND scope_revision=:revision AND expires_at>:now")
                .param("id", id).param("owner", owner).param("revision", revision).param("now", utc(now))
                .query(JdbcBillingWorkflowStore::work).optional();
    }
    @Override public List<Work> recent(String owner, String revision, Instant now) {
        // Keep the current report reachable even after more than 100 questions or failed attempts.
        return jdbc.sql("""
                WITH visible AS (
                    SELECT * FROM billing_workflow_work
                    WHERE owner_open_id=:owner AND scope_revision=:revision AND expires_at>:now
                ), selected AS (
                    (SELECT id FROM visible ORDER BY created_at DESC,id DESC LIMIT 100)
                    UNION
                    (SELECT id FROM visible WHERE kind='GENERATION' AND status IN ('COMPLETE','FACTUAL')
                     ORDER BY created_at DESC,id DESC LIMIT 1)
                )
                SELECT visible.* FROM visible JOIN selected USING (id) ORDER BY created_at DESC,id DESC
                """)
                .param("owner", owner).param("revision", revision).param("now", utc(now)).query(JdbcBillingWorkflowStore::work).list();
    }
    @Override public List<Work> history(String owner, String revision, UUID reportId, UUID before, Instant now) {
        var cursor = before == null ? null : find(owner, revision, before, now)
                .filter(work -> reportId == null ? work.kind() == Kind.GENERATION && (work.state() == State.COMPLETE || work.state() == State.FACTUAL)
                        : work.kind() == Kind.QUESTION && reportId.equals(work.parentReportId()))
                .orElseThrow(() -> new BillingException(BillingException.Reason.INVALID_REQUEST));
        String membership = reportId == null
                ? "kind='GENERATION' AND status IN ('COMPLETE','FACTUAL') AND EXISTS (SELECT 1 FROM billing_workflow_report r WHERE r.work_id=w.id)"
                : "kind='QUESTION' AND parent_report_id=:parent";
        var query = jdbc.sql("SELECT w.* FROM billing_workflow_work w WHERE owner_open_id=:owner AND scope_revision=:revision AND expires_at>:now AND "
                + membership + (cursor == null ? "" : " AND (created_at,id)<(:beforeTime,:beforeId)") + " ORDER BY created_at DESC,id DESC LIMIT 50")
                .param("owner", owner).param("revision", revision).param("now", utc(now));
        if (reportId != null) query.param("parent", reportId);
        if (cursor != null) query.param("beforeTime", utc(cursor.createdAt())).param("beforeId", cursor.id());
        return query.query(JdbcBillingWorkflowStore::work).list();
    }
    @Override public void bindSnapshot(UUID id, UUID snapshot, Instant expires) {
        jdbc.sql("UPDATE billing_workflow_work SET snapshot_id=:snapshot,expires_at=LEAST(expires_at,:expires) WHERE id=:id AND (snapshot_id IS NULL OR snapshot_id=:snapshot)")
                .param("id", id).param("snapshot", snapshot).param("expires", utc(expires)).update();
    }
    @Override public void bindTask(UUID id, UUID task, UUID conversation) {
        jdbc.sql("UPDATE billing_workflow_work SET task_id=:task,conversation_id=:conversation WHERE id=:id AND task_id IS NULL")
                .param("id", id).param("task", task).param("conversation", conversation).update();
    }
    @Override public void bindRun(UUID id, UUID run) {
        jdbc.sql("UPDATE billing_workflow_work SET run_id=:run WHERE id=:id AND run_id IS NULL")
                .param("id", id).param("run", run).update();
    }
    @Override public boolean advance(UUID id, State state) {
        if (state != State.PREPARING && state != State.ANALYZING) throw new IllegalArgumentException("Invalid work advance");
        return jdbc.sql("UPDATE billing_workflow_work SET status=:state WHERE id=:id AND " + ACTIVE)
                .param("id", id).param("state", state.name()).update() == 1;
    }
    @Override public boolean stop(UUID id) {
        return jdbc.sql("UPDATE billing_workflow_work SET status='STOPPING' WHERE id=:id AND " + ACTIVE).param("id", id).update() == 1;
    }
    @Override @Transactional public boolean publish(UUID id, Report report, byte[] pdf, Instant now) {
        if (!id.equals(report.id())) throw new IllegalArgumentException("Report identity mismatch");
        return publish(id, report, pdf, report.analysis() == null ? "FACTUAL" : "COMPLETE", now);
    }
    @Override @Transactional public boolean answer(UUID id, BillingReportAnalysis.Statement answer, Instant now) {
        return publish(id, answer, null, "COMPLETE", now);
    }
    private boolean publish(UUID id, Object document, byte[] pdf, String state, Instant now) {
        String json = mapper.writeValueAsString(document);
        if (json.length() > 10 * 1024 * 1024 || (pdf != null && pdf.length > 10 * 1024 * 1024)) throw new BillingException(BillingException.Reason.LIMIT_EXCEEDED);
        int updated = jdbc.sql("UPDATE billing_workflow_work SET status=:state WHERE id=:id AND expires_at>:now AND " + ACTIVE)
                .param("id", id).param("state", state).param("now", utc(now)).update();
        if (updated == 0) return false;
        jdbc.sql("INSERT INTO billing_workflow_report(work_id,document,pdf) VALUES (:id,CAST(:document AS jsonb),:pdf)")
                .param("id", id).param("document", json).param("pdf", pdf, java.sql.Types.BINARY).update();
        return true;
    }
    @Override public void fail(UUID id, String failure) {
        jdbc.sql("UPDATE billing_workflow_work SET status=CASE WHEN status='STOPPING' THEN 'STOPPED' ELSE 'FAILED' END,failure=:failure WHERE id=:id AND status IN ('FETCHING','PREPARING','ANALYZING','STOPPING')")
                .param("id", id).param("failure", failure).update();
    }
    @Override public Optional<Report> report(String owner, String revision, UUID id, Instant now) {
        return document(owner, revision, id, now, Kind.GENERATION).map(json -> mapper.readValue(json, Report.class));
    }
    @Override public Optional<BillingReportAnalysis.Statement> answer(String owner, String revision, UUID id, Instant now) {
        return document(owner, revision, id, now, Kind.QUESTION).map(json -> mapper.readValue(json, BillingReportAnalysis.Statement.class));
    }
    private Optional<String> document(String owner, String revision, UUID id, Instant now, Kind kind) {
        return find(owner, revision, id, now).filter(work -> work.kind() == kind).flatMap(work ->
                jdbc.sql("SELECT document::text FROM billing_workflow_report WHERE work_id=:id").param("id", id).query(String.class).optional());
    }
    @Override public Optional<byte[]> pdf(String owner, String revision, UUID id, Instant now) {
        return find(owner, revision, id, now).filter(work -> work.kind() == Kind.GENERATION).flatMap(work ->
                jdbc.sql("SELECT pdf FROM billing_workflow_report WHERE work_id=:id AND pdf IS NOT NULL").param("id", id).query(byte[].class).optional());
    }
    @Override public void recover() {
        jdbc.sql("UPDATE billing_workflow_work SET status='INTERRUPTED',failure='INTERRUPTED' WHERE status IN ('FETCHING','PREPARING','ANALYZING','STOPPING')").update();
    }
    @Override public List<Work> expired(Instant now) {
        return jdbc.sql("SELECT * FROM billing_workflow_work WHERE expires_at<=:now AND NOT cleanup_finished ORDER BY expires_at LIMIT 100")
                .param("now", utc(now)).query(JdbcBillingWorkflowStore::work).list();
    }
    @Override @Transactional public void clearExpired(UUID id) {
        jdbc.sql("DELETE FROM billing_workflow_report WHERE work_id=:id").param("id", id).update();
        jdbc.sql("UPDATE billing_workflow_work SET question=NULL WHERE id=:id").param("id", id).update();
    }
    @Override public void cleanupFinished(UUID id) {
        jdbc.sql("DELETE FROM billing_workflow_work WHERE id=:id").param("id", id).update();
    }
    private static OffsetDateTime utc(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
    private static Work work(ResultSet row, int ignored) throws SQLException {
        return new Work(row.getObject("id", UUID.class), row.getString("owner_open_id"), row.getString("scope_revision"),
                row.getString("request_key"), Kind.valueOf(row.getString("kind")),
                new BillingData.Range(YearMonth.parse(row.getString("first_month")), YearMonth.parse(row.getString("last_month")), row.getBoolean("comparison")),
                row.getObject("parent_report_id", UUID.class), row.getObject("snapshot_id", UUID.class), row.getObject("task_id", UUID.class),
                row.getObject("conversation_id", UUID.class), row.getObject("run_id", UUID.class), State.valueOf(row.getString("status")),
                row.getString("question"), row.getString("failure"), row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("expires_at", OffsetDateTime.class).toInstant());
    }
}
