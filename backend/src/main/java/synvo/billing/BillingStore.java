package synvo.billing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Durable owner/revision-scoped claims and atomic publication; staging is never exposed by reads. */
public interface BillingStore {
    record Claim(BillingData.Snapshot snapshot, boolean acquired) { }
    Claim claim(String owner, String revision, String key, BillingData.Range range, Instant now);
    Optional<BillingData.Snapshot> find(String owner, String revision, UUID id, Instant now);
    void stage(String owner, String revision, UUID id, List<BillingData.CostRow> rows);
    void discardDataset(String owner, String revision, UUID id, String dataset);
    void visitStaging(String owner, String revision, UUID id, String dataset, Consumer<BillingData.CostRow> sink);
    void publish(String owner, String revision, UUID id, BillingData.Summary summary, List<BillingData.Invoice> invoices, Instant now);
    void fail(String owner, String revision, UUID id, BillingException.Reason reason, Instant now);
    List<BillingData.CostRow> evidence(String owner, String revision, UUID id, int offset, int limit, Instant now);
    void visitEvidence(String owner, String revision, UUID id, Instant now, Consumer<BillingData.CostRow> sink);
    void recoverAndCleanup(Instant now);
    void cleanup(Instant now);
}
