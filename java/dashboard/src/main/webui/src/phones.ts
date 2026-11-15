import { apiFetch, signInRequired } from './api'

export const PHONES_PATH = '/phones'
export const EMPTY_NUMBERS = 'No phone numbers yet. Create an invite to let someone sign in with a text code.'
export const ENTER_PHONE = 'Enter a 10-digit US phone number.'
export const ALREADY_LISTED = 'That number is already on the list.'
export const INVITE_FAILED = 'The number could not be added.'

/** Whole-entry US shapes from the phone-or-username router (#155). */
const SHAPE =
  /^(?:[0-9]{10}|[0-9]{3}-[0-9]{3}-[0-9]{4}|\([0-9]{3}\) [0-9]{3}-[0-9]{4}|[0-9]{3}\.[0-9]{3}\.[0-9]{4}|[0-9]{3} [0-9]{3} [0-9]{4}|1-[0-9]{3}-[0-9]{3}-[0-9]{4}|\+1 [0-9]{3} [0-9]{3} [0-9]{4}|\+1[0-9]{10})$/

/** `+1` and ten digits, or null when `raw` is not one of the shapes. */
export function canonicalPhone(raw: string): string | null {
  if (!SHAPE.test(raw)) {
    return null
  }
  const digits = raw.replace(/\D/g, '')
  const national = digits.length === 10 ? digits : digits.length === 11 && digits.startsWith('1') ? digits.slice(1) : ''
  return national.length === 10 ? `+1${national}` : null
}

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

/** The login link from create. The number stays in the fragment, so a query is not a link. */
export function acceptLoginLink(link: unknown): string | null {
  if (typeof link !== 'string' || link.length === 0) {
    return null
  }
  let url: URL
  try {
    url = new URL(link)
  } catch {
    return null
  }
  if (url.username !== '' || url.password !== '' || url.search !== '' || url.pathname !== '/sign-in' || url.hash.length < 2) {
    return null
  }
  return link
}

export type PhoneRow = {
  phone: string
  name: string
  role: string
  addedAt: string
  consentedAt: string | null
  lastSignIn: string | null
  loginLink?: string | null
}

export type PhonePage =
  | { status: 'ok'; phones: PhoneRow[]; csrfToken: string }
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

/** The phone list carries the CSRF token for invite create and phone removal. */
export async function loadPhonePage(signal?: AbortSignal): Promise<PhonePage> {
  const response = await apiFetch('/api/phones', { signal })
  if (signInRequired(response)) {
    return { status: 'login' }
  }
  if (response.status === 403) {
    return { status: 'denied' }
  }
  if (!response.ok) {
    return { status: 'error' }
  }
  const page = (await response.json()) as { phones: PhoneRow[]; csrfToken: string }
  return { status: 'ok', phones: page.phones, csrfToken: page.csrfToken }
}
