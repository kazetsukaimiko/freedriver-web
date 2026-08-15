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

function App() {
  const path = window.location.pathname
  if (!knownPaths.has(path)) {
    return (
      <div className="shell">
        <BrandBar />
        <main className="content status-page">
          <img className="solo" src="/assets/lonewatt/solo-404.png" alt="" />
          <h1>404</h1>
          <p className="lede">Solo cannot find that page.</p>
        </main>
      </div>
    )
  }

  return <Dashboard />
}

function BrandBar() {
  return (
    <header className="topbar">
      <a className="brand" href="/">
        <img src="/assets/lonewatt/lonewatt-icon.png" alt="" />
        <span>Freedriver</span>
      </a>
      <nav aria-label="Primary">
        <span className="nav-current">Dashboard</span>
      </nav>
    </header>
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
    <div className="shell">
      <BrandBar />

      <main className="content">
        <h1>Dashboard</h1>
        <p className="lede">Product app shell. Marketing stays in the static site.</p>

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
    </div>
  )
}

export default App
