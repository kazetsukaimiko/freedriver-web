import { useEffect, useRef, useState } from 'react'
import {
  COMMAND_WAIT_MS,
  DEMO_APPLIANCES,
  POLL_MS,
  STALE_AFTER_MS,
  type Appliance,
  type ApplianceMap,
  type CommandResult,
  type DemoMode,
  type DeniedReason,
  demoCommand,
  demoLastUpdated,
  demoModeFromSearch,
  fetchApplianceMap,
  formatLastHeard,
  postApplianceCommand,
} from './dashboard'

type Row = Appliance & { pending: boolean; error: string | null }

type View =
  | { kind: 'waiting' }
  | { kind: 'denied'; reason: DeniedReason }
  | { kind: 'empty' }
  | { kind: 'ready'; rows: Row[]; unreachable: boolean; lastUpdated: string | null }

function rowsFrom(appliances: Appliance[], keep: Row[] = []): Row[] {
  const prior = new Map(keep.map((row) => [row.id, row]))
  return appliances.map((appliance) => {
    const existing = prior.get(appliance.id)
    if (existing?.pending) {
      return existing
    }
    return { ...appliance, pending: false, error: existing?.error ?? null }
  })
}

function asAppliances(rows: Row[]): Appliance[] {
  return rows.map(({ id, name, on }) => ({ id, name, on }))
}

function applyConfirmed(rows: Row[], map: ApplianceMap, id: string): Row[] {
  return rowsFrom(
    map.appliances.length > 0 ? map.appliances : asAppliances(rows),
    rows.map((row) => (row.id === id ? { ...row, pending: false, error: null } : row)),
  )
}

