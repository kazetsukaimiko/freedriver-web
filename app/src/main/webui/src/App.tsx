import { useEffect, useState, type MouseEvent } from 'react'
import './App.css'

type HelloResponse = {
  message: string
  service: string
}

type LoadState =
  | { status: 'loading' }
  | { status: 'ok'; data: HelloResponse }
  | { status: 'error'; error: string; statusCode?: number }

type Splash = 'playing' | 'docking' | 'revealing' | 'done'

const knownPaths = new Set(['/', '/dashboard'])
const SPLASH_KEY = 'freedriver.splash.seen'

function shouldPlaySplash() {
  const params = new URLSearchParams(window.location.search)
  if (params.get('splash') === '0') {
    return false
  }
  if (params.get('splash') === '1') {
    return true
  }
  return sessionStorage.getItem(SPLASH_KEY) !== '1'
}

function markSplashSeen() {
  sessionStorage.setItem(SPLASH_KEY, '1')
  const params = new URLSearchParams(window.location.search)
  if (params.get('splash') !== '1') {
    return
  }
  params.delete('splash')
  const query = params.toString()
  const next = window.location.pathname + (query ? `?${query}` : '')
  window.history.replaceState({}, '', next)
}

function App() {
  const [splash, setSplash] = useState<Splash>(() => (shouldPlaySplash() ? 'playing' : 'done'))
  const [path, setPath] = useState(() => window.location.pathname)

  useEffect(() => {
    if (splash !== 'playing') {
      return
    }
    markSplashSeen()
    const dock = window.setTimeout(() => setSplash('docking'), 700)
    const reveal = window.setTimeout(() => setSplash('revealing'), 1500)
    const done = window.setTimeout(() => setSplash('done'), 2200)
    return () => {
      window.clearTimeout(dock)
      window.clearTimeout(reveal)
      window.clearTimeout(done)
    }
  }, [splash])

  useEffect(() => {
    const onPop = () => setPath(window.location.pathname)
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [])

  const page = knownPaths.has(path) ? 'dashboard' : 'not-found'

  function go(event: MouseEvent<HTMLAnchorElement>, to: string) {
    event.preventDefault()
    if (window.location.pathname === to) {
      return
    }
    window.history.pushState({}, '', to)
    setPath(to)
  }

  return (
    <div className={`app${splash !== 'done' ? ' is-splashing' : ''}`}>
      {splash !== 'done' && (
        <div className="splash" aria-hidden="true">
          <div className={`splash-bg${splash === 'revealing' ? ' is-leaving' : ''}`} />
          <img
            className={`splash-lockup${splash === 'playing' ? '' : ' is-docked'}`}
            src="/assets/freedriver/logos/freedriver-lockup.png"
            alt=""
          />
        </div>
      )}

      <aside className="nav">
        <a className="nav-brand" href="/" onClick={(event) => go(event, '/')}>
          <img src="/assets/freedriver/logos/freedriver-lockup.png" alt="Freedriver" />
        </a>
        <nav aria-label="Primary">
          <a
            className={page === 'dashboard' ? 'nav-item is-current' : 'nav-item'}
            href="/"
            onClick={(event) => go(event, '/')}
          >
            Dashboard
          </a>
        </nav>
      </aside>

      <div className="workspace">{page === 'not-found' ? <NotFound /> : <Dashboard />}</div>
    </div>
  )
}

function NotFound() {
  return (
    <main className="content status-page">
      <img className="mark-art" src="/assets/freedriver/pages/freedriver-404.png" alt="" />
      <h1>404</h1>
      <p className="lede">That page drifted.</p>
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
            <img className="mark-art snow-spin" src="/assets/freedriver/pages/freedriver-loader.png" alt="" />
            Loading…
          </p>
        )}
        {hello.status === 'error' && (
          <div className="error" role="alert">
            <img
              className="mark-art"
              src={
                hello.statusCode && hello.statusCode >= 500
                  ? '/assets/freedriver/pages/freedriver-500.png'
                  : '/assets/freedriver/pages/freedriver-404.png'
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
