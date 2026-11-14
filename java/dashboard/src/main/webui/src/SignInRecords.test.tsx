// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { SignInRecords } from './SignInRecords'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

it('lists a sign-in document and opens the raw record', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () =>
      Response.json({
        signIns: [
          {
            id: '+15555550123',
            phone: '+15555550123',
            acceptedAt: '2026-11-11T16:04:00Z',
          },
        ],
      }),
    ),
  )

  render(<SignInRecords />)

  expect(await screen.findByText('+15555550123')).toBeTruthy()
  expect(screen.getByText('2026-11-11T16:04:00Z')).toBeTruthy()

  fireEvent.click(screen.getByRole('button', { name: 'View document' }))
  await waitFor(() => {
    expect(screen.getByText(/"acceptedAt": "2026-11-11T16:04:00Z"/)).toBeTruthy()
  })
})