export function Dashboard({ search }: { search: string }) {
  const demo = demoModeFromSearch(search)
  const [view, setView] = useState<View>(() => initialView(demo))
  const [now, setNow] = useState(() => Date.now())
  const rowsRef = useRef<Row[]>([])
  const lastFreshAt = useRef<number | null>(demo === 'live' || demo === 'timeout' || demo === 'empty' ? Date.now() : null)
  const lastUpdatedRef = useRef<string | null>(
    demo === 'unreachable' ? demoLastUpdated() : demo === 'live' || demo === 'timeout' ? demoLastUpdated(2) : null,
  )
  const inFlight = useRef(new Map<string, AbortController>())

  useEffect(() => {
    rowsRef.current = view.kind === 'ready' ? view.rows : []
  }, [view])

  useEffect(() => {
    setView(initialView(demo))
    lastFreshAt.current = demo === 'live' || demo === 'timeout' || demo === 'empty' ? Date.now() : null
    lastUpdatedRef.current = demo === 'unreachable' ? demoLastUpdated() : null
    inFlight.current.forEach((controller) => controller.abort())
    inFlight.current.clear()
  }, [demo])

  useEffect(() => {
    if (demo) {
      return
    }

    let cancelled = false
    const poll = new AbortController()

    async function load() {
      try {
        const result = await fetchApplianceMap(poll.signal)
        if (cancelled) {
          return
        }
        if (result.status === 'denied') {
          lastFreshAt.current = null
          lastUpdatedRef.current = null
          rowsRef.current = []
          setView({ kind: 'denied', reason: result.reason })
          return
        }
        if (result.status === 'ok') {
          applyMap(result.map)
          return
        }
        if (lastFreshAt.current != null && Date.now() - lastFreshAt.current >= STALE_AFTER_MS) {
          setView({
            kind: 'ready',
            rows: rowsRef.current,
            unreachable: true,
            lastUpdated: lastUpdatedRef.current,
          })
        }
      } catch (error) {
        if (error instanceof DOMException && error.name === 'AbortError') {
          return
        }
      }
    }

    void load()
    const id = window.setInterval(() => void load(), POLL_MS)
    return () => {
      cancelled = true
      poll.abort()
      window.clearInterval(id)
    }
  }, [demo])

  useEffect(() => {
    if (view.kind !== 'ready' || !view.unreachable) {
      return
    }
    const id = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(id)
  }, [view])

  useEffect(() => {
    const pending = inFlight.current
    return () => {
      pending.forEach((controller) => controller.abort())
      pending.clear()
    }
  }, [])

  function applyMap(map: ApplianceMap) {
    if (map.stale) {
      const lastKnown = map.appliances.length > 0 ? rowsFrom(map.appliances) : rowsRef.current.map((row) => ({
        ...row,
        pending: false,
      }))
      if (map.lastUpdated) {
        lastUpdatedRef.current = map.lastUpdated
      }
      rowsRef.current = lastKnown
      setView({
        kind: 'ready',
        rows: lastKnown,
        unreachable: true,
        lastUpdated: lastUpdatedRef.current,
      })
      return
    }

    lastFreshAt.current = Date.now()
    lastUpdatedRef.current = map.lastUpdated
    if (map.appliances.length === 0) {
      rowsRef.current = []
      setView({ kind: 'empty' })
      return
    }
    const next = rowsFrom(map.appliances, rowsRef.current)
    rowsRef.current = next
    setView({ kind: 'ready', rows: next, unreachable: false, lastUpdated: map.lastUpdated })
  }

  function deny(reason: DeniedReason) {
    inFlight.current.forEach((controller) => controller.abort())
    inFlight.current.clear()
    rowsRef.current = []
    lastFreshAt.current = null
    lastUpdatedRef.current = null
    setView({ kind: 'denied', reason })
  }

  function finishCommand(id: string, result: CommandResult) {
    inFlight.current.delete(id)
    if (result.status === 'denied') {
      deny(result.reason)
      return
    }
    if (result.status === 'stale') {
      if (result.map) {
        applyMap({ ...result.map, stale: true })
      } else {
        setView((current) =>
          current.kind === 'ready'
            ? { ...current, unreachable: true, rows: current.rows.map((row) => ({ ...row, pending: false })) }
            : current,
        )
      }
      return
    }
    if (result.status === 'confirmed') {
      if (result.map.stale) {
        applyMap(result.map)
        return
      }
      lastFreshAt.current = Date.now()
      lastUpdatedRef.current = result.map.lastUpdated
      const next = applyConfirmed(rowsRef.current, result.map, id)
      rowsRef.current = next
      setView({ kind: 'ready', rows: next, unreachable: false, lastUpdated: result.map.lastUpdated })
      return
    }

    const message = result.status === 'timeout' ? 'Command timed out' : result.message
    const next = rowsRef.current.map((row) =>
      row.id === id ? { ...row, pending: false, error: message } : row,
    )
    rowsRef.current = next
    setView((current) =>
      current.kind === 'ready' && !current.unreachable
        ? { ...current, rows: next }
        : current,
    )
  }

  function toggle(row: Row) {
    if (view.kind !== 'ready' || view.unreachable || row.pending) {
      return
    }
    if (inFlight.current.has(row.id)) {
      return
    }

    const next = view.rows.map((item) =>
      item.id === row.id ? { ...item, pending: true, error: null } : item,
    )
    rowsRef.current = next
    setView({ ...view, rows: next })

    const controller = new AbortController()
    inFlight.current.set(row.id, controller)
    const timeoutId = window.setTimeout(() => controller.abort(), COMMAND_WAIT_MS)
    const wanted = !row.on
    const current = asAppliances(next)

    const request = demo
      ? demoCommand(demo, current, row.id, wanted, controller.signal)
      : postApplianceCommand(row.id, wanted, controller.signal)

    void request
      .then((result) => {
        window.clearTimeout(timeoutId)
        finishCommand(row.id, result)
      })
      .catch((error: unknown) => {
        window.clearTimeout(timeoutId)
        if (error instanceof DOMException && error.name === 'AbortError') {
          finishCommand(row.id, {
            status: 'timeout',
            map: { lastUpdated: lastUpdatedRef.current, stale: false, timeout: true, appliances: current },
          })
        }
      })
  }

  if (view.kind === 'denied') {
    return <Denied reason={view.reason} />
  }

  return (
    <main className="content">
      <h1>Dashboard</h1>
      {view.kind === 'waiting' && (
        <section className="card" aria-labelledby="home-status">
          <h2 id="home-status" className="visually-hidden">
            Home status
          </h2>
          <p className="loader">
            <img className="mark-art snow-spin" src="/assets/freedriver/pages/freedriver-loader.png" alt="" />
            Waiting for home.
          </p>
        </section>
      )}
      {view.kind === 'empty' && (
        <section className="card" aria-labelledby="home-status">
          <h2 id="home-status" className="visually-hidden">
            Home status
          </h2>
          <p className="empty-copy">No appliances yet</p>
        </section>
      )}
      {view.kind === 'ready' && (
        <AppliancePanel
          rows={view.rows}
          unreachable={view.unreachable}
          lastUpdated={view.lastUpdated}
          now={now}
          onToggle={toggle}
        />
      )}
    </main>
  )
}

