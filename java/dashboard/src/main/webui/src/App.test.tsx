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
