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

it('shows a flat left nav and sends a portal admin to Phones/Accounts', async () => {
  window.history.replaceState({}, '', '/?splash=0')
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) =>
      url === '/api/invites'
        ? Response.json({ invites: [], csrfToken: 'csrf-1' })
        : new Response(null, { status: 404 }),
    ),
  )
  render(<App />)
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })

  expect(screen.queryByRole('button', { name: /Dashboard|Menu|Home/ })).toBeNull()
  const phone = screen.getByRole('link', { name: 'Phones/Accounts' })
  expect(phone.getAttribute('href')).toBe('/phones?splash=0')
  expect(screen.getByRole('link', { name: 'Instances' }).getAttribute('href')).toBe('/instances?splash=0')
  expect(screen.getByRole('link', { name: 'Home/Appliances' }).getAttribute('href')).toBe('/?splash=0')
  expect(screen.getByRole('link', { name: 'Account' }).getAttribute('href')).toBe('/account?splash=0')
  expect(screen.queryByRole('link', { name: 'Dispatcher MCP' })).toBeNull()
  expect(screen.getByRole('link', { name: 'Agent jobs' }).getAttribute('href')).toBe('/agent-jobs?splash=0')
  expect(screen.getByRole('link', { name: 'Sign-ins' }).getAttribute('href')).toBe('/sign-ins?splash=0')

  fireEvent.click(phone)
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })

  expect(window.location.pathname).toBe('/phones')
  expect(screen.getByRole('heading', { level: 1, name: 'Phone sign-in' })).toBeTruthy()
  fireEvent.click(screen.getByRole('link', { name: 'Home/Appliances' }))
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })
  expect(window.location.pathname).toBe('/')
  expect(screen.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeTruthy()
})

it('keeps Phones/Accounts and Instances out of the nav without portal-admin', async () => {
  window.history.replaceState({}, '', '/changelog?splash=0')
  render(<App />)
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })

  expect(screen.queryByRole('link', { name: 'Phones/Accounts' })).toBeNull()
  expect(screen.queryByRole('link', { name: 'Agent jobs' })).toBeNull()
  expect(screen.queryByRole('link', { name: 'Sign-ins' })).toBeNull()
  expect(screen.queryByRole('link', { name: 'Instances' })).toBeNull()
  expect(screen.queryByRole('link', { name: 'Dispatcher MCP' })).toBeNull()
  expect(screen.getByRole('link', { name: 'Home/Appliances' })).toBeTruthy()
  expect(screen.getByRole('link', { name: 'Account' })).toBeTruthy()
  fireEvent.click(screen.getByRole('link', { name: 'Home/Appliances' }))
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })
  expect(window.location.pathname).toBe('/')
  expect(screen.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeTruthy()
})

it('opens Instances beside Phones/Accounts for a portal admin', async () => {
  window.history.replaceState({}, '', '/?splash=0')
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) =>
      url === '/api/invites'
        ? Response.json({ invites: [], csrfToken: 'csrf-1' })
        : url === '/api/instances'
          ? Response.json({ instances: [], csrfToken: 'csrf-1' })
          : new Response(null, { status: 404 }),
    ),
  )
  render(<App />)
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })

  const instances = screen.getByRole('link', { name: 'Instances' })
  expect(instances.getAttribute('href')).toBe('/instances?splash=0')
  fireEvent.click(instances)
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })

  expect(window.location.pathname).toBe('/instances')
  expect(screen.getByRole('heading', { level: 1, name: 'Instances' })).toBeTruthy()
})

