import { isInteraction, parseActivity, type CodexInteractionDecision } from './codex'

const states = ['FETCHING', 'PREPARING', 'ANALYZING', 'STOPPING', 'COMPLETE', 'FACTUAL', 'FAILED', 'STOPPED', 'INTERRUPTED'] as const
export type BillingState = typeof states[number]
export interface BillingWork {
  id: string; kind: 'GENERATION' | 'QUESTION'; state: BillingState; first: string; last: string
  parentReportId: string | null; question: string | null; failure: string | null
}
export interface Statement { text: string; references: string[] }
export interface BillingDisplayRow { label: string; amount: string }
export interface BillingPresentation {
  period: string; comparisonPeriod: string; total: string; baseline: string; change: string; percent: string
  highlights: string[]; coverage: string[]; reconciliation: string[]
  services: BillingDisplayRow[]; serviceChanges: BillingDisplayRow[]; months: BillingDisplayRow[]
  baselineMonths: BillingDisplayRow[]; subscriptions: BillingDisplayRow[]; buckets: BillingDisplayRow[]
}
export interface BillingReport {
  presentation: BillingPresentation
  id: string; snapshotId: string; first: string; last: string; baselineMonths: string[]
  createdAt: string; expiresAt: string; analysisFailure: string | null; pdfFailure: string | null
  facts: { selectedTotal: string | null; baselineTotal: string | null; delta: string | null; percent: string | null
    services: Record<string, string>; months: Record<string, string>; subscriptions: Record<string, string>; serviceChanges: Record<string, string>
    buckets: Record<string, { rows: number; cost: string }> }
  analysis: { executiveSummary: Statement; costDrivers: Statement[]; periodChanges: Statement[]
    optimizationPriorities: { evidence: Statement; hypothesis: string; missingInputs: string; risk: string; nextStep: string }[] } | null
  source: { currency: string; basis: string; mappingVersion: string; calculationVersion: string; limitations: string[]
    reconciliation: { invoiceId: string; status: string; sourceCharges: string | null; residual: string | null }[]
    partitions: { dataset: string; retrievedAt: string; sourceVersion: string; attributionComplete: boolean; datesWithinPartition: boolean }[] }
}
export interface BillingHome { enabled: boolean; works: BillingWork[]; reportId: string | null }
export interface DailyAmount { exact: string; display: string }
export interface DailyService { label: string; amount: DailyAmount }
export interface BillingDay {
  date: string; state: 'RECORDED' | 'ZERO' | 'NEGATIVE' | 'MISSING' | 'FUTURE' | 'FUTURE_RECORDED'
  level: number; selectedPeriod: boolean; rows: number; amount: DailyAmount | null
  selected: DailyAmount | null; comparison: DailyAmount | null; services: DailyService[]
}
export interface BillingDailyCosts {
  reportId: string; year: number; years: number[]; first: string; last: string; comparisonFirst: string | null; comparisonLast: string | null
  currency: string; basis: string; calculationVersion: string; expiresAt: string; retrievedFirst: string; retrievedLast: string
  limitations: string[]; days: BillingDay[]; bands: { level: number; upper: string; label: string }[]
  recordedDays: number; allRecordedDays: number; spilloverRows: number; selectedTotal: DailyAmount; comparisonTotal: DailyAmount
  visibleTotal: DailyAmount; outsideYearTotal: DailyAmount; excluded: DailyService[]
}
export interface BillingPage<T> { items: T[]; nextCursor: string | null }
export type DailyCalendar = Pick<BillingDailyCosts, 'year' | 'years' | 'days' | 'bands' | 'recordedDays' | 'allRecordedDays' | 'spilloverRows' | 'selectedTotal' | 'comparisonTotal' | 'visibleTotal' | 'outsideYearTotal' | 'excluded'>
export interface DailyRefresh { id: string; state: 'RUNNING' | 'COMPLETE' | 'PARTIAL' | 'FAILED' | 'INTERRUPTED'; completed: number; total: number; failure: string | null }
export interface DailyFeed {
  revision: string; calendar: DailyCalendar; expiresAt: string; lastAttempt: string | null; lastSuccess: string | null; retrievedLast: string | null
  observedThrough: string | null; coveredThrough: string | null; coverage: { month: string; through: string; retrievedAt: string }[]
  missingMonths: string[]; staleMonths: string[]; provisional: boolean; refresh: DailyRefresh | null
}
export interface SavedAnalysis { id: string; first: string; last: string; createdAt: string; state: 'COMPLETE' | 'FACTUAL' }
export interface BillingEvidence { reference: string; date: string; currency: string; cost: string; displayCost: string; service: string | null; subscription: string | null; bucket: string }
export const billingActive = (work: BillingWork) => ['FETCHING', 'PREPARING', 'ANALYZING', 'STOPPING'].includes(work.state)
const root = '/api/billing-insights'
const record = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
const string = (v: unknown): v is string => typeof v === 'string'
const nullable = (v: unknown): v is string | null => v === null || string(v)
const strings = (v: unknown): v is string[] => Array.isArray(v) && v.every(string)
const decimal = (v: unknown): v is string => string(v) && /^-?\d+(\.\d+)?$/.test(v)
const amount = (v: unknown) => v === null || decimal(v)
const amounts = (v: unknown) => record(v) && Object.values(v).every(decimal)
const id = (v: unknown) => string(v) && /^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i.test(v)
const month = (v: unknown) => string(v) && /^\d{4}-(0[1-9]|1[0-2])$/.test(v)
const instant = (v: unknown) => string(v) && Number.isFinite(Date.parse(v))
const dateOnly = (v: unknown): v is string => string(v) && /^\d{4}-\d{2}-\d{2}$/.test(v) && Number.isFinite(Date.parse(v)) && new Date(v).toISOString().slice(0, 10) === v
const dailyAmount = (v: unknown): v is DailyAmount => record(v) && decimal(v.exact) && string(v.display)
const dailyServices = (v: unknown): v is DailyService[] => Array.isArray(v) && v.length <= 6 && v.every(s => record(s) && string(s.label) && dailyAmount(s.amount))
function dailyCosts(v: unknown): v is BillingDailyCosts {
  if (!record(v) || !id(v.reportId) || !Number.isInteger(v.year) || !Array.isArray(v.years) || v.years.length > 4 || !v.years.includes(v.year)
    || !v.years.every(y => Number.isInteger(y) && y >= 1 && y <= 9999) || !dateOnly(v.first) || !dateOnly(v.last)
    || !(v.comparisonFirst === null || dateOnly(v.comparisonFirst)) || !(v.comparisonLast === null || dateOnly(v.comparisonLast))
    || !['currency', 'basis', 'calculationVersion'].every(k => string(v[k])) || !['expiresAt', 'retrievedFirst', 'retrievedLast'].every(k => instant(v[k]))
    || !strings(v.limitations) || !['recordedDays', 'allRecordedDays', 'spilloverRows'].every(k => Number.isSafeInteger(v[k]) && (v[k] as number) >= 0)
    || !['selectedTotal', 'comparisonTotal', 'visibleTotal', 'outsideYearTotal'].every(k => dailyAmount(v[k])) || !dailyServices(v.excluded)
    || !Array.isArray(v.bands) || v.bands.length > 5 || !v.bands.every((b, i) => record(b) && b.level === i + 1 && decimal(b.upper) && string(b.label))
    || !Array.isArray(v.days)) return false
  return calendar(v)
}
function calendar(v: unknown): v is DailyCalendar {
  if (!record(v) || !Number.isInteger(v.year) || !Array.isArray(v.years) || !v.years.includes(v.year) || v.years.length > 4
    || !v.years.every(y => Number.isInteger(y) && y >= 1 && y <= 9999)
    || !['recordedDays', 'allRecordedDays', 'spilloverRows'].every(k => Number.isSafeInteger(v[k]) && (v[k] as number) >= 0)
    || !['selectedTotal', 'comparisonTotal', 'visibleTotal', 'outsideYearTotal'].every(k => dailyAmount(v[k])) || !dailyServices(v.excluded)
    || !Array.isArray(v.bands) || v.bands.length > 5 || !v.bands.every((b, i) => record(b) && b.level === i + 1 && decimal(b.upper) && string(b.label))
    || !Array.isArray(v.days)) return false
  const first = `${String(v.year).padStart(4, '0')}-01-01`
  const leap = (v.year as number) % 4 === 0 && ((v.year as number) % 100 !== 0 || (v.year as number) % 400 === 0)
  return v.days.length === (leap ? 366 : 365) && v.days.every((d, i) => {
    const expected = new Date(Date.parse(first) + i * 86400000).toISOString().slice(0, 10)
    if (!record(d) || d.date !== expected || !['RECORDED', 'ZERO', 'NEGATIVE', 'MISSING', 'FUTURE', 'FUTURE_RECORDED'].includes(d.state as string)
      || !Number.isInteger(d.level) || (d.level as number) < 0 || (d.level as number) > (v.bands as unknown[]).length || typeof d.selectedPeriod !== 'boolean'
      || !Number.isSafeInteger(d.rows) || (d.rows as number) < 0 || !dailyServices(d.services)) return false
    return ['MISSING', 'FUTURE'].includes(d.state as string) ? d.amount === null && d.selected === null && d.comparison === null && d.rows === 0
      : dailyAmount(d.amount) && dailyAmount(d.selected) && dailyAmount(d.comparison) && (d.rows as number) > 0
  })
}
const dailyRefresh = (v: unknown): v is DailyRefresh => record(v) && id(v.id) && ['RUNNING', 'COMPLETE', 'PARTIAL', 'FAILED', 'INTERRUPTED'].includes(v.state as string)
  && Number.isInteger(v.total) && (v.total as number) >= 1 && (v.total as number) <= 13 && Number.isInteger(v.completed) && (v.completed as number) >= 0 && (v.completed as number) <= (v.total as number) && nullable(v.failure)
