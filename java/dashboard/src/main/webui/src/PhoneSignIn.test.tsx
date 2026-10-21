// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { PhoneSignIn } from './PhoneSignIn'
import { EMPTY_INVITES, EMPTY_NUMBERS, ENTER_NAME } from './phones'

let fetchMock: ReturnType<typeof vi.fn>

function emptyLists() {
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (url === '/api/invites' && init?.method === 'POST') {
      return Response.json({
        link: 'https://app.freedriver.io/invite/#abc',
        invite: { name: 'Mom' },
      })
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
  expect(screen.getByRole('button', { name: 'Create invite' })).toBeTruthy()
})

it('asks for a name before creating an invite', async () => {
  render(<PhoneSignIn />)
  await screen.findByText(EMPTY_NUMBERS)
  fireEvent.click(screen.getByRole('button', { name: 'Create invite' }))
  fireEvent.click(screen.getByRole('button', { name: 'Create invite link' }))
  expect(screen.getByText(ENTER_NAME)).toBeTruthy()
  const posted = fetchMock.mock.calls.some((call) => call[1]?.method === 'POST')
  expect(posted).toBe(false)
})

it('shows the one-time link after a named invite is created', async () => {
  render(<PhoneSignIn />)
  await screen.findByText(EMPTY_NUMBERS)
  fireEvent.click(screen.getByRole('button', { name: 'Create invite' }))
  fireEvent.change(screen.getByRole('textbox', { name: 'Name' }), { target: { value: 'Mom' } })
  fireEvent.click(screen.getByRole('button', { name: 'Create invite link' }))

  expect(await screen.findByRole('heading', { name: 'Invite link for Mom' })).toBeTruthy()
  expect(screen.getByDisplayValue('https://app.freedriver.io/invite/#abc')).toBeTruthy()
  expect(
    screen.getByText(
      'Send this link to Mom. It works once and expires in 72 hours. This link is shown only now, so copy it before you close this panel.',
    ),
  ).toBeTruthy()

  const post = fetchMock.mock.calls.find((call) => call[0] === '/api/invites' && call[1]?.method === 'POST')
  expect(post).toBeTruthy()
  expect(JSON.parse(String(post?.[1].body))).toEqual({ name: 'Mom' })
  expect(new Headers(post?.[1].headers).get('X-CSRF-Token')).toBe('csrf-1')
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
