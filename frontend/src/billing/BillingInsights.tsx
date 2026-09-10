import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { billingApi, billingActive, BillingAccessError, type BillingEvidence, type BillingHome, type BillingPage, type SavedAnalysis, type BillingReport, type BillingWork, type Statement } from '../api/billing'
import type { CodexActivity, CodexInteraction } from '../api/codex'
import { CodexActivityTimeline } from '../codex/CodexActivityTimeline'
import { CodexInteractionDrawer } from '../codex/CodexInteractionDrawer'
import { BillingCalendar } from './BillingCalendar'
import { BillingSelect, type BillingSelectOption } from './BillingSelect'

const labels: Record<BillingWork['state'], string> = {
  FETCHING: 'Fetching Azure billing data…', PREPARING: 'Preparing saved evidence…', ANALYZING: 'Analyzing saved billing data…',
  STOPPING: 'Stopping…', COMPLETE: 'Report ready', FACTUAL: 'Factual report ready — AI analysis unavailable',
  FAILED: 'Request failed. Your previous report is unchanged.', STOPPED: 'Request stopped. Your previous report is unchanged.',
  INTERRUPTED: 'Request interrupted. Start a new request to continue.',
}
function workMessage(work: BillingWork) {
  const answerFailures: Record<string, string> = {
    ANSWER_TOO_MANY_REFERENCES: 'The answer included too many evidence references. Please ask again for a shorter answer. Your saved report is unchanged.',
    ANSWER_TOO_MANY_CLAIMS: 'The answer included too many numeric claims. Please ask a more focused question. Your saved report is unchanged.',
    ANSWER_TOO_LONG: 'The answer exceeded the supported length. Please ask again for a shorter answer. Your saved report is unchanged.',
    ANSWER_INVALID: 'The answer could not be validated against the saved evidence or required format. Please try a more focused question. Your saved report is unchanged.',
  }
  return work.kind === 'QUESTION' && work.state === 'FAILED' && work.failure ? answerFailures[work.failure] ?? labels[work.state] : labels[work.state]
}
function completedRange(count: number, now = new Date()) {
  const last = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1))
  const first = new Date(Date.UTC(last.getUTCFullYear(), last.getUTCMonth() - count + 1, 1))
  return { first: first.toISOString().slice(0, 7), last: last.toISOString().slice(0, 7) }
}
export function BillingInsights({ visible = true, api = billingApi }: { visible?: boolean; api?: typeof billingApi }) {
  const [home, setHome] = useState<BillingHome | null>(null)
  const [report, setReport] = useState<BillingReport | null>(null)
  const [selection, setSelection] = useState('latest')
  const selectionRef = useRef(selection); selectionRef.current = selection
  const pendingGeneration = useRef<string | null>(null)
  const [setupOpen, setSetupOpen] = useState(false)
  const returnSelection = useRef('latest')
  const [history, setHistory] = useState<BillingPage<SavedAnalysis>>({ items: [], nextCursor: null })
  const [historyPages, setHistoryPages] = useState(1)
  const [historyLoading, setHistoryLoading] = useState(false)
  const [historyError, setHistoryError] = useState<string | null>(null)
  const [questionHistory, setQuestionHistory] = useState<BillingPage<BillingWork>>({ items: [], nextCursor: null })
  const [questionPages, setQuestionPages] = useState(1)
  const [range, setRange] = useState(() => completedRange(1))
  const [preset, setPreset] = useState('1')
  const [consent, setConsent] = useState(false)
  const [retryConsent, setRetryConsent] = useState(false)
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const [answers, setAnswers] = useState<Record<string, Statement>>({})
  const [activity, setActivity] = useState<{ id: string; rows: CodexActivity[] }>({ id: '', rows: [] })
  const [interaction, setInteraction] = useState<{ work: string; value: CodexInteraction } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [decisionError, setDecisionError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [refresh, setRefresh] = useState(0)
  const reportRef = useRef(report); reportRef.current = report
  const answersRef = useRef(answers); answersRef.current = answers
  const mounted = useRef(true)
  const mutation = useRef(false)
  const requestKey = useRef<{ payload: string; key: string } | null>(null)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])

  useEffect(() => {
    if (!visible) return
    const controller = new AbortController()
    let timer: number | undefined
    const read = async () => {
      try {
        const next = await api.home(controller.signal)
        if (controller.signal.aborted || selectionRef.current !== selection) return
        setHome(next); setError(null)
        const pending = pendingGeneration.current && next.works.find(work => work.id === pendingGeneration.current)
        if (pending && !billingActive(pending)) {
          pendingGeneration.current = null
          if (selection === 'new' && ['COMPLETE', 'FACTUAL'].includes(pending.state)) {
            selectionRef.current = pending.id; setSelection(pending.id); setSetupOpen(false); return
          }
        }
        const reportId = selection === 'new' ? null : selection === 'latest' ? next.reportId : selection
        if (!reportId) { if (selection !== 'new') setReport(null); setQuestionHistory({ items: [], nextCursor: null }) }
        else if (reportRef.current?.id !== reportId) {
          const value = await api.report(reportId, controller.signal)
          if (controller.signal.aborted || selectionRef.current !== selection) return
          if (value.id !== reportId) throw new Error('The returned report did not match this request.')
          setReport(value)
          setRange({ first: value.first, last: value.last }); setPreset('custom')
        }
        const current = next.works.find(billingActive)
        if (current) {
          const [events, decisions] = await Promise.all([api.activity(current.id, controller.signal), api.interactions(current.id, controller.signal)])
          if (controller.signal.aborted || selectionRef.current !== selection) return
          setActivity({ id: current.id, rows: events })
          const pending = decisions.find(item => item.status === 'PENDING')
          setInteraction(pending ? { work: current.id, value: pending } : null)
        } else setInteraction(null)
        const savedQuestions = reportId ? await readPages((before) => api.questions(reportId, before, controller.signal), questionPages) : { items: [], nextCursor: null }
        if (controller.signal.aborted || selectionRef.current !== selection) return
        if (savedQuestions.items.some(work => work.kind !== 'QUESTION' || work.parentReportId !== reportId)) throw new Error('The returned questions did not match this report.')
        setQuestionHistory(savedQuestions)
        const questionWorks = savedQuestions.items.filter(w => w.state === 'COMPLETE' && !answersRef.current[w.id])
        for (const question of questionWorks) {
          const answer = await api.answer(question.id, controller.signal)
          if (controller.signal.aborted || selectionRef.current !== selection) return
          if (answer) setAnswers(values => ({ ...values, [question.id]: answer }))
        }
      } catch (failure) {
        if (!controller.signal.aborted && selectionRef.current === selection) {
          setError(message(failure))
          if (failure instanceof BillingAccessError) { setHome(null); setReport(null); setHistory({ items: [], nextCursor: null }); setQuestionHistory({ items: [], nextCursor: null }); setDrafts({}); setAnswers({}); setInteraction(null); setActivity({ id: '', rows: [] }) }
        }
      } finally {
        if (!controller.signal.aborted) timer = window.setTimeout(() => void read(), 3000)
      }
    }
    void read()
    return () => { controller.abort(); window.clearTimeout(timer) }
  }, [api, visible, refresh, selection, questionPages])

  useEffect(() => {
    if (!visible || !home?.enabled) return
    const controller = new AbortController(); setHistoryLoading(true); setHistoryError(null)
    void readPages(before => api.history(before, controller.signal), historyPages).then(page => {
      if (!controller.signal.aborted) setHistory(page)
    }).catch(failure => {
      if (!controller.signal.aborted) { setHistoryError(message(failure)); if (failure instanceof BillingAccessError) setHistory({ items: [], nextCursor: null }) }
    }).finally(() => { if (!controller.signal.aborted) setHistoryLoading(false) })
    return () => controller.abort()
  }, [api, visible, home?.enabled, home?.reportId, refresh, historyPages])

  const submit = useCallback(async (payload: string, action: (key: string) => Promise<BillingWork>, success?: () => void) => {
    if (mutation.current) return
    mutation.current = true; setBusy(true); setError(null)
    if (requestKey.current?.payload !== payload) requestKey.current = { payload, key: crypto.randomUUID() }
    try {
      const accepted = await action(requestKey.current.key)
      requestKey.current = null
      if (mounted.current) { setHome(value => value ? { ...value, works: [accepted, ...value.works.filter(w => w.id !== accepted.id)] } : value); success?.(); setRefresh(value => value + 1) }
    } catch (failure) { if (mounted.current) setError(message(failure)) }
    finally { mutation.current = false; if (mounted.current) setBusy(false) }
  }, [])
  const current = home?.works.find(billingActive)
  const calendarReport = home?.enabled && report && (selection === 'new' || selection === 'latest' || selection === report.id) && Date.now() < Date.parse(report.expiresAt) ? report : null
  const currentReport = selection !== 'new' ? calendarReport : null
  const question = currentReport ? drafts[currentReport.id] ?? '' : ''
  const disabled = busy || Boolean(current) || home?.enabled !== true
  const generate = (event: FormEvent) => {
    event.preventDefault()
    if (disabled || !consent) return
    void submit(JSON.stringify(['generate', range, consent]), async key => {
      const accepted = await api.generate(range.first, range.last, key, consent)
      pendingGeneration.current = accepted.id
      selectionRef.current = 'new'; setSelection('new'); setQuestionHistory({ items: [], nextCursor: null })
      return accepted
    })
  }
  const ask = (event: FormEvent) => {
    event.preventDefault()
    if (!currentReport || disabled || !question.trim()) return
    const id = currentReport.id
    void submit(JSON.stringify(['ask', id, question]), key => api.ask(id, question, key), () => setDrafts(values => ({ ...values, [id]: '' })))
  }
  const currentQuestion = current?.kind === 'QUESTION' && current.parentReportId === currentReport?.id ? current : null
  const questions = questionHistory.items.filter(w => w.parentReportId === currentReport?.id).toReversed()
  // Keep the accepted question visible before the history poll catches up.
  if (currentQuestion && !questions.some(work => work.id === currentQuestion.id)) questions.push(currentQuestion)
  const progress = current ? <section className="billing-progress" aria-label="Billing request status"><p role="status">{labels[current.state]}</p>
    <button type="button" disabled={busy || current.state === 'STOPPING'} onClick={() => void submit(`stop:${current.id}`, () => api.stop(current.id))}>Stop</button>
    {current.state === 'ANALYZING' || current.state === 'STOPPING' ? <CodexActivityTimeline active operationStatus={interaction ? 'WAITING_FOR_INTERACTION' : 'RUNNING'} reconnecting={Boolean(error)} interaction={interaction?.value ?? null} activity={activity.id === current.id ? activity.rows : []} phase={current.state === 'STOPPING' ? 'stopping' : 'thinking'} /> : null}
  </section> : null
  const choose = (value: string) => {
    if (value === 'new' && disabled) return
    if (value === 'new' && selection !== 'new') returnSelection.current = currentReport?.id ?? selection
    selectionRef.current = value; setSelection(value)
    if (value !== 'new' && value !== report?.id) setReport(null)
    setQuestionHistory({ items: [], nextCursor: null }); setQuestionPages(1)
    setSetupOpen(value === 'new'); setRetryConsent(false); pendingGeneration.current = null; setError(null)
    setConsent(false)
    if (value === 'new') { setRange(completedRange(1)); setPreset('1') }
  }
  const selectedId = selection === 'new' ? 'new' : currentReport?.id ?? (selection === 'latest' ? home?.reportId ?? 'new' : selection)
  const historyOptions: BillingSelectOption[] = history.items.map(item => {
    const label = `${monthLabel(item.first)}${item.first !== item.last ? ` – ${monthLabel(item.last)}` : ''}`
    const repeated = history.items.filter(other => other.first === item.first && other.last === item.last).length > 1
    const collision = history.items.filter(other => other.first === item.first && other.last === item.last && dateTime(other.createdAt) === dateTime(item.createdAt)).length > 1
    const shortId = history.items.some(other => other.id !== item.id && other.id.slice(0, 8) === item.id.slice(0, 8)) ? item.id : item.id.slice(0, 8)
    return { value: item.id, label: `${label}${repeated ? ` · ${dateTime(item.createdAt)}` : ''}${collision ? ` · ${shortId}` : ''}${item.state === 'FACTUAL' ? ' · Factual report' : ''}` }
  })
  if (selectedId === 'new') historyOptions.unshift({ value: 'new', label: setupOpen ? 'New analysis draft' : 'Choose a saved analysis', disabled: true })
  else if (!historyOptions.some(option => option.value === selectedId)) historyOptions.unshift({ value: selectedId, label: currentReport ? currentReport.presentation.period : 'Loading selected analysis…' })
  return <div className="billing-window workspace-themed-scrollbar" hidden={!visible}>
    <div className="billing-reading-column">
      <header><h2>Understand Your Cloud Spending</h2></header>
      {home?.enabled && <BillingCalendar visible={visible} api={api} onAccessError={message => { setHome(null); setReport(null); setError(message); setHistory({ items: [], nextCursor: null }); setQuestionHistory({ items: [], nextCursor: null }) }} />}
      {error && <div className="billing-notice" role="alert"><p>{error}</p><button type="button" onClick={() => setRefresh(value => value + 1)}>Refresh saved status</button></div>}
      {!home && !error && <p role="status">Loading Billing Insights…</p>}
      {home && !home.enabled && <p role="status">Billing Insights is not enabled. Ask your administrator to finish the local Billing workspace configuration.</p>}
      <div className="billing-history-toolbar">
        <div className="billing-history-selection"><label htmlFor="billing-history">Saved analyses</label>
          <BillingSelect id="billing-history" label="Saved analyses" value={selectedId} options={historyOptions} onChange={choose} disabled={busy || !home?.enabled || !visible} reportIcon />
        </div>
        <button className="button--primary" type="button" disabled={disabled} onClick={() => choose('new')}>+ New Analysis</button>
        {currentReport && <p className="billing-history-meta" title={currentReport.createdAt}>Generated {dateTime(currentReport.createdAt)}</p>}
        {history.nextCursor && <button type="button" disabled={historyLoading} onClick={() => setHistoryPages(value => value + 1)}>Load older analyses</button>}
      </div>
      {historyError && <div role="alert"><p>{historyError}</p><button type="button" onClick={() => { setHistoryPages(1); setRefresh(value => value + 1) }}>Refresh saved analyses</button></div>}
      {setupOpen && <button className="billing-back" type="button" aria-label="Back to saved analysis" disabled={busy} onClick={() => choose(returnSelection.current)}><span aria-hidden="true">←</span> Back</button>}
      <form id="billing-period-form" className="billing-period" hidden={!setupOpen || Boolean(currentReport)} onSubmit={generate} aria-busy={busy}>
        <fieldset disabled={busy || home?.enabled !== true}><legend>Billing period</legend>
          <div className="billing-presets">{[1, 3, 6].map(count => <button key={count} type="button" aria-pressed={preset === String(count)} onClick={() => { setPreset(String(count)); setRange(completedRange(count)) }}>Last {count} {count === 1 ? 'month' : 'months'}</button>)}
            <button type="button" aria-pressed={preset === 'custom'} onClick={() => setPreset('custom')}>Custom range</button></div>
          {preset === 'custom' && <div className="billing-month-fields"><label>First month<input type="month" required max={range.last} value={range.first} onChange={e => setRange(value => ({ ...value, first: e.target.value }))} /></label>
            <label>Last month<input type="month" required min={range.first} max={completedRange(1).last} value={range.last} onChange={e => setRange(value => ({ ...value, last: e.target.value }))} /></label></div>}
          <p><strong>{monthLabel(range.first)}{range.first !== range.last ? ` – ${monthLabel(range.last)}` : ''}</strong> · completed months only · compared with the preceding equal-length period when available.</p>
          <label className="billing-consent"><input type="checkbox" checked={consent} onChange={e => setConsent(e.target.checked)} /><span>Allow Codex to read the saved billing evidence and edit files in the configured Billing workspace.</span></label>
        </fieldset>
        <button className="button--primary" disabled={disabled || !consent} type="submit">{busy ? 'Submitting…' : 'Generate insights'}</button>
      </form>
      {current ? !currentQuestion && progress : home?.works[0]?.kind === 'GENERATION' && (home.works[0].id === currentReport?.id || selection === 'new' || !home.reportId && selection === 'latest') && <p role="status" className={home.works[0].state === 'COMPLETE' ? 'billing-sr-only' : undefined}>{labels[home.works[0].state]}</p>}
      {currentReport ? <>
        <Report report={currentReport} api={api} />
        {(currentReport.analysisFailure || currentReport.pdfFailure) && <div className="billing-notice"><p>The saved facts remain available. Retry analysis uses this same snapshot without fetching Azure again.</p>
          <label className="billing-consent"><input type="checkbox" checked={retryConsent} disabled={disabled} onChange={event => setRetryConsent(event.target.checked)} /><span>Allow Codex to read and edit the saved Billing workspace for this retry.</span></label>
          <button type="button" disabled={disabled || !retryConsent} onClick={() => void submit(`retry:${currentReport.id}`, async key => {
            const accepted = await api.retry(currentReport.id, key, retryConsent)
            pendingGeneration.current = accepted.id; selectionRef.current = 'new'; setSelection('new'); setReport(null); setQuestionHistory({ items: [], nextCursor: null })
            return accepted
          })}>Retry saved report</button></div>}
        <section className="billing-questions" aria-label="Report questions">
          {questionHistory.nextCursor && <button type="button" onClick={() => setQuestionPages(value => value + 1)}>Load older questions</button>}
          {questions.map(work => <article key={work.id} className="billing-question"><h4>{work.question}</h4>{answers[work.id] ? <StatementView value={answers[work.id]} /> : work.id !== currentQuestion?.id && <p role={billingActive(work) ? 'status' : undefined}>{workMessage(work)}</p>}</article>)}
          {currentQuestion && progress}
          <form onSubmit={ask} aria-busy={busy}><label htmlFor="billing-question">Your question</label><textarea id="billing-question" maxLength={4000} rows={3} value={question} onChange={e => setDrafts(values => ({ ...values, [currentReport.id]: e.target.value }))} placeholder="Which services contributed most to the change?" />
            <button className="button--primary" disabled={disabled || !question.trim() || !currentReport.analysis} type="submit">Ask Synvo</button>
            {!currentReport.analysis && <p>Retry the saved report to enable contextual questions.</p>}</form>
        </section>
      </> : home && !current && <p className="billing-empty">{selection === 'new' || !home.reportId ? setupOpen ? 'Choose a billing period and generate insights. Saved analyses are kept unchanged.' : 'Click + New Analysis to choose a billing period. Saved analyses are kept unchanged.' : 'Loading selected analysis…'}</p>}
    </div>
    {visible && interaction && <CodexInteractionDrawer interaction={interaction.value} submitting={busy} error={decisionError} onDecide={async (decision, values) => {
      if (mutation.current) return
      mutation.current = true; setBusy(true); setDecisionError(null)
      try {
        await api.decide(interaction.work, interaction.value.interactionId, decision, values)
        if (mounted.current) { setInteraction(current => current?.value.interactionId === interaction.value.interactionId ? null : current); setRefresh(value => value + 1) }
      } catch (failure) { if (mounted.current) setDecisionError(message(failure)) }
      finally { mutation.current = false; if (mounted.current) setBusy(false) }
    }} />}
  </div>
}

