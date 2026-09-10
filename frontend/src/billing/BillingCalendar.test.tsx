import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { billingApi, BillingAccessError, type DailyFeed } from '../api/billing'
import { BillingCalendar } from './BillingCalendar'
import { feedFixture } from './billingDaily.test-fixture'

describe('Billing calendar', () => {
  afterEach(() => { cleanup(); vi.restoreAllMocks() })
  const id = 'calendar-a'
  it.each(['RECORDED', 'ZERO', 'NEGATIVE'] as const)('defaults to the latest %s date, ignoring report-period preference and future-dated charges', async state => {
    const feed = feedFixture()
    const latest = feed.calendar.days.find(day => day.date === '2026-09-08')!
    latest.state = state; latest.amount = { exact: state === 'ZERO' ? '0' : state === 'NEGATIVE' ? '-1' : '1', display: 'USD 1.00' }
    const future = feed.calendar.days.find(day => day.date === '2026-12-31')!
    future.state = 'FUTURE_RECORDED'; future.amount = { exact: '2', display: 'USD 2.00' }
    render(<BillingCalendar api={{ ...billingApi, dailyFeed: vi.fn().mockResolvedValue(feed) }} onAccessError={vi.fn()} />)
    expect(await screen.findByLabelText('Selected day details')).toHaveTextContent('8 September 2026')
    expect(screen.getByRole('button', { name: /^8 September 2026:/ })).toHaveAttribute('tabindex', '0')
    expect(document.querySelectorAll('.billing-day[tabindex="0"]')).toHaveLength(1)
  })
  it('uses the latest non-future date when the year has no records', async () => {
    const feed = feedFixture()
    feed.calendar.days.forEach(day => { day.amount = null; day.state = day.date <= '2026-09-08' ? 'MISSING' : 'FUTURE' })
    render(<BillingCalendar api={{ ...billingApi, dailyFeed: vi.fn().mockResolvedValue(feed) }} onAccessError={vi.fn()} />)
    expect(await screen.findByLabelText('Selected day details')).toHaveTextContent('8 September 2026No recorded data')
  })
  it('preserves a chosen date when the feed reloads and defaults to the latest record when switching years', async () => {
    const client = { ...billingApi, dailyFeed: vi.fn().mockResolvedValue(feedFixture()) }
    const onAccessError = vi.fn()
    const view = render(<BillingCalendar api={client} onAccessError={onAccessError} />)
    expect(await screen.findByLabelText('Selected day details')).toHaveTextContent('31 August 2026')
    fireEvent.click(screen.getByRole('button', { name: /^8 June 2026:/ }))
    view.rerender(<BillingCalendar api={client} visible={false} onAccessError={onAccessError} />)
    view.rerender(<BillingCalendar api={client} onAccessError={onAccessError} />)
    await waitFor(() => expect(client.dailyFeed).toHaveBeenCalledTimes(2))
    expect(screen.getByLabelText('Selected day details')).toHaveTextContent('8 June 2026')
    client.dailyFeed.mockResolvedValue(feedFixture(2024))
    fireEvent.click(screen.getByLabelText('Spending year')); fireEvent.click(screen.getByRole('option', { name: '2024' }))
    expect(await screen.findByLabelText('Selected day details')).toHaveTextContent('31 August 2024')
  })
  it('collapses the legend and coverage context, shows only the refresh date and keeps failure details accessible', async () => {
    const feed = { ...feedFixture(), retrievedLast: '2026-09-08T10:02:42Z', provisional: true,
      missingMonths: ['2025-09', '2025-10'], refresh: { id: 'partial', state: 'PARTIAL' as const, completed: 11, total: 13, failure: 'UNSUPPORTED_PRECISION' } }
    render(<BillingCalendar api={{ ...billingApi, dailyFeed: vi.fn().mockResolvedValue(feed) }} onAccessError={vi.fn()} />)
    await screen.findByLabelText('Selected day details')
    const disclosure = screen.getByText('Legend').closest('details')!
    expect(disclosure).not.toHaveAttribute('open')
    expect(disclosure).toContainElement(screen.getByLabelText('Daily cost color scale'))
    expect(disclosure).toHaveTextContent('Missing months: 2025-09, 2025-10')
    expect(disclosure).toHaveTextContent('not finalized invoice amounts')
    expect(disclosure).toHaveTextContent('supported precision')
    expect(screen.getByText('Last refreshed', { exact: false })).toHaveTextContent('8 Sept 2026')
    expect(screen.queryByText(/6:02:42|Refresh incomplete\.|Data through|months awaiting data/)).not.toBeInTheDocument()
    expect(document.querySelector('.billing-daily-freshness')).not.toHaveTextContent(/Missing|provisional|unfinished|precision/i)
    fireEvent.click(screen.getByText('Legend'))
    expect(disclosure).toHaveAttribute('open')
    fireEvent.click(screen.getByText('Legend'))
    expect(disclosure).not.toHaveAttribute('open')
  })
  it('keeps the legend compact and future cells blank without losing accessible cost states', async () => {
    const feed = feedFixture()
    const refund = feed.calendar.days.find(day => day.date === '2026-06-02')!
    refund.state = 'NEGATIVE'; refund.level = 0; refund.amount = { exact: '-1.00', display: '-USD 1.00' }
    const client = { ...billingApi, dailyFeed: vi.fn().mockResolvedValue(feed) }
    render(<BillingCalendar api={client} onAccessError={vi.fn()} />)
    await screen.findByLabelText('Selected day details')
    const legend = screen.getByLabelText('Daily cost color scale')
    expect(legend).not.toHaveTextContent(/Net negative|Future/)
    expect(legend).toHaveTextContent('Recorded net zero')
    expect(legend).toHaveTextContent('No data')
    const future = screen.getByRole('button', { name: '1 October 2026: Future date' })
    expect(future).toBeEmptyDOMElement()
    fireEvent.mouseEnter(future)
    expect(screen.getByLabelText('Selected day details')).toHaveTextContent('Future date')
    const negative = screen.getByRole('button', { name: '2 June 2026: -USD 1.00 · Net negative charges' })
    expect(negative).toHaveTextContent('−')
    fireEvent.mouseEnter(negative)
    expect(screen.getByLabelText('Selected day details')).toHaveTextContent('-USD 1.00 · Net negative charges')
    expect(screen.getByRole('combobox', { name: 'Spending year' })).toBeEnabled()
    expect(client.refreshDaily).toBe(billingApi.refreshDaily)
  })
  it('refreshes independently, preserves the old grid and safely retries a lost acknowledgement', async () => {
    const client = { ...billingApi, dailyFeed: vi.fn().mockResolvedValue(feedFixture()),
      refreshDaily: vi.fn().mockRejectedValueOnce(new Error('Acknowledgement lost')).mockResolvedValue({ id: 'refresh', state: 'RUNNING', completed: 1, total: 13, failure: null }),
      generate: vi.fn() }
    render(<BillingCalendar api={client} onAccessError={vi.fn()} />)
    await screen.findByLabelText('Selected day details')
    expect(client.refreshDaily).not.toHaveBeenCalled()
    const grid = screen.getByRole('group', { name: 'Daily costs; use arrow keys to move between days' })
    fireEvent.click(screen.getByRole('button', { name: 'Refresh daily spending' }))
    await screen.findByText('Acknowledgement lost')
    expect(screen.getByRole('group', { name: 'Daily costs; use arrow keys to move between days' })).toBe(grid)
    const running = { ...feedFixture(), refresh: { id: 'refresh', state: 'RUNNING' as const, completed: 1, total: 13, failure: null } }
    client.dailyFeed.mockResolvedValue(running)
    fireEvent.click(screen.getByRole('button', { name: 'Refresh daily spending' }))
    await screen.findByText(/Refreshing daily spending… 1 of 13/)
    expect(client.refreshDaily.mock.calls[0][0]).toBe(client.refreshDaily.mock.calls[1][0])
    expect(screen.getByRole('button', { name: 'Refresh daily spending' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: /^8 June 2026:/ }))
    expect(screen.getByLabelText('Selected day details')).toHaveTextContent('8 June 2026')
    expect(client.generate).not.toHaveBeenCalled()
  })
  it('previews hover without latching selection and restores details when the pointer leaves the grid', async () => {
    const client = { ...billingApi, dailyFeed: vi.fn().mockResolvedValue(feedFixture()) }
    render(<BillingCalendar api={client} onAccessError={vi.fn()} />)
    const selected = await screen.findByRole('button', { name: /^31 August 2026:/ })
    const hovered = screen.getByRole('button', { name: /^8 June 2026:/ })
    fireEvent.mouseEnter(hovered)
    expect(screen.getByLabelText('Selected day details').querySelector('strong')).toHaveTextContent('8 June 2026')
    expect(hovered).toHaveAttribute('aria-pressed', 'false')
    expect(selected).toHaveAttribute('tabindex', '0')
    fireEvent.mouseLeave(screen.getByRole('group', { name: 'Daily costs; use arrow keys to move between days' }))
    expect(screen.getByLabelText('Selected day details').querySelector('strong')).toHaveTextContent('31 August 2026')
    fireEvent.click(hovered)
    fireEvent.mouseLeave(hovered)
    expect(screen.getByLabelText('Selected day details').querySelector('strong')).toHaveTextContent('8 June 2026')
    expect(hovered).toHaveAttribute('aria-pressed', 'true')
  })
  it('shows the full year, states, compact date/cost detail and keyboard/native date navigation without removed context', async () => {
    const client = { ...billingApi, dailyFeed: vi.fn().mockResolvedValue(feedFixture()) }
    render(<BillingCalendar api={client} onAccessError={vi.fn()} />)
    await screen.findByLabelText('Selected day details')
    expect(document.querySelectorAll('.billing-day')).toHaveLength(365)
    expect(document.querySelectorAll('.billing-day[tabindex="0"]')).toHaveLength(1)
    const first = screen.getByRole('button', { name: /^1 January 2026: No recorded data$/ })
    fireEvent.click(first)
    expect(screen.getByLabelText('Selected day details')).toHaveTextContent('No recorded data')
    const day = screen.getByRole('button', { name: /^1 June 2026:/ })
    day.focus(); fireEvent.keyDown(day, { key: 'ArrowRight' })
    expect(document.activeElement).toHaveAttribute('aria-label', expect.stringContaining('8 June 2026'))
    fireEvent.click(first)
    expect(first).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByLabelText('Selected day details')).toHaveTextContent('No recorded data')
    expect(screen.queryByText('Daily amounts and source context')).not.toBeInTheDocument()
    expect(screen.queryByText(/days with records in|Selected analysis:|source records ·/)).not.toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Spending date')).not.toBeInTheDocument()
    expect(document.querySelector('.billing-calendar-ramp')).not.toBeInTheDocument()
    expect(document.querySelectorAll('.billing-calendar-legend [class*="billing-day--level-"]')).toHaveLength(5)
    expect(screen.getByText('Relative spending')).toBeInTheDocument()
    expect(client.dailyFeed).toHaveBeenCalledWith(undefined, expect.any(AbortSignal))
  })
  it('replaces old costs with a leap-year skeleton and ignores delayed stale responses', async () => {
    let finish: (data: DailyFeed) => void = () => {}
    const client = { ...billingApi, dailyFeed: vi.fn().mockResolvedValueOnce(feedFixture()).mockImplementationOnce(() => new Promise(resolve => { finish = resolve })) }
    const view = render(<BillingCalendar key={id} api={client} onAccessError={vi.fn()} />)
    await screen.findByLabelText('Selected day details')
    fireEvent.click(screen.getByLabelText('Spending year'))
    fireEvent.click(screen.getByRole('option', { name: '2024' }))
    expect(document.querySelectorAll('.billing-day')).toHaveLength(0)
    expect(document.querySelectorAll('.billing-skeleton-cell')).toHaveLength(366)
    expect(document.querySelector('.billing-calendar-skeleton')).toHaveAttribute('aria-hidden', 'true')
    expect(document.querySelector('.billing-calendar-body')).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByLabelText('Spending year')).toHaveValue('2024')
    expect(screen.queryByLabelText('Selected day details')).not.toBeInTheDocument()
    const other = '00000000-0000-4000-8000-000000000009'
    client.dailyFeed.mockResolvedValue(feedFixture())
    view.rerender(<BillingCalendar key={other} api={client} onAccessError={vi.fn()} />)
    await screen.findByLabelText('Selected day details')
    finish(feedFixture(2024))
    await waitFor(() => expect(document.querySelectorAll('.billing-day')).toHaveLength(365))
    expect(document.querySelector('.billing-calendar-skeleton')).not.toBeInTheDocument()
  })
  it('keeps layout and controls while loading, replaces the skeleton, and ignores rapid-switch stale data', async () => {
    const pending: { year: number; resolve: (feed: DailyFeed) => void }[] = []
    const client = { ...billingApi, dailyFeed: vi.fn().mockResolvedValueOnce(feedFixture()).mockImplementation((year: number) =>
      new Promise<DailyFeed>(resolve => pending.push({ year, resolve }))), refreshDaily: vi.fn() }
    render(<BillingCalendar api={client} onAccessError={vi.fn()} />)
    await screen.findByLabelText('Selected day details')
    vi.spyOn(document.querySelector('.billing-calendar-body')!, 'getBoundingClientRect').mockReturnValue({ height: 400 } as DOMRect)
    const choose = (year: string) => { fireEvent.click(screen.getByLabelText('Spending year')); fireEvent.click(screen.getByRole('option', { name: year })) }
    choose('2024')
    expect(document.querySelector('.billing-calendar-body')).toHaveStyle({ minHeight: '400px' })
    expect(screen.getByLabelText('Spending year')).toBeEnabled()
    choose('2026')
    pending[0].resolve(feedFixture(2024))
    pending[1].resolve(feedFixture())
    await screen.findByLabelText('Selected day details')
    expect(screen.getByLabelText('Spending year')).toHaveValue('2026')
    expect(document.querySelectorAll('.billing-day')).toHaveLength(365)
    expect(document.querySelector('.billing-calendar-body')).toHaveAttribute('aria-busy', 'false')
    choose('2024')
    expect(document.querySelectorAll('.billing-skeleton-cell')).toHaveLength(366)
    pending[2].resolve(feedFixture(2024))
    await screen.findByLabelText('Selected day details')
    expect(document.querySelectorAll('.billing-day')).toHaveLength(366)
    expect(document.querySelector('.billing-calendar-skeleton')).not.toBeInTheDocument()
    expect(document.querySelector('.billing-calendar-body')).toHaveStyle({ minHeight: '' })
    expect(client.refreshDaily).not.toHaveBeenCalled()
  })
  it('replaces a failed year skeleton with a retry and never restores another year’s costs', async () => {
    const client = { ...billingApi, dailyFeed: vi.fn().mockResolvedValueOnce(feedFixture()).mockRejectedValueOnce(new Error('Year unavailable')).mockResolvedValue(feedFixture(2024)) }
    render(<BillingCalendar api={client} onAccessError={vi.fn()} />)
    await screen.findByLabelText('Selected day details')
    fireEvent.click(screen.getByLabelText('Spending year')); fireEvent.click(screen.getByRole('option', { name: '2024' }))
    await screen.findByText('Year unavailable')
    expect(document.querySelector('.billing-calendar-skeleton')).not.toBeInTheDocument()
    expect(document.querySelectorAll('.billing-day')).toHaveLength(0)
    expect(screen.getByLabelText('Spending year')).toHaveValue('2024')
    fireEvent.click(screen.getByText('Retry daily costs'))
    expect(document.querySelectorAll('.billing-skeleton-cell')).toHaveLength(366)
    await screen.findByLabelText('Selected day details')
    expect(document.querySelectorAll('.billing-day')).toHaveLength(366)
  })
  it('shows an isolated recoverable failure and removes data on access revocation', async () => {
    const onAccessError = vi.fn()
    const client = { ...billingApi, dailyFeed: vi.fn().mockRejectedValueOnce(new Error('Temporarily unavailable')).mockResolvedValueOnce(feedFixture()).mockRejectedValue(new BillingAccessError('Expired')) }
    render(<BillingCalendar api={client} onAccessError={onAccessError} />)
    await screen.findByText('Temporarily unavailable'); expect(onAccessError).not.toHaveBeenCalled()
    fireEvent.click(screen.getByText('Retry daily costs')); await screen.findByLabelText('Selected day details')
    fireEvent.click(screen.getByLabelText('Spending year'))
    fireEvent.click(screen.getByRole('option', { name: '2024' }))
    await waitFor(() => expect(onAccessError).toHaveBeenCalledWith('Expired'))
    expect(document.querySelectorAll('.billing-day')).toHaveLength(0)
    expect(document.querySelector('.billing-calendar-skeleton')).not.toBeInTheDocument()
  })
})
