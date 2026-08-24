CREATE TABLE workspace_agent_task (
    task_id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL UNIQUE
        REFERENCES conversation (conversation_id) ON DELETE CASCADE,
    owner_open_id VARCHAR(128) NOT NULL,
    workspace_id VARCHAR(100) NOT NULL,
    run_mode VARCHAR(32) NOT NULL
        CHECK (run_mode IN ('READ_ONLY', 'WORKSPACE_WRITE')),
    title VARCHAR(160) NOT NULL,
    task_reference VARCHAR(256) NOT NULL UNIQUE,
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX workspace_agent_task_owner_state_idx
    ON workspace_agent_task (owner_open_id, archived, pinned DESC, updated_at DESC);

CREATE TABLE workspace_agent_operation (
    operation_id UUID PRIMARY KEY,
    task_id UUID NOT NULL
        REFERENCES workspace_agent_task (task_id) ON DELETE CASCADE,
    conversation_run_id UUID UNIQUE
        REFERENCES agent_run (run_id) ON DELETE CASCADE,
    request_key VARCHAR(128) NOT NULL UNIQUE,
    operation_type VARCHAR(16) NOT NULL
        CHECK (operation_type IN ('TURN', 'REVIEW')),
    status VARCHAR(32) NOT NULL
        CHECK (status IN (
            'RUNNING', 'WAITING_FOR_INTERACTION',
            'COMPLETED', 'FAILED', 'STOPPED'
        )),
    operation_reference VARCHAR(256),
    terminal_status VARCHAR(64),
    safe_terminal_message VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX workspace_agent_one_active_operation_idx
    ON workspace_agent_operation ((TRUE))
    WHERE status IN ('RUNNING', 'WAITING_FOR_INTERACTION');

CREATE INDEX workspace_agent_operation_task_idx
    ON workspace_agent_operation (task_id, created_at DESC);

CREATE TABLE workspace_agent_activity (
    operation_id UUID NOT NULL
        REFERENCES workspace_agent_operation (operation_id) ON DELETE CASCADE,
    sequence_number BIGINT NOT NULL,
    activity_kind VARCHAR(64) NOT NULL,
    safe_summary VARCHAR(512),
    terminal BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (operation_id, sequence_number)
);

CREATE TABLE workspace_agent_interaction (
    interaction_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL
        REFERENCES workspace_agent_operation (operation_id) ON DELETE CASCADE,
    task_id UUID NOT NULL
        REFERENCES workspace_agent_task (task_id) ON DELETE CASCADE,
    owner_open_id VARCHAR(128) NOT NULL,
    workspace_id VARCHAR(100) NOT NULL,
    source_reference VARCHAR(128) NOT NULL,
    interaction_kind VARCHAR(64) NOT NULL,
    safe_action_category VARCHAR(128) NOT NULL,
    safe_reason VARCHAR(512) NOT NULL,
    permission_scope VARCHAR(64) NOT NULL,
    available_decisions VARCHAR(256) NOT NULL,
    status VARCHAR(16) NOT NULL
        CHECK (status IN ('PENDING', 'DECIDED', 'EXPIRED', 'CANCELLED')),
    decision VARCHAR(32),
    decision_scope VARCHAR(64),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ,
    terminal_reason VARCHAR(64),
    UNIQUE (operation_id, source_reference)
);

CREATE INDEX workspace_agent_interaction_owner_pending_idx
    ON workspace_agent_interaction (owner_open_id, task_id, created_at)
    WHERE status = 'PENDING';

ALTER TABLE agent_run_event
    ADD COLUMN action_task_id UUID
        REFERENCES workspace_agent_task (task_id) ON DELETE CASCADE,
    ADD COLUMN action_interaction_id UUID
        REFERENCES workspace_agent_interaction (interaction_id) ON DELETE CASCADE,
    ADD COLUMN safe_action_category VARCHAR(128),
    ADD COLUMN safe_workspace_name VARCHAR(160),
    ADD COLUMN safe_action_reason VARCHAR(512),
    ADD COLUMN action_permission_scope VARCHAR(64);

ALTER TABLE agent_run_event
    ADD CONSTRAINT agent_run_event_action_handoff_check CHECK (
        (
            event_type = 'ACTION_REQUIRED'
            AND action_task_id IS NOT NULL
            AND action_interaction_id IS NOT NULL
            AND safe_action_category IS NOT NULL
            AND safe_workspace_name IS NOT NULL
            AND safe_action_reason IS NOT NULL
            AND action_permission_scope IS NOT NULL
        )
        OR
        (
            event_type <> 'ACTION_REQUIRED'
            AND action_task_id IS NULL
            AND action_interaction_id IS NULL
            AND safe_action_category IS NULL
            AND safe_workspace_name IS NULL
            AND safe_action_reason IS NULL
            AND action_permission_scope IS NULL
        )
    );
