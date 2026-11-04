import { apiFetch, signInRequired } from './api'

export const ACCOUNT_PATH = '/account'
export const SET_OR_CHANGE_PASSWORD = 'Set or change password'
export const SET_UP_A_PASSKEY = 'Set up a passkey'
export const ACCOUNT_DENIED = 'This account needs a dashboard or portal-admin role to set a password.'
export const ACCOUNT_ERROR = "Couldn't start a password change."
export const PASSKEY_DENIED = 'This account needs a dashboard or portal-admin role to set up a passkey.'
export const PASSKEY_ERROR = "Couldn't start passkey setup."

export type PasswordActionResult =
  | { status: 'ok'; url: string }
  | { status: 'denied' }
  | { status: 'login' }
  | { status: 'error' }

/** Only the BFF login hop. A Grok Bot redirect or a javascript URL is dropped. */
export function acceptPasswordActionUrl(url: unknown): string | null {
  return url === '/login' ? url : null
}

export function goToPasswordAction(url: string): void {
  window.location.assign(url)
}

/** GET /api/account/password through apiFetch. The body is never a password. */
export async function startPasswordAction(signal?: AbortSignal): Promise<PasswordActionResult> {
  return startAccountAction('/api/account/password', signal)
}

/** GET /api/account/passkeys. WebAuthn runs on Keycloak, not this origin. */
export async function startPasskeyAction(signal?: AbortSignal): Promise<PasswordActionResult> {
  return startAccountAction('/api/account/passkeys', signal)
}

async function startAccountAction(path: string, signal?: AbortSignal): Promise<PasswordActionResult> {
  try {
    const response = await apiFetch(path, { signal })
    if (signInRequired(response)) {
      return { status: 'login' }
    }
    if (response.status === 403) {
      return { status: 'denied' }
    }
    if (response.status !== 200) {
      return { status: 'error' }
    }
    const body = (await response.json()) as { url?: unknown }
    const url = acceptPasswordActionUrl(body.url)
    if (url == null) {
      return { status: 'error' }
    }
    return { status: 'ok', url }
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error
    }
    return { status: 'error' }
  }
}
