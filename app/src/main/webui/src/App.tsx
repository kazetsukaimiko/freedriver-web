import { useEffect, useState } from 'react'
import './App.css'

type HelloResponse = {
  message: string
  service: string
}

type LoadState =
  | { status: 'loading' }
  | { status: 'ok'; data: HelloResponse }
  | { status: 'error'; error: string; statusCode?: number }

const knownPaths = new Set(['/', '/dashboard'])
const SPLASH_KEY = 'freedriver.splash.seen'

function shouldPlaySplash() {
  const params = new URLSearchParams(window.location.search)
  if (params.get('splash') === '1') {
    return true
  }
  return sessionStorage.getItem(SPLASH_KEY) !== '1'
}

function App() {
  const [splash, setSplash] = useState<'playing' | 'leaving' | 'done'>(() =>
    shouldPlaySplash() ? 'playing' : 'done',
  )

  useEffect(() => {
    if (splash !== 'playing') {
      return
    }
    const leave = window.setTimeout(() => setSplash('leaving'), 900)
    const done = window.setTimeout(() => {
      sessionStorage.setItem(SPLASH_KEY, '1')
      setSplash('done')
    }, 1700)
    return () => {
      window.clearTimeout(leave)
      window.clearTimeout(done)
    }
  }, [splash])

  const path = window.location.pathname
  const page = knownPaths.has(path) ? 'dashboard' : 'not-found'

  return (
    <div className={`app${splash !== 'done' ? ' is-splashing' : ''}`}>
      {splash !== 'done' && (
        <div className={`splash${splash === 'leaving' ? ' is-leaving' : ''}`} aria-hidden="true">
          <img className="splash-lockup" src="/assets/lonewatt/lonewatt-lockup.png" alt="" />
        </div>
      )}

      <aside className="nav">
        <a className="nav-brand" href="/">
          <img src="/assets/lonewatt/lonewatt-lockup.png" alt="Lonewatt" />
        </a>
        <nav aria-label="Primary">
          <a className={page === 'dashboard' ? 'nav-item is-current' : 'nav-item'} href="/">
            Dashboard
          </a>
        </nav>
      </aside>

      <div className="workspace">
        {page === 'not-found' ? <NotFound /> : <Dashboard />}
      </div>
    </div>
  )
}

function NotFound() {
  return (
    <main className="content status-page">
      <img className="solo" src="/assets/lonewatt/solo-404.png" alt="" />
      <h1>404</h1>
      <p className="lede">Solo cannot find that page.</p>
    </main>
  )
}

function Dashboard() {
  const [hello, setHello] = useState<LoadState>({ status: 'loading' })

  useEffect(() => {
    const controller = new AbortController()

    fetch('/api/hello', { signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) {
          const error = new Error(`GET /api/hello failed (${response.status})`) as Error & {
            statusCode?: number
          }
          error.statusCode = response.status
          throw error
        }
        return (await response.json()) as HelloResponse
      })
      .then((data) => setHello({ status: 'ok', data }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') {
          return
        }
        const message = error instanceof Error ? error.message : 'Unknown error'
        const statusCode =
          error instanceof Error && 'statusCode' in error
            ? (error as { statusCode?: number }).statusCode
            : undefined
        setHello({ status: 'error', error: message, statusCode })
      })

    return () => controller.abort()
  }, [])

  return (
    <main className="content">
      <h1>Dashboard</h1>
      <p className="lede">Product app shell.</p>

      <section className="card" aria-labelledby="hello-heading">
        <h2 id="hello-heading">API</h2>
        <p className="endpoint">GET /api/hello</p>
        {hello.status === 'loading' && (
          <p className="loader">
            <img className="solo solo-run" src="/assets/lonewatt/solo-run.png" alt="" />
            Loading…
          </p>
        )}
        {hello.status === 'error' && (
          <div className="error" role="alert">
            <img
              className="solo"
              src={
                hello.statusCode && hello.statusCode >= 500
                  ? '/assets/lonewatt/solo-500.png'
                  : '/assets/lonewatt/solo-404.png'
              }
              alt=""
            />
            <p>{hello.error}</p>
          </div>
        )}
        {hello.status === 'ok' && (
          <dl>
            <div>
              <dt>message</dt>
              <dd>{hello.data.message}</dd>
            </div>
            <div>
              <dt>service</dt>
              <dd>{hello.data.service}</dd>
            </div>
          </dl>
        )}
      </section>
    </main>
  )
}

export default App
