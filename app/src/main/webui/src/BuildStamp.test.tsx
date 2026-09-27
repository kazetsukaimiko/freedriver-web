// @vitest-environment happy-dom
import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { BuildStamp } from './BuildStamp'

const BUILD = '2026-09_r52'

let fetchMock: ReturnType<typeof vi.fn>

/** Answers GET /api/build with BUILD and GET /api/changelog with `changelog` (a status, or 'network' for a failed request). */
function route(changelog: number | 'network') {
  fetchMock.mockImplementation(async (url: string) => {
    if (url === '/api/build') {
      return Response.json({ build: BUILD })
    }
    if (url === '/api/changelog') {
      if (changelog === 'network') {
        throw new TypeError('Failed to fetch')
      }
      return changelog === 200
        ? new Response('# Changelog\n', { status: 200, headers: { 'Content-Type': 'text/plain' } })
        : new Response(null, { status: changelog })
    }
    return new Response(null, { status: 404 })
  })
}

async function renderStamp() {
  render(<BuildStamp changelogHref="/changelog" />)
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })
}

function changelogCalls() {
  return fetchMock.mock.calls.filter(([url]) => url === '/api/changelog')
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

describe('BuildStamp', () => {
  it('links the stamp text to /changelog in the same tab when the probe answers 200', async () => {
    route(200)
    await renderStamp()

    const link = screen.getByRole('link', { name: BUILD })
    expect(link.getAttribute('href')).toBe('/changelog')
    expect(link.getAttribute('target')).toBeNull()
    expect(screen.getByLabelText('Build').textContent).toBe(BUILD)
  })

  it('probes GET /api/changelog once, with X-Requested-With: JavaScript', async () => {
    route(200)
    await renderStamp()

    expect(changelogCalls()).toHaveLength(1)
    const init = changelogCalls()[0][1] as RequestInit | undefined
    expect(new Headers(init?.headers).get('X-Requested-With')).toBe('JavaScript')
  })

  it.each([403, 404, 401, 499, 500, 'network' as const])(
    'stays plain text and does not navigate when the probe answers %s',
    async (answer) => {
      const replace = vi.spyOn(window.location, 'replace').mockImplementation(() => {})
      route(answer)
      await renderStamp()

      expect(screen.getByLabelText('Build').textContent).toBe(BUILD)
      expect(screen.queryByRole('link')).toBeNull()
      expect(changelogCalls()).toHaveLength(1)
      expect(replace).not.toHaveBeenCalled()
    },
  )
})
