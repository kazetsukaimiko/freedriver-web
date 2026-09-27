// @vitest-environment happy-dom
import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Dashboard } from './Dashboard'
import { POLL_MS, fetchApplianceMap } from './dashboard'

const OFF_COPY = "Remote control is off for now. Your homes will appear here once it's switched on."

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

  it('shows Access denied on 403', async () => {
    answer(403)
    await renderDashboard()

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