it('lists and gets dispatcher jobs for an account with access', async () => {
  const jobId = '550e8400-e29b-41d4-a716-446655440000'
  window.history.replaceState({}, '', '/?splash=0')
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url === '/api/dispatcher/jobs') {
        return Response.json([
          {
            jobId,
            dispatcherSubject: 'grok',
            channel: 'TEXT',
            prompt: 'say hello',
            assignments: [{ assignmentId: 'a1', jobId, deviceId: 'pc', agentName: 'yuni', channel: 'TEXT', prompt: 'say hello', state: 'SUCCEEDED' }],
          },
        ])
      }
      if (url === `/api/dispatcher/jobs/${jobId}`) {
        return Response.json({
          jobId,
          dispatcherSubject: 'grok',
          channel: 'TEXT',
          prompt: 'say hello',
          assignments: [{ assignmentId: 'a1', jobId, deviceId: 'pc', agentName: 'yuni', channel: 'TEXT', prompt: 'say hello', state: 'SUCCEEDED', note: 'done' }],
        })
      }
      return new Response(null, { status: 404 })
    }),
  )
  render(<App />)
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })

  const dispatcher = screen.getByRole('link', { name: 'Dispatcher MCP' })
  expect(dispatcher.getAttribute('href')).toBe('/dispatcher?splash=0')
  fireEvent.click(dispatcher)
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })
  expect(window.location.pathname).toBe('/dispatcher')
  expect(screen.getByRole('heading', { level: 1, name: 'Dispatcher MCP' })).toBeTruthy()
  fireEvent.click(screen.getByRole('link', { name: 'say hello' }))
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })
  expect(window.location.pathname).toBe(`/dispatcher/${jobId}`)
  expect(screen.getByRole('heading', { level: 2, name: 'say hello' })).toBeTruthy()
  expect(screen.getByText(/pc yuni SUCCEEDED/)).toBeTruthy()
})

it('shows the dashboard at /, where /login returns the browser after sign-in', async () => {
  window.history.replaceState({}, '', '/?splash=0')
  render(<App />)
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })

  expect(screen.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeTruthy()
})

it('shows the account page at /account for every signed-in nav', async () => {
  window.history.replaceState({}, '', '/account?splash=0')
  render(<App />)
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
  })

  expect(screen.getByRole('heading', { level: 1, name: 'Account' })).toBeTruthy()
  expect(screen.getByRole('button', { name: 'Set or change password' })).toBeTruthy()
  expect(screen.getByRole('navigation', { name: 'Primary' })).toBeTruthy()
  expect(screen.getByRole('link', { name: 'Account' }).getAttribute('aria-current')).toBe('page')
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
    ['/account?splash=0', 'Account'],
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

it('docks the splash onto the nav brand box, not the nav padding', async () => {
  const root = document.documentElement
  let top = 116.640625
  let left = 16
  const spy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    if (this.classList.contains('brand') && this.closest('.nav-brand')) {
      return new DOMRect(left, top, 137.265625, 24)
    }
    return new DOMRect(0, 0, 0, 0)
  })
  try {
    window.history.replaceState({}, '', '/?splash=1')
    sessionStorage.removeItem('freedriver.splash.seen')
    render(<App />)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })

    expect(root.style.getPropertyValue('--brand-top')).toBe('116.640625px')
    expect(root.style.getPropertyValue('--brand-left')).toBe('16px')
    expect(root.style.getPropertyValue('--brand-size')).toBe('')

    top = 80.5
    left = 21.59375
    window.dispatchEvent(new Event('resize'))
    expect(root.style.getPropertyValue('--brand-top')).toBe('80.5px')
    expect(root.style.getPropertyValue('--brand-left')).toBe('21.59375px')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(700)
    })
    expect(document.querySelector('.splash-brand')?.classList.contains('is-docked')).toBe(true)
    expect(root.style.getPropertyValue('--brand-top')).toBe('80.5px')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(800)
    })
    expect(document.querySelector('.splash-bg')?.classList.contains('is-leaving')).toBe(true)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(700)
    })
    expect(document.querySelector('.splash')).toBeNull()
    expect(root.style.getPropertyValue('--brand-top')).toBe('')
    expect(root.style.getPropertyValue('--brand-left')).toBe('')
  } finally {
    spy.mockRestore()
    root.style.removeProperty('--brand-left')
    root.style.removeProperty('--brand-top')
    sessionStorage.removeItem('freedriver.splash.seen')
  }
})
