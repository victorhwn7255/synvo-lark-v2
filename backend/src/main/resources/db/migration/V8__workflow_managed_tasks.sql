-- Workflow tasks are accessible only through their owning application workflow.
ALTER TABLE workspace_agent_task
    ADD COLUMN workflow_managed BOOLEAN NOT NULL DEFAULT FALSE;
