import { useEffect, useState, type MouseEvent } from 'react'
import { SIGN_OUT_PATH } from './api'
import { Brand } from './Brand'
import { BuildStamp } from './BuildStamp'
import { Changelog } from './Changelog'
import { CHANGELOG_PATH } from './changelog'
import { Dashboard } from './Dashboard'
import './App.css'

type Splash = 'playing' | 'docking' | 'revealing' | 'done'

type Page = 'dashboard' | 'changelog' | 'not-found'

const pages = new Map<string, Page>([
  ['/', 'dashboard'],
  ['/dashboard', 'dashboard'],
  [CHANGELOG_PATH, 'changelog'],
])
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
  const [signingOut, setSigningOut] = useState(false)

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

  useEffect(() => {
    if (!signingOut) {
      return
    }
    const id = window.setTimeout(() => {
      window.location.assign(SIGN_OUT_PATH)
    }, 0)
    return () => window.clearTimeout(id)
  }, [signingOut])

  const page: Page = pages.get(path) ?? 'not-found'
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

  function signOut(event: MouseEvent<HTMLAnchorElement>) {
    event.preventDefault()
    setSigningOut(true)
  }

  return (
    <div className={`app${splash !== 'done' ? ' is-splashing' : ''}`}>
      {splash !== 'done' && (
        <div className="splash" aria-hidden="true">
          <div className={`splash-bg${splash === 'revealing' ? ' is-leaving' : ''}`} />
          <Brand className={`splash-brand${splash === 'playing' ? '' : ' is-docked'}`} />
        </div>
      )}

      <header className="nav">
        <a className="nav-brand" href={hrefFor('/')} onClick={(event) => go(event, '/')}>
          <Brand />
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
        <a
          className="sign-out"
          href={SIGN_OUT_PATH}
          aria-label="Sign out"
          aria-busy={signingOut}
          onClick={signOut}
        >
          {signingOut ? (
            <img className="sign-out-loader" src="/assets/freedriver/pages/freedriver-loader.svg" alt="" />
          ) : (
            'Sign out'
          )}
        </a>
      </header>

      <div className="workspace">
        {page === 'dashboard' && <Dashboard search={search} />}
        {page === 'changelog' && <Changelog />}
        {page === 'not-found' && <NotFound />}
      </div>
      <BuildStamp changelogHref={hrefFor(CHANGELOG_PATH)} onOpenChangelog={(event) => go(event, CHANGELOG_PATH)} />
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

export default App
