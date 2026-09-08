import { afterEach, describe, expect, it, vi } from 'vitest'
import { billingApi, BillingAccessError } from './billing'
import { dailyFixture, feedFixture } from '../billing/billingDaily.test-fixture'
const id = '00000000-0000-4000-8000-000000000001'
describe('billing API boundary', () => {
  it('reads the independent feed and refreshes with CSRF and an idempotency key only', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify(feedFixture())))
      .mockResolvedValueOnce(new Response(JSON.stringify({ csrfToken: 'synthetic' })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id, state: 'RUNNING', completed: 0, total: 13, failure: null })))
    vi.stubGlobal('fetch', fetcher)
    expect((await billingApi.dailyFeed(2026)).calendar.days).toHaveLength(365)
    expect(fetcher.mock.calls[0]).toEqual(['/api/billing-insights/daily?year=2026', expect.objectContaining({ method: 'GET', cache: 'no-store', credentials: 'include' })])
    expect((await billingApi.refreshDaily('daily-request-1')).state).toBe('RUNNING')
    expect(fetcher.mock.calls[2][0]).toBe('/api/billing-insights/daily/refresh')
    expect(fetcher.mock.calls[2][1]).toMatchObject({ method: 'POST', credentials: 'include', cache: 'no-store', headers: { 'X-SYNVO-CSRF': 'synthetic' } })
    expect(JSON.parse(fetcher.mock.calls[2][1].body)).toEqual({ key: 'daily-request-1' })
    const invalid = feedFixture(); invalid.calendar.days[1].date = invalid.calendar.days[0].date
    fetcher.mockResolvedValue(new Response(JSON.stringify(invalid)))
    await expect(billingApi.dailyFeed(2026)).rejects.toThrow('unreadable response')
  })
  it('validates bounded calendar dates and exact decimal amounts without provider calls', async () => {
    const fetcher = vi.fn().mockImplementation(async () => new Response(JSON.stringify(dailyFixture(id))))
    vi.stubGlobal('fetch', fetcher)
    expect((await billingApi.dailyCosts(id, 2026)).days).toHaveLength(365)
    expect((await billingApi.dailyCosts(id, 2026)).bands).toHaveLength(5)
    expect(fetcher).toHaveBeenCalledWith(`/api/billing-insights/reports/${id}/daily-costs?year=2026`, expect.objectContaining({ cache: 'no-store', credentials: 'include', method: 'GET' }))
    const malformed = dailyFixture(id); malformed.days[1].date = malformed.days[0].date
    fetcher.mockImplementation(async () => new Response(JSON.stringify(malformed)))
    await expect(billingApi.dailyCosts(id)).rejects.toThrow('unreadable response')
    const numeric = dailyFixture(id); (numeric.days.find(d => d.amount)!.amount!.exact as unknown) = 0.1
    fetcher.mockImplementation(async () => new Response(JSON.stringify(numeric)))
    await expect(billingApi.dailyCosts(id)).rejects.toThrow('unreadable response')
    fetcher.mockImplementation(async () => new Response(JSON.stringify(dailyFixture(id, 2024))))
    expect((await billingApi.dailyCosts(id, 2024)).days).toHaveLength(366)
  })
  it('accepts collapsed tied bands but rejects unsupported color levels', async () => {
    const tied = dailyFixture(id)
    tied.bands = [{ level: 1, upper: '5.00', label: '> USD 0.00 – ≤ USD 5.00' }]
    tied.days.forEach(day => { if (day.amount) day.level = 1 })
    const fetcher = vi.fn().mockImplementation(async () => new Response(JSON.stringify(tied)))
    vi.stubGlobal('fetch', fetcher)
    expect((await billingApi.dailyCosts(id)).bands).toHaveLength(1)
    tied.days.find(day => day.amount)!.level = 2
    await expect(billingApi.dailyCosts(id)).rejects.toThrow('unreadable response')
    const oversized = dailyFixture(id)
    oversized.bands.push({ level: 6, upper: '6', label: 'invalid sixth band' })
    fetcher.mockImplementation(async () => new Response(JSON.stringify(oversized)))
    await expect(billingApi.dailyCosts(id)).rejects.toThrow('unreadable response')
  })
  afterEach(() => vi.unstubAllGlobals())
  it('uses session, no-store, CSRF and exact idempotency payload for generation', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ csrfToken: 'synthetic' })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id, kind: 'GENERATION', state: 'FETCHING', first: '2026-07', last: '2026-08', parentReportId: null, question: null, failure: null })))
    vi.stubGlobal('fetch', fetcher)
    await billingApi.generate('2026-07', '2026-08', 'request-1', true)
    expect(fetcher.mock.calls[1][1]).toMatchObject({ credentials: 'include', cache: 'no-store', headers: { 'X-SYNVO-CSRF': 'synthetic' } })
    expect(JSON.parse(fetcher.mock.calls[1][1].body)).toEqual({ first: '2026-07', last: '2026-08', key: 'request-1', workspaceWrite: true })
  })
  it('rejects numeric cost payloads so browser floating-point arithmetic cannot replace exact amounts', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify([{ reference: 'row:a/0/1', date: '2026-07-01', cost: 0.1, currency: 'USD', service: 'Synthetic', subscription: null, bucket: 'AZURE' }]))))
    await expect(billingApi.evidence(id, 0)).rejects.toThrow('unreadable response')
  })
  it('reads bounded history pages with session/no-store and rejects malformed entries', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ items: [{ id, first: '2026-07', last: '2026-08', createdAt: '2026-09-08T00:00:00Z', state: 'COMPLETE' }], nextCursor: id })))
    vi.stubGlobal('fetch', fetcher)
    expect((await billingApi.history(id)).items[0].id).toBe(id)
    expect(fetcher).toHaveBeenCalledWith(`/api/billing-insights/reports?before=${id}`, expect.objectContaining({ method: 'GET', credentials: 'include', cache: 'no-store' }))
    fetcher.mockResolvedValue(new Response(JSON.stringify({ items: [{ id, first: '2026-07', last: '2026-08', createdAt: 'invalid', state: 'COMPLETE' }], nextCursor: null })))
    await expect(billingApi.history()).rejects.toThrow('unreadable response')
    fetcher.mockResolvedValue(new Response(JSON.stringify({ items: [], nextCursor: 'not-an-id' })))
    await expect(billingApi.questions(id)).rejects.toThrow('unreadable response')
  })
  it('accepts empty successful decision responses and never displays arbitrary error content', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ csrfToken: 'synthetic' }))).mockResolvedValueOnce(new Response(null, { status: 200 })))
    await expect(billingApi.decide(id, id, 'DECLINE', {})).resolves.toBeNull()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: 'SECRET_RAW_DIAGNOSTIC' }), { status: 403 })))
    await expect(billingApi.home()).rejects.toBeInstanceOf(BillingAccessError)
    await expect(billingApi.home()).rejects.not.toThrow('SECRET_RAW_DIAGNOSTIC')
  })
})
