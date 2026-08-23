# Sales Performance Validation

## Executive Summary

The available pipeline totals $356,000 gross and $176,200 probability-weighted against combined 2026-Q3 and 2026-Q4 targets of $2,050,000. This represents gross pipeline-to-target attainment of 17.37% and weighted pipeline-to-target attainment of 8.60%. Gross pipeline provides only 5.40% of the $6,595,000 required by the segment-specific minimum coverage rules.

These are pipeline coverage indicators, not booked-revenue attainment. `sales-pipeline.csv` contains no closed-won or realized-revenue field, and the playbook says probability is a planning estimate rather than a commitment.

## Pipeline Performance

Opportunities are assigned to quarters from `target_close_date`. Probability-weighted pipeline is calculated as `amount_usd × probability_pct ÷ 100`.

| Close quarter | Gross pipeline | Probability-weighted pipeline |
|---|---:|---:|
| 2026-Q3 | $168,000 | $79,200 |
| 2026-Q4 | $188,000 | $97,000 |
| **Total** | **$356,000** | **$176,200** |

The weighted total is 49.49% of gross pipeline. Q4 has $20,000 more gross pipeline than Q3, but both quarters remain substantially below their targets and minimum coverage requirements.

## Target Attainment

Gross attainment is `gross pipeline ÷ target × 100`; weighted attainment is `probability-weighted pipeline ÷ target × 100`. Gross coverage is `gross pipeline ÷ target`. Required pipeline is `target × minimum_pipeline_coverage_x`.

| Quarter | Segment | Gross pipeline | Weighted pipeline | Target | Gross attainment | Weighted attainment | Gross coverage | Minimum coverage |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| 2026-Q3 | Small business | $24,000 | $19,200 | $120,000 | 20.00% | 16.00% | 0.20x | 2.50x |
| 2026-Q3 | Mid-market | $48,000 | $12,000 | $280,000 | 17.14% | 4.29% | 0.17x | 3.00x |
| 2026-Q3 | Enterprise | $96,000 | $48,000 | $520,000 | 18.46% | 9.23% | 0.18x | 3.50x |
| 2026-Q4 | Small business | $0 | $0 | $150,000 | 0.00% | 0.00% | 0.00x | 2.50x |
| 2026-Q4 | Mid-market | $56,000 | $11,200 | $340,000 | 16.47% | 3.29% | 0.16x | 3.00x |
| 2026-Q4 | Enterprise | $132,000 | $85,800 | $640,000 | 20.63% | 13.41% | 0.21x | 3.50x |

Quarterly target and requirement subtotals reconcile as follows. Required-coverage attainment is `gross pipeline ÷ required pipeline × 100`.

| Quarter | Gross pipeline | Weighted pipeline | Target subtotal | Gross attainment | Weighted attainment | Required pipeline | Required-coverage attainment |
|---|---:|---:|---:|---:|---:|---:|---:|
| 2026-Q3 | $168,000 | $79,200 | $920,000 | 18.26% | 8.61% | $2,960,000 | 5.68% |
| 2026-Q4 | $188,000 | $97,000 | $1,130,000 | 16.64% | 8.58% | $3,635,000 | 5.17% |
| **Total** | **$356,000** | **$176,200** | **$2,050,000** | **17.37%** | **8.60%** | **$6,595,000** | **5.40%** |

## Risks and Recommended Actions

- Coverage risk: every quarter-segment combination is below its minimum coverage rule. Prioritize qualified pipeline creation against the largest dollar gaps, while treating probability-weighted values only as planning estimates.
- Segment gap: 2026-Q4 small business has no represented pipeline against a $150,000 target and a $375,000 minimum pipeline requirement. Review whether opportunities are missing or whether an internal coverage plan is needed.
- Forecast risk: the dataset cannot establish actual sales attainment because it has no closed-won outcome or realized revenue. Add a validated outcome measure before using this report as a revenue-performance scorecard.
- Hygiene risk: the opportunity records contain next-step text but no dated next-step field or documented risk field. In line with `sales-playbook.md`, add dates and risks through an authorized internal process.
- Governance: recommendations are internal planning actions only. Do not infer authorization for external communication, CRM writes, contract changes, pricing commitments, or access to another workspace.

## Validation Results

Validation was defined before report creation and applied deterministically to the named sources. An absolute tolerance of 0.01 in the displayed unit was used for every comparison; displayed percentages and ratios were rounded to two decimals only after calculation.

| Check | Deterministic method | Result |
|---|---|---|
| CSV parsing | Parse each complete file with the standard CSV parser in strict mode and require every data row to match the header width. | PASS — 5 pipeline rows and 6 target rows parsed. |
| Headers | Trim each header, require every header to be non-empty, and compare header count with unique-header count. | PASS — both files have non-empty, unique headers. |
| Calculation inputs | Parse every `amount_usd`, `probability_pct`, `target_usd`, and `minimum_pipeline_coverage_x` value as a finite decimal. | PASS — all calculation values are valid finite numbers. |
| Pipeline calculations | Independently sum gross amounts and row-level `amount × probability ÷ 100` results, then reconcile quarter and segment subtotals to the displayed totals. | PASS — maximum absolute difference is no more than 0.01. |
| Target calculations | Independently sum targets and `target × minimum coverage` by quarter and overall. | PASS — Q3, Q4, and overall subtotals reconcile within 0.01. |
| Attainment calculations | Recalculate each displayed gross, weighted, and required-coverage percentage from unrounded decimal inputs. | PASS — every displayed value differs from its recalculation by no more than 0.01 percentage points; displayed coverage ratios differ by no more than 0.01x. |
| Required sections | Count exact level-two required headings and reject missing, duplicate, or unexpected required-heading occurrences. | PASS — all five required headings occur exactly once. |
| Output readability | Require `sales-performance-validation.md` to exist as a regular file and be readable after the final write. | PASS — the report exists and is readable. |
| Change isolation | Compare the final root-level file inventory with the pre-write inventory and compare SHA-256 fingerprints for all four existing files. | PASS — only `sales-performance-validation.md` was added; all existing file fingerprints are unchanged. |
