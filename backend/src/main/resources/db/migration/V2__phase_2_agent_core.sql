CREATE TABLE conversation (
    conversation_id UUID PRIMARY KEY,
    owner_open_id VARCHAR(128) NOT NULL,
    title VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX conversation_owner_updated_at_idx
    ON conversation (owner_open_id, updated_at DESC);

CREATE TABLE conversation_turn (
    turn_id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversation (conversation_id) ON DELETE CASCADE,
    ordinal BIGINT GENERATED ALWAYS AS IDENTITY,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (conversation_id, ordinal)
);

CREATE INDEX conversation_turn_context_idx
    ON conversation_turn (conversation_id, ordinal DESC);

CREATE TABLE agent_run (
    run_id UUID PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL UNIQUE,
    conversation_id UUID NOT NULL REFERENCES conversation (conversation_id) ON DELETE CASCADE,
    user_turn_id UUID NOT NULL REFERENCES conversation_turn (turn_id),
    assistant_turn_id UUID NOT NULL REFERENCES conversation_turn (turn_id),
    requested_by_open_id VARCHAR(128) NOT NULL,
    intent VARCHAR(32) NOT NULL,
    outcome VARCHAR(32),
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX agent_run_conversation_created_at_idx
    ON agent_run (conversation_id, created_at DESC);

CREATE TABLE agent_run_event (
    run_id UUID NOT NULL REFERENCES agent_run (run_id) ON DELETE CASCADE,
    sequence_number INTEGER NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    safe_message VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (run_id, sequence_number)
);
