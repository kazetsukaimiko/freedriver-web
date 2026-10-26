import { apiFetch, signInRequired } from './api'

export const PHONES_PATH = '/phones'
export const EMPTY_NUMBERS = 'No phone numbers yet. Create an invite to let someone sign in with a text code.'
export const EMPTY_INVITES = 'No open invites.'
export const ENTER_PHONE = 'Enter a 10-digit US phone number.'
export const ALREADY_LISTED = 'That number is already on the list.'
export const INVITE_FAILED = 'The invitation could not be sent.'
export const INVITE_SENT = 'The invitation was sent.'

const STABLE_REASONS: Record<string, string> = {
  phone: ENTER_PHONE,
  listed: ALREADY_LISTED,
  'already-listed': ALREADY_LISTED,
  unavailable: INVITE_FAILED,
}

/** A create failure is one of the fixed sentences. A provider body is not one of them. */
export function inviteFailureReason(body: unknown): string {
  if (body && typeof body === 'object') {
    const record = body as Record<string, unknown>
    for (const key of ['reason', 'error']) {
      const value = record[key]
      if (typeof value === 'string' && Object.prototype.hasOwnProperty.call(STABLE_REASONS, value)) {
        return STABLE_REASONS[value]
      }
    }
  }
  return INVITE_FAILED
}

export type PhoneRow = {
  phone: string
  name: string
  role: string
  addedAt: string
  consentedAt: string | null
  lastSignIn: string | null
}

export type OpenInvite = {
  id: string
  name: string
  createdAt: string
  expiresAt: string
}

export type PhonePage =
  | { status: 'ok'; phones: PhoneRow[]; invites: OpenInvite[]; csrfToken: string }
  | { status: 'login' }
  | { status: 'denied' }
  | { status: 'error' }

/** Last four digits. The remove call still uses the full number. */
export function displayPhone(e164: string) {
  const digits = e164.replace(/\D/g, '')
  if (digits.length < 4) {
    return '***'
  }
  return `***${digits.slice(-4)}`
}

export function when(iso: string) {
  return new Date(iso).toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  })
}

export async function loadPhonePage(signal?: AbortSignal): Promise<PhonePage> {
  const [phonesResponse, invitesResponse] = await Promise.all([
    apiFetch('/api/phones', { signal }),
    apiFetch('/api/invites', { signal }),
  ])
  if (signInRequired(phonesResponse) || signInRequired(invitesResponse)) {
    return { status: 'login' }
  }
  if (phonesResponse.status === 403 || invitesResponse.status === 403) {
    return { status: 'denied' }
  }
  if (!phonesResponse.ok || !invitesResponse.ok) {
    return { status: 'error' }
  }
  const phones = (await phonesResponse.json()) as { phones: PhoneRow[] }
  const invites = (await invitesResponse.json()) as { invites: OpenInvite[]; csrfToken: string }
  return { status: 'ok', phones: phones.phones, invites: invites.invites, csrfToken: invites.csrfToken }
}
