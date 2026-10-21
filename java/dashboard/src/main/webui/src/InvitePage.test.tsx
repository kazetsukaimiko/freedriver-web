// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import App from './App'
import { InvitePage } from './InvitePage'
import { InviteWelcome } from './InviteWelcome'
import { INVITE_WORDING, WELCOME_KEY } from './invitePhone'

const WORDING = INVITE_WORDING

beforeEach(() => {
  sessionStorage.clear()
  window.history.replaceState({}, '', '/invite')
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  sessionStorage.clear()
  window.history.replaceState({}, '', '/')
})

it('reads the fragment, strips it, and does not take a code from the query', async () => {
  window.history.replaceState({}, '', '/invite?code=from-query#from-fragment')
  const fetchMock = vi.fn(async () => Response.json({ codesSent: 1 }))
  vi.stubGlobal('fetch', fetchMock)
  render(<InvitePage />)

  expect(window.location.hash).toBe('')
  expect(window.location.search).not.toContain('code')
  expect(window.location.href).not.toContain('from-fragment')
  expect(screen.getByRole('heading', { name: 'Set up text sign-in' })).toBeTruthy()
  expect(screen.getByText(WORDING)).toBeTruthy()

  fireEvent.change(screen.getByRole('textbox', { name: 'Phone number' }), { target: { value: '5555550123' } })
  expect(screen.getByText('(555) 555-0123')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))
  await screen.findByRole('heading', { name: 'Enter your code' })

  const calls = fetchMock.mock.calls as unknown as Array<[string, RequestInit]>
  const body = JSON.parse(String(calls[0][1].body))
  expect(body.code).toBe('from-fragment')
  expect(body.phone).toBe('+15555550123')
  expect(body.code).not.toBe('from-query')
  expect(JSON.stringify(body)).not.toContain('from-query')
})

it('shows a listed number and a dead link without calling the admin list', async () => {
  window.history.replaceState({}, '', '/invite#abc')
  vi.stubGlobal('fetch', vi.fn(async () => Response.json({ error: 'already-listed' }, { status: 409 })))
  render(<App />)
  fireEvent.change(screen.getByRole('textbox', { name: 'Phone number' }), { target: { value: '(555) 555-0123' } })
  fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))
  expect(await screen.findByText('This number is already on the list.')).toBeTruthy()
  expect(screen.getByRole('link', { name: 'Go to sign-in' }).getAttribute('href')).toBe('/login')
  expect(screen.queryByRole('link', { name: 'Phone sign-in' })).toBeNull()
})

it('shows the dead-link message when the fragment is missing', () => {
  render(<InvitePage />)
  expect(screen.getByText('This invite link has expired. Ask for a new one.')).toBeTruthy()
})

it('shows a wrong code and the three-code line', async () => {
  window.history.replaceState({}, '', '/invite#abc')
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (String(url).endsWith('/start')) {
        return Response.json({ codesSent: 3 })
      }
      return Response.json({ error: 'invalid-code' }, { status: 400 })
    }),
  )
  render(<InvitePage />)
  fireEvent.change(screen.getByRole('textbox', { name: 'Phone number' }), { target: { value: '5555550123' } })
  fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))
  expect(await screen.findByText('We texted a code to (555) 555-0123.')).toBeTruthy()
  expect(screen.getByText('This invite has sent all three codes. Ask for a new invite.')).toBeTruthy()
  expect(screen.queryByRole('button', { name: 'Send a new code' })).toBeNull()
  fireEvent.change(screen.getByRole('textbox', { name: 'Code' }), { target: { value: '000000' } })
  fireEvent.click(screen.getByRole('button', { name: 'Verify' }))
  expect(await screen.findByText("That code didn't work. Try again or send a new code.")).toBeTruthy()
})

it('shows the setup banner once', () => {
  sessionStorage.setItem(WELCOME_KEY, '1')
  render(<InviteWelcome />)
  expect(screen.getByText("You're set up. Next time, sign in with your phone number.")).toBeTruthy()
  expect(sessionStorage.getItem(WELCOME_KEY)).toBeNull()
})
