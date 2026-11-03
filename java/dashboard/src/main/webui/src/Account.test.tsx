// @vitest-environment happy-dom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Account } from './Account'
import { SET_OR_CHANGE_PASSWORD, SET_UP_A_PASSKEY, acceptPasswordActionUrl } from './account'

let fetchMock: ReturnType<typeof vi.fn>

function answer(status: number, body: unknown = null) {
  fetchMock.mockImplementation(async () => {
    if (body === null) {
      return new Response(null, { status })
    }
    return Response.json(body, { status })
  })
}

async function renderPage() {
  render(<Account />)
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })
}

async function clickStart(name: string = SET_OR_CHANGE_PASSWORD) {
  fireEvent.click(screen.getByRole('button', { name }))
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })
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

describe('acceptPasswordActionUrl', () => {
  it('keeps only the BFF login hop', () => {
    expect(acceptPasswordActionUrl('/login')).toBe('/login')
    expect(acceptPasswordActionUrl('https://grok.com/connectors-oauth-exchange-code/')).toBeNull()
    expect(acceptPasswordActionUrl('javascript:alert(1)')).toBeNull()
    expect(acceptPasswordActionUrl('https://auth.freedriver.io/realms/freedriver/protocol/openid-connect/auth')).toBeNull()
  })
})

describe('Account', () => {
  it('shows Set or change password in a card under the Account heading', async () => {
    await renderPage()

    expect(screen.getByRole('heading', { level: 1, name: 'Account' })).toBeTruthy()
    expect(screen.getByRole('button', { name: SET_OR_CHANGE_PASSWORD }).closest('section')?.className).toBe('card')
    expect(screen.getByRole('button', { name: SET_UP_A_PASSKEY }).closest('section')?.className).toBe('card')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('reads GET /api/account/password through apiFetch and goes to /login', async () => {
    const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => {})
    answer(200, { url: '/login' })
    await renderPage()
    await clickStart()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit | undefined]
    expect(url).toBe('/api/account/password')
    expect(init?.method ?? 'GET').toMatch(/GET/i)
    expect(new Headers(init?.headers).get('X-Requested-With')).toBe('JavaScript')
    expect(init?.body).toBeUndefined()
    expect(assign).toHaveBeenCalledTimes(1)
    expect(assign).toHaveBeenCalledWith('/login')
  })

  it('does not follow a Grok Bot redirect in the JSON body', async () => {
    const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => {})
    answer(200, { url: 'https://grok.com/connectors-oauth-exchange-code/' })
    await renderPage()
    await clickStart()

    expect(assign).not.toHaveBeenCalled()
    expect(screen.getByText("Couldn't start a password change.")).toBeTruthy()
  })

  it('shows the Access denied page on 403', async () => {
    const replace = vi.spyOn(window.location, 'replace').mockImplementation(() => {})
    answer(403)
    await renderPage()
    await clickStart()

    expect(screen.getByRole('heading', { level: 1, name: 'Access denied' })).toBeTruthy()
    expect(screen.getByText('This account needs a dashboard or portal-admin role to set a password.')).toBeTruthy()
    expect(screen.queryByRole('heading', { name: 'Account' })).toBeNull()
    expect(replace).not.toHaveBeenCalled()
  })

  it.each([499, 401])('sends the page to /login when sign-in is required (%s)', async (status) => {
    const replace = vi.spyOn(window.location, 'replace').mockImplementation(() => {})
    answer(status)
    await renderPage()
    await clickStart()

    expect(replace).toHaveBeenCalledTimes(1)
    expect(replace).toHaveBeenCalledWith('/login')
  })

  it('reads GET /api/account/passkeys through apiFetch and goes to /login', async () => {
    const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => {})
    answer(200, { url: '/login' })
    await renderPage()
    await clickStart(SET_UP_A_PASSKEY)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit | undefined]
    expect(url).toBe('/api/account/passkeys')
    expect(init?.method ?? 'GET').toMatch(/GET/i)
    expect(init?.body).toBeUndefined()
    expect(assign).toHaveBeenCalledWith('/login')
  })

  it('does not follow a Grok Bot redirect for passkeys', async () => {
    const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => {})
    answer(200, { url: 'https://grok.com/connectors-oauth-exchange-code/' })
    await renderPage()
    await clickStart(SET_UP_A_PASSKEY)

    expect(assign).not.toHaveBeenCalled()
    expect(screen.getByText("Couldn't start passkey setup.")).toBeTruthy()
  })

  it('does not call WebAuthn on the dashboard origin', async () => {
    const create = vi.fn()
    Object.defineProperty(globalThis, 'navigator', {
      value: { credentials: { create } },
      configurable: true,
    })
    answer(200, { url: '/login' })
    await renderPage()
    await clickStart(SET_UP_A_PASSKEY)

    expect(create).not.toHaveBeenCalled()
  })
})