function initialView(demo: DemoMode | null): View {
  if (demo === 'denied') {
    return { kind: 'denied', reason: 'role' }
  }
  if (demo === 'waiting') {
    return { kind: 'waiting' }
  }
  if (demo === 'empty') {
    return { kind: 'empty' }
  }
  if (demo === 'unreachable') {
    return {
      kind: 'ready',
      rows: rowsFrom(DEMO_APPLIANCES),
      unreachable: true,
      lastUpdated: demoLastUpdated(),
    }
  }
  if (demo === 'live' || demo === 'timeout') {
    return {
      kind: 'ready',
      rows: rowsFrom(DEMO_APPLIANCES),
      unreachable: false,
      lastUpdated: demoLastUpdated(2),
    }
  }
  return { kind: 'waiting' }
}

function Denied({ reason }: { reason: DeniedReason }) {
  return (
    <main className="content status-page">
      <img className="mark-art" src="/assets/freedriver/pages/freedriver-404.png" alt="" />
      <h1>Access denied</h1>
      <p className="lede">
        {reason === 'session'
          ? 'Sign in with a dashboard or portal-admin role.'
          : 'This account needs a dashboard or portal-admin role.'}
      </p>
    </main>
  )
}

function AppliancePanel({
  rows,
  unreachable,
  lastUpdated,
  now,
  onToggle,
}: {
  rows: Row[]
  unreachable: boolean
  lastUpdated: string | null
  now: number
  onToggle: (row: Row) => void
}) {
  const heard = unreachable ? formatLastHeard(lastUpdated, now) : null
  return (
    <section
      className={`card${unreachable ? ' is-unreachable' : ''}`}
      aria-labelledby="appliances-heading"
      aria-busy={unreachable ? undefined : rows.some((row) => row.pending) || undefined}
    >
      <h2 id="appliances-heading">Appliances</h2>
      {unreachable && (
        <div className="unreachable-banner" role="alert">
          <p>Home is unreachable</p>
          {heard && <p className="last-heard">{heard}</p>}
        </div>
      )}
      <ul className={`appliance-list${unreachable ? ' is-frozen' : ''}`}>
        {rows.map((row) => (
          <li key={row.id} className="appliance-row">
            <span className="appliance-name">
              {row.name}
              {unreachable && <span className="last-known">{row.on ? 'On' : 'Off'}</span>}
            </span>
            <button
              type="button"
              className={`switch${row.on ? ' is-on' : ''}${row.pending ? ' is-pending' : ''}`}
              role="switch"
              aria-checked={row.pending ? 'mixed' : row.on}
              aria-busy={row.pending}
              aria-label={row.name}
              disabled={unreachable || row.pending}
              onClick={() => onToggle(row)}
            >
              <span className="switch-thumb" />
            </button>
            {row.error && !unreachable && <p className="switch-error">{row.error}</p>}
          </li>
        ))}
      </ul>
    </section>
  )
}
