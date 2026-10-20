// @vitest-environment happy-dom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Dashboard } from './Dashboard'
import { POLL_MS, SIGN_IN_NOTICE_MS, fetchApplianceMap, postApplianceCommand } from './dashboard'

const OFF_COPY = "Remote control is off for now. Your homes will appear here once it's switched on."
const SIGN_IN_COPY = "Couldn't confirm. Check it after you sign in."

const CABIN = {
  instanceId: '550e8400-e29b-41d4-a716-446655440000',
  instanceName: 'Cabin',
  lastUpdated: new Date().toISOString(),
  stale: false,
  timeout: false,
  appliances: [{ applianceName: 'hallway', on: true }],
}

let fetchMock: ReturnType<typeof vi.fn>

function answer(status: number, body?: unknown) {
  fetchMock.mockImplementation(async () =>
    body === undefined ? new Response(null, { status }) : Response.json(body, { status }),
  )
}

/** Answers GET /api/appliances with each status in turn (the last one repeats) and the command POST with `command`. */
function route(mapStatuses: number[], command: 'hang' | number) {
  let polls = 0
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (url === '/api/appliances') {
      const status = mapStatuses[Math.min(polls, mapStatuses.length - 1)]
      polls += 1
      return status === 200 ? Response.json({ instances: [CABIN] }) : new Response(null, { status })
    }
    if (command === 'hang') {
      return new Promise<Response>((_, reject) => {
        init?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
      })
    }
    return new Response(null, { status: command })
  })
}

function requestedWith(call: unknown[]) {
  const init = call[1] as RequestInit | undefined
  return new Headers(init?.headers).get('X-Requested-With')
}

function applianceCalls() {
  return fetchMock.mock.calls.filter(([url]) => url === '/api/appliances').length
}

async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
}

async function renderDashboard() {
  render(<Dashboard search="" />)
  await advance(0)
}

beforeEach(() => {
  vi.useFakeTimers()
  fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('fetchApplianceMap', () => {
  it('reads a 404 as remote control off', async () => {
    answer(404)
    await expect(fetchApplianceMap()).resolves.toEqual({ status: 'off' })
  })
})

describe('Dashboard', () => {
  it('shows the remote-control-off card when the appliance list answers 404', async () => {
    answer(404)
    await renderDashboard()

    expect(screen.getByText(OFF_COPY)).toHaveProperty('className', 'empty-copy')
    expect(screen.getByText(OFF_COPY).closest('section')).toHaveProperty('className', 'card')
    expect(screen.queryByText('Waiting for home.')).toBeNull()
    expect(screen.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeTruthy()
  })

  it('stops polling after a 404', async () => {
    answer(404)
    await renderDashboard()
    expect(applianceCalls()).toBe(1)

    answer(200, { instances: [CABIN] })
    await advance(POLL_MS * 5)

    expect(applianceCalls()).toBe(1)
    expect(screen.getByText(OFF_COPY)).toBeTruthy()
  })

  it('shows No homes yet for an empty list and keeps polling', async () => {
    answer(200, { instances: [] })
    await renderDashboard()

    expect(screen.getByText('No homes yet')).toBeTruthy()
    await advance(POLL_MS)
    expect(applianceCalls()).toBe(2)
  })

  it('shows the appliances of the first home', async () => {
    answer(200, { instances: [CABIN] })
    await renderDashboard()

    expect(screen.getByRole('tab', { name: 'Cabin' })).toBeTruthy()
    expect(screen.getByRole('switch', { name: 'hallway' })).toBeTruthy()
  })

  it('shows Access denied with the dashboard role line on 403', async () => {
    answer(403)
    await renderDashboard()

    expect(screen.getByRole('heading', { name: 'Access denied' })).toBeTruthy()
    expect(screen.getByText('This account needs a dashboard or portal-admin role.')).toBeTruthy()
  })

  it('stops polling after a 403 on the first poll', async () => {
    answer(403)
    await renderDashboard()
    expect(applianceCalls()).toBe(1)

    answer(404)
    await advance(POLL_MS * 5)

    expect(applianceCalls()).toBe(1)
    expect(screen.getByRole('heading', { name: 'Access denied' })).toBeTruthy()
    expect(screen.queryByText(OFF_COPY)).toBeNull()
  })

  it('shows Access denied and stops polling when a switch command answers 403', async () => {
    route([200], 403)
    await renderDashboard()

    fireEvent.click(screen.getByRole('switch', { name: 'hallway' }))
    await advance(0)
    expect(screen.getByRole('heading', { name: 'Access denied' })).toBeTruthy()

    await advance(POLL_MS * 5)
    expect(applianceCalls()).toBe(1)
    expect(screen.getByRole('heading', { name: 'Access denied' })).toBeTruthy()
  })

  it('sends the browser to /login on 401', async () => {
    const replace = vi.spyOn(window.location, 'replace').mockImplementation(() => {})
    answer(401)
    await renderDashboard()

    expect(replace).toHaveBeenCalledWith('/login')
  })

  it('keeps waiting and polling while the API gives no response', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))
    await renderDashboard()

    expect(screen.getByText('Waiting for home.')).toBeTruthy()
    await advance(POLL_MS)
    expect(applianceCalls()).toBe(2)
    expect(screen.getByText('Waiting for home.')).toBeTruthy()
  })
})

