// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { PhoneSignIn } from './PhoneSignIn'
import { ALREADY_LISTED, EMPTY_INVITES, EMPTY_NUMBERS, ENTER_PHONE, INVITE_FAILED, INVITE_SENT } from './phones'

let fetchMock: ReturnType<typeof vi.fn>

function emptyLists() {
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (url === '/api/invites' && init?.method === 'POST') {
      return Response.json({ link: 'https://app.freedriver.io/invite/#abc' })
    }
    if (url === '/api/phones') {
      return Response.json({ phones: [] })
    }
    if (url === '/api/invites') {
      return Response.json({ invites: [], csrfToken: 'csrf-1' })
    }
    return new Response(null, { status: 404 })
  })
}

beforeEach(() => {
  fetchMock = vi.fn()
  emptyLists()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

it('shows the empty phone and invite copy', async () => {
  render(<PhoneSignIn />)
  expect(await screen.findByText(EMPTY_NUMBERS)).toBeTruthy()
  expect(screen.getByText(EMPTY_INVITES)).toBeTruthy()
  expect(screen.getByRole('textbox', { name: 'Phone number' })).toBeTruthy()
  expect(screen.queryByRole('textbox', { name: 'Name' })).toBeNull()
  expect(screen.getByRole('button', { name: 'Create invite' })).toBeTruthy()
})

it('asks for a phone number before creating an invite', async () => {
  render(<PhoneSignIn />)
  await screen.findByText(EMPTY_NUMBERS)
  fireEvent.click(screen.getByRole('button', { name: 'Create invite' }))
  expect(screen.getByRole('alert').textContent).toBe(ENTER_PHONE)
  const posted = fetchMock.mock.calls.some((call) => call[1]?.method === 'POST')
  expect(posted).toBe(false)
})

it('submits the phone number from the button', async () => {
  render(<PhoneSignIn />)
  await screen.findByText(EMPTY_NUMBERS)
  fireEvent.change(screen.getByRole('textbox', { name: 'Phone number' }), { target: { value: '(555) 555-0123' } })
  fireEvent.click(screen.getByRole('button', { name: 'Create invite' }))

  expect(await screen.findByRole('status')).toHaveProperty('textContent', INVITE_SENT)
  expect(screen.queryByDisplayValue('https://app.freedriver.io/invite/#abc')).toBeNull()
  expect(screen.queryByText(/\/invite\/#/)).toBeNull()

  const post = fetchMock.mock.calls.find((call) => call[0] === '/api/invites' && call[1]?.method === 'POST')
  expect(post).toBeTruthy()
  expect(JSON.parse(String(post?.[1].body))).toEqual({ phone: '+15555550123' })
  expect(new Headers(post?.[1].headers).get('X-CSRF-Token')).toBe('csrf-1')
})

it('submits the phone number when Enter is pressed', async () => {
  render(<PhoneSignIn />)
  await screen.findByText(EMPTY_NUMBERS)
  const input = screen.getByRole('textbox', { name: 'Phone number' })
  fireEvent.change(input, { target: { value: '5555550123' } })
  fireEvent.keyDown(input, { key: 'Enter' })

  expect(await screen.findByRole('status')).toHaveProperty('textContent', INVITE_SENT)
  const posts = fetchMock.mock.calls.filter((call) => call[0] === '/api/invites' && call[1]?.method === 'POST')
  expect(posts).toHaveLength(1)
  expect(JSON.parse(String(posts[0][1].body))).toEqual({ phone: '+15555550123' })
})

it('refuses a number already on the list with a stable reason', async () => {
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (url === '/api/invites' && init?.method === 'POST') {
      return Response.json(
        { reason: 'listed', message: 'Twilio 21211 Keycloak user already exists' },
        { status: 409 },
      )
    }
    if (url === '/api/phones') {
      return Response.json({ phones: [] })
    }
    if (url === '/api/invites') {
      return Response.json({ invites: [], csrfToken: 'csrf-1' })
    }
    return new Response(null, { status: 404 })
  })
  render(<PhoneSignIn />)
  await screen.findByText(EMPTY_NUMBERS)
  fireEvent.change(screen.getByRole('textbox', { name: 'Phone number' }), { target: { value: '+15555550123' } })
  fireEvent.click(screen.getByRole('button', { name: 'Create invite' }))

  expect(await screen.findByRole('alert')).toHaveProperty('textContent', ALREADY_LISTED)
  expect(screen.queryByText(/Twilio/)).toBeNull()
  expect(screen.queryByText(/Keycloak/)).toBeNull()
  expect(screen.queryByText(INVITE_SENT)).toBeNull()
})

it('hides a provider body when create fails', async () => {
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (url === '/api/invites' && init?.method === 'POST') {
      return Response.json(
        { error: 'Twilio 21211', detail: 'Keycloak grant failed', link: 'https://app.freedriver.io/invite/#abc' },
        { status: 502 },
      )
    }
    if (url === '/api/phones') {
      return Response.json({ phones: [] })
    }
    if (url === '/api/invites') {
      return Response.json({ invites: [], csrfToken: 'csrf-1' })
    }
    return new Response(null, { status: 404 })
  })
  render(<PhoneSignIn />)
  await screen.findByText(EMPTY_NUMBERS)
  fireEvent.change(screen.getByRole('textbox', { name: 'Phone number' }), { target: { value: '5555550123' } })
  fireEvent.click(screen.getByRole('button', { name: 'Create invite' }))

  expect(await screen.findByRole('alert')).toHaveProperty('textContent', INVITE_FAILED)
  expect(screen.queryByText(/Twilio/)).toBeNull()
  expect(screen.queryByText(/Keycloak/)).toBeNull()
  expect(screen.queryByText(/\/invite\/#/)).toBeNull()
})

it('shows the masked number and remove still sends the full number', async () => {
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (url === '/api/phones/remove' && init?.method === 'POST') {
      return new Response(null, { status: 204 })
    }
    if (url === '/api/phones') {
      return Response.json({
        phones: [
          {
            phone: '+15555550123',
            name: 'Mom',
            role: 'dashboard',
            addedAt: '2026-10-20T12:00:00Z',
            consentedAt: null,
            lastSignIn: null,
          },
        ],
      })
    }
    if (url === '/api/invites') {
      return Response.json({ invites: [], csrfToken: 'csrf-1' })
    }
    return new Response(null, { status: 404 })
  })
  vi.stubGlobal('confirm', () => true)
  render(<PhoneSignIn />)
  expect(await screen.findByText('***0123')).toBeTruthy()
  expect(screen.queryByText('(555) 555-0123')).toBeNull()
  expect(screen.queryByText('+15555550123')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Remove' }))
  const post = fetchMock.mock.calls.find((call) => call[0] === '/api/phones/remove')
  expect(JSON.parse(String(post?.[1].body))).toEqual({ phone: '+15555550123' })
})

it('keeps an invite when the cancel confirmation is dismissed', async () => {
  fetchMock.mockImplementation(async (url: string) => {
    if (url === '/api/phones') {
      return Response.json({ phones: [] })
    }
    if (url === '/api/invites') {
      return Response.json({
        invites: [
          {
            id: 'inv-1',
            name: 'Mom',
            createdAt: '2026-10-20T12:00:00Z',
            expiresAt: '2026-10-23T12:00:00Z',
          },
        ],
        csrfToken: 'csrf-1',
      })
    }
    return new Response(null, { status: 404 })
  })
  const confirm = vi.fn(() => false)
  vi.stubGlobal('confirm', confirm)
  render(<PhoneSignIn />)
  fireEvent.click(await screen.findByRole('button', { name: 'Cancel invite' }))
  expect(confirm).toHaveBeenCalledWith("Cancel Mom's invite? The link will stop working.")
  const posted = fetchMock.mock.calls.some((call) => call[1]?.method === 'POST')
  expect(posted).toBe(false)
})
