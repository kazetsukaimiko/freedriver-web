// @vitest-environment happy-dom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import App from './App'

beforeEach(() => {
  vi.useFakeTimers()
  vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 404 })))
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.useRealTimers()
  window.history.replaceState({}, '', '/')
})

it('shows the dashboard at /, where /login returns the browser after sign-in', async () => {
  window.history.replaceState({}, '', '/?splash=0')
  render(<App />)
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })

  expect(screen.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeTruthy()
})

it('shows the changelog page at /changelog inside the portal shell', async () => {
  window.history.replaceState({}, '', '/changelog?splash=0')
  render(<App />)
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })

  expect(screen.getByRole('heading', { level: 1, name: 'Changelog' })).toBeTruthy()
  expect(screen.getByRole('navigation', { name: 'Primary' })).toBeTruthy()
  expect(screen.getByText('No changelog yet.')).toBeTruthy()
})

it('opens /changelog in the same tab from the build stamp link', async () => {
  window.history.replaceState({}, '', '/?splash=0&demo=build')
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) =>
      url === '/api/changelog' ? new Response('# Changelog\n', { status: 200 }) : new Response(null, { status: 404 }),
    ),
  )
  render(<App />)
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })

  fireEvent.click(screen.getByRole('link', { name: '2026-08_r45' }))
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })

  expect(window.location.pathname).toBe('/changelog')
  expect(screen.getByRole('heading', { level: 1, name: 'Changelog' })).toBeTruthy()
})

it('keeps Sign out at the end of the header on the dashboard, changelog, denied, and unknown pages', async () => {
  for (const [href, heading] of [
    ['/?splash=0', 'Dashboard'],
    ['/changelog?splash=0', 'Changelog'],
    ['/?splash=0&demo=denied', 'Access denied'],
    ['/no-such-page?splash=0', '404'],
  ] as const) {
    window.history.replaceState({}, '', href)
    render(<App />)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(screen.getByRole('heading', { level: 1, name: heading })).toBeTruthy()
    const signOut = screen.getByRole('link', { name: 'Sign out' })
    expect(signOut.getAttribute('href')).toBe('/logout')
    expect(document.querySelector('header.nav')?.contains(signOut)).toBe(true)
    cleanup()
  }
})

it('one tap shows the loader and leaves for /logout with no confirm step', async () => {
  window.history.replaceState({}, '', '/?splash=0')
  render(<App />)
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })

  fireEvent.click(screen.getByRole('link', { name: 'Sign out' }))
  const signOut = screen.getByRole('link', { name: 'Sign out' })
  expect(signOut.getAttribute('aria-busy')).toBe('true')
  expect(signOut.querySelector('img')?.getAttribute('src')).toBe('/assets/freedriver/pages/freedriver-loader.svg')
  expect(screen.queryByRole('dialog')).toBeNull()

  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })
  expect(window.location.pathname).toBe('/logout')
})
