export const COMMAND_WAIT_MS = 5000
export const STALE_AFTER_MS = 20_000
export const POLL_MS = 4000
const DEMO_CONFIRM_MS = 900
const DEMO_INSTANCE_ID = '550e8400-e29b-41d4-a716-446655440000'

export type DemoMode = 'live' | 'waiting' | 'empty' | 'unreachable' | 'denied' | 'timeout'

const DEMO_MODES = new Set<string>([
  'live',
  'waiting',
  'empty',
  'unreachable',
  'denied',
  'timeout',
])

export type Appliance = {
  id: string
  name: string
  on: boolean
}

export type Instance = {
  instanceId: string
  instanceName: string
  lastUpdated: string | null
  stale: boolean
  timeout: boolean
  appliances: Appliance[]
}

export type ApplianceMap = {
  instances: Instance[]
}

export type DeniedReason = 'session' | 'role'

export type CommandResult =
  | { status: 'confirmed'; instance: Instance }
  | { status: 'timeout'; instance: Instance }
  | { status: 'stale'; instance: Instance | null }
  | { status: 'denied'; reason: DeniedReason }
  | { status: 'error'; message: string }

export type MapResult =
  | { status: 'ok'; map: ApplianceMap }
  | { status: 'denied'; reason: DeniedReason }
  | { status: 'error'; message: string }

export function demoModeFromSearch(search: string): DemoMode | null {
  const value = new URLSearchParams(search).get('demo')
  if (value && DEMO_MODES.has(value)) {
    return value as DemoMode
  }
  return null
}

export const DEMO_APPLIANCES: Appliance[] = [
  { id: 'hallway', name: 'Hallway', on: true },
  { id: 'kitchen', name: 'Kitchen', on: false },
  { id: 'living-room', name: 'Living room', on: true },
  { id: 'bedroom', name: 'Bedroom', on: false },
  { id: 'garage', name: 'Garage', on: false },
  { id: 'porch', name: 'Porch', on: true },
]

export function demoInstance(stale: boolean, appliances = DEMO_APPLIANCES): Instance {
  return {
    instanceId: DEMO_INSTANCE_ID,
    instanceName: 'Cabin',
    lastUpdated: demoLastUpdated(stale ? 25 : 2),
    stale,
    timeout: false,
    appliances,
  }
}

export function demoLastUpdated(staleSeconds = 25): string {
  return new Date(Date.now() - staleSeconds * 1000).toISOString()
}

export function parseApplianceMap(raw: unknown): ApplianceMap {
  if (!raw || typeof raw !== 'object') {
    throw new Error('Invalid appliance map')
  }
  const body = raw as { instances?: unknown }
  if (!Array.isArray(body.instances)) {
    throw new Error('Invalid appliance map')
  }
  return { instances: body.instances.map(readInstance) }
}

export function parseInstanceView(raw: unknown): Instance {
  return readInstance(raw)
}

function readInstance(raw: unknown): Instance {
  if (!raw || typeof raw !== 'object') {
    throw new Error('Invalid instance')
  }
  const body = raw as {
    instanceId?: unknown
    instanceName?: unknown
    lastUpdated?: string | null
    stale?: boolean
    timeout?: boolean
    appliances?: unknown
  }
  if (typeof body.instanceId !== 'string' || body.instanceId.length === 0) {
    throw new Error('Invalid instance')
  }
  if (typeof body.instanceName !== 'string' || body.instanceName.length === 0) {
    throw new Error('Invalid instance')
  }
  if (!Array.isArray(body.appliances)) {
    throw new Error('Invalid instance')
  }
  return {
    instanceId: body.instanceId,
    instanceName: body.instanceName,
    lastUpdated: typeof body.lastUpdated === 'string' ? body.lastUpdated : null,
    stale: Boolean(body.stale),
    timeout: Boolean(body.timeout),
    appliances: body.appliances.map(readAppliance),
  }
}

