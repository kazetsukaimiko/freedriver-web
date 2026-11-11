import { apiFetch, signInRequired } from './api'

export const SIGN_IN_RECORDS_PATH = '/sign-ins'

export const SIGN_IN_RECORDS_DENIED = 'This account needs a portal-admin role.'

export type SignInRecord = {
  id: string
  phone: string
  document: Record<string, unknown>
}

export type SignInRecordsPage =
  | { status: 'login' }
  | { status: 'denied' }
  | { status: 'error' }
  | { status: 'ok'; signIns: SignInRecord[] }

export async function loadSignInRecords(signal?: AbortSignal): Promise<SignInRecordsPage> {
  const response = await apiFetch('/api/sign-ins', { signal, headers: { Accept: 'application/json' } })
  if (signInRequired(response)) {
    return { status: 'login' }
  }
  if (response.status === 403) {
    return { status: 'denied' }
  }
  if (!response.ok) {
    return { status: 'error' }
  }
  const body = (await response.json()) as { signIns?: SignInRecord[] }
  return { status: 'ok', signIns: Array.isArray(body.signIns) ? body.signIns : [] }
}
