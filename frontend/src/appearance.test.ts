import { afterEach, describe, expect, it, vi } from 'vitest'
import { startAppearance } from './appearance'
import appHtml from '../index.html?raw'

let dispose: (() => void) | undefined

function media(initial: 'light' | 'dark' | 'unknown', legacy = false) {
  let current = initial
  const listeners = { light: new Set<() => void>(), dark: new Set<() => void>() }
  vi.stubGlobal('matchMedia', vi.fn((query: string) => {
    const tone = query.includes(': dark') ? 'dark' : 'light'
    const add = (callback: () => void) => listeners[tone].add(callback)
    const remove = (callback: () => void) => listeners[tone].delete(callback)
    return {
      get matches() { return current === tone },
      ...(legacy ? { addListener: add, removeListener: remove } : {
        addEventListener: (_: string, callback: () => void) => add(callback),
        removeEventListener: (_: string, callback: () => void) => remove(callback),
      }),
    }
  }))
  return {
    set(value: typeof initial, notify = true) {
      current = value
      if (notify) [...listeners.dark, ...listeners.light].forEach(callback => callback())
    },
    listeners,
  }
}

afterEach(() => {
  dispose?.()
  dispose = undefined
  document.documentElement.removeAttribute('data-appearance')
  document.documentElement.removeAttribute('style')
  document.head.innerHTML = ''
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

describe('document appearance', () => {
  it('sets Light in the real HTML before loading application code and tolerates an unavailable source', () => {
    const script = appHtml.match(/<script id="appearance-bootstrap">([\s\S]*?)<\/script>/)![1]
    media('light')
    new Function(script)()
    expect(document.documentElement.dataset.appearance).toBe('light')
    vi.stubGlobal('matchMedia', undefined)
    expect(() => new Function(script)()).not.toThrow()
    expect(document.documentElement.dataset.appearance).toBe('light')
  })

  it.each(['light', 'dark'] as const)('starts with the current %s preference before React mounts', tone => {
    media(tone)
    dispose = startAppearance()
    expect(document.documentElement.dataset.appearance).toBe(tone)
    expect(document.documentElement.style.colorScheme).toBe(tone)
  })

  it('updates native metadata from the resolved foundation and preserves DOM, input and focus', () => {
    const source = media('dark')
    document.head.innerHTML = '<meta name="theme-color" content="#171819">'
    document.documentElement.style.setProperty('--page-bg', '#f8f9fb')
    document.body.innerHTML = '<main><textarea aria-label="Draft"></textarea><dialog open><input value="Pending decision"></dialog></main>'
    const main = document.querySelector('main')!
    const draft = document.querySelector('textarea')!
    const decision = document.querySelector('input')!
    draft.value = 'Unsent instructions'
    draft.focus()
    draft.setSelectionRange(2, 8)
    dispose = startAppearance()
    source.set('light')
    expect(document.documentElement.dataset.appearance).toBe('light')
    expect(document.querySelector('meta')?.content).toBe('#f8f9fb')
    expect(document.querySelector('main')).toBe(main)
    expect(document.activeElement).toBe(draft)
    expect(draft.value).toBe('Unsent instructions')
    expect([draft.selectionStart, draft.selectionEnd]).toEqual([2, 8])
    expect(decision.value).toBe('Pending decision')
  })

  it('keeps the latest preference through rapid changes and ignores stale notification payloads', () => {
    const source = media('dark')
    dispose = startAppearance()
    source.set('light')
    source.set('dark')
    source.set('light')
    source.listeners.dark.forEach(callback => callback())
    expect(document.documentElement.dataset.appearance).toBe('light')
  })

  it('uses Dark for unknown preference, then retains the last valid value during unavailability', () => {
    const source = media('unknown')
    dispose = startAppearance()
    expect(document.documentElement.dataset.appearance).toBe('dark')
    source.set('light')
    source.set('unknown')
    expect(document.documentElement.dataset.appearance).toBe('light')
  })

  it.each(['pageshow', 'visibilitychange'])('refreshes after %s without a media change notification', event => {
    const source = media('dark')
    dispose = startAppearance()
    source.set('light', false)
    const target = event === 'pageshow' ? window : document
    target.dispatchEvent(new Event(event))
    expect(document.documentElement.dataset.appearance).toBe('light')
  })

  it('replaces a previous owner and removes all subscriptions on disposal', () => {
    const source = media('dark')
    const first = startAppearance()
    dispose = startAppearance()
    first()
    expect(source.listeners.dark.size).toBe(1)
    expect(source.listeners.light.size).toBe(1)
    dispose()
    source.set('light')
    window.dispatchEvent(new Event('pageshow'))
    document.dispatchEvent(new Event('visibilitychange'))
    expect(source.listeners.dark.size + source.listeners.light.size).toBe(0)
    expect(document.documentElement.dataset.appearance).toBe('dark')
  })

  it('supports legacy WebView media listeners and cleans them up', () => {
    const source = media('dark', true)
    dispose = startAppearance()
    source.set('light')
    expect(document.documentElement.dataset.appearance).toBe('light')
    dispose()
    expect(source.listeners.light.size + source.listeners.dark.size).toBe(0)
  })

  it('does not prevent startup when appearance APIs are unavailable', () => {
    vi.stubGlobal('matchMedia', undefined)
    expect(() => { dispose = startAppearance() }).not.toThrow()
    expect(document.documentElement.dataset.appearance).toBe('dark')
  })
})
