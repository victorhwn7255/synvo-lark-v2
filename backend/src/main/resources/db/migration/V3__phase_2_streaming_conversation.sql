ALTER TABLE agent_run_event
    ADD COLUMN content_delta TEXT;

ALTER TABLE agent_run_event
    ADD CONSTRAINT agent_run_event_payload_check CHECK (
        (event_type = 'CONTENT_DELTA' AND content_delta IS NOT NULL AND safe_message IS NULL)
        OR
        (event_type <> 'CONTENT_DELTA' AND content_delta IS NULL)
    );

CREATE TABLE lark_chat_conversation (
    chat_id VARCHAR(128) PRIMARY KEY,
    owner_open_id VARCHAR(128) NOT NULL,
    conversation_id UUID NOT NULL UNIQUE
        REFERENCES conversation (conversation_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX lark_chat_conversation_owner_idx
    ON lark_chat_conversation (owner_open_id, updated_at DESC);
