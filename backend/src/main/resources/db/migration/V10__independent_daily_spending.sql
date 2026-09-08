CREATE TABLE billing_daily_run (
    id UUID PRIMARY KEY,
    owner_id TEXT NOT NULL,
    scope_revision TEXT NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('RUNNING','COMPLETE','PARTIAL','FAILED','INTERRUPTED')),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    plan JSONB NOT NULL,
    completed INTEGER NOT NULL DEFAULT 0 CHECK (completed BETWEEN 0 AND 13),
    attempted INTEGER NOT NULL DEFAULT 0 CHECK (attempted BETWEEN 0 AND 13),
    failure TEXT,
    UNIQUE(owner_id,scope_revision,idempotency_key)
);
CREATE UNIQUE INDEX billing_daily_one_running ON billing_daily_run ((true)) WHERE state='RUNNING';
CREATE TABLE billing_daily_partition (
    id UUID PRIMARY KEY,
    run_id UUID REFERENCES billing_daily_run(id) ON DELETE SET NULL,
    owner_id TEXT NOT NULL,
    scope_revision TEXT NOT NULL,
    first_date DATE NOT NULL,
    through_date DATE NOT NULL CHECK (through_date>=first_date AND through_date<first_date+INTERVAL '1 month'),
    published BOOLEAN NOT NULL DEFAULT false,
    retrieved_at TIMESTAMPTZ,
    row_count BIGINT,
    source_version TEXT,
    parts JSONB,
    CHECK (EXTRACT(DAY FROM first_date)=1)
);
CREATE UNIQUE INDEX billing_daily_one_published ON billing_daily_partition(owner_id,scope_revision,first_date) WHERE published;
CREATE TABLE billing_daily_row (
    version_id UUID NOT NULL REFERENCES billing_daily_partition(id) ON DELETE CASCADE,
    part INTEGER NOT NULL,
    ordinal BIGINT NOT NULL,
    evidence JSONB NOT NULL,
    PRIMARY KEY(version_id,part,ordinal)
);