describe('sign-in required', () => {
  it('sends X-Requested-With: JavaScript on the appliance list and switch commands', async () => {
    answer(200, { instances: [CABIN] })
    await fetchApplianceMap()
    await postApplianceCommand(CABIN.instanceId, 'hallway', false)

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls.map(requestedWith)).toEqual(['JavaScript', 'JavaScript'])
    expect(new Headers(fetchMock.mock.calls[1][1].headers).get('Content-Type')).toBe('application/json')
  })

  it('reads a 499 as sign-in required', async () => {
    answer(499)
    await expect(fetchApplianceMap()).resolves.toEqual({ status: 'login' })
    await expect(postApplianceCommand(CABIN.instanceId, 'hallway', false)).resolves.toEqual({ status: 'login' })
  })

  it('stops polling and sends the whole page to /login on a 499, with no signed-out card', async () => {
    const replace = vi.spyOn(window.location, 'replace').mockImplementation(() => {})
    answer(499)
    await renderDashboard()

    expect(replace).toHaveBeenCalledTimes(1)
    expect(replace).toHaveBeenCalledWith('/login')
    expect(screen.getByText('Waiting for home.')).toBeTruthy()
    expect(screen.queryByText(/sign/i)).toBeNull()

    await advance(POLL_MS * 5)
    expect(applianceCalls()).toBe(1)
    expect(replace).toHaveBeenCalledTimes(1)
  })

  it('keeps the homes on screen while a later poll sends the page to /login', async () => {
    const replace = vi.spyOn(window.location, 'replace').mockImplementation(() => {})
    route([200, 499], 'hang')
    await renderDashboard()
    expect(replace).not.toHaveBeenCalled()

    await advance(POLL_MS)
    expect(replace).toHaveBeenCalledWith('/login')
    expect(screen.getByRole('switch', { name: 'hallway' })).toBeTruthy()
    expect(screen.queryByText(/sign/i)).toBeNull()

    await advance(POLL_MS * 5)
    expect(applianceCalls()).toBe(2)
  })

  it('shows the unconfirmed note on a pending switch before a poll 499 sends the page to /login', async () => {
    const replace = vi.spyOn(window.location, 'replace').mockImplementation(() => {})
    route([200, 499], 'hang')
    await renderDashboard()

    fireEvent.click(screen.getByRole('switch', { name: 'hallway' }))
    await advance(0)
    expect(screen.getByRole('switch', { name: 'hallway' }).getAttribute('aria-checked')).toBe('mixed')

    await advance(POLL_MS)
    expect(screen.getByText(SIGN_IN_COPY)).toHaveProperty('className', 'switch-error')
    expect(screen.getByRole('switch', { name: 'hallway' })).toHaveProperty('disabled', true)
    expect(replace).not.toHaveBeenCalled()

    await advance(SIGN_IN_NOTICE_MS)
    expect(replace).toHaveBeenCalledTimes(1)
    expect(replace).toHaveBeenCalledWith('/login')
    expect(screen.getByText(SIGN_IN_COPY)).toBeTruthy()
    expect(applianceCalls()).toBe(2)
  })

  it('shows the unconfirmed note when the switch command itself answers 499', async () => {
    const replace = vi.spyOn(window.location, 'replace').mockImplementation(() => {})
    route([200], 499)
    await renderDashboard()

    fireEvent.click(screen.getByRole('switch', { name: 'hallway' }))
    await advance(0)
    expect(screen.getByText(SIGN_IN_COPY)).toBeTruthy()
    expect(replace).not.toHaveBeenCalled()

    await advance(SIGN_IN_NOTICE_MS)
    expect(replace).toHaveBeenCalledWith('/login')
    await advance(POLL_MS * 5)
    expect(applianceCalls()).toBe(1)
  })
})
