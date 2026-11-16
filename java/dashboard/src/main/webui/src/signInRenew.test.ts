// @vitest-environment happy-dom
// The Keycloak login theme's sign-in renewal (#552), keycloak/themes/freedriver/login/resources/js/sign-in-renew.js.
// It lives in the theme; this suite runs it because the web UI suite is the repository's JavaScript test runner.
/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'

// Vitest runs from java/dashboard/src/main/webui, locally and in CI.
const renewSource = readFileSync(
  `${process.cwd()}/../../../../../keycloak/themes/freedriver/login/resources/js/sign-in-renew.js`,
  'utf8',
)

type Renew = { install: (win: Window, doc: Document, script: Element) => { check: () => void } | null }

const RESTART = 'https://app.freedriver.io/'
let replace: ReturnType<typeof vi.fn>

function load(renewAfterSeconds: number, typed = ''): Renew {
  document.body.innerHTML =
    `<form id="f"><input name="username" value="${typed}"></form>` +
    `<script id="s" data-renew-after-seconds="${renewAfterSeconds}" data-restart-url="${RESTART}"></script>`
  replace = vi.fn()
  Object.defineProperty(window, 'location', { configurable: true, value: { replace } })
  new Function(renewSource)()
  const api = (window as unknown as { freedriverSignInRenew: Renew }).freedriverSignInRenew
  api.install(window, document, document.getElementById('s')!)
  return api
}

function setHidden(hidden: boolean) {
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden })
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-11-16T09:00:00Z'))
  setHidden(false)
  sessionStorage.clear()
})

afterEach(() => {
  vi.useRealTimers()
  document.body.innerHTML = ''
})

it('renews an untouched page left in view once half the lifetime has passed', () => {
  load(3600)
  vi.advanceTimersByTime(3599 * 1000)
  expect(replace).not.toHaveBeenCalled()
  vi.advanceTimersByTime(1000)
  expect(replace).toHaveBeenCalledWith(RESTART)
})

it('renews a page that comes back into view after a day, with a shortened lifetime', () => {
  load(30, '+1 555 555 0100')
  setHidden(true)
  vi.advanceTimersByTime(24 * 3600 * 1000)
  expect(replace).not.toHaveBeenCalled()
  setHidden(false)
  document.dispatchEvent(new Event('visibilitychange'))
  expect(replace).toHaveBeenCalledTimes(1)
  expect(replace).toHaveBeenCalledWith(RESTART)
})

it('keeps a fresh page in place when it comes back into view', () => {
  load(30, '+1 555 555 0100')
  vi.advanceTimersByTime(29 * 1000)
  window.dispatchEvent(new Event('focus'))
  document.dispatchEvent(new Event('visibilitychange'))
  expect(replace).not.toHaveBeenCalled()
})

it('keeps what was typed and starts a fresh sign-in when a stale page is used', () => {
  load(30, '+1 555 555 0100')
  vi.advanceTimersByTime(31 * 1000)
  const submit = new Event('submit', { bubbles: true, cancelable: true })
  document.getElementById('f')!.dispatchEvent(submit)
  expect(submit.defaultPrevented).toBe(true)
  expect(sessionStorage.getItem('freedriver.login.phone')).toBe('+1 555 555 0100')
  expect(replace).toHaveBeenCalledWith(RESTART)
})

it('lets a fresh page submit to Keycloak', () => {
  load(30, '+1 555 555 0100')
  vi.advanceTimersByTime(10 * 1000)
  const submit = new Event('submit', { bubbles: true, cancelable: true })
  document.getElementById('f')!.dispatchEvent(submit)
  expect(submit.defaultPrevented).toBe(false)
  expect(replace).not.toHaveBeenCalled()
})
