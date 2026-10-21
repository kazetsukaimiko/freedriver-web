import { apiFetch, signInRequired } from './api'

export const PHONES_PATH = '/phones'
export const EMPTY_NUMBERS = 'No phone numbers yet. Create an invite to let someone sign in with a text code.'
export const EMPTY_INVITES = 'No open invites.'
export const ENTER_NAME = 'Enter a name.'

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

export function displayPhone(e164: string) {
  const digits = e164.replace(/\D/g, '').slice(-10)
  if (digits.length !== 10) {
    return e164
  }
  return `(${digits.slice(0, 3)}) ${digits.slice(3, 6)}-${digits.slice(6)}`
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
