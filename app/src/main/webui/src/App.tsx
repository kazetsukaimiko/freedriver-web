import { useEffect, useState, type MouseEvent } from 'react'
import { demoBuild, publishedBuild } from './build.ts'
import { Dashboard } from './Dashboard'
import './App.css'

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
  const [search, setSearch] = useState(() => window.location.search)

  useEffect(() => {
    if (splash === 'playing') {
      markSplashSeen()
      const id = window.setTimeout(() => setSplash('docking'), 700)
      return () => window.clearTimeout(id)
    }
    if (splash === 'docking') {
      const id = window.setTimeout(() => setSplash('revealing'), 800)
      return () => window.clearTimeout(id)
    }
    if (splash === 'revealing') {
      const id = window.setTimeout(() => setSplash('done'), 700)
      return () => window.clearTimeout(id)
    }
  }, [splash])

  useEffect(() => {
    const onPop = () => {
      setPath(window.location.pathname)
      setSearch(window.location.search)
    }
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [])

  const page = knownPaths.has(path) ? 'dashboard' : 'not-found'
  const hrefFor = (to: string) => to + search

  function go(event: MouseEvent<HTMLAnchorElement>, to: string) {
    event.preventDefault()
    const next = hrefFor(to)
    if (window.location.pathname === to && window.location.search === search) {
      return
    }
    window.history.pushState({}, '', next)
    setPath(to)
    setSearch(window.location.search)
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
        <a className="nav-brand" href={hrefFor('/')} onClick={(event) => go(event, '/')}>
          <img src="/assets/freedriver/logos/freedriver-lockup.png" alt="Freedriver" />
        </a>
        <nav aria-label="Primary">
          <a
            className={page === 'dashboard' ? 'nav-item is-current' : 'nav-item'}
            href={hrefFor('/')}
            onClick={(event) => go(event, '/')}
          >
            Dashboard
          </a>
        </nav>
      </aside>

      <div className="workspace">{page === 'not-found' ? <NotFound /> : <Dashboard search={search} />}</div>
      <BuildStamp />
    </div>
  )
}

function BuildStamp() {
  const [build, setBuild] = useState<string | null>(() => demoBuild())

  useEffect(() => {
    if (build) {
      return
    }
    const controller = new AbortController()

    fetch('/api/build', { signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) {
          return null
        }
        return (await response.json()) as { build?: unknown }
      })
      .then((data) => {
        if (!data) {
          return
        }
        setBuild(publishedBuild(data.build))
      })
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') {
          return
        }
      })

    return () => controller.abort()
  }, [build])

  if (!build) {
    return null
  }

  return (
    <p className="build-stamp" aria-label="Build">
      {build}
    </p>
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

export default App
