import { useEffect, useState } from 'react'
import './App.css'

type HelloResponse = {
  message: string
  service: string
}

type LoadState =
  | { status: 'loading' }
  | { status: 'ok'; data: HelloResponse }
  | { status: 'error'; error: string }

function App() {
  const [hello, setHello] = useState<LoadState>({ status: 'loading' })

  useEffect(() => {
    const controller = new AbortController()

    fetch('/api/hello', { signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error(`GET /api/hello failed (${response.status})`)
        }
        return (await response.json()) as HelloResponse
      })
      .then((data) => setHello({ status: 'ok', data }))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') {
          return
        }
        const message = error instanceof Error ? error.message : 'Unknown error'
        setHello({ status: 'error', error: message })
      })

    return () => controller.abort()
  }, [])

  return (
    <div className="shell">
      <header className="topbar">
        <div className="brand">Freedriver</div>
        <nav aria-label="Primary">
          <span className="nav-current">Dashboard</span>
        </nav>
      </header>

      <main className="content">
        <h1>Dashboard</h1>
        <p className="lede">Product app shell. Marketing stays in the static site.</p>

        <section className="card" aria-labelledby="hello-heading">
          <h2 id="hello-heading">API</h2>
          <p className="endpoint">GET /api/hello</p>
          {hello.status === 'loading' && <p>Loading…</p>}
          {hello.status === 'error' && (
            <p className="error" role="alert">
              {hello.error}
            </p>
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
