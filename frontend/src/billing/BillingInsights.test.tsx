import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { billingApi, BillingAccessError, type BillingReport, type BillingWork } from '../api/billing'
import { BillingInsights } from './BillingInsights'
import { feedFixture } from './billingDaily.test-fixture'

const id = '00000000-0000-4000-8000-000000000001'
const work: BillingWork = { id, kind: 'GENERATION', state: 'COMPLETE', first: '2026-07', last: '2026-07', parentReportId: null, question: null, failure: null }
const statement = { text: 'Selected cost is USD 0.30.', references: ['fact:/selectedTotal'] }
const report: BillingReport = {
  presentation: { period: 'Jul 2026', comparisonPeriod: 'Jun 2026', total: 'USD 0.30', baseline: 'USD 0.10', change: 'USD 0.20', percent: '200.00%', highlights: ['Monthly costs are based on saved evidence.'], coverage: ['Jul 2026: not invoice-reconciled.'], reconciliation: [], services: [{ label: 'Synthetic service', amount: 'USD 0.30' }], serviceChanges: [], months: [{ label: 'Jul 2026', amount: 'USD 0.30' }], baselineMonths: [], subscriptions: [], buckets: [] },
  id, snapshotId: '00000000-0000-4000-8000-000000000002', first: '2026-07', last: '2026-07', baselineMonths: ['2026-06'],
  createdAt: '2026-09-08T00:00:00Z', expiresAt: '2099-01-01T00:00:00Z', analysisFailure: null, pdfFailure: null,
  facts: { selectedTotal: '0.30', baselineTotal: '0.10', delta: '0.20', percent: '200.00', services: { 'Synthetic service': '0.30' }, months: { '2026-07': '0.30' }, subscriptions: {}, serviceChanges: {}, buckets: { AZURE: { rows: 2, cost: '0.30' } } },
  analysis: { executiveSummary: statement, costDrivers: [statement], periodChanges: [], optimizationPriorities: [] },
  source: { currency: 'USD', basis: 'Actual cost', mappingVersion: 'v1', calculationVersion: 'v1', limitations: ['SOURCE_FINALITY_NOT_ESTABLISHED'], reconciliation: [], partitions: [] },
}
function selectSaved(value: string) {
  const trigger = screen.getByRole('combobox', { name: 'Saved analyses' })
  if (trigger.getAttribute('aria-expanded') !== 'true') fireEvent.click(trigger)
  fireEvent.click(screen.getAllByRole('option').find(option => (option as HTMLButtonElement).value === value)!)
}
function api() {
  return { ...billingApi, home: vi.fn().mockResolvedValue({ enabled: true, works: [work], reportId: id }), report: vi.fn().mockResolvedValue(report),
    dailyFeed: vi.fn().mockRejectedValue(new Error('Daily view temporarily unavailable')),
    history: vi.fn().mockResolvedValue({ items: [{ id, first: report.first, last: report.last, createdAt: report.createdAt, state: 'COMPLETE' }], nextCursor: null }),
    questions: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    generate: vi.fn().mockResolvedValue({ ...work, state: 'FETCHING' }), ask: vi.fn().mockResolvedValue({ ...work, kind: 'QUESTION', state: 'ANALYZING', parentReportId: id }),
    activity: vi.fn().mockResolvedValue([]), interactions: vi.fn().mockResolvedValue([]), stop: vi.fn().mockResolvedValue({ ...work, state: 'STOPPING' }) }
}
describe('Billing Insights', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks() })
  it.each([
    ['ANSWER_TOO_MANY_REFERENCES', 'The answer included too many evidence references.'],
    ['ANSWER_TOO_MANY_CLAIMS', 'The answer included too many numeric claims.'],
    ['ANSWER_TOO_LONG', 'The answer exceeded the supported length.'],
    ['ANSWER_INVALID', 'The answer could not be validated against the saved evidence or required format.'],
    ['SOURCE_INVALID', 'Request failed.'],
  ])('shows a safe actionable question failure for %s', async (failure, message) => {
    const client = api()
    client.questions.mockResolvedValue({ items: [{ ...work, id: 'failed-question', kind: 'QUESTION', parentReportId: id, question: 'Explain the change', state: 'FAILED', failure }], nextCursor: null })
    render(<BillingInsights api={client} />)
    const question = await screen.findByRole('heading', { name: 'Explain the change' })
    expect(question.parentElement).toHaveTextContent(message)
    expect(question.parentElement).toHaveTextContent(/report is unchanged/)
    expect(client.ask).not.toHaveBeenCalled()
    expect(client.generate).not.toHaveBeenCalled()
    expect(screen.getByRole('article', { name: 'Billing analysis report' })).toBeInTheDocument()
  })
  it('places follow-up activity after its accepted question and before the composer while history catches up', async () => {
    const client = api(), questionId = '00000000-0000-4000-8000-000000000005'
    const active: BillingWork = { ...work, id: questionId, kind: 'QUESTION', state: 'ANALYZING', parentReportId: id, question: 'Explain the decrease' }
    client.home.mockResolvedValue({ enabled: true, works: [active, work], reportId: id })
    client.stop.mockResolvedValue({ ...active, state: 'STOPPING' })
    render(<BillingInsights api={client} />)
    const heading = await screen.findByRole('heading', { name: 'Explain the decrease' })
    const progress = screen.getByRole('region', { name: 'Billing request status' })
    const input = screen.getByRole('textbox', { name: 'Your question' })
    expect(heading.compareDocumentPosition(progress) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(progress.compareDocumentPosition(input) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(progress.closest('.billing-questions')).not.toBeNull()
    expect(screen.getAllByRole('region', { name: 'Billing request status' })).toHaveLength(1)
    expect(client.questions).toHaveBeenCalled()
    client.home.mockResolvedValue({ enabled: true, works: [{ ...active, state: 'FAILED', failure: 'SOURCE_INVALID' }, work], reportId: id })
    client.questions.mockResolvedValue({ items: [{ ...active, state: 'FAILED', failure: 'SOURCE_INVALID' }], nextCursor: null })
    fireEvent.click(screen.getByRole('button', { name: 'Stop' }))
    await screen.findByText('Request failed. Your previous report is unchanged.')
    expect(client.stop).toHaveBeenCalledWith(questionId)
    expect(screen.queryByRole('region', { name: 'Billing request status' })).not.toBeInTheDocument()
    expect(screen.getAllByRole('heading', { name: 'Explain the decrease' })).toHaveLength(1)
    expect(screen.getByRole('article', { name: 'Billing analysis report' })).toBeInTheDocument()
  })
  it('keeps generation activity above the report rather than inside report questions', async () => {
    const client = api()
    client.home.mockResolvedValue({ enabled: true, works: [{ ...work, id: '00000000-0000-4000-8000-000000000005', state: 'ANALYZING' }, work], reportId: id })
    render(<BillingInsights api={client} />)
    const reportView = await screen.findByRole('article', { name: 'Billing analysis report' })
    const progress = screen.getByRole('region', { name: 'Billing request status' })
    expect(progress.compareDocumentPosition(reportView) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(progress.closest('.billing-questions')).toBeNull()
  })
  it.each([
    ['-216.817996740753600000', '-44.42', '−USD 216.82', '-44.42%', 'USD 216.82 less', '44.42% lower', 'decrease'],
    ['216.82', '44.42', 'USD 216.82', '44.42%', 'USD 216.82 more', '44.42% higher', 'increase'],
    ['0.00', '0.00', 'USD 0.00', '0.00%', 'No change', '0.00%', 'unchanged'],
    [null, null, 'Unavailable', 'Unavailable', 'Unavailable', 'Unavailable', 'unavailable'],
    ['-0.001', '0.00', '< USD 0.01 (negative)', '0.00%', '< USD 0.01 less', '< 0.01% lower', 'decrease'],
    ['2.00', null, 'USD 2.00', 'Unavailable', 'USD 2.00 more', 'Unavailable', 'increase'],
  ])('shows directional change wording without altering exact facts (%s)', async (delta, percent, amountDisplay, percentDisplay, amountText, percentText, direction) => {
    const client = api()
    const saved = { ...report, facts: { ...report.facts, delta, percent }, presentation: { ...report.presentation, change: amountDisplay!, percent: percentDisplay! } }
    client.report.mockResolvedValue(saved)
    render(<BillingInsights api={client} />)
    await screen.findByRole('article', { name: 'Billing analysis report' })
    const card = screen.getByText('Change from comparison period').parentElement!
    expect(card.querySelector('strong')).toHaveTextContent(amountText!)
    expect(card.querySelector('strong')).toHaveClass(`billing-change--${direction}`)
    expect(card.querySelector('strong + span')).toHaveTextContent(percentText!)
    if (percent !== null) expect(card.querySelector('strong + span')).toHaveClass(`billing-change--${direction}`)
    else expect(card.querySelector('strong + span')).not.toHaveAttribute('class')
    expect(saved.facts.delta).toBe(delta)
    expect(card).toHaveTextContent('Comparison cost: USD 0.10')
  })
  it('requires New Analysis even on first visit and keeps initial failure recovery visible', async () => {
    const client = api(); client.home.mockResolvedValue({ enabled: true, works: [{ ...work, state: 'FAILED' }], reportId: null })
    client.history.mockResolvedValue({ items: [], nextCursor: null })
    render(<BillingInsights api={client} />)
    await screen.findByText('Request failed. Your previous report is unchanged.')
    expect(screen.queryByRole('button', { name: 'Generate insights' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '+ New Analysis' }))
    expect(screen.getByRole('button', { name: 'Generate insights' })).toBeDisabled()
    expect(client.generate).not.toHaveBeenCalled()
  })
  it('loads one saved report, keeps decimal strings, and does not fetch Azure when changing the draft', async () => {
    const client = api(); render(<BillingInsights api={client} />)
    await screen.findByRole('article', { name: 'Billing analysis report' })
    expect(screen.getAllByText('USD 0.30').length).toBeGreaterThan(0)
    expect(screen.getByRole('heading', { name: 'Understand Your Cloud Spending' })).toBeInTheDocument()
    expect(screen.queryByText(/^Synvo AI agents that fetch and analyze Azure billing data/)).not.toBeInTheDocument()
    expect(screen.queryByText('Azure cost analysis')).not.toBeInTheDocument()
    expect(screen.queryByText(/^All Azure subscriptions billed through/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Billing period · Jul 2026' })).not.toBeInTheDocument()
    expect(screen.queryByText('Analysis complete')).not.toBeInTheDocument()
    expect(client.generate).not.toHaveBeenCalled()
    expect(screen.getByRole('heading', { name: 'Jul 2026' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'What your team should know' })).toBeInTheDocument()
    expect(screen.getByText('Jul 2026: not invoice-reconciled.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Download report PDF' })).toHaveAttribute('href', `/api/billing-insights/reports/${id}/pdf`)
    expect(screen.getByText('Missing evidence is not zero spend or verified savings.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '+ New Analysis' }))
    fireEvent.click(screen.getByRole('button', { name: 'Last 3 months' }))
    expect(client.generate).not.toHaveBeenCalled()
  })
  it('formats and emphasizes the selected month range', async () => {
    render(<BillingInsights api={api()} />)
    await screen.findByRole('article', { name: 'Billing analysis report' })
    fireEvent.click(screen.getByRole('button', { name: '+ New Analysis' }))
    fireEvent.click(screen.getByRole('button', { name: 'Custom range' }))
    fireEvent.change(screen.getByLabelText('First month'), { target: { value: '2026-06' } })
    fireEvent.change(screen.getByLabelText('Last month'), { target: { value: '2026-08' } })
    expect(screen.getByText('Jun 2026 – Aug 2026').tagName).toBe('STRONG')
  })
  it('keeps the interactive calendar above navigation during setup and Back restores the report and question draft', async () => {
    const client = api(); client.dailyFeed.mockResolvedValue(feedFixture())
    render(<BillingInsights api={client} />)
    const grid = await screen.findByRole('group', { name: 'Daily costs; use arrow keys to move between days' })
    const region = screen.getByRole('region', { name: 'Daily Azure Spending' })
    expect(screen.getByRole('heading', { name: 'Understand Your Cloud Spending' }).parentElement?.nextElementSibling).toBe(region)
    fireEvent.change(screen.getByRole('textbox', { name: 'Your question' }), { target: { value: 'Keep this question' } })
    fireEvent.click(screen.getByRole('button', { name: /^8 June 2026:/ }))
    fireEvent.click(screen.getByRole('button', { name: '+ New Analysis' }))
    expect(screen.getByRole('group', { name: 'Daily costs; use arrow keys to move between days' })).toBe(grid)
    expect(screen.getByLabelText('Selected day details').querySelector('strong')).toHaveTextContent('8 June 2026')
    fireEvent.click(screen.getByRole('button', { name: /^9 June 2026:/ }))
    fireEvent.click(screen.getByRole('checkbox'))
    await waitFor(() => expect(client.home.mock.calls.length).toBeGreaterThan(1))
    fireEvent.click(screen.getByRole('button', { name: 'Back to saved analysis' }))
    await screen.findByRole('article', { name: 'Billing analysis report' })
    expect(screen.getByRole('textbox', { name: 'Your question' })).toHaveValue('Keep this question')
    expect(screen.getByLabelText('Selected day details').querySelector('strong')).toHaveTextContent('9 June 2026')
    expect(screen.getByRole('group', { name: 'Daily costs; use arrow keys to move between days' })).toBe(grid)
    expect(client.dailyFeed).toHaveBeenCalledTimes(1)
    expect(client.generate).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '+ New Analysis' }))
    expect(screen.getByRole('checkbox')).not.toBeChecked()
  })
  it('returns from first-use setup without creating an analysis', async () => {
    const client = api(); client.home.mockResolvedValue({ enabled: true, works: [], reportId: null })
    client.history.mockResolvedValue({ items: [], nextCursor: null })
    render(<BillingInsights api={client} />)
    await waitFor(() => expect(screen.getByRole('button', { name: '+ New Analysis' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '+ New Analysis' }))
    fireEvent.click(screen.getByRole('button', { name: 'Back to saved analysis' }))
    expect(screen.queryByRole('button', { name: 'Generate insights' })).not.toBeInTheDocument()
    expect(client.generate).not.toHaveBeenCalled()
  })
  it('keeps saved daily data interactive while generation runs and Back never cancels work', async () => {
    const client = api(); client.dailyFeed.mockResolvedValue(feedFixture())
    render(<BillingInsights api={client} />)
    await screen.findByLabelText('Selected day details')
    const grid = screen.getByRole('group', { name: 'Daily costs; use arrow keys to move between days' })
    fireEvent.click(screen.getByRole('button', { name: '+ New Analysis' }))
    client.home.mockResolvedValue({ enabled: true, works: [{ ...work, state: 'FETCHING' }], reportId: id })
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: 'Generate insights' }))
    await screen.findByRole('button', { name: 'Stop' })
    expect(screen.getByRole('group', { name: 'Daily costs; use arrow keys to move between days' })).toBe(grid)
    fireEvent.click(screen.getByRole('button', { name: /^9 June 2026:/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Back to saved analysis' }))
    await screen.findByRole('article', { name: 'Billing analysis report' })
    expect(screen.getByLabelText('Selected day details').querySelector('strong')).toHaveTextContent('9 June 2026')
    expect(client.stop).not.toHaveBeenCalled()
  })
  it('clears retained calendar data if its access is revoked while drafting', async () => {
    const client = api(); client.dailyFeed.mockResolvedValueOnce(feedFixture()).mockRejectedValue(new BillingAccessError('Saved evidence unavailable'))
    render(<BillingInsights api={client} />)
    await screen.findByLabelText('Selected day details')
    fireEvent.click(screen.getByRole('button', { name: '+ New Analysis' }))
    fireEvent.click(screen.getByLabelText('Spending year'))
    fireEvent.click(screen.getByRole('option', { name: '2024' }))
    await screen.findByText('Saved evidence unavailable')
    expect(screen.queryByRole('region', { name: 'Daily Azure Spending' })).not.toBeInTheDocument()
    expect(screen.queryByRole('article', { name: 'Billing analysis report' })).not.toBeInTheDocument()
  })
  it('requires explicit workspace consent and submits only one idempotent generation', async () => {
    const client = api(); render(<BillingInsights api={client} />)
    await screen.findByRole('article', { name: 'Billing analysis report' })
    fireEvent.click(screen.getByRole('button', { name: '+ New Analysis' }))
    const generate = screen.getByRole('button', { name: 'Generate insights' })
    expect(generate).toBeDisabled()
    fireEvent.click(screen.getByRole('checkbox'))
    client.generate.mockImplementation(() => new Promise(() => {}))
    fireEvent.click(generate); fireEvent.click(generate)
    await waitFor(() => expect(client.generate).toHaveBeenCalledTimes(1))
    expect(client.generate.mock.calls[0][2]).toMatch(/^[\da-f-]{36}$/)
    expect(client.generate.mock.calls[0][3]).toBe(true)
  })
  it('keeps questions bound to the displayed report and retains drafts across workflow navigation', async () => {
    const client = api(); const { rerender } = render(<BillingInsights api={client} />)
    await screen.findByRole('article', { name: 'Billing analysis report' })
    fireEvent.change(screen.getByRole('textbox', { name: 'Your question' }), { target: { value: 'Which service increased?' } })
    rerender(<BillingInsights api={client} visible={false} />); rerender(<BillingInsights api={client} visible />)
    expect(screen.getByRole('textbox', { name: 'Your question' })).toHaveValue('Which service increased?')
    expect(screen.getByRole('region', { name: 'Report questions' })).toBeInTheDocument()
    expect(screen.queryByText('Ask about this report')).not.toBeInTheDocument()
    expect(screen.queryByText(/questions stay bound to this report and its saved evidence/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Ask Synvo' }))
    await waitFor(() => expect(client.ask).toHaveBeenCalledWith(id, 'Which service increased?', expect.any(String)))
  })
  it('retains the saved report and input if a new request acknowledgment is lost', async () => {
    const client = api(); client.generate.mockRejectedValue(new Error('Connection lost'))
    render(<BillingInsights api={client} />); await screen.findByRole('article', { name: 'Billing analysis report' })
    fireEvent.click(screen.getByRole('button', { name: '+ New Analysis' }))
    fireEvent.click(screen.getByRole('checkbox')); fireEvent.click(screen.getByRole('button', { name: 'Generate insights' }))
    await screen.findByText('Connection lost'); expect(screen.queryByRole('article', { name: 'Billing analysis report' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Generate insights' }))
    await waitFor(() => expect(client.generate).toHaveBeenCalledTimes(2))
    expect(client.generate.mock.calls[0][2]).toBe(client.generate.mock.calls[1][2])
    selectSaved(id)
    await screen.findByRole('article', { name: 'Billing analysis report' })
  })
  it('shows factual fallback and explicit retry without inventing AI recommendations', async () => {
    const client = api(); client.report.mockResolvedValue({ ...report, analysis: null, analysisFailure: 'SOURCE_INVALID' })
    render(<BillingInsights api={client} />)
    await screen.findByText(/AI analysis is unavailable. The figures below/)
    expect(screen.getByRole('button', { name: 'Retry saved report' })).toBeDisabled()
    expect(screen.queryByRole('heading', { name: 'Cost optimization priorities' })).not.toBeInTheDocument()
  })
  it('starts a clean draft without fetching, deleting or letting polling restore the old report', async () => {
    const client = api(); render(<BillingInsights api={client} />)
    await screen.findByRole('article', { name: 'Billing analysis report' })
    expect(screen.queryByRole('button', { name: 'Generate insights' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '+ New Analysis' }))
    expect(screen.queryByRole('article', { name: 'Billing analysis report' })).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: 'Your question' })).not.toBeInTheDocument()
    expect(screen.getByRole('checkbox')).not.toBeChecked()
    expect(screen.getByRole('button', { name: 'Last 1 month' })).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => expect(client.home.mock.calls.length).toBeGreaterThan(1))
    expect(screen.queryByRole('article', { name: 'Billing analysis report' })).not.toBeInTheDocument()
    expect(client.generate).not.toHaveBeenCalled(); expect(client.stop).not.toHaveBeenCalled()
    selectSaved(id)
    await screen.findByRole('article', { name: 'Billing analysis report' })
  })
  it('restores the selected report conversation, draft and PDF, including repeated periods', async () => {
    const second = '00000000-0000-4000-8000-000000000003', questionId = '00000000-0000-4000-8000-000000000004'
    const client = api()
    client.history.mockResolvedValue({ items: [id, second].map(value => ({ id: value, first: report.first, last: report.last, createdAt: report.createdAt, state: 'COMPLETE' })), nextCursor: null })
    client.report.mockImplementation(async value => ({ ...report, id: value }))
    client.questions.mockImplementation(async value => ({ items: value === second ? [{ ...work, id: questionId, kind: 'QUESTION', parentReportId: second, question: 'Older report question' }] : [], nextCursor: null }))
    client.answer = vi.fn().mockResolvedValue({ text: 'Older report answer', references: [] })
    render(<BillingInsights api={client} />); await screen.findByRole('article', { name: 'Billing analysis report' })
    fireEvent.change(screen.getByRole('textbox', { name: 'Your question' }), { target: { value: 'First report draft' } })
    fireEvent.click(screen.getByRole('combobox', { name: 'Saved analyses' }))
    await waitFor(() => expect(screen.getAllByRole('option')).toHaveLength(2))
    expect(new Set(screen.getAllByRole('option').map(option => option.textContent)).size).toBe(2)
    selectSaved(second)
    await screen.findByText('Older report answer')
    expect(screen.getByRole('textbox', { name: 'Your question' })).toHaveValue('')
    expect(screen.getByRole('link', { name: 'Download report PDF' })).toHaveAttribute('href', `/api/billing-insights/reports/${second}/pdf`)
    fireEvent.change(screen.getByRole('textbox', { name: 'Your question' }), { target: { value: 'Second report question' } })
    fireEvent.click(screen.getByRole('button', { name: 'Ask Synvo' }))
    await waitFor(() => expect(client.ask).toHaveBeenCalledWith(second, 'Second report question', expect.any(String)))
    selectSaved(id)
    await waitFor(() => expect(screen.getByRole('textbox', { name: 'Your question' })).toHaveValue('First report draft'))
    expect(screen.queryByText('Older report answer')).not.toBeInTheDocument()
  })
  it('ignores a late report response after New Analysis and disables new work during active analysis', async () => {
    const client = api(); let finish: ((value: BillingReport) => void) | undefined
    client.report.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    render(<BillingInsights api={client} />)
    await waitFor(() => expect(client.report).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: '+ New Analysis' }))
    finish!(report)
    await waitFor(() => expect(client.home.mock.calls.length).toBeGreaterThan(1))
    expect(screen.queryByRole('article', { name: 'Billing analysis report' })).not.toBeInTheDocument()
    client.home.mockResolvedValue({ enabled: true, works: [{ ...work, state: 'ANALYZING' }], reportId: id })
    fireEvent.click(screen.getByRole('checkbox')); fireEvent.click(screen.getByRole('button', { name: 'Generate insights' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '+ New Analysis' })).toBeDisabled())
    expect(screen.getByRole('button', { name: 'Stop' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Saved analyses' })).toHaveTextContent('New analysis draft')
    fireEvent.click(screen.getByRole('combobox', { name: 'Saved analyses' }))
    expect(screen.getByRole('option', { name: 'New analysis draft' })).toBeDisabled()
  })
  it('opens a newly completed generation without replacing or deleting saved choices', async () => {
    const second = '00000000-0000-4000-8000-000000000003'
    const client = api(); client.generate.mockResolvedValue({ ...work, id: second, state: 'FETCHING' })
    client.report.mockImplementation(async value => ({ ...report, id: value }))
    render(<BillingInsights api={client} />); await screen.findByRole('article', { name: 'Billing analysis report' })
    fireEvent.click(screen.getByRole('button', { name: '+ New Analysis' }))
    fireEvent.click(screen.getByRole('checkbox'))
    client.home.mockResolvedValue({ enabled: true, works: [{ ...work, id: second }, work], reportId: second })
    fireEvent.click(screen.getByRole('button', { name: 'Generate insights' }))
    await waitFor(() => expect(screen.getByRole('link', { name: 'Download report PDF' })).toHaveAttribute('href', `/api/billing-insights/reports/${second}/pdf`))
    expect(screen.queryByRole('button', { name: 'Generate insights' })).not.toBeInTheDocument()
    expect(client.generate).toHaveBeenCalledTimes(1)
    selectSaved(id)
    await waitFor(() => expect(screen.getByRole('link', { name: 'Download report PDF' })).toHaveAttribute('href', `/api/billing-insights/reports/${id}/pdf`))
  })
  it('loads older analyses and report-specific questions without a new generation', async () => {
    const older = '00000000-0000-4000-8000-000000000003', questionId = '00000000-0000-4000-8000-000000000004'
    const client = api()
    const entry = { id, first: report.first, last: report.last, createdAt: report.createdAt, state: 'COMPLETE' }
    client.history.mockImplementation(async before => ({ items: [{ ...entry, id: before ? older : id }], nextCursor: before ? null : id }))
    client.questions.mockImplementation(async (_report, before) => ({ items: before ? [{ ...work, id: questionId, kind: 'QUESTION', parentReportId: id, question: 'Earlier question' }] : [], nextCursor: before ? null : questionId }))
    client.answer = vi.fn().mockResolvedValue({ text: 'Earlier answer', references: [] })
    render(<BillingInsights api={client} />); await screen.findByRole('article', { name: 'Billing analysis report' })
    fireEvent.click(await screen.findByRole('button', { name: 'Load older analyses' }))
    await waitFor(() => expect(client.history).toHaveBeenCalledWith(id, expect.any(AbortSignal)))
    fireEvent.click(await screen.findByRole('button', { name: 'Load older questions' }))
    await screen.findByText('Earlier answer')
    expect(client.questions).toHaveBeenCalledWith(id, questionId, expect.any(AbortSignal))
    expect(client.generate).not.toHaveBeenCalled()
  })
})
