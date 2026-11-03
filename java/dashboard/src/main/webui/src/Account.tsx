import { useState } from 'react'
import { goToSignIn } from './api'
import {
  ACCOUNT_DENIED,
  ACCOUNT_ERROR,
  SET_OR_CHANGE_PASSWORD,
  goToPasswordAction,
  startPasswordAction,
} from './account'
import { Denied } from './Dashboard'

type View = 'ready' | 'busy' | 'denied' | 'error'

export function Account() {
  const [view, setView] = useState<View>('ready')

  async function start() {
    if (view === 'busy') {
      return
    }
    setView('busy')
    try {
      const result = await startPasswordAction()
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
    return <Denied message={ACCOUNT_DENIED} />
  }

  return (
    <main className="content">
      <h1>Account</h1>
      <p className="lede">Set a password if this account has none, or change it if it has one.</p>
      <section className="card" aria-labelledby="account-password-heading">
        <h2 id="account-password-heading">Password</h2>
        <p className="empty-copy">
          Keycloak asks for the current password when there is one. A dashboard session does not set
          the password by itself.
        </p>
        <p>
          <button type="button" disabled={view === 'busy'} onClick={() => void start()}>
            {SET_OR_CHANGE_PASSWORD}
          </button>
        </p>
        {view === 'error' && <p className="error">{ACCOUNT_ERROR}</p>}
      </section>
    </main>
  )
}
