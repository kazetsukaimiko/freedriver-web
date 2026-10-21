import { useRef, useState, type FormEvent } from 'react'
import { SIGN_IN_PATH, apiFetch } from './api'
import { Brand } from './Brand'
import {
  INVITE_WORDING,
  WELCOME_KEY,
  canonicalPhone,
  clearInviteCode,
  displayPhone,
  takeInviteCode,
} from './invitePhone'
import './invite.css'

type Step =
  | { kind: 'loading' }
  | { kind: 'dead' }
  | { kind: 'phone'; error: string | null; listed: boolean }
  | { kind: 'code'; phone: string; display: string; codesSent: number; error: string | null }
  | { kind: 'codes'; display: string }

const BAD_PHONE = 'Enter a 10-digit US phone number.'
const WRONG_CODE = "That code didn't work. Try again or send a new code."
const CODES_LINE = 'This invite has sent all three codes. Ask for a new invite.'
const DEAD_LINE = 'This invite link has expired. Ask for a new one.'
const LISTED_LINE = 'This number is already on the list.'
const SEND_WAIT = "We couldn't send a code right now. Try again in a minute."
const CHECK_WAIT = "We couldn't check your code right now. Try again in a minute."

export function InvitePage() {
  const code = useRef<string | null>(null)
  const [step, setStep] = useState<Step>(() => {
    const invite = takeInviteCode()
    code.current = invite
    return invite ? { kind: 'phone', error: null, listed: false } : { kind: 'dead' }
  })
  const [typed, setTyped] = useState('')
  const [otp, setOtp] = useState('')
  const [busy, setBusy] = useState(false)

  function dead() {
    clearInviteCode()
    setStep({ kind: 'dead' })
  }

  async function confirm(event: FormEvent) {
    event.preventDefault()
    const canonical = canonicalPhone(typed.trim())
    if (!code.current) {
      dead()
      return
    }
    if (!canonical) {
      setStep({ kind: 'phone', error: BAD_PHONE, listed: false })
      return
    }
    setBusy(true)
    try {
      const response = await apiFetch('/api/invites/redeem/start', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code: code.current, phone: canonical, wording: INVITE_WORDING }),
      })
      const body = (await response.json().catch(() => ({}))) as { error?: string; codesSent?: number }
      if (body.error === 'dead') {
        dead()
        return
      }
      if (body.error === 'already-listed') {
        setStep({ kind: 'phone', error: null, listed: true })
        return
      }
      if (body.error === 'bad-phone') {
        setStep({ kind: 'phone', error: BAD_PHONE, listed: false })
        return
      }
      if (body.error === 'codes') {
        setStep({ kind: 'codes', display: displayPhone(canonical) })
        return
      }
      if (!response.ok || body.codesSent == null) {
        setStep({ kind: 'phone', error: SEND_WAIT, listed: false })
        return
      }
      setOtp('')
      setStep({
        kind: 'code',
        phone: canonical,
        display: displayPhone(canonical),
        codesSent: body.codesSent,
        error: null,
      })
    } finally {
      setBusy(false)
    }
  }

  async function resend() {
    if (step.kind !== 'code' || !code.current || step.codesSent >= 3) {
      return
    }
    setBusy(true)
    try {
      const response = await apiFetch('/api/invites/redeem/resend', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code: code.current }),
      })
      const body = (await response.json().catch(() => ({}))) as { error?: string; codesSent?: number }
      if (body.error === 'dead') {
        dead()
        return
      }
      if (body.error === 'codes' || (body.codesSent != null && body.codesSent >= 3)) {
        setStep({ ...step, codesSent: body.codesSent ?? 3, error: null })
        return
      }
      if (!response.ok || body.codesSent == null) {
        setStep({ ...step, error: SEND_WAIT })
        return
      }
      setStep({ ...step, codesSent: body.codesSent, error: null })
    } finally {
      setBusy(false)
    }
  }

  async function verify(event: FormEvent) {
    event.preventDefault()
    if (step.kind !== 'code' || !code.current) {
      return
    }
    setBusy(true)
    try {
      const response = await apiFetch('/api/invites/redeem/verify', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code: code.current, otp }),
      })
      const body = (await response.json().catch(() => ({}))) as { error?: string; next?: string }
      if (body.error === 'dead') {
        dead()
        return
      }
      if (body.error === 'invalid-code') {
        setStep({ ...step, error: WRONG_CODE })
        return
      }
      if (!response.ok || body.next !== '/login') {
        setStep({ ...step, error: CHECK_WAIT })
        return
      }
      clearInviteCode()
      sessionStorage.setItem(WELCOME_KEY, '1')
      window.location.assign(SIGN_IN_PATH)
    } finally {
      setBusy(false)
    }
  }

  const normalized = canonicalPhone(typed.trim())

  return (
    <main className="invite-page">
      <section className="invite-card" aria-busy={busy}>
        <Brand className="is-light" />
        {step.kind === 'loading' || busy ? (
          <p className="loader">
            <img className="mark-art" src="/assets/freedriver/pages/freedriver-loader.svg" alt="Loading" />
          </p>
        ) : null}
        {step.kind === 'dead' && !busy && <p>{DEAD_LINE}</p>}
        {step.kind === 'phone' && !busy && (
          <form onSubmit={(event) => void confirm(event)}>
            <h1>Set up text sign-in</h1>
            <p className="lede">Enter your phone number to sign in to Freedriver with a text code.</p>
            <label>
              Phone number
              <input
                value={typed}
                placeholder="(123) 456-7890"
                autoComplete="tel"
                inputMode="tel"
                onChange={(event) => setTyped(event.target.value)}
              />
            </label>
            {normalized && <p className="invite-normalized">{displayPhone(normalized)}</p>}
            <p className="invite-wording">{INVITE_WORDING}</p>
            {step.error && <p className="invite-error">{step.error}</p>}
            {step.listed && (
              <p className="invite-error">
                {LISTED_LINE} <a href={SIGN_IN_PATH}>Go to sign-in</a>
              </p>
            )}
            <button className="invite-primary" type="submit">
              Confirm
            </button>
          </form>
        )}
        {step.kind === 'code' && !busy && (
          <form onSubmit={(event) => void verify(event)}>
            <h1>Enter your code</h1>
            <p className="lede">We texted a code to {step.display}.</p>
            <label>
              Code
              <input
                value={otp}
                inputMode="numeric"
                autoComplete="one-time-code"
                onChange={(event) => setOtp(event.target.value)}
              />
            </label>
            {step.error && <p className="invite-error">{step.error}</p>}
            <div className="invite-actions">
              <button className="invite-primary" type="submit">
                Verify
              </button>
              {step.codesSent >= 3 ? (
                <p className="invite-note">{CODES_LINE}</p>
              ) : (
                <button className="invite-text-button" type="button" onClick={() => void resend()}>
                  Send a new code
                </button>
              )}
            </div>
          </form>
        )}
        {step.kind === 'codes' && !busy && (
          <>
            <h1>Enter your code</h1>
            <p className="lede">We texted a code to {step.display}.</p>
            <p className="invite-note">{CODES_LINE}</p>
          </>
        )}
      </section>
    </main>
  )
}
