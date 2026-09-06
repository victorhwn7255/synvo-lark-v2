type Appearance = 'light' | 'dark'

let disposeCurrent: (() => void) | undefined

/** Update document colors without participating in React or task lifecycles. */
export function startAppearance(): () => void {
  disposeCurrent?.()
  let appearance: Appearance = document.documentElement.dataset.appearance === 'light' ? 'light' : 'dark'
  let disposed = false
  const cleanups: Array<() => void> = []

  const apply = () => {
    const changed = document.documentElement.dataset.appearance !== appearance
    document.documentElement.dataset.appearance = appearance
    document.documentElement.style.colorScheme = appearance
    const meta = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')
    if (meta) {
      const background = getComputedStyle(document.documentElement).getPropertyValue('--page-bg').trim()
      if (background) meta.content = background
    }
    // Existing hover fades must not leave old surfaces behind new text colors.
    // Finish only palette transitions; keep movement and activity animations.
    if (changed) {
      for (const animation of document.getAnimations?.() ?? []) {
        if ('transitionProperty' in animation && typeof animation.transitionProperty === 'string'
          && /^(color|.*-color|box-shadow|fill|stroke)$/.test(animation.transitionProperty)) {
          animation.finish()
        }
      }
    }
  }

  try {
    // Lark desktop exposes its own preference here, including live changes.
    // Plain browsers expose their browser/OS preference through the same source.
    const dark = window.matchMedia('(prefers-color-scheme: dark)')
    const light = window.matchMedia('(prefers-color-scheme: light)')
    const refresh = () => {
      if (disposed) return
      // Read current values, not a possibly stale event's payload.
      try {
        if (dark.matches !== light.matches) appearance = dark.matches ? 'dark' : 'light'
      } catch {
        // A failed source read retains the last valid in-memory appearance.
      }
      apply()
    }
    refresh()
    for (const query of [dark, light]) {
      if (typeof query.addEventListener === 'function') {
        query.addEventListener('change', refresh)
        cleanups.push(() => query.removeEventListener('change', refresh))
      } else {
        query.addListener(refresh)
        cleanups.push(() => query.removeListener(refresh))
      }
    }
    window.addEventListener('pageshow', refresh)
    document.addEventListener('visibilitychange', refresh)
    cleanups.push(() => window.removeEventListener('pageshow', refresh))
    cleanups.push(() => document.removeEventListener('visibilitychange', refresh))
  } catch {
    // Appearance availability must never prevent app startup or authorization.
    apply()
  }

  const dispose = () => {
    if (disposed) return
    disposed = true
    cleanups.forEach(cleanup => cleanup())
    if (disposeCurrent === dispose) disposeCurrent = undefined
  }
  disposeCurrent = dispose
  return dispose
}
