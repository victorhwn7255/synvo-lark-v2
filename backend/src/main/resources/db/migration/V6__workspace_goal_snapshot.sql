ALTER TABLE workspace_agent_task
    ADD COLUMN goal_objective VARCHAR(10000),
    ADD COLUMN goal_status VARCHAR(32),
    ADD COLUMN goal_tokens_used BIGINT,
    ADD COLUMN goal_time_used_seconds BIGINT,
    ADD COLUMN goal_updated_at TIMESTAMPTZ,
    ADD CONSTRAINT workspace_agent_goal_snapshot_check CHECK (
        (
            goal_objective IS NULL
            AND goal_status IS NULL
            AND goal_tokens_used IS NULL
            AND goal_time_used_seconds IS NULL
            AND goal_updated_at IS NULL
        )
        OR
        (
            goal_objective IS NOT NULL
            AND goal_status IN (
                'active', 'paused', 'blocked',
                'usageLimited', 'budgetLimited', 'complete'
            )
            AND goal_tokens_used >= 0
            AND goal_time_used_seconds >= 0
            AND goal_updated_at IS NOT NULL
        )
    );
