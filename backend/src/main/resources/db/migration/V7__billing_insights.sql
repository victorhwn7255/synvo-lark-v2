CREATE TABLE billing_snapshot (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(128) NOT NULL,
    scope_revision VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    first_month DATE NOT NULL,
    last_month DATE NOT NULL,
    comparison BOOLEAN NOT NULL,
    state VARCHAR(32) NOT NULL CHECK (state IN ('RUNNING','READY','READY_WITH_LIMITATIONS','FAILED','INTERRUPTED')),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    summary JSONB,
    failure VARCHAR(64),
    UNIQUE (owner_id, scope_revision, idempotency_key),
    CHECK (first_month <= last_month),
    CHECK ((state IN ('READY','READY_WITH_LIMITATIONS')) = (summary IS NOT NULL))
);
CREATE UNIQUE INDEX billing_one_active_import ON billing_snapshot ((1)) WHERE state = 'RUNNING';
CREATE INDEX billing_snapshot_retention ON billing_snapshot (expires_at);

CREATE TABLE billing_cost_evidence (
    snapshot_id UUID NOT NULL REFERENCES billing_snapshot(id) ON DELETE CASCADE,
    dataset VARCHAR(160) NOT NULL,
    part INTEGER NOT NULL CHECK (part >= 0),
    ordinal BIGINT NOT NULL CHECK (ordinal > 0),
    cost NUMERIC NOT NULL CHECK (cost::text NOT IN ('NaN','Infinity','-Infinity') AND abs(cost) < 1e38 AND scale(cost) <= 18
        AND length(ltrim(replace(abs(cost)::text, '.', ''), '0')) <= 38),
    evidence JSONB NOT NULL,
    PRIMARY KEY (snapshot_id, dataset, part, ordinal)
);

CREATE TABLE billing_invoice_evidence (
    snapshot_id UUID NOT NULL REFERENCES billing_snapshot(id) ON DELETE CASCADE,
    invoice_id VARCHAR(128) NOT NULL,
    evidence JSONB NOT NULL,
    PRIMARY KEY (snapshot_id, invoice_id)
);
