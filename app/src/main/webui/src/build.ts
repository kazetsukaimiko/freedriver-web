const PUBLISHED_BUILD = /^\d{4}-\d{2}_r\d+$/
export const DEMO_BUILD = '2026-08_r184'

export function publishedBuild(raw: unknown): string | null {
  if (typeof raw !== 'string') {
    return null
  }
  const value = raw.trim()
  return PUBLISHED_BUILD.test(value) ? value : null
}

export function demoBuild(search = window.location.search): string | null {
  return new URLSearchParams(search).get('demo') === 'build' ? DEMO_BUILD : null
}
