import { useEffect, useId, useLayoutEffect, useRef, useState, type MouseEvent } from 'react'
import { SIGN_OUT_PATH, apiFetch } from './api'
import { Brand } from './Brand'
import { BuildStamp } from './BuildStamp'
import { Changelog } from './Changelog'
import { CHANGELOG_PATH } from './changelog'
import { Dashboard } from './Dashboard'
import { InvitePage } from './InvitePage'
import { PhoneSignIn } from './PhoneSignIn'
import { PHONES_PATH } from './phones'
import './App.css'

type Splash = 'playing' | 'docking' | 'revealing' | 'done'

type Page = 'dashboard' | 'changelog' | 'phones' | 'not-found'

const pages = new Map<string, Page>([
  ['/', 'dashboard'],
  ['/dashboard', 'dashboard'],
  [CHANGELOG_PATH, 'changelog'],
  [PHONES_PATH, 'phones'],
])

type PrimaryItem = { to: string; label: string; page: Page }

function primaryNavItems(portalAdmin: boolean): PrimaryItem[] {
  const items: PrimaryItem[] = [{ to: '/', label: 'Dashboard', page: 'dashboard' }]
  if (portalAdmin) {
    items.push({ to: PHONES_PATH, label: 'Phone sign-in', page: 'phones' })
  }
  return items
}
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

function placeSplashDock(brand: Element) {
  const rect = brand.getBoundingClientRect()
  const root = document.documentElement
  root.style.setProperty('--brand-left', `${rect.left}px`)
  root.style.setProperty('--brand-top', `${rect.top}px`)
}

function clearSplashDock() {
  const root = document.documentElement
  root.style.removeProperty('--brand-left')
  root.style.removeProperty('--brand-top')
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

function isInvitePath(pathname: string) {
  return pathname === '/invite' || pathname === '/invite/'
}

function App() {
  const [path, setPath] = useState(() => window.location.pathname)
  const [splash, setSplash] = useState<Splash>(() =>
    isInvitePath(window.location.pathname) || !shouldPlaySplash() ? 'done' : 'playing',
  )
  const [search, setSearch] = useState(() => window.location.search)
  const [signingOut, setSigningOut] = useState(false)
  const [portalAdmin, setPortalAdmin] = useState(false)
  const [navOpen, setNavOpen] = useState(false)
  const navBrand = useRef<HTMLAnchorElement>(null)
  const navMenu = useRef<HTMLDivElement>(null)
  const navButton = useRef<HTMLButtonElement>(null)
  const navMenuId = useId()

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

  // Mobile nav centers the brand in its row, so the dock target is that box.
  useLayoutEffect(() => {
    if (splash === 'done') {
      clearSplashDock()
      return
    }
    const brand = navBrand.current?.querySelector('.brand')
    if (!brand) {
      return
    }
    const place = () => placeSplashDock(brand)
    place()
    const nav = navBrand.current?.closest('.nav')
    const observer = new ResizeObserver(place)
    if (nav) {
      observer.observe(nav)
    }
    window.addEventListener('resize', place)
    return () => {
      observer.disconnect()
      window.removeEventListener('resize', place)
    }
  }, [splash])

  useEffect(() => {
    if (isInvitePath(path)) {
      return
    }
    let cancelled = false
    apiFetch('/api/invites')
      .then((response) => {
        if (!cancelled) {
          setPortalAdmin(response.ok)
        }
      })
      .catch(() => {
        if (!cancelled) {
          setPortalAdmin(false)
        }
      })
    return () => {
      cancelled = true
    }
  }, [path])

  useEffect(() => {
    const onPop = () => {
      setPath(window.location.pathname)
      setSearch(window.location.search)
      setNavOpen(false)
    }
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [])

  useEffect(() => {
    if (!navOpen) {
      return
    }
    const menu = navMenu.current
    function onPointerDown(event: PointerEvent) {
      if (menu?.contains(event.target as Node)) {
        return
      }
      setNavOpen(false)
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key !== 'Escape') {
        return
      }
      setNavOpen(false)
      navButton.current?.focus()
    }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [navOpen])

  useEffect(() => {
    if (!signingOut) {
      return
    }
    const id = window.setTimeout(() => {
      window.location.assign(SIGN_OUT_PATH)
    }, 0)
    return () => window.clearTimeout(id)
  }, [signingOut])

  if (isInvitePath(path)) {
    return <InvitePage />
  }

  const page: Page = pages.get(path) ?? 'not-found'
  const hrefFor = (to: string) => to + search
  const primaryNav = primaryNavItems(portalAdmin)
  const currentPrimary = primaryNav.find((item) => item.page === page)
  const menuLabel = currentPrimary?.label ?? 'Menu'

  function go(event: MouseEvent<HTMLAnchorElement>, to: string) {
    event.preventDefault()
    setNavOpen(false)
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
        <a ref={navBrand} className="nav-brand" href={hrefFor('/')} onClick={(event) => go(event, '/')}>
          <Brand />
        </a>
        <nav aria-label="Primary">
          <div className="nav-menu" ref={navMenu}>
            <button
              ref={navButton}
              type="button"
              className="nav-menu-button"
              aria-expanded={navOpen}
              aria-controls={navMenuId}
              data-current={currentPrimary ? 'true' : 'false'}
              onClick={() => setNavOpen((open) => !open)}
            >
              {menuLabel}
              <span className="nav-menu-caret" aria-hidden="true" />
            </button>
            <div id={navMenuId} className="nav-menu-list" hidden={!navOpen}>
              {primaryNav.map((item) => (
                <a
                  key={item.to}
                  className={item.page === page ? 'nav-item is-current' : 'nav-item'}
                  href={hrefFor(item.to)}
                  aria-current={item.page === page ? 'page' : undefined}
                  onClick={(event) => go(event, item.to)}
                >
                  {item.label}
                </a>
              ))}
            </div>
          </div>
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
        {page === 'phones' && <PhoneSignIn />}
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
