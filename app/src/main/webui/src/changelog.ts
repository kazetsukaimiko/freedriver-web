import { apiFetch, signInRequired } from './api'

export const CHANGELOG_PATH = '/changelog'
export const NO_CHANGELOG = 'No changelog yet.'
export const CHANGELOG_ERROR = "Couldn't load the changelog."

export type ChangelogResult =
  | { status: 'ok'; text: string }
  | { status: 'missing' }
  | { status: 'denied' }
  | { status: 'login' }
  | { status: 'error' }

/** GET /api/changelog through apiFetch. Reading the answer never navigates; callers decide what to do. */
export async function fetchChangelog(signal?: AbortSignal): Promise<ChangelogResult> {
  try {
    const response = await apiFetch('/api/changelog', { signal })
    if (response.status === 200) {
      return { status: 'ok', text: await response.text() }
    }
    if (signInRequired(response)) {
      return { status: 'login' }
    }
    if (response.status === 403) {
      return { status: 'denied' }
    }
    if (response.status === 404) {
      return { status: 'missing' }
    }
    return { status: 'error' }
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error
    }
    return { status: 'error' }
  }
}