async function readPages<T extends { id: string }>(read: (before: string | null) => Promise<BillingPage<T>>, count: number): Promise<BillingPage<T>> {
  const items = new Map<string, T>(); let before: string | null = null
  for (let index = 0; index < count; index++) {
    const page = await read(before)
    for (const item of page.items) items.set(item.id, item)
    if (page.nextCursor !== null && page.nextCursor === before) throw new Error('History could not advance. Refresh saved status to recover.')
    before = page.nextCursor
    if (!before) break
  }
  return { items: [...items.values()], nextCursor: before }
}

function Report({ report, api }: { report: BillingReport; api: typeof billingApi }) {
  const source = report.source, view = report.presentation
  // Use the saved exact delta's sign, never subtraction of rounded display totals.
  const delta = report.facts.delta
  const direction = delta === null ? null : !/[1-9]/.test(delta) ? 'unchanged' : delta.startsWith('-') ? 'decrease' : 'increase'
  const changed = direction === 'decrease' || direction === 'increase'
  const amount = view.change.replace(/^[−+-]/, '').replace(/ \(negative\)$/, '')
  const changeText = changed ? `${amount} ${direction === 'decrease' ? 'less' : 'more'}` : direction === 'unchanged' ? 'No change' : view.change
  const percentText = changed && report.facts.percent !== null
    ? `${!/[1-9]/.test(report.facts.percent) ? '< 0.01%' : view.percent.replace(/^[−+-]/, '')} ${direction === 'decrease' ? 'lower' : 'higher'}`
    : view.percent
  return <article className="billing-report" aria-label="Billing analysis report">
    <header><h3>{view.period}</h3><p>Azure subscription costs · USD · before tax</p>
      <p>Compared with {view.comparisonPeriod}</p>
      <p className="billing-hint">Report record: {dateTime(report.createdAt)} · available until {dateTime(report.expiresAt, true)}</p>
      <a href={api.pdfUrl(report.id)} download>Download report PDF</a></header>
    <div className="billing-metrics"><div><span>Selected-period cost</span><strong>{view.total}</strong></div>
      <div><span>Change from comparison period</span><strong className={`billing-change--${direction ?? 'unavailable'}`}>{changeText}</strong><span className={report.facts.percent !== null ? `billing-change--${direction ?? 'unavailable'}` : undefined}>{percentText}</span><span className="billing-hint">Comparison cost: {view.baseline}</span></div></div>
    <section className="billing-summary"><h4>What your team should know</h4><ul>{view.highlights.map(value => <li key={value}>{value}</li>)}</ul></section>
    <section className="billing-notice"><h4>Coverage and confidence</h4><ul>{view.coverage.map(value => <li key={value}>{value}</li>)}</ul>
      {source.limitations.length > 0 && <ul>{source.limitations.map(value => <li key={value}>{readable(value)}</li>)}</ul>}
      <p>Missing evidence is not zero spend or verified savings.</p></section>
    <Breakdown title="Service breakdown" rows={view.services} />
    <Breakdown title="Monthly costs" rows={view.months} />
    <details><summary>Compare monthly costs and service changes</summary>
      <Breakdown title="Comparison months" rows={view.baselineMonths} /><Breakdown title="Largest service changes" rows={view.serviceChanges} /></details>
    <p className="billing-hint">Amounts are rounded for readability. Exact values remain in the saved evidence. Service capitalization variants are combined without changing the total.</p>
    {report.analysis ? <section><h4>Executive interpretation</h4><StatementView value={report.analysis.executiveSummary} />
      <details><summary>Detailed cost drivers and changes</summary>{report.analysis.costDrivers.map((value, index) => <StatementView key={index} value={value} />)}
        {report.analysis.periodChanges.map((value, index) => <StatementView key={index} value={value} />)}</details></section>
      : <p role="status">AI analysis is unavailable. The figures below were calculated and verified by Synvo from saved Azure evidence.</p>}
    {report.analysis && <section><h4>Cost optimization priorities</h4><p>Investigation candidates, not verified savings. No Azure resources are changed.</p>
      {report.analysis.optimizationPriorities.map((value, index) => <article className="billing-priority" key={index}><h5>Priority {index + 1}</h5>
        <StatementView value={value.evidence} /><p>{value.hypothesis}</p><h5>Next action</h5><p>{value.nextStep}</p>
        <details><summary>Evidence needed and operational risk</summary><dl><div><dt>Evidence needed</dt><dd>{value.missingInputs}</dd></div>
          <div><dt>Risk to manage</dt><dd>{value.risk}</dd></div></dl></details></article>)}</section>}
    <details><summary>Subscription breakdown and attribution</summary><Breakdown title="Azure subscriptions" rows={view.subscriptions} />
      <Breakdown title="Source attribution" rows={view.buckets} /><p>Profile adjustments, excluded charges and unresolved attribution are separate from Azure subscription costs.</p></details>
    <details className="billing-sources"><summary>Sources, versions and invoice reconciliation</summary><dl><div><dt>Report</dt><dd>{report.id}</dd></div>
      <div><dt>Snapshot</dt><dd>{report.snapshotId}</dd></div><div><dt>Calculation</dt><dd>{source.calculationVersion} · {source.mappingVersion}</dd></div></dl>
      <p>Actual cost is assigned to the billing period in which charges were received. Monthly and invoice datasets are not added together.</p>
      <ul>{source.partitions.map(part => <li key={part.dataset}>{monthLabel(part.dataset)} · retrieved {dateTime(part.retrievedAt)}{!part.attributionComplete ? ' · incomplete attribution' : ''}{!part.datesWithinPartition ? ' · date coverage warning' : ''}</li>)}</ul>
      <h4>Invoice reconciliation</h4>{view.reconciliation.length ? <ul>{view.reconciliation.map(item => <li key={item}>{item}</li>)}</ul> : <p>No matching invoice reconciliation is available.</p>}
      <Evidence key={report.id} reportId={report.id} api={api} /></details>
  </article>
}
function Evidence({ reportId, api }: { reportId: string; api: typeof billingApi }) {
  const [offset, setOffset] = useState(0), [rows, setRows] = useState<BillingEvidence[] | null>(null), [error, setError] = useState<string | null>(null), [loading, setLoading] = useState(false)
  const controller = useRef<AbortController | null>(null)
  useEffect(() => () => controller.current?.abort(), [])
  const load = async (next: number) => {
    controller.current?.abort(); const request = new AbortController(); controller.current = request; setLoading(true); setError(null)
    try { const values = await api.evidence(reportId, next, request.signal); if (!request.signal.aborted) { setRows(values); setOffset(next) } }
    catch (failure) { if (!request.signal.aborted) setError(message(failure)) }
    finally { if (!request.signal.aborted) setLoading(false) }
  }
  return <section><h4>Saved source rows</h4>{error && <p role="alert">{error}</p>}{!rows ? <button type="button" disabled={loading} onClick={() => void load(0)}>{loading ? 'Loading…' : 'Inspect evidence'}</button> : <>
    <div className="billing-table-scroll" tabIndex={0} role="region" aria-label="Saved Azure evidence"><table><thead><tr><th scope="col">Reference / date</th><th scope="col">Service</th><th scope="col">Cost</th><th scope="col">Attribution</th></tr></thead><tbody>{rows.map(row => <tr key={row.reference}><td>{row.reference}<br />{row.date}</td><td>{row.service ?? 'Unspecified'}</td><td>{row.displayCost}<details><summary>Exact amount</summary><code>{row.currency} {row.cost}</code></details></td><td>{readable(row.bucket)}</td></tr>)}</tbody></table></div>
    <p>Rows {rows.length ? offset + 1 : 0}–{offset + rows.length}. This page alone is not the complete dataset.</p><div className="billing-presets"><button disabled={loading || offset === 0} type="button" onClick={() => void load(Math.max(0, offset - 50))}>Previous rows</button><button disabled={loading || rows.length < 50} type="button" onClick={() => void load(offset + 50)}>Next rows</button></div></>}</section>
}
function StatementView({ value }: { value: Statement }) { return <div className="billing-statement"><p>{value.text}</p>{value.references.length > 0 && <details><summary>Evidence references</summary><ul>{value.references.map(ref => <li key={ref}><code>{ref}</code></li>)}</ul></details>}</div> }
function Breakdown({ title, rows }: { title: string; rows: import('../api/billing').BillingDisplayRow[] }) { return <section><h4>{title}</h4>{rows.length ? <dl className="billing-breakdown">{rows.map(row => <div key={row.label}><dt>{row.label}</dt><dd>{row.amount}</dd></div>)}</dl> : <p>No supported breakdown is available.</p>}</section> }
function monthLabel(value: string) {
  const month = value.replace('month:', '')
  if (!/^\d{4}-\d{2}$/.test(month)) return value
  return new Date(month + '-01T00:00:00Z').toLocaleDateString('en-SG', { month: 'short', year: 'numeric', timeZone: 'UTC' })
}
function dateTime(value: string, dateOnly = false) { return new Date(value).toLocaleString('en-SG', { day: 'numeric', month: 'short', year: 'numeric', ...(dateOnly ? {} : { hour: '2-digit', minute: '2-digit', timeZoneName: 'short' }), timeZone: 'Asia/Singapore' }) }
function readable(value: string) {
  const labels: Record<string, string> = { SOURCE_FINALITY_NOT_ESTABLISHED: 'Provisional: Azure may still revise these charges.', OPTIMIZATION_REQUIRES_UTILIZATION_AND_COMMITMENT_EVIDENCE: 'Utilization and commitment data are needed before savings can be verified.', BASELINE_UNAVAILABLE: 'A complete comparison period is unavailable.', INVOICE_UNAVAILABLE: 'Invoice evidence is unavailable.', RECONCILIATION_INCOMPLETE: 'Some invoice reconciliation checks are incomplete.' }
  return labels[value] ?? value.replaceAll('_', ' ').toLowerCase()
}
function message(value: unknown) { return value instanceof Error ? value.message : 'Billing is unavailable. Refresh saved status to recover.' }
