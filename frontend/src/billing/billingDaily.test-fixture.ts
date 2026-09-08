import type { BillingDailyCosts, BillingDay, DailyFeed } from '../api/billing'

export function feedFixture(year = 2026): DailyFeed {
  return { revision: '00000000-0000-4000-8000-000000000004', calendar: dailyFixture(undefined, year), expiresAt: '2099-01-01T00:00:00Z',
    lastAttempt: '2026-09-08T00:00:00Z', lastSuccess: '2026-09-08T00:00:00Z', retrievedLast: '2026-09-08T00:00:00Z',
    observedThrough: '2026-08-31', coveredThrough: null, coverage: [], missingMonths: ['2025-09'], staleMonths: [], provisional: false, refresh: null }
}

export function dailyFixture(reportId = '00000000-0000-4000-8000-000000000001', year = 2026): BillingDailyCosts {
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  const amount = (value: number) => ({ exact: value.toFixed(2), display: `USD ${value.toFixed(2)}` })
  const days: BillingDay[] = Array.from({ length: leap ? 366 : 365 }, (_, i) => {
    const date = new Date(Date.parse(`${year}-01-01`) + i * 86400000).toISOString().slice(0, 10)
    const month = Number(date.slice(5, 7)), recorded = month >= 3 && month <= 8
    const value = i % 5 + 1
    return { date, state: recorded ? 'RECORDED' : month > 9 ? 'FUTURE' : 'MISSING', rows: recorded ? 1 : 0,
      level: recorded ? value : 0, selectedPeriod: month >= 6 && month <= 8,
      amount: recorded ? amount(value) : null, selected: recorded ? amount(month >= 6 ? value : 0) : null,
      comparison: recorded ? amount(month < 6 ? value : 0) : null, services: recorded ? [{ label: 'Storage', amount: amount(value) }] : [] }
  })
  const selected = days.reduce((sum, d) => sum + Number(d.selected?.exact ?? 0), 0)
  const comparison = days.reduce((sum, d) => sum + Number(d.comparison?.exact ?? 0), 0)
  return { reportId, year, years: [2024, 2026], first: `${year}-06-01`, last: `${year}-08-31`, comparisonFirst: `${year}-03-01`, comparisonLast: `${year}-05-31`,
    currency: 'USD', basis: 'actual-cost/billing-received-period', calculationVersion: 'daily-recorded-v2', expiresAt: '2099-01-01T00:00:00Z',
    retrievedFirst: '2026-09-08T00:00:00Z', retrievedLast: '2026-09-08T00:01:00Z', limitations: ['Recorded charges are provisional; missing records are not zero spending.'], days,
    bands: [1, 2, 3, 4, 5].map(level => ({ level, upper: String(level), label: `> USD ${level - 1} – ≤ USD ${level}` })), recordedDays: days.filter(d => d.amount).length,
    allRecordedDays: days.filter(d => d.amount).length, spilloverRows: 0, selectedTotal: amount(selected), comparisonTotal: amount(comparison), visibleTotal: amount(selected + comparison), outsideYearTotal: amount(0), excluded: [] }
}
