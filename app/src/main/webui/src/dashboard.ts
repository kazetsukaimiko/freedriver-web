export const COMMAND_WAIT_MS = 5000
export const STALE_AFTER_MS = 20_000
export const POLL_MS = 4000
const DEMO_CONFIRM_MS = 900

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

export type ApplianceMap = {
  lastUpdated: string | null
  stale: boolean
  timeout: boolean
  appliances: Appliance[]
}

export type DeniedReason = 'session' | 'role'

export type CommandResult =
  | { status: 'confirmed'; map: ApplianceMap }
  | { status: 'timeout'; map: ApplianceMap }
  | { status: 'stale'; map: ApplianceMap | null }
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
  { id: 'living-room-lamp', name: 'Living room lamp', on: true },
  { id: 'workshop-heater', name: 'Workshop heater', on: false },
  { id: 'porch-light', name: 'Porch light', on: true },
]

export function demoLastUpdated(staleSeconds = 25): string {
  return new Date(Date.now() - staleSeconds * 1000).toISOString()
}

export function parseApplianceMap(raw: unknown): ApplianceMap {
  if (!raw || typeof raw !== 'object') {
    throw new Error('Invalid appliance map')
  }
  const body = raw as {
    lastUpdated?: string | null
    stale?: boolean
    timeout?: boolean
    appliances?: unknown
  }
  if (!Array.isArray(body.appliances)) {
    throw new Error('Invalid appliance map')
  }
  return {
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
  const body = raw as { id?: unknown; name?: unknown; on?: unknown }
  if (typeof body.name !== 'string' || body.name.length === 0) {
    throw new Error('Invalid appliance')
  }
  if (typeof body.on !== 'boolean') {
    throw new Error('Invalid appliance')
  }
  const id = typeof body.id === 'string' && body.id.length > 0 ? body.id : body.name
  return { id, name: body.name, on: body.on }
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
  id: string,
  on: boolean,
  signal?: AbortSignal,
): Promise<CommandResult> {
  try {
    const response = await fetch(`/api/appliances/${encodeURIComponent(id)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ on }),
      signal,
    })
    if (response.status === 401) {
      return { status: 'denied', reason: 'session' }
    }
    if (response.status === 403) {
      return { status: 'denied', reason: 'role' }
    }
    if (response.status === 409) {
      let map: ApplianceMap | null = null
      try {
        map = parseApplianceMap(await response.json())
      } catch {
        map = null
      }
      return { status: 'stale', map }
    }
    if (!response.ok) {
      return { status: 'error', message: `Command failed (${response.status})` }
    }
    const map = parseApplianceMap(await response.json())
    return { status: map.timeout ? 'timeout' : 'confirmed', map }
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      return { status: 'timeout', map: { lastUpdated: null, stale: false, timeout: true, appliances: [] } }
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
  current: Appliance[],
  id: string,
  on: boolean,
  signal?: AbortSignal,
): Promise<CommandResult> {
  if (mode === 'timeout') {
    await wait(COMMAND_WAIT_MS, signal)
    return {
      status: 'timeout',
      map: { lastUpdated: new Date().toISOString(), stale: false, timeout: true, appliances: current },
    }
  }
  await wait(DEMO_CONFIRM_MS, signal)
  const appliances = current.map((item) => (item.id === id ? { ...item, on } : item))
  return {
    status: 'confirmed',
    map: { lastUpdated: new Date().toISOString(), stale: false, timeout: false, appliances },
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
