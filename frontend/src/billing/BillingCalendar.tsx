import { useEffect, useRef, useState } from 'react'
import { billingApi, BillingAccessError, type DailyFeed, type BillingDay } from '../api/billing'
import { BillingSelect } from './BillingSelect'

const fullDate = (date: string) => new Intl.DateTimeFormat('en-SG', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' }).format(new Date(date))
const stateText = (day: BillingDay) => day.state === 'MISSING' ? 'No recorded data' : day.state === 'FUTURE' ? 'Future date'
  : day.state === 'FUTURE_RECORDED' ? 'Future-dated source charge — check source' : day.state === 'ZERO' ? 'Recorded net zero' : day.state === 'NEGATIVE' ? 'Net negative charges' : 'Recorded cost'
const describe = (day: BillingDay) => `${fullDate(day.date)}: ${day.amount?.display ?? stateText(day)}${day.amount ? ` · ${stateText(day)}` : ''}`

function CalendarSkeleton({ year }: { year: number }) {
  const first = Date.UTC(year, 0, 1), offset = new Date(first).getUTCDay()
  const count = (Date.UTC(year + 1, 0, 1) - first) / 86400000
  return <div className="billing-calendar-skeleton" aria-hidden="true">
    <div className="billing-calendar-scroll"><div className="billing-calendar-grid" style={{ gridTemplateColumns: `2rem repeat(${Math.ceil((offset + count) / 7)}, minmax(0, 1fr))` }}>
      {Array.from({ length: 12 }, (_, month) => <span className="billing-calendar-month" key={month}
        style={{ gridColumn: Math.floor((offset + (Date.UTC(year, month, 1) - first) / 86400000) / 7) + 2, gridRow: 1 }}>
        {new Intl.DateTimeFormat('en', { month: 'short', timeZone: 'UTC' }).format(new Date(Date.UTC(year, month, 1)))}</span>)}
      {['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'].map((day, index) => <span className="billing-calendar-weekday" key={day} style={{ gridColumn: 1, gridRow: index + 2 }}>{index % 2 === 1 ? day : ''}</span>)}
      {Array.from({ length: count }, (_, index) => <span key={index} className="billing-skeleton-cell"
        style={{ gridColumn: Math.floor((offset + index) / 7) + 2, gridRow: (offset + index) % 7 + 2 }} />)}
    </div></div>
    <div className="billing-calendar-legend"><span className="billing-skeleton-line" /></div>
    <div className="billing-day-detail"><div className="billing-day-heading"><span className="billing-skeleton-line" /><span className="billing-skeleton-line" /></div></div>
  </div>
}

export function BillingCalendar({ visible = true, api = billingApi, onAccessError }: {
  visible?: boolean; api?: typeof billingApi; onAccessError: (message: string) => void
}) {
  const [year, setYear] = useState<number>()
  const [feed, setFeed] = useState<DailyFeed | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [reload, setReload] = useState(0)
  const [selected, setSelected] = useState('')
  const [hovered, setHovered] = useState('')
  const [pending, setPending] = useState(false)
  const body = useRef<HTMLDivElement>(null)
  const [loadingHeight, setLoadingHeight] = useState<number>()
  const command = useRef<AbortController | null>(null)
  const key = useRef<string | null>(null)
  const data = feed && (year === undefined || feed.calendar.year === year) ? feed.calendar : null
  const loading = !data && !error
  const cells = useRef<(HTMLButtonElement | null)[]>([])
  const access = useRef(onAccessError); access.current = onAccessError
  useEffect(() => {
    if (!visible) return
    const controller = new AbortController()
    let timer: number
    const load = () => void api.dailyFeed(year, controller.signal).then(value => {
      if (controller.signal.aborted) return
      if (year !== undefined && value.calendar.year !== year) throw new Error('Daily data did not match the selected year.')
      if (Date.parse(value.expiresAt) <= Date.now()) throw new BillingAccessError('This billing data has expired.')
      setFeed(value); setError(null)
      const day = value.calendar.days.find(d => d.selectedPeriod && d.amount) ?? value.calendar.days.find(d => d.amount) ?? value.calendar.days[0]
      setSelected(previous => value.calendar.days.some(d => d.date === previous) ? previous : day.date)
      timer = window.setTimeout(load, value.refresh?.state === 'RUNNING' ? 3000 : 30000)
    }).catch(reason => {
      if (controller.signal.aborted) return
      const message = reason instanceof Error ? reason.message : 'Daily spending is unavailable.'
      setError(message)
      if (reason instanceof BillingAccessError) { setFeed(null); access.current(message) }
      else timer = window.setTimeout(load, 30000)
    })
    load()
    return () => { controller.abort(); window.clearTimeout(timer) }
  }, [api, year, reload, visible])
  useEffect(() => () => { command.current?.abort() }, [])
  useEffect(() => {
    if (!feed) return
    let timer: number
    const check = () => {
      const remaining = Date.parse(feed.expiresAt) - Date.now()
      if (remaining <= 0) { setFeed(null); setYear(undefined); setReload(n => n + 1); return }
      timer = window.setTimeout(check, Math.min(remaining, 2147483647))
    }
    check()
    return () => window.clearTimeout(timer)
  }, [feed])
  const refresh = async () => {
    if (command.current || feed?.refresh?.state === 'RUNNING') return
    const controller = new AbortController(); command.current = controller
    key.current ??= crypto.randomUUID()
    setPending(true); setError(null)
    try {
      const run = await api.refreshDaily(key.current, controller.signal)
      if (controller.signal.aborted) return
      key.current = null
      setFeed(previous => previous ? { ...previous, refresh: run } : previous)
      setReload(n => n + 1)
    } catch (reason) {
      if (controller.signal.aborted) return
      const message = reason instanceof Error ? reason.message : 'Daily refresh could not be confirmed. Retry safely.'
      setError(message)
      if (reason instanceof BillingAccessError) { setFeed(null); access.current(message) }
    } finally {
      if (!controller.signal.aborted) { command.current = null; setPending(false) }
    }
  }
  const refreshing = pending || feed?.refresh?.state === 'RUNNING'
  const current = data?.days.find(d => d.date === (hovered || selected))
  const offset = data ? new Date(data.days[0].date).getUTCDay() : 0
  const choose = (day: BillingDay) => { setSelected(day.date) }
  return <section className="billing-calendar" aria-label="Daily Azure Spending">
    <div className="billing-calendar-heading"><div className="billing-calendar-title">
      <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true"><rect x="3" y="5" width="18" height="16" rx="3" /><path d="M7 3v4m10-4v4M3 11h18m-13 4v2m4-3v3m4-1v1" strokeLinecap="round" /></svg>
      <h3>Daily Azure Spending</h3></div><div className="billing-calendar-controls">
      {feed && feed.calendar.years.length > 1 && <div className="billing-calendar-year"><span>Year</span><BillingSelect label="Spending year" compact value={String(year ?? feed.calendar.year)} disabled={!visible}
        options={feed.calendar.years.map(y => ({ value: String(y), label: String(y) }))}
        onChange={value => { if (Number(value) === (year ?? feed.calendar.year)) return; setLoadingHeight(body.current?.getBoundingClientRect().height); setError(null); setSelected(''); setHovered(''); setYear(Number(value)) }} /></div>}
      {feed && feed.calendar.years.length === 1 && <span>{year ?? feed.calendar.year}</span>}
      <button className="billing-daily-refresh" aria-label="Refresh daily spending" title="Fetch missing history and recheck recent months for Azure corrections" disabled={refreshing} onClick={() => void refresh()}>
        <svg className={refreshing ? 'billing-refresh-spinning' : undefined} viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M20 7v5h-5M4 17v-5h5M19 11a7 7 0 0 0-12-5L4 9m1 4a7 7 0 0 0 12 5l3-3" /></svg>
      </button>
      </div>
    </div>
    <div className="billing-daily-freshness" role="status">
      {refreshing ? <span>Refreshing daily spending… {feed?.refresh && `${feed.refresh.completed} of ${feed.refresh.total} months saved`}</span>
        : feed?.retrievedLast ? <span>Last refreshed <time dateTime={feed.retrievedLast}>{new Intl.DateTimeFormat('en-SG', { day: 'numeric', month: 'short', year: 'numeric' }).format(new Date(feed.retrievedLast))}</time></span> : <span>Refresh to load daily spending from Azure.</span>}
    </div>
    {loading && <span className="billing-sr-only" role="status">Loading recorded daily costs{year ? ` for ${year}` : ''}…</span>}
    {error && <div role="alert"><p>{error}</p><button onClick={() => { setError(null); setReload(n => n + 1) }}>Retry daily costs</button></div>}
    <div ref={body} className="billing-calendar-body" aria-busy={loading} style={{ minHeight: loading ? loadingHeight : undefined }}>
    {loading && <CalendarSkeleton year={year ?? new Date().getUTCFullYear()} />}
    {data && <>
      <div className="billing-calendar-scroll" role="region" aria-label="Daily spending calendar, scroll horizontally for more months">
        <div className="billing-calendar-grid" onMouseLeave={() => setHovered('')} style={{ gridTemplateColumns: `2rem repeat(${Math.ceil((offset + data.days.length) / 7)}, minmax(0, 1fr))` }} role="group" aria-label="Daily costs; use arrow keys to move between days">
          {Array.from({ length: 12 }, (_, month) => {
            const day = data.days.findIndex(d => Number(d.date.slice(5, 7)) === month + 1)
            return <span className="billing-calendar-month" key={month} style={{ gridColumn: Math.floor((offset + day) / 7) + 2, gridRow: 1 }}>{new Intl.DateTimeFormat('en', { month: 'short', timeZone: 'UTC' }).format(new Date(data.days[day].date))}</span>
          })}
          {['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'].map((day, index) => <span className="billing-calendar-weekday" key={day} style={{ gridColumn: 1, gridRow: index + 2 }}>{index % 2 === 1 ? day : ''}</span>)}
          {data.days.map((day, index) => <button key={day.date} ref={element => { cells.current[index] = element }}
            className={`billing-day billing-day--${day.state.toLowerCase()} billing-day--level-${day.level}`}
            data-period={day.selectedPeriod} data-selected={day.date === selected} aria-label={describe(day)} aria-pressed={day.date === selected}
            style={{ gridColumn: Math.floor((offset + index) / 7) + 2, gridRow: (offset + index) % 7 + 2 }}
            tabIndex={day.date === selected ? 0 : -1} title={describe(day)} onFocus={() => { setHovered(''); choose(day) }} onMouseEnter={() => setHovered(day.date)} onMouseLeave={() => setHovered('')} onClick={() => choose(day)}
            onKeyDown={event => {
              const delta: Record<string, number> = { ArrowLeft: -7, ArrowRight: 7, ArrowUp: -1, ArrowDown: 1, Home: -index, End: data.days.length - index - 1 }
              if (delta[event.key] === undefined) return
              event.preventDefault(); cells.current[Math.max(0, Math.min(data.days.length - 1, index + delta[event.key]))]?.focus()
            }}>{day.state === 'NEGATIVE' ? '−' : day.state === 'FUTURE_RECORDED' ? '!' : day.state === 'ZERO' ? '0' : ''}</button>)}
        </div>
      </div>
      <details className="billing-legend-disclosure"><summary>Legend</summary>
      <div className="billing-calendar-legend" aria-label="Daily cost color scale">
        <span>Relative spending</span>
        {data.bands.map(band => <span key={band.level}><i className={`billing-swatch billing-day--level-${band.level}`} />{band.label}</span>)}
        {!data.bands.length && <span>No positive daily costs in the saved feed</span>}
        <span><i className="billing-swatch billing-day--zero">0</i>Recorded net zero</span><span><i className="billing-swatch billing-day--missing" />No data</span>
      </div>
      <div className="billing-legend-context">
        <p>Shades show relative recorded spending. Missing records are not zero spending.</p>
        {feed?.observedThrough && <p>Latest recorded charge: {fullDate(feed.observedThrough)}. Later or revised charges may still arrive.</p>}
        {feed?.provisional && <p>Current-period costs are provisional, not finalized invoice amounts.</p>}
        {!!feed?.missingMonths.length && <p>Missing months: {feed.missingMonths.join(', ')}.</p>}
        {!!feed?.staleMonths.length && !refreshing && <p>Refresh available for recent or older data.</p>}
        {feed?.refresh && ['FAILED', 'PARTIAL', 'INTERRUPTED'].includes(feed.refresh.state) && <p>The last refresh did not finish all months. Previously saved records are retained; use Refresh to retry.</p>}
        {feed?.refresh?.failure === 'UNSUPPORTED_PRECISION' && <p>Some Azure amounts exceed the supported precision. Those months remain unavailable; no amounts were rounded.</p>}
      </div></details>
      {current && <div className="billing-day-detail" aria-label="Selected day details">
        <div className="billing-day-heading">
          <strong>{fullDate(current.date)}</strong>
          <span>{current.amount?.display ?? stateText(current)}{current.amount && ` · ${stateText(current)}`}</span>
        </div>
        {current.amount && <dl className="billing-breakdown">{current.services.map((s, i) => <div key={i}><dt>{s.label}</dt><dd>{s.amount.display}</dd></div>)}</dl>}
      </div>}
    </>}
    </div>
  </section>
}
