# Finance Management Review — FY2026 through July

## 1. Executive summary

- **Source fact:** The July 2026 close is in review, with three of six checklist items complete. The deferred-revenue schedule, cost-center forecast variance review, and management summary remain open.
- **Derived calculation:** The five cost centers have a combined annual budget of **USD 1,860,000.00**, January–July actual of **USD 1,080,500.00**, and full-year forecast of **USD 1,869,000.00**. The portfolio forecast is **USD 9,000.00 over budget (0.48%)**, and January–July actual budget utilization is **58.09%**.
- **Source fact:** Product Development and Customer Success are identified as requiring narrative context. Their forecast overages are not approved budget changes.
- **Unapproved recommendation:** Management should review the two forecast overages and complete the three remaining close tasks. This recommendation does not authorize spending, a payment, a policy exception, or a budget change.

## 2. Portfolio totals

All amounts and rates below are derived from `FY2026-budget.csv`.

| Measure | Amount or rate |
|---|---:|
| Annual budget | USD 1,860,000.00 |
| January–July actual | USD 1,080,500.00 |
| Full-year forecast | USD 1,869,000.00 |
| Forecast variance | USD 9,000.00 unfavorable |
| Forecast variance percentage | 0.48% unfavorable |
| Actual budget utilization | 58.09% |
| Forecast less January–July actual | USD 788,500.00 |

**Calculation basis:** Forecast variance equals full-year forecast minus annual budget. Forecast variance percentage equals forecast variance divided by annual budget. Actual budget utilization equals January–July actual divided by annual budget. “Unfavorable” means forecast above budget; “favorable” means forecast below budget. These are analytical labels, not approvals.

## 3. Cost-center analysis

The source status is reproduced from the CSV; every monetary amount, variance, and percentage shown is calculated from its source row.

| Cost center | Owner team | Source status | Annual budget | Jan–Jul actual | Full-year forecast | Forecast variance | Variance % | Actual utilization |
|---|---|---|---:|---:|---:|---:|---:|---:|
| FIN-100 | Finance Operations | on_track | USD 180,000.00 | USD 101,500.00 | USD 176,000.00 | USD 4,000.00 favorable | 2.22% favorable | 56.39% |
| PROD-210 | Product Development | watch | USD 720,000.00 | USD 448,000.00 | USD 758,000.00 | USD 38,000.00 unfavorable | 5.28% unfavorable | 62.22% |
| GTM-310 | Sales and Marketing | on_track | USD 460,000.00 | USD 251,000.00 | USD 438,000.00 | USD 22,000.00 favorable | 4.78% favorable | 54.57% |
| CS-410 | Customer Success | watch | USD 260,000.00 | USD 148,000.00 | USD 266,000.00 | USD 6,000.00 unfavorable | 2.31% unfavorable | 56.92% |
| CORP-510 | General and Administrative | on_track | USD 240,000.00 | USD 132,000.00 | USD 231,000.00 | USD 9,000.00 favorable | 3.75% favorable | 55.00% |

## 4. Forecast exceptions

- **Source fact:** Product Development is forecasting above annual plan because of an accelerated quality initiative. **Derived calculation:** Its forecast is USD 38,000.00, or 5.28%, above budget.
- **Source fact:** Customer Success is forecasting slightly above plan after adding temporary onboarding support. **Derived calculation:** Its forecast is USD 6,000.00, or 2.31%, above budget.
- **Source fact:** Both items require narrative context in the management summary, and neither is an approved budget change.
- **Derived calculation:** Favorable forecasts in Finance Operations, Sales and Marketing, and General and Administrative offset USD 35,000.00 of the two unfavorable variances, leaving a net portfolio overage of USD 9,000.00.

## 5. Monthly-close status

- **Source fact:** Reporting period: July 2026; close owner: Finance Operations; target completion: 7 August 2026; current state: In review.
- **Derived calculation:** Three of six checklist items are complete (50.00%).
- **Source facts — complete:** Synthetic bank activity imported; subscription receipts reconciled; employee expense submissions reviewed.
- **Source facts — open:** Confirm deferred-revenue schedule; review cost-center forecast variances; publish management summary.

## 6. Recommended follow-up actions

The following are **unapproved recommendations** only:

1. Finance Operations should confirm the deferred-revenue schedule and document completion.
2. Cost-center owners and Finance Operations should review the Product Development and Customer Success forecast narratives and quantify any timing or execution risk.
3. Finance Operations should publish the management summary after the open reviews are resolved.
4. Reviewers should confirm that expense evidence follows the documented controls: business necessity, reasonableness, documentation, correct cost-center assignment, receipts for individual expenses of USD 25 or more, the applicable approval threshold, and submission within 15 calendar days.

These actions do not authorize payments, spending changes, policy exceptions, or changes to any budget or financial system.

## 7. Controls and limitations

- **Source facts:** Expenses must be necessary, reasonable, documented, and assigned to the correct cost center. A receipt is required for an individual expense of USD 25 or more. Required approval is the cost-center owner up to USD 250, the department lead for USD 251–1,500, and Finance Operations above USD 1,500. Expense reports should be submitted within 15 calendar days; Finance may return incomplete reports for correction.
- **Source fact:** The expense policy does not authorize payments or changes to financial systems.
- **Limitation:** This review uses only `FY2026-budget.csv`, `monthly-close.md`, and `expense-policy.md`. The workspace describes all data as synthetic. No transaction detail, supporting evidence, approvals, or subsequent-period results were available, so this review does not attest to them.
- **Limitation:** Forecasts are estimates. A forecast variance is neither an approved budget change nor evidence that spending, a payment, or a policy exception has been authorized.

## 8. Deterministic validation

- **PASS:** The CSV parsed strictly with the expected six fields per record, non-empty unique headers, and exactly five cost-center rows.
- **PASS:** All required numeric fields parsed as finite values.
- **PASS:** Totals, row calculations, and percentages were independently recomputed using decimal arithmetic and integer-cent arithmetic. The two paths agreed within USD 0.01; displayed percentages use half-up rounding to two decimal places.
- **PASS:** Each of the eight required numbered section headings occurred exactly once and in the required order.
- **PASS:** The report decoded as UTF-8, contained no disallowed control characters, and had structurally complete Markdown tables.
- **PASS:** Source-file hashes before and after report creation matched. Workspace inventory comparison confirmed that only `finance-management-review.md` was added or changed.
