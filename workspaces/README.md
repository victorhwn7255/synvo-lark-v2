# Synvo Test Workspaces

These folders contain synthetic business data for exercising Codex in Lark.
They are not production Synvo records and contain no real customers, financial
results, credentials, personal data, or confidential enterprise content.

Each folder is mounted into the Codex runner as a separate workspace. A task
created in one workspace must not read or modify either of the other folders.

- `Finance/` — budgets, close procedures, and expense policy
- `Products/` — roadmap, customer feedback, and release metrics
- `Sales/` — pipeline, targets, and sales playbook

