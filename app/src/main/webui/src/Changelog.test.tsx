// @vitest-environment happy-dom
import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Changelog } from './Changelog'

const TEXT = `# Changelog

## 2026-09_r52
- feat(changelog): serve the changelog to read-only roles
- a long line that keeps going ${'and going '.repeat(20)}
`

let fetchMock: ReturnType<typeof vi.fn>

function answer(status: number, body: string | null = null) {
  fetchMock.mockImplementation(async () =>
    new Response(body, { status, headers: body === null ? {} : { 'Content-Type': 'text/plain' } }),
  )
}

async function renderPage() {
  render(<Changelog />)
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

describe('Changelog', () => {
  it('shows the changelog text exactly as served, in a card under the Changelog heading', async () => {
    answer(200, TEXT)
    await renderPage()

    expect(screen.getByRole('heading', { level: 1, name: 'Changelog' })).toBeTruthy()
    const text = document.querySelector('.changelog-text')
    expect(text?.textContent).toBe(TEXT)
    expect(text?.closest('section')?.className).toBe('card')
  })

  it('reads GET /api/changelog through apiFetch', async () => {
    answer(200, TEXT)
    await renderPage()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit | undefined]
    expect(url).toBe('/api/changelog')
    expect(new Headers(init?.headers).get('X-Requested-With')).toBe('JavaScript')
  })

  it('shows No changelog yet. in the card on 404', async () => {
    answer(404)
    await renderPage()

    expect(screen.getByRole('heading', { level: 1, name: 'Changelog' })).toBeTruthy()
    expect(screen.getByText('No changelog yet.').closest('section')?.className).toBe('card')
    expect(document.querySelector('.changelog-text')).toBeNull()
  })

  it('shows the Access denied page on 403', async () => {
    const replace = vi.spyOn(window.location, 'replace').mockImplementation(() => {})
    answer(403)
    await renderPage()

    expect(screen.getByRole('heading', { level: 1, name: 'Access denied' })).toBeTruthy()
    expect(screen.queryByRole('heading', { name: 'Changelog' })).toBeNull()
    expect(replace).not.toHaveBeenCalled()
  })

  it.each([499, 401])('sends the page to /login when sign-in is required (%s)', async (status) => {
    const replace = vi.spyOn(window.location, 'replace').mockImplementation(() => {})
    answer(status)
    await renderPage()

    expect(replace).toHaveBeenCalledTimes(1)
    expect(replace).toHaveBeenCalledWith('/login')
    expect(screen.queryByText('No changelog yet.')).toBeNull()
  })
})
