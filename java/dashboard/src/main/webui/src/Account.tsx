import { useState } from 'react'
import { goToSignIn } from './api'
import {
  ACCOUNT_DENIED,
  ACCOUNT_ERROR,
  PASSKEY_DENIED,
  PASSKEY_ERROR,
  SET_OR_CHANGE_PASSWORD,
  SET_UP_A_PASSKEY,
  goToPasswordAction,
  startPasskeyAction,
  startPasswordAction,
} from './account'
import { Denied } from './Dashboard'

type Kind = 'password' | 'passkey'
type View = 'ready' | 'busy' | 'denied' | 'error'

export function Account() {
  const [kind, setKind] = useState<Kind>('password')
  const [view, setView] = useState<View>('ready')

  async function start(next: Kind) {
    if (view === 'busy') {
      return
    }
    setKind(next)
    setView('busy')
    try {
      const result = next === 'password' ? await startPasswordAction() : await startPasskeyAction()
      if (result.status === 'login') {
        goToSignIn()
        return
      }
      if (result.status === 'denied') {
        setView('denied')
        return
      }
      if (result.status === 'ok') {
        goToPasswordAction(result.url)
        return
      }
      setView('error')
    } catch (error: unknown) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        return
      }
      setView('error')
    }
  }

  if (view === 'denied') {
    return <Denied message={kind === 'password' ? ACCOUNT_DENIED : PASSKEY_DENIED} />
  }

  return (
    <main className="content">
      <h1>Account</h1>
      <p className="lede">Set a password or a passkey on Keycloak for this account.</p>
      <section className="card" aria-labelledby="account-password-heading">
        <h2 id="account-password-heading">Password</h2>
        <p className="empty-copy">
          Keycloak asks for the current password when there is one. A dashboard session does not set
          the password by itself.
        </p>
        <p>
          <button type="button" disabled={view === 'busy'} onClick={() => void start('password')}>
            {SET_OR_CHANGE_PASSWORD}
          </button>
        </p>
        {view === 'error' && kind === 'password' && <p className="error">{ACCOUNT_ERROR}</p>}
      </section>
      <section className="card" aria-labelledby="account-passkeys-heading">
        <h2 id="account-passkeys-heading">Passkeys</h2>
        <p className="empty-copy">
          Keycloak registers the passkey on auth.freedriver.io. This page does not run WebAuthn on
          the dashboard origin.
        </p>
        <p>
          <button type="button" disabled={view === 'busy'} onClick={() => void start('passkey')}>
            {SET_UP_A_PASSKEY}
          </button>
        </p>
        {view === 'error' && kind === 'passkey' && <p className="error">{PASSKEY_ERROR}</p>}
      </section>
    </main>
  )
}