const dailyFeed = (v: unknown): v is DailyFeed => record(v) && id(v.revision) && calendar(v.calendar) && instant(v.expiresAt)
  && ['lastAttempt', 'lastSuccess', 'retrievedLast'].every(k => v[k] === null || instant(v[k]))
  && ['observedThrough', 'coveredThrough'].every(k => v[k] === null || dateOnly(v[k])) && typeof v.provisional === 'boolean'
  && ['missingMonths', 'staleMonths'].every(k => Array.isArray(v[k]) && v[k].length <= 13 && v[k].every(month))
  && Array.isArray(v.coverage) && v.coverage.length <= 13 && v.coverage.every(p => record(p) && month(p.month) && dateOnly(p.through) && instant(p.retrievedAt))
  && (v.refresh === null || dailyRefresh(v.refresh))
const statement = (v: unknown): v is Statement => record(v) && string(v.text) && strings(v.references)
const savedAnalysis = (v: unknown): v is SavedAnalysis => record(v) && id(v.id) && month(v.first) && month(v.last)
  && instant(v.createdAt) && ['COMPLETE', 'FACTUAL'].includes(v.state as string)
const page = <T,>(item: (v: unknown) => v is T) => (v: unknown): v is BillingPage<T> => record(v)
  && Array.isArray(v.items) && v.items.length <= 50 && v.items.every(item) && (v.nextCursor === null || id(v.nextCursor))
