// @vitest-environment happy-dom
import { act, cleanup, render, screen } from '@testing-library/react'
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
