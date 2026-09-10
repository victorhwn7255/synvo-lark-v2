CREATE TABLE billing_workflow_work (
    id UUID PRIMARY KEY,
    owner_open_id VARCHAR(128) NOT NULL,
    scope_revision VARCHAR(128) NOT NULL,
    request_key VARCHAR(100) NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('GENERATION','QUESTION')),
    first_month VARCHAR(7) NOT NULL,
    last_month VARCHAR(7) NOT NULL,
    comparison BOOLEAN NOT NULL,
    parent_report_id UUID,
    snapshot_id UUID,
    task_id UUID,
    conversation_id UUID,
    run_id UUID,
    status VARCHAR(24) NOT NULL CHECK (status IN ('FETCHING','PREPARING','ANALYZING','STOPPING','COMPLETE','FACTUAL','FAILED','STOPPED','INTERRUPTED')),
    question TEXT CHECK (length(question) <= 4000),
    failure VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    cleanup_finished BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE(owner_open_id, scope_revision, request_key)
);
CREATE UNIQUE INDEX billing_one_active_work ON billing_workflow_work ((1))
    WHERE status IN ('FETCHING','PREPARING','ANALYZING','STOPPING');
CREATE INDEX billing_work_expiry ON billing_workflow_work(expires_at) WHERE NOT cleanup_finished;
CREATE TABLE billing_workflow_report (
    work_id UUID PRIMARY KEY REFERENCES billing_workflow_work(id) ON DELETE CASCADE,
    document JSONB NOT NULL CHECK (octet_length(document::text) <= 10485760),
    pdf BYTEA CHECK (octet_length(pdf) <= 10485760)
);
