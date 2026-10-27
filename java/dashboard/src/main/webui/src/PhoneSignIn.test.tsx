// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { PhoneSignIn } from './PhoneSignIn'
import { ALREADY_LISTED, EMPTY_INVITES, EMPTY_NUMBERS, ENTER_PHONE, INVITE_FAILED } from './phones'

const LOGIN_LINK = 'https://app.freedriver.io/sign-in#%2B15555550123'

let fetchMock: ReturnType<typeof vi.fn>

function emptyLists() {
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (url === '/api/invites' && init?.method === 'POST') {
      return Response.json({ phone: '***0123', link: LOGIN_LINK })
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

it('opens a modal with the login link and copies it', async () => {
  const writeText = vi.fn().mockResolvedValue(undefined)
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
  render(<PhoneSignIn />)
  await screen.findByText(EMPTY_NUMBERS)
  fireEvent.change(screen.getByRole('textbox', { name: 'Phone number' }), { target: { value: '(555) 555-0123' } })
  fireEvent.click(screen.getByRole('button', { name: 'Create invite' }))

  const dialog = await screen.findByRole('dialog')
  expect(dialog.textContent).not.toMatch(/invitation was sent|text was sent/i)
  const link = screen.getByRole('textbox', { name: 'Login link' })
  expect(link).toHaveProperty('value', LOGIN_LINK)
  expect(String((link as HTMLInputElement).value)).not.toContain('?')
  expect(screen.queryByText(/\/invite\/#/)).toBeNull()

  fireEvent.click(screen.getByRole('button', { name: 'Copy link' }))
  expect(await screen.findByRole('button', { name: 'Copied' })).toBeTruthy()
  expect(writeText).toHaveBeenCalledWith(LOGIN_LINK)

  const post = fetchMock.mock.calls.find((call) => call[0] === '/api/invites' && call[1]?.method === 'POST')
  expect(post).toBeTruthy()
  expect(JSON.parse(String(post?.[1].body))).toEqual({ phone: '+15555550123' })
  expect(new Headers(post?.[1].headers).get('X-CSRF-Token')).toBe('csrf-1')
  const postedUrls = fetchMock.mock.calls.map((call) => String(call[0]))
  expect(postedUrls.some((url) => /twilio|messages/i.test(url))).toBe(false)
})

it('submits the phone number when Enter is pressed', async () => {
  render(<PhoneSignIn />)
  await screen.findByText(EMPTY_NUMBERS)
  const input = screen.getByRole('textbox', { name: 'Phone number' })
  fireEvent.change(input, { target: { value: '5555550123' } })
  fireEvent.keyDown(input, { key: 'Enter' })

  expect(await screen.findByRole('dialog')).toBeTruthy()
  expect(screen.getByRole('textbox', { name: 'Login link' })).toHaveProperty('value', LOGIN_LINK)
  expect(screen.queryByText(/invitation was sent/i)).toBeNull()
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
  expect(screen.queryByRole('dialog')).toBeNull()
  expect(screen.queryByText(/invitation was sent/i)).toBeNull()
})

it('does not show a login link that puts the number in the query', async () => {
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (url === '/api/invites' && init?.method === 'POST') {
      return Response.json({ link: 'https://app.freedriver.io/sign-in?phone=%2B15555550123' })
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
  expect(screen.queryByRole('dialog')).toBeNull()
  expect(screen.queryByDisplayValue(/phone=/)).toBeNull()
})

it('opens the same link from the row until the first sign-in', async () => {
  const mom = 'https://invite.example/sign-in#%2B15555550123'
  fetchMock.mockImplementation(async (url: string) => {
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
            loginLink: mom,
          },
          {
            phone: '+15555550199',
            name: 'Dad',
            role: 'dashboard',
            addedAt: '2026-10-21T12:00:00Z',
            consentedAt: '2026-10-21T12:00:00Z',
            lastSignIn: '2026-10-22T12:00:00Z',
            loginLink: 'https://invite.example/sign-in#%2B15555550199',
          },
        ],
      })
    }
    if (url === '/api/invites') {
      return Response.json({ invites: [], csrfToken: 'csrf-1' })
    }
    return new Response(null, { status: 404 })
  })
  render(<PhoneSignIn />)
  expect(await screen.findAllByRole('button', { name: 'Login link' })).toHaveLength(1)
  fireEvent.click(screen.getByRole('button', { name: 'Login link' }))
  const dialog = await screen.findByRole('dialog')
  expect(dialog.querySelector('input')?.getAttribute('value')).toBe(mom)
  expect(dialog.textContent).not.toMatch(/\?/)
  fireEvent.click(screen.getByRole('button', { name: 'Close' }))
  expect(screen.queryByRole('dialog')).toBeNull()
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
