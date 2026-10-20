import { useEffect, useState } from 'react'
import { goToSignIn } from './api'
import { CHANGELOG_DENIED, CHANGELOG_ERROR, NO_CHANGELOG, fetchChangelog } from './changelog'
import { Denied } from './Dashboard'

type View =
  | { kind: 'loading' }
  | { kind: 'ready'; text: string }
  | { kind: 'missing' }
  | { kind: 'denied' }
  | { kind: 'error' }

export function Changelog() {
  const [view, setView] = useState<View>({ kind: 'loading' })

  useEffect(() => {
    const controller = new AbortController()
    fetchChangelog(controller.signal)
      .then((result) => {
        if (result.status === 'login') {
          goToSignIn()
          return
        }
        if (result.status === 'ok') {
          setView({ kind: 'ready', text: result.text })
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

  if (view.kind === 'denied') {
    return <Denied message={CHANGELOG_DENIED} />
  }

  return (
    <main className="content">
      <h1>Changelog</h1>
      <section className="card" aria-labelledby="changelog-text" aria-busy={view.kind === 'loading'}>
        <h2 id="changelog-text" className="visually-hidden">
          Release notes
        </h2>
        {view.kind === 'ready' && <div className="changelog-text">{view.text}</div>}
        {view.kind === 'missing' && <p className="empty-copy">{NO_CHANGELOG}</p>}
        {view.kind === 'error' && <p className="empty-copy">{CHANGELOG_ERROR}</p>}
      </section>
    </main>
  )
}
