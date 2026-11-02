import { apiFetch, signInRequired } from './api'

export const INSTANCES_PATH = '/instances'
export const EMPTY_HOUSES = 'No houses yet.'
export const LABEL_TOO_LONG = 'The name is too long.'
export const BROKER_REFUSED = 'The house could not be saved.'
export const MISSING_HOUSE = 'That house is not in the list.'
export const CREATE_HOUSE = 'Create house'
export const OPEN_HOUSE = 'Open'
export const COPY_CREDENTIALS = 'Copy uuid and password'

const CAUSES: Record<string, string> = {
  label: LABEL_TOO_LONG,
  broker: BROKER_REFUSED,
  missing: MISSING_HOUSE,
}

export type InstanceSummary = {
  instanceId: string
  label?: string | null
}

export type ProvisionedInstance = {
  instanceId: string
  label?: string | null
  password: string
}

export type InstancesPage =
  | { status: 'ok'; instances: InstanceSummary[]; csrfToken: string }
  | { status: 'login' }
  | { status: 'denied' }
  | { status: 'error' }

/** A create or open failure is one of the fixed sentences. A provider body is not one of them. */
export function instanceFailureReason(body: unknown): string {
  if (body && typeof body === 'object') {
    const record = body as Record<string, unknown>
    if (record.type === 'instance' && typeof record.cause === 'string' && Object.prototype.hasOwnProperty.call(CAUSES, record.cause)) {
      return CAUSES[record.cause]
    }
  }
  return BROKER_REFUSED
}

export function copyText(instance: ProvisionedInstance): string {
  return `${instance.instanceId}\n${instance.password}`
}

export async function loadInstancesPage(signal?: AbortSignal): Promise<InstancesPage> {
  const response = await apiFetch('/api/instances', { signal })
  if (signInRequired(response)) {
    return { status: 'login' }
  }
  if (response.status === 403) {
    return { status: 'denied' }
  }
  if (!response.ok) {
    return { status: 'error' }
  }
  const body = (await response.json()) as { instances: InstanceSummary[]; csrfToken: string }
  return { status: 'ok', instances: body.instances ?? [], csrfToken: body.csrfToken ?? '' }
}