const cursor = (before?: string | null) => before ? `?before=${encodeURIComponent(before)}` : ''
const presentation = (v: unknown): v is BillingPresentation => record(v)
  && ['period', 'comparisonPeriod', 'total', 'baseline', 'change', 'percent'].every(k => string(v[k]))
  && ['highlights', 'coverage', 'reconciliation'].every(k => strings(v[k]))
  && ['services', 'serviceChanges', 'months', 'baselineMonths', 'subscriptions', 'buckets'].every(k => Array.isArray(v[k]) && v[k].every(r => record(r) && string(r.label) && string(r.amount)))
function work(v: unknown): v is BillingWork {
  return record(v) && id(v.id) && ['GENERATION', 'QUESTION'].includes(v.kind as string) && states.includes(v.state as BillingState)
    && month(v.first) && month(v.last) && (v.parentReportId === null || id(v.parentReportId)) && nullable(v.question) && nullable(v.failure)
}
function report(v: unknown): v is BillingReport {
  if (!record(v) || !presentation(v.presentation) || !id(v.id) || !id(v.snapshotId) || !month(v.first) || !month(v.last) || !strings(v.baselineMonths)
    || !instant(v.createdAt) || !instant(v.expiresAt) || !nullable(v.analysisFailure) || !nullable(v.pdfFailure) || !record(v.facts) || !record(v.source)) return false
  const f = v.facts, s = v.source, a = v.analysis
  return ['selectedTotal', 'baselineTotal', 'delta', 'percent'].every(k => amount(f[k]))
    && ['services', 'months', 'subscriptions', 'serviceChanges'].every(k => amounts(f[k]))
    && record(f.buckets) && Object.values(f.buckets).every(b => record(b) && Number.isSafeInteger(b.rows) && (b.rows as number) >= 0 && decimal(b.cost))
    && ['currency', 'basis', 'mappingVersion', 'calculationVersion'].every(k => string(s[k])) && strings(s.limitations)
    && Array.isArray(s.reconciliation) && s.reconciliation.every(r => record(r) && string(r.invoiceId) && string(r.status) && amount(r.sourceCharges) && amount(r.residual))
    && Array.isArray(s.partitions) && s.partitions.every(p => record(p) && string(p.dataset) && instant(p.retrievedAt) && string(p.sourceVersion) && typeof p.attributionComplete === 'boolean' && typeof p.datesWithinPartition === 'boolean')
    && (a === null || record(a) && statement(a.executiveSummary) && Array.isArray(a.costDrivers) && a.costDrivers.every(statement)
      && Array.isArray(a.periodChanges) && a.periodChanges.every(statement) && Array.isArray(a.optimizationPriorities)
      && a.optimizationPriorities.every(p => record(p) && statement(p.evidence) && ['hypothesis', 'missingInputs', 'risk', 'nextStep'].every(k => string(p[k]))))
}
const messages: Record<string, string> = {
  FORBIDDEN: 'You do not have access to Billing Insights.', NOT_FOUND: 'This billing data has expired or is no longer available.',
  BUSY: 'Another billing request is running. Wait for it or stop it first.', NOT_READY: 'This result is not ready. Refresh to check its status.',
  DISABLED: 'Billing Insights has not been enabled on this installation.', INVALID_REQUEST: 'Check the completed-month range and workspace consent.',
}
export class BillingAccessError extends Error { }
async function request<T>(path: string, validate: (value: unknown) => value is T, signal?: AbortSignal, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {}
  if (body !== undefined) {
    const bootstrap = await fetch('/api/lark/auth/bootstrap', { credentials: 'include', cache: 'no-store', signal })
    const csrf: unknown = await bootstrap.json()
    if (!bootstrap.ok || !record(csrf) || !string(csrf.csrfToken)) throw new Error('Reconnect to Synvo before submitting your request.')
    headers['X-SYNVO-CSRF'] = csrf.csrfToken; headers['Content-Type'] = 'application/json'
  }
  const response = await fetch(root + path, { method: body === undefined ? 'GET' : 'POST', body: body === undefined ? undefined : JSON.stringify(body), credentials: 'include', cache: 'no-store', signal, headers })
  const text = await response.text()
  let value: unknown = null
  try { value = text ? JSON.parse(text) : null } catch { throw new Error('Billing returned an unreadable response. Refresh saved status to recover.') }
  if (!response.ok) {
    const code = record(value) && string(value.error) ? value.error : ''
    if ([401, 403, 404].includes(response.status)) throw new BillingAccessError(messages[code] || 'Billing access is unavailable. Reconnect to Synvo.')
    throw new Error(messages[code] || 'Billing request unavailable. Refresh to check whether it was received; your input is retained.')
  }
  if (!validate(value)) throw new Error('Billing returned an unreadable response. Refresh to recover the saved result.')
  return value
}
export const billingApi = {
  dailyFeed: (year?: number, signal?: AbortSignal) => request(`/daily${year === undefined ? '' : `?year=${year}`}`, dailyFeed, signal),
  refreshDaily: (key: string, signal?: AbortSignal) => request('/daily/refresh', dailyRefresh, signal, { key }),
  dailyCosts: (reportId: string, year?: number, signal?: AbortSignal) => request(`/reports/${encodeURIComponent(reportId)}/daily-costs${year === undefined ? '' : `?year=${year}`}`, dailyCosts, signal),
  history: (before?: string | null, signal?: AbortSignal) => request('/reports' + cursor(before), page(savedAnalysis), signal),
  questions: (reportId: string, before?: string | null, signal?: AbortSignal) => request(`/reports/${encodeURIComponent(reportId)}/questions` + cursor(before), page(work), signal),
  home: (signal?: AbortSignal) => request('', (v): v is BillingHome => record(v) && typeof v.enabled === 'boolean' && Array.isArray(v.works) && v.works.every(work) && (v.reportId === null || id(v.reportId)), signal),
  generate: (first: string, last: string, key: string, workspaceWrite: boolean) => request('/generations', work, undefined, { first, last, key, workspaceWrite }),
  retry: (reportId: string, key: string, workspaceWrite: boolean) => request(`/reports/${encodeURIComponent(reportId)}/retry`, work, undefined, { key, workspaceWrite }),
  stop: (workId: string) => request(`/work/${encodeURIComponent(workId)}/stop`, work, undefined, {}),
  ask: (reportId: string, question: string, key: string) => request(`/reports/${encodeURIComponent(reportId)}/questions`, work, undefined, { question, key }),
  report: (reportId: string, signal?: AbortSignal) => request(`/reports/${encodeURIComponent(reportId)}`, report, signal),
  answer: (workId: string, signal?: AbortSignal) => request(`/work/${encodeURIComponent(workId)}/answer`, (v): v is Statement | null => v === null || statement(v), signal),
  evidence: (reportId: string, offset: number, signal?: AbortSignal) => request(`/reports/${encodeURIComponent(reportId)}/evidence?offset=${offset}&limit=50`, (v): v is BillingEvidence[] => Array.isArray(v) && v.every(r => record(r) && string(r.reference) && string(r.date) && string(r.currency) && decimal(r.cost) && string(r.displayCost) && nullable(r.service) && nullable(r.subscription) && string(r.bucket)), signal),
  activity: async (workId: string, signal?: AbortSignal) => {
    const values = await request(`/work/${encodeURIComponent(workId)}/activity`, (v): v is unknown[] => Array.isArray(v) && v.every(item => parseActivity(item) !== null), signal)
    return values.map(v => parseActivity(v)!)
  },
  interactions: (workId: string, signal?: AbortSignal) => request(`/work/${encodeURIComponent(workId)}/interactions`, (v): v is import('./codex').CodexInteraction[] => Array.isArray(v) && v.every(isInteraction), signal),
  decide: (workId: string, interactionId: string, decision: CodexInteractionDecision, values: Record<string, string>) => request(`/work/${encodeURIComponent(workId)}/interactions/${encodeURIComponent(interactionId)}`, (v): v is null => v === null, undefined, { decision, values }),
  pdfUrl: (reportId: string) => `${root}/reports/${encodeURIComponent(reportId)}/pdf`,
}
