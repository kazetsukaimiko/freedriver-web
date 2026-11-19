// @vitest-environment happy-dom
// The Keycloak login theme's sign-in renewal (#552),
// keycloak/sms-otp-spi/src/main/resources/theme/freedriver/login/resources/js/sign-in-renew.js,
// and the number it keeps for phone-fragment.js on the fresh page. Both use us-phone.js.
// They live in the theme; this suite runs them because the web UI suite is the repository's JavaScript test runner.
/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'

// Vitest runs from java/dashboard/src/main/webui, locally and in CI.
const themeJs = `${process.cwd()}/../../../../../keycloak/sms-otp-spi/src/main/resources/theme/freedriver/login/resources/js`
const usPhoneSource = readFileSync(`${themeJs}/us-phone.js`, 'utf8')
new Function(usPhoneSource)()
const renewSource = readFileSync(`${themeJs}/sign-in-renew.js`, 'utf8')
const fragmentSource = readFileSync(`${themeJs}/phone-fragment.js`, 'utf8')

type Renew = { install: (win: Window, doc: Document, script: Element) => { check: () => void } | null }

const HOME = 'https://app.freedriver.io/'
let replace: ReturnType<typeof vi.fn>
// Each test installs into its own page and window, so no listener of an earlier test fires.
let page: Document
let win: EventTarget & { Date: DateConstructor; location: { replace: typeof replace }; sessionStorage: Storage;
  setTimeout: typeof setTimeout }

function setLocation(hash = '') {
  replace = vi.fn()
  Object.defineProperty(window, 'location', { configurable: true, value: { replace, hash } })
}

// The page freedriver-renew.ftl renders: renewal after half the lifetime, the full lifetime, and the
// client's home address; a client without a home address gets no attributes and so no renewal.
function load(lifetimeSeconds: number, typed = '', home: string | null = HOME) {
  const attrs = home
    ? ` data-renew-after-seconds="${Math.floor(lifetimeSeconds / 2)}" data-lifetime-seconds="${lifetimeSeconds}" data-restart-url="${home}"`
    : ''
  page = document.implementation.createHTMLDocument('sign-in')
  page.body.innerHTML = `<form id="f"><input name="username" value="${typed}"></form><script id="s"${attrs}></script>`
  setHidden(false)
  setLocation()
  win = Object.assign(new EventTarget(), {
    Date, location: { replace }, sessionStorage: window.sessionStorage,
    setTimeout: ((fn: () => void, ms: number) => setTimeout(fn, ms)) as typeof setTimeout,
  })
  new Function(renewSource)()
  const api = (window as unknown as { freedriverSignInRenew: Renew }).freedriverSignInRenew
  return api.install(win as unknown as Window, page, page.getElementById('s')!)
}

function submit() {
  const event = new Event('submit', { bubbles: true, cancelable: true })
  page.getElementById('f')!.dispatchEvent(event)
  return event
}

// The fresh sign-in page: phone-fragment.js fills the username field from what was kept.
function freshPage(): string {
  document.body.innerHTML = '<form id="freedriver-phone-username-form"><input name="username" value=""></form>'
  setLocation()
  new Function(fragmentSource)()
  return (document.querySelector('input[name="username"]') as HTMLInputElement).value
}

function setHidden(hidden: boolean) {
  Object.defineProperty(page, 'hidden', { configurable: true, get: () => hidden })
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-11-16T09:00:00Z'))
  sessionStorage.clear()
})

afterEach(() => {
  vi.useRealTimers()
  document.body.innerHTML = ''
})

it('renews an untouched page left in view to its client home address at half the lifetime', () => {
  load(7200)
  vi.advanceTimersByTime(3599 * 1000)
  expect(replace).not.toHaveBeenCalled()
  vi.advanceTimersByTime(1000)
  expect(replace).toHaveBeenCalledWith(HOME)
})

it('renews a page that comes back into view after a day, with a shortened lifetime', () => {
  load(60, '+15555550100')
  setHidden(true)
  vi.advanceTimersByTime(24 * 3600 * 1000)
  expect(replace).not.toHaveBeenCalled()
  setHidden(false)
  page.dispatchEvent(new Event('visibilitychange'))
  expect(replace).toHaveBeenCalledTimes(1)
  expect(replace).toHaveBeenCalledWith(HOME)
})

it('leaves the page of a client without a home address alone', () => {
  expect(load(60, '', null)).toBeNull()
  vi.advanceTimersByTime(24 * 3600 * 1000)
  page.dispatchEvent(new Event('visibilitychange'))
  expect(submit().defaultPrevented).toBe(false)
  expect(replace).not.toHaveBeenCalled()
})

it('keeps a fresh page in place when it comes back into view', () => {
  load(60, '+15555550100')
  vi.advanceTimersByTime(29 * 1000)
  win.dispatchEvent(new Event('focus'))
  page.dispatchEvent(new Event('visibilitychange'))
  expect(replace).not.toHaveBeenCalled()
})

it('lets a submit between half and the full lifetime go to Keycloak, whose session is still alive', () => {
  load(60, '+15555550100')
  vi.advanceTimersByTime(59 * 1000)
  expect(submit().defaultPrevented).toBe(false)
  expect(replace).not.toHaveBeenCalled()
})

it('after the full lifetime keeps a +1 number and the fresh page fills it in', () => {
  load(60, '+15555550100')
  vi.advanceTimersByTime(60 * 1000)
  expect(submit().defaultPrevented).toBe(true)
  expect(replace).toHaveBeenCalledWith(HOME)
  expect(freshPage()).toBe('+15555550100')
})

it('phone checks use the shared stored-form string', () => {
  expect(usPhoneSource).toContain(String.raw`^\\+1[0-9]{10}$`)
  expect(fragmentSource).not.toContain('[0-9]{10}')
  expect(renewSource).not.toContain('[0-9]{10}')
  expect(fragmentSource).toContain('freedriverStoredUsPhone')
  expect(renewSource).toContain('freedriverStoredUsPhone')
})

it('fills the username only when the fragment matches the shared pattern', () => {
  document.body.innerHTML = '<form id="freedriver-phone-username-form"><input name="username" value=""></form>'
  setLocation('#+15555550100')
  new Function(fragmentSource)()
  expect((document.querySelector('input[name="username"]') as HTMLInputElement).value).toBe('+15555550100')
  expect(sessionStorage.getItem('freedriver.login.phone')).toBe('+15555550100')

  sessionStorage.clear()
  document.body.innerHTML = '<form id="freedriver-phone-username-form"><input name="username" value=""></form>'
  setLocation('#+447700900123')
  new Function(fragmentSource)()
  expect((document.querySelector('input[name="username"]') as HTMLInputElement).value).toBe('')
  expect(sessionStorage.getItem('freedriver.login.phone')).toBeNull()
})

it('after the full lifetime keeps no number in a form the fresh page cannot fill in', () => {
  load(60, '555-555-0100')
  vi.advanceTimersByTime(60 * 1000)
  expect(submit().defaultPrevented).toBe(true)
  expect(replace).toHaveBeenCalledWith(HOME)
  expect(sessionStorage.getItem('freedriver.login.phone')).toBeNull()
  expect(freshPage()).toBe('')
})
