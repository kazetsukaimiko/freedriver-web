/** Whole-entry US shapes from the phone-or-username router (#155). */
const SHAPE =
  /^(?:[0-9]{10}|[0-9]{3}-[0-9]{3}-[0-9]{4}|\([0-9]{3}\) [0-9]{3}-[0-9]{4}|[0-9]{3}\.[0-9]{3}\.[0-9]{4}|[0-9]{3} [0-9]{3} [0-9]{4}|1-[0-9]{3}-[0-9]{3}-[0-9]{4}|\+1 [0-9]{3} [0-9]{3} [0-9]{4}|\+1[0-9]{10})$/

export const INVITE_WORDING =
  'By tapping Confirm, you agree to receive a one-time sign-in code by text from Freedriver. Message and data rates may apply. Reply STOP to opt out.'

export const WELCOME_KEY = 'freedriver.invite.welcome'
const CODE_KEY = 'freedriver.invite.code'

/** `+1` and ten digits, or null when `raw` is not one of the shapes. */
export function canonicalPhone(raw: string): string | null {
  if (!SHAPE.test(raw)) {
    return null
  }
  const digits = raw.replace(/\D/g, '')
  const national = digits.length === 10 ? digits : digits.length === 11 && digits.startsWith('1') ? digits.slice(1) : ''
  return national.length === 10 ? `+1${national}` : null
}

export function displayPhone(canonical: string): string {
  const national = canonical.slice(2)
  return `(${national.slice(0, 3)}) ${national.slice(3, 6)}-${national.slice(6)}`
}

/** Reads the fragment once, then keeps the code out of the address bar. */
export function takeInviteCode(): string | null {
  const hash = window.location.hash.startsWith('#') ? window.location.hash.slice(1) : ''
  const params = new URLSearchParams(window.location.search)
  params.delete('code')
  const query = params.toString()
  const path = window.location.pathname
  if (hash) {
    sessionStorage.setItem(CODE_KEY, hash)
  }
  window.history.replaceState(null, '', path + (query ? `?${query}` : ''))
  const code = hash || sessionStorage.getItem(CODE_KEY)
  return code && code.length > 0 ? code : null
}

export function clearInviteCode(): void {
  sessionStorage.removeItem(CODE_KEY)
}
