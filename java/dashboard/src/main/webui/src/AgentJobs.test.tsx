// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { AgentJobs } from './AgentJobs'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

it('filters the saved job and opens the raw document', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () =>
      Response.json({
        jobs: [
          {
            assignmentId: 'assignment-1',
            jobId: 'job-1',
            deviceId: 'pc-a',
            agentName: 'yuni',
            channel: 'TEXT',
            prompt: 'say hello',
            state: 'QUEUED',
            grokSessionId: null,
            note: 'not yet',
            on_busy: 'supersede',
          },
        ],
      }),
    ),
  )

  render(<AgentJobs />)

  expect(await screen.findByText('say hello')).toBeTruthy()
  expect(screen.getByText('TEXT')).toBeTruthy()
  expect(screen.getByText('QUEUED')).toBeTruthy()
  expect(screen.getByText('not yet')).toBeTruthy()

  fireEvent.click(screen.getByRole('button', { name: 'View document' }))
  await waitFor(() => {
    expect(screen.getByText(/"prompt": "say hello"/)).toBeTruthy()
  })
})