function readAppliance(raw: unknown): Appliance {
  if (!raw || typeof raw !== 'object') {
    throw new Error('Invalid appliance')
  }
  const body = raw as { applianceName?: unknown; on?: unknown }
  if (typeof body.applianceName !== 'string' || body.applianceName.length === 0) {
    throw new Error('Invalid appliance')
  }
  if (typeof body.on !== 'boolean') {
    throw new Error('Invalid appliance')
  }
  return { id: body.applianceName, name: body.applianceName, on: body.on }
}

export async function fetchApplianceMap(signal?: AbortSignal): Promise<MapResult> {
  try {
    const response = await fetch('/api/appliances', { signal })
    if (response.status === 401) {
      return { status: 'denied', reason: 'session' }
    }
    if (response.status === 403) {
      return { status: 'denied', reason: 'role' }
    }
    if (!response.ok) {
      return { status: 'error', message: `GET /api/appliances failed (${response.status})` }
    }
    return { status: 'ok', map: parseApplianceMap(await response.json()) }
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error
    }
    return { status: 'error', message: error instanceof Error ? error.message : 'Unknown error' }
  }
}

export async function postApplianceCommand(
  instanceId: string,
  applianceName: string,
  on: boolean,
  signal?: AbortSignal,
): Promise<CommandResult> {
  try {
    const response = await fetch(
      `/api/appliances/${encodeURIComponent(instanceId)}/${encodeURIComponent(applianceName)}`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ on }),
        signal,
      },
    )
    if (response.status === 401) {
      return { status: 'denied', reason: 'session' }
    }
    if (response.status === 403) {
      return { status: 'denied', reason: 'role' }
    }
    if (response.status === 409) {
      let instance: Instance | null = null
      try {
        instance = parseInstanceView(await response.json())
      } catch {
        instance = null
      }
      return { status: 'stale', instance }
    }
    if (!response.ok) {
      return { status: 'error', message: `Command failed (${response.status})` }
    }
    const instance = parseInstanceView(await response.json())
    return { status: instance.timeout ? 'timeout' : 'confirmed', instance }
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      return {
        status: 'timeout',
        instance: {
          instanceId,
          instanceName: '',
          lastUpdated: null,
          stale: false,
          timeout: true,
          appliances: [],
        },
      }
    }
    return { status: 'error', message: error instanceof Error ? error.message : 'Unknown error' }
  }
}

export function wait(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const id = window.setTimeout(() => {
      signal?.removeEventListener('abort', onAbort)
      resolve()
    }, ms)
    const onAbort = () => {
      window.clearTimeout(id)
      reject(new DOMException('Aborted', 'AbortError'))
    }
    if (!signal) {
      return
    }
    if (signal.aborted) {
      onAbort()
      return
    }
    signal.addEventListener('abort', onAbort, { once: true })
  })
}

export async function demoCommand(
  mode: DemoMode,
  current: Instance,
  applianceName: string,
  on: boolean,
  signal?: AbortSignal,
): Promise<CommandResult> {
  if (mode === 'timeout') {
    await wait(COMMAND_WAIT_MS, signal)
    return {
      status: 'timeout',
      instance: { ...current, timeout: true, lastUpdated: new Date().toISOString() },
    }
  }
  await wait(DEMO_CONFIRM_MS, signal)
  const appliances = current.appliances.map((item) => (item.id === applianceName ? { ...item, on } : item))
  return {
    status: 'confirmed',
    instance: {
      ...current,
      lastUpdated: new Date().toISOString(),
      stale: false,
      timeout: false,
      appliances,
    },
  }
}

export function formatLastHeard(iso: string | null, now = Date.now()): string | null {
  if (!iso) {
    return null
  }
  const then = Date.parse(iso)
  if (Number.isNaN(then)) {
    return null
  }
  const seconds = Math.max(0, Math.round((now - then) / 1000))
  if (seconds < 60) {
    return `Last heard ${seconds}s ago`
  }
  const minutes = Math.round(seconds / 60)
  return `Last heard ${minutes}m ago`
}
