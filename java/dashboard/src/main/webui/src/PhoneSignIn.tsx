import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { apiFetch, goToSignIn } from './api'
import { Denied } from './Dashboard'
import { canonicalPhone } from './invitePhone'
import {
  EMPTY_INVITES,
  EMPTY_NUMBERS,
  ENTER_PHONE,
  INVITE_SENT,
  displayPhone,
  inviteFailureReason,
  loadPhonePage,
  when,
  type OpenInvite,
  type PhoneRow,
} from './phones'
import './phones.css'

type View =
  | { kind: 'loading' }
  | { kind: 'denied' }
  | { kind: 'error' }
  | { kind: 'ready'; phones: PhoneRow[]; invites: OpenInvite[]; csrfToken: string }

export function PhoneSignIn() {
  const [view, setView] = useState<View>({ kind: 'loading' })
  const [phone, setPhone] = useState('')
  const [phoneError, setPhoneError] = useState('')
  const [notice, setNotice] = useState('')
  const busy = useRef(false)

  function reload() {
    return loadPhonePage().then((result) => {
      if (result.status === 'login') {
        goToSignIn()
        return
      }
      if (result.status === 'ok') {
        setView({
          kind: 'ready',
          phones: result.phones,
          invites: result.invites,
          csrfToken: result.csrfToken,
        })
        return
      }
      setView({ kind: result.status })
    })
  }

  useEffect(() => {
    const controller = new AbortController()
    loadPhonePage(controller.signal)
      .then((result) => {
        if (result.status === 'login') {
          goToSignIn()
          return
        }
        if (result.status === 'ok') {
          setView({
            kind: 'ready',
            phones: result.phones,
            invites: result.invites,
            csrfToken: result.csrfToken,
          })
          return
        }
        setView({ kind: result.status })
      })
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') {
          return
        }
        setView({ kind: 'error' })
      })
    return () => controller.abort()
  }, [])

  if (view.kind === 'loading') {
    return (
      <main className="content status-page">
        <p className="lede">Loading phone sign-in.</p>
      </main>
    )
  }
  if (view.kind === 'denied') {
    return <Denied message="This account needs a portal-admin role." />
  }
  if (view.kind === 'error') {
    return (
      <main className="content status-page">
        <h1>Phone sign-in</h1>
        <p className="lede">The phone list didn't load.</p>
      </main>
    )
  }

  async function createInvite() {
    if (busy.current) {
      return
    }
    const canonical = canonicalPhone(phone.trim())
    if (!canonical) {
      setPhoneError(ENTER_PHONE)
      setNotice('')
      return
    }
    busy.current = true
    setPhoneError('')
    setNotice('')
    try {
      const response = await apiFetch('/api/invites', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-CSRF-Token': view.kind === 'ready' ? view.csrfToken : '',
        },
        body: JSON.stringify({ phone: canonical }),
      })
      if (signIn(response)) {
        return
      }
      if (!response.ok) {
        let body: unknown = null
        try {
          body = await response.json()
        } catch {
          body = null
        }
        setPhoneError(inviteFailureReason(body))
        return
      }
      setPhone('')
      setNotice(INVITE_SENT)
      await reload()
    } finally {
      busy.current = false
    }
  }

  function submitInvite(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    void createInvite()
  }

  function submitOnEnter(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key !== 'Enter') {
      return
    }
    event.preventDefault()
    event.currentTarget.form?.requestSubmit()
  }

  async function cancelInvite(invite: OpenInvite) {
    if (!window.confirm(`Cancel ${invite.name}'s invite? The link will stop working.`)) {
      return
    }
    const response = await apiFetch(`/api/invites/${invite.id}/cancel`, {
      method: 'POST',
      headers: { 'X-CSRF-Token': view.kind === 'ready' ? view.csrfToken : '' },
    })
    if (signIn(response) || !response.ok) {
      return
    }
    await reload()
  }

  async function removePhone(row: PhoneRow) {
    if (!window.confirm(`Remove ${row.name}'s number? They won't be able to sign in with a text code.`)) {
      return
    }
    const response = await apiFetch('/api/phones/remove', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-CSRF-Token': view.kind === 'ready' ? view.csrfToken : '',
      },
      body: JSON.stringify({ phone: row.phone }),
    })
    if (signIn(response) || !response.ok) {
      return
    }
    await reload()
  }

  return (
    <main className="content phone-sign-in">
      <div className="phone-sign-in-head">
        <h1>Phone sign-in</h1>
      </div>

      <section className="card">
        <h2>Create invite</h2>
        <form onSubmit={submitInvite}>
          <label>
            Phone number
            <input
              value={phone}
              inputMode="tel"
              autoComplete="tel"
              placeholder="(555) 555-0123"
              onChange={(event) => setPhone(event.target.value)}
              onKeyDown={submitOnEnter}
            />
          </label>
          {phoneError && <p role="alert">{phoneError}</p>}
          {notice && <p role="status">{notice}</p>}
          <button type="submit">Create invite</button>
        </form>
      </section>

      <section className="card">
        <h2>Phone numbers</h2>
        {view.phones.length === 0 ? (
          <p>{EMPTY_NUMBERS}</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Number</th>
                <th>Role</th>
                <th>Consent</th>
                <th>Added</th>
                <th>Last sign-in</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {view.phones.map((row) => (
                <tr key={row.phone}>
                  <td>{row.name}</td>
                  <td>{displayPhone(row.phone)}</td>
                  <td>{row.role}</td>
                  <td>{row.consentedAt ? `Consented ${when(row.consentedAt)}` : 'Waiting for consent'}</td>
                  <td>{when(row.addedAt)}</td>
                  <td>{row.lastSignIn ? when(row.lastSignIn) : '—'}</td>
                  <td>
                    <button type="button" className="danger" onClick={() => void removePhone(row)}>
                      Remove
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="card">
        <h2>Open invites</h2>
        {view.invites.length === 0 ? (
          <p>{EMPTY_INVITES}</p>
        ) : (
          <ul>
            {view.invites.map((invite) => (
              <li key={invite.id}>
                <span>{invite.name}</span>
                <span>{when(invite.createdAt)}</span>
                <span>{when(invite.expiresAt)}</span>
                <button type="button" className="danger" onClick={() => void cancelInvite(invite)}>
                  Cancel invite
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  )
}

function signIn(response: Response) {
  if (response.status === 499 || response.status === 401) {
    goToSignIn()
    return true
  }
  return false
}
