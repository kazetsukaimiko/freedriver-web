import { apiFetch, signInRequired } from './api'

export const PHONE_RECORDS_PATH = '/phone-records'

export const PHONE_RECORDS_DENIED = 'This account needs a portal-admin role.'

export type PhoneRecord = {
  id: string
  phone: string
  document: Record<string, unknown>
}

export type PhoneRecordsPage =
  | { status: 'login' }
  | { status: 'denied' }
  | { status: 'error' }
  | { status: 'ok'; phones: PhoneRecord[] }

export async function loadPhoneRecords(signal?: AbortSignal): Promise<PhoneRecordsPage> {
  const response = await apiFetch('/api/phones/records', { signal, headers: { Accept: 'application/json' } })
  if (signInRequired(response)) {
    return { status: 'login' }
  }
  if (response.status === 403) {
    return { status: 'denied' }
  }
  if (!response.ok) {
    return { status: 'error' }
  }
  const body = (await response.json()) as { phones?: PhoneRecord[] }
  return { status: 'ok', phones: Array.isArray(body.phones) ? body.phones : [] }
}
