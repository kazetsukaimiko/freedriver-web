import { useEffect, useState } from 'react'
import { apiFetch, goToSignIn } from './api'
import { Denied } from './Dashboard'
import {
  EMPTY_INVITES,
  EMPTY_NUMBERS,
  ENTER_NAME,
  displayPhone,
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
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState('')
  const [nameError, setNameError] = useState('')
  const [link, setLink] = useState<{ name: string; href: string } | null>(null)
  const [copied, setCopied] = useState(false)

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
    const label = name.trim()
    if (!label) {
      setNameError(ENTER_NAME)
      return
    }
    setNameError('')
    const response = await apiFetch('/api/invites', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-CSRF-Token': view.kind === 'ready' ? view.csrfToken : '',
      },
      body: JSON.stringify({ name: label }),
    })
    if (signIn(response)) {
      return
    }
    if (!response.ok) {
      setNameError(ENTER_NAME)
      return
    }
    const body = (await response.json()) as { link: string; invite: { name: string } }
    setLink({ name: body.invite.name, href: body.link })
    setCreating(false)
    setName('')
    setCopied(false)
    await reload()
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

  async function copyLink() {
    if (!link) {
      return
    }
    await navigator.clipboard.writeText(link.href)
    setCopied(true)
    window.setTimeout(() => setCopied(false), 2000)
  }

  return (
    <main className="content phone-sign-in">
      <div className="phone-sign-in-head">
        <h1>Phone sign-in</h1>
        <button type="button" onClick={() => setCreating(true)}>
          Create invite
        </button>
      </div>

      {creating && (
        <section className="card">
          <h2>Create invite</h2>
          <label>
            Name
            <input
              value={name}
              maxLength={40}
              placeholder="Mom"
              onChange={(event) => setName(event.target.value)}
            />
          </label>
          {nameError && <p>{nameError}</p>}
          <button type="button" onClick={() => void createInvite()}>
            Create invite link
          </button>
        </section>
      )}

      {link && (
        <section className="card" aria-label={`Invite link for ${link.name}`}>
          <h2>Invite link for {link.name}</h2>
          <input readOnly value={link.href} aria-label="Invite link" />
          <button type="button" onClick={() => void copyLink()}>
            {copied ? 'Copied' : 'Copy'}
          </button>
          <p>
            Send this link to {link.name}. It works once and expires in 72 hours. This link is shown only now, so
            copy it before you close this panel.
          </p>
          <button type="button" onClick={() => setLink(null)}>
            Done
          </button>
        </section>
      )}

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
