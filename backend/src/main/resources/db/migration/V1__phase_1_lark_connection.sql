CREATE TABLE lark_message_processing (
    message_id VARCHAR(128) PRIMARY KEY,
    sender_open_id VARCHAR(128) NOT NULL,
    chat_type VARCHAR(32) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    processing_outcome VARCHAR(32) NOT NULL,
    reply_message_id VARCHAR(128),
    error_code VARCHAR(64),
    attempt_count INTEGER NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX lark_message_processing_received_at_idx
    ON lark_message_processing (received_at);

CREATE TABLE lark_user_connection (
    open_id VARCHAR(128) PRIMARY KEY,
    tenant_key VARCHAR(128) NOT NULL,
    display_name VARCHAR(256) NOT NULL,
    access_token_ciphertext TEXT NOT NULL,
    refresh_token_ciphertext TEXT NOT NULL,
    access_expires_at TIMESTAMPTZ NOT NULL,
    refresh_expires_at TIMESTAMPTZ NOT NULL,
    connection_status VARCHAR(32) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE lark_authorization_code_claim (
    code_hash CHAR(64) PRIMARY KEY,
    claimed_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX lark_authorization_code_claim_claimed_at_idx
    ON lark_authorization_code_claim (claimed_at);
