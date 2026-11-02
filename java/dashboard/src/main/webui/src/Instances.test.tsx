// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { Instances } from './Instances'
import {
  BROKER_REFUSED,
  COPY_CREDENTIALS,
  CREATE_HOUSE,
  EMPTY_HOUSES,
  LABEL_TOO_LONG,
  MISSING_HOUSE,
  OPEN_HOUSE,
  copyText,
} from './instances'

const HOUSE = {
  instanceId: '123e4567-e89b-12d3-a456-426614174000',
  label: 'porch',
  password: 'minted-password-token',
}

let fetchMock: ReturnType<typeof vi.fn>
let listed: Array<{ instanceId: string; label?: string }>

function jsonResponse(body: unknown, status = 200) {
  return Response.json(body, { status })
}

function emptyList() {
  listed = []
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (url === '/api/instances' && init?.method === 'POST') {
      listed = [{ instanceId: HOUSE.instanceId, label: HOUSE.label }]
      return jsonResponse(HOUSE, 201)
    }
    if (typeof url === 'string' && url.endsWith('/open') && init?.method === 'POST') {
      return jsonResponse({ ...HOUSE, password: 'new-password-token' })
    }
    if (url === '/api/instances') {
      return jsonResponse({ instances: listed, csrfToken: 'csrf-1' })
    }
    return new Response(null, { status: 404 })
  })
}

beforeEach(() => {
  fetchMock = vi.fn()
  emptyList()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

it('shows the empty house list', async () => {
  render(<Instances />)
  expect(await screen.findByText(EMPTY_HOUSES)).toBeTruthy()
  expect(screen.getByRole('textbox', { name: 'Name' })).toBeTruthy()
  expect(screen.getByRole('button', { name: CREATE_HOUSE })).toBeTruthy()
  expect(screen.queryByLabelText('House password')).toBeNull()
})

it('creates a house and copies the uuid and password', async () => {
  const writeText = vi.fn().mockResolvedValue(undefined)
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
  render(<Instances />)
  await screen.findByText(EMPTY_HOUSES)
  fireEvent.change(screen.getByRole('textbox', { name: 'Name' }), { target: { value: '  porch ' } })
  fireEvent.click(screen.getByRole('button', { name: CREATE_HOUSE }))

  expect(await screen.findByLabelText('House uuid')).toHaveProperty('value', HOUSE.instanceId)
  expect(screen.getByLabelText('House password')).toHaveProperty('value', HOUSE.password)
  fireEvent.click(screen.getByRole('button', { name: COPY_CREDENTIALS }))
  expect(await screen.findByRole('button', { name: 'Copied' })).toBeTruthy()
  expect(writeText).toHaveBeenCalledWith(copyText(HOUSE))
  expect(screen.getByLabelText('House password')).toHaveProperty('value', HOUSE.password)

  const post = fetchMock.mock.calls.find((call) => call[0] === '/api/instances' && call[1]?.method === 'POST')
  expect(JSON.parse(String(post?.[1].body))).toEqual({ label: 'porch' })
  expect(new Headers(post?.[1].headers).get('X-CSRF-Token')).toBe('csrf-1')
  expect(screen.getByText(HOUSE.instanceId)).toBeTruthy()
  expect(screen.queryByText(/Keycloak/)).toBeNull()
})

it('shows the broker refusal and keeps the list empty', async () => {
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (url === '/api/instances' && init?.method === 'POST') {
      return jsonResponse(
        { type: 'instance', cause: 'broker', detail: 'Keycloak 502 admin refused' },
        502,
      )
    }
    if (url === '/api/instances') {
      return jsonResponse({ instances: [], csrfToken: 'csrf-1' })
    }
    return new Response(null, { status: 404 })
  })
  render(<Instances />)
  await screen.findByText(EMPTY_HOUSES)
  fireEvent.click(screen.getByRole('button', { name: CREATE_HOUSE }))
  expect(await screen.findByRole('alert')).toHaveProperty('textContent', BROKER_REFUSED)
  expect(screen.getByText(EMPTY_HOUSES)).toBeTruthy()
  expect(screen.queryByLabelText('House password')).toBeNull()
  expect(screen.queryByText(/Keycloak/)).toBeNull()
})

it('shows a fixed sentence when the name is too long', async () => {
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (url === '/api/instances' && init?.method === 'POST') {
      return jsonResponse({ type: 'instance', cause: 'label' }, 400)
    }
    if (url === '/api/instances') {
      return jsonResponse({ instances: [], csrfToken: 'csrf-1' })
    }
    return new Response(null, { status: 404 })
  })
  render(<Instances />)
  await screen.findByText(EMPTY_HOUSES)
  fireEvent.change(screen.getByRole('textbox', { name: 'Name' }), { target: { value: 'porch' } })
  fireEvent.click(screen.getByRole('button', { name: CREATE_HOUSE }))
  expect(await screen.findByRole('alert')).toHaveProperty('textContent', LABEL_TOO_LONG)
})

it('opens a house again with a new password', async () => {
  listed = [{ instanceId: HOUSE.instanceId, label: HOUSE.label }]
  render(<Instances />)
  expect(await screen.findByText('porch')).toBeTruthy()
  expect(screen.queryByLabelText('House password')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: OPEN_HOUSE }))
  expect(await screen.findByLabelText('House password')).toHaveProperty('value', 'new-password-token')
  expect(screen.queryByDisplayValue(HOUSE.password)).toBeNull()
  const open = fetchMock.mock.calls.find(
    (call) => String(call[0]).endsWith(`/${HOUSE.instanceId}/open`) && call[1]?.method === 'POST',
  )
  expect(open).toBeTruthy()
  expect(new Headers(open?.[1].headers).get('X-CSRF-Token')).toBe('csrf-1')
})

it('shows a fixed sentence when the opened house is missing', async () => {
  listed = [{ instanceId: HOUSE.instanceId, label: HOUSE.label }]
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (typeof url === 'string' && url.endsWith('/open') && init?.method === 'POST') {
      return jsonResponse({ type: 'instance', cause: 'missing', instanceId: HOUSE.instanceId }, 404)
    }
    if (url === '/api/instances') {
      return jsonResponse({ instances: listed, csrfToken: 'csrf-1' })
    }
    return new Response(null, { status: 404 })
  })
  render(<Instances />)
  fireEvent.click(await screen.findByRole('button', { name: OPEN_HOUSE }))
  expect(await screen.findByRole('alert')).toHaveProperty('textContent', MISSING_HOUSE)
  expect(screen.queryByLabelText('House password')).toBeNull()
})

it('denies an account without portal-admin', async () => {
  fetchMock.mockImplementation(async () => new Response(null, { status: 403 }))
  render(<Instances />)
  expect(await screen.findByRole('heading', { level: 1, name: 'Access denied' })).toBeTruthy()
  expect(screen.queryByRole('button', { name: CREATE_HOUSE })).toBeNull()
})
