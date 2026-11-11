// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { PhoneRecords } from './PhoneRecords'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

it('lists a phone document and opens the raw record', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () =>
      Response.json({
        phones: [
          {
            id: '+15555550123',
            phone: '+15555550123',
            document: { _id: '+15555550123', phone: '+15555550123', name: 'ada', username: 'pending' },
          },
        ],
      }),
    ),
  )

  render(<PhoneRecords />)

  expect(await screen.findByText('+15555550123')).toBeTruthy()
  expect(screen.getByText('ada')).toBeTruthy()
  expect(screen.getByText('pending')).toBeTruthy()

  fireEvent.click(screen.getByRole('button', { name: 'View document' }))
  await waitFor(() => {
    expect(screen.getByText(/"name": "ada"/)).toBeTruthy()
  })
})
