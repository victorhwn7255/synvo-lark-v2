# Sales Goal Ownership Check

## Executive Summary

All six quarter-segment goals have a non-empty owner role, and every pipeline opportunity maps to exactly one goal by its `target_close_date` quarter and `segment`. Sales lead owns $890,000 of target and $2,535,000 of required pipeline; Sales director owns $1,160,000 of target and $4,060,000 of required pipeline.

The available pipeline totals $356,000 gross and $176,200 probability-weighted against $2,050,000 of target and $6,595,000 of required pipeline. This leaves a $6,239,000 gross pipeline gap to minimum coverage. Neither owner role meets its assigned minimum pipeline requirement.

Ownership in this report is role-based, not person-based. `quarterly-targets.csv` supplies `owner_role`; `sales-pipeline.csv` contains no owner field, so opportunity accountability is derived only through the matching quarter-segment goal.

## Ownership Summary

Gross attainment is `gross pipeline ÷ target × 100`. Weighted attainment is `probability-weighted pipeline ÷ target × 100`. Required-coverage attainment is `gross pipeline ÷ required pipeline × 100`, where required pipeline is `target × minimum_pipeline_coverage_x`.

| Owner role | Gross pipeline | Weighted pipeline | Target | Required pipeline | Gross attainment | Weighted attainment | Required-coverage attainment | Gap to required pipeline |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Sales lead | $128,000 | $42,400 | $890,000 | $2,535,000 | 14.38% | 4.76% | 5.05% | $2,407,000 |
| Sales director | $228,000 | $133,800 | $1,160,000 | $4,060,000 | 19.66% | 11.53% | 5.62% | $3,832,000 |
| **Total** | **$356,000** | **$176,200** | **$2,050,000** | **$6,595,000** | **17.37%** | **8.60%** | **5.40%** | **$6,239,000** |

## Goal Detail

Opportunities are assigned to a close quarter from `target_close_date`. Weighted pipeline is calculated at the opportunity level as `amount_usd × probability_pct ÷ 100` before aggregation.

| Quarter | Segment | Owner role | Gross pipeline | Weighted pipeline | Target | Minimum coverage | Required pipeline | Gross attainment | Weighted attainment | Required-coverage attainment | Gap to required pipeline |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 2026-Q3 | Small business | Sales lead | $24,000 | $19,200 | $120,000 | 2.50x | $300,000 | 20.00% | 16.00% | 8.00% | $276,000 |
| 2026-Q3 | Mid-market | Sales lead | $48,000 | $12,000 | $280,000 | 3.00x | $840,000 | 17.14% | 4.29% | 5.71% | $792,000 |
| 2026-Q3 | Enterprise | Sales director | $96,000 | $48,000 | $520,000 | 3.50x | $1,820,000 | 18.46% | 9.23% | 5.27% | $1,724,000 |
| 2026-Q4 | Small business | Sales lead | $0 | $0 | $150,000 | 2.50x | $375,000 | 0.00% | 0.00% | 0.00% | $375,000 |
| 2026-Q4 | Mid-market | Sales lead | $56,000 | $11,200 | $340,000 | 3.00x | $1,020,000 | 16.47% | 3.29% | 5.49% | $964,000 |
| 2026-Q4 | Enterprise | Sales director | $132,000 | $85,800 | $640,000 | 3.50x | $2,240,000 | 20.63% | 13.41% | 5.89% | $2,108,000 |

## Ownership Findings

- Completeness: all six target rows have a populated owner role.
- Uniqueness: each quarter-segment goal occurs once and therefore resolves to one owner role.
- Pipeline mapping: all five opportunities map to exactly one of the six goals; none are unmatched or ambiguously matched.
- Coverage accountability: Sales lead has a $2,407,000 gap and Sales director has a $3,832,000 gap to their assigned minimum pipeline requirements.
- Attribution limit: the sources establish role ownership only. They do not support assigning a named person or asserting that an opportunity record itself contains an owner.

## Validation Results

Validation uses an absolute tolerance of 0.01 in each displayed unit. Percentages and coverage ratios are calculated from unrounded decimal inputs and rounded to two decimals only for display.

| Check | Deterministic method | Result |
|---|---|---|
| CSV integrity | Parse each complete CSV in strict mode; require consistent row widths and non-empty, unique headers. | PASS — 5 pipeline rows and 6 target rows parsed. |
| Numeric inputs | Parse every amount, probability, target, and coverage value as a finite decimal. | PASS — all calculation inputs are valid. |
| Goal ownership | Require every target row to have a non-empty owner role and every quarter-segment key to occur exactly once. | PASS — all 6 goals have exactly one role owner. |
| Pipeline mapping | Derive each opportunity quarter from its close date and require exactly one matching quarter-segment target. | PASS — all 5 opportunities map exactly once. |
| Calculations | Independently recompute row-level weighted pipeline, all aggregations, attainments, requirements, and gaps. | PASS — every displayed figure reconciles within 0.01. |
| Reconciliation | Sum goal detail by owner and reconcile owner totals to the overall totals. | PASS — owner and overall totals reconcile within 0.01. |
| Required sections | Count the five exact level-two headings and reject missing, duplicate, or unexpected headings. | PASS — all required sections occur exactly once. |
| Readability | Require the completed Markdown report to exist as a regular readable file. | PASS — the report exists and is readable. |
| Source preservation | Compare final SHA-256 fingerprints of both CSV sources with their pre-write fingerprints. | PASS — both source fingerprints are unchanged. |
| Change isolation | Compare the final root-level inventory with the pre-write inventory and require the write patch to target only this report. | PASS — only `sales-goal-ownership-check.md` was added; no other file changed. |
