package synvo.billingworkflow;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import synvo.billing.BillingData;

/** Atomic billing work claims and immutable publications; no provider or engine protocol. */
public interface BillingWorkflowStore {
    enum Kind { GENERATION, QUESTION }
    enum State { FETCHING, PREPARING, ANALYZING, STOPPING, COMPLETE, FACTUAL, FAILED, STOPPED, INTERRUPTED }
    record Work(UUID id, String owner, String revision, String key, Kind kind, BillingData.Range range,
            UUID parentReportId, UUID snapshotId, UUID taskId, UUID conversationId, UUID runId,
            State state, String question, String failure, Instant createdAt, Instant expiresAt) {
        public boolean active() { return state == State.FETCHING || state == State.PREPARING || state == State.ANALYZING || state == State.STOPPING; }
        @Override public String toString() { return "BillingWork[state=" + state + "]"; }
    }
    record Claim(Work work, boolean acquired) { }
    record Report(UUID id, UUID snapshotId, BillingData.Range range, Instant createdAt, Instant expiresAt,
            BillingReportFacts facts, BillingData.Summary source, BillingReportAnalysis.Draft analysis,
            String analysisFailure, String pdfFailure, UUID taskId) {
        @Override public String toString() { return "BillingReport[protected]"; }
    }
    Claim claim(String owner, String revision, String key, Kind kind, BillingData.Range range,
            UUID parentReportId, UUID snapshotId, String question, Instant now, Instant expiresAt);
    Optional<Work> find(String owner, String revision, UUID id, Instant now);
    List<Work> recent(String owner, String revision, Instant now);
    /** Newest-first page, scoped to published generations or one report's questions. */
    List<Work> history(String owner, String revision, UUID reportId, UUID before, Instant now);
    void bindSnapshot(UUID id, UUID snapshotId, Instant expiresAt);
    void bindTask(UUID id, UUID taskId, UUID conversationId);
    void bindRun(UUID id, UUID runId);
    boolean advance(UUID id, State state);
    boolean stop(UUID id);
    boolean publish(UUID id, Report report, byte[] pdf, Instant now);
    boolean answer(UUID id, BillingReportAnalysis.Statement answer, Instant now);
    void fail(UUID id, String failure);
    Optional<Report> report(String owner, String revision, UUID id, Instant now);
    Optional<BillingReportAnalysis.Statement> answer(String owner, String revision, UUID id, Instant now);
    Optional<byte[]> pdf(String owner, String revision, UUID id, Instant now);
    void recover();
    List<Work> expired(Instant now);
    void clearExpired(UUID id);
    void cleanupFinished(UUID id);
}
