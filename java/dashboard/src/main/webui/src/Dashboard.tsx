import { useEffect, useRef, useState } from 'react'
import {
  COMMAND_WAIT_MS,
  POLL_MS,
  STALE_AFTER_MS,
  type Appliance,
  type CommandResult,
  type DemoMode,
  type Instance,
  demoCommand,
  demoInstance,
  demoModeFromSearch,
  fetchApplianceMap,
  formatLastHeard,
  postApplianceCommand,
} from './dashboard'

type Row = Appliance & { pending: boolean; error: string | null }

type View =
  | { kind: 'waiting' }
  | { kind: 'denied' }
  | { kind: 'empty' }
  | {
      kind: 'ready'
      instances: Instance[]
      selectedId: string
      rows: Row[]
      unreachable: boolean
      lastUpdated: string | null
    }

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

function pickSelected(instances: Instance[], current: string | null): string {
  if (current && instances.some((item) => item.instanceId === current)) {
    return current
  }
  return instances[0]?.instanceId ?? ''
}

export function Dashboard({ search }: { search: string }) {
  const demo = demoModeFromSearch(search)
  const [view, setView] = useState<View>(() => initialView(demo))
  const [now, setNow] = useState(() => Date.now())
  const rowsRef = useRef<Row[]>([])
  const selectedRef = useRef<string | null>(null)
  const lastFreshAt = useRef<number | null>(demo === 'live' || demo === 'timeout' || demo === 'empty' ? Date.now() : null)
  const lastUpdatedRef = useRef<string | null>(null)
  const inFlight = useRef(new Map<string, AbortController>())

  useEffect(() => {
    rowsRef.current = view.kind === 'ready' ? view.rows : []
    selectedRef.current = view.kind === 'ready' ? view.selectedId : null
  }, [view])

  useEffect(() => {
    setView(initialView(demo))
    lastFreshAt.current = demo === 'live' || demo === 'timeout' || demo === 'empty' ? Date.now() : null
    lastUpdatedRef.current = demo === 'unreachable' ? demoInstance(true).lastUpdated : null
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
        if (result.status === 'login') {
          return
        }
        if (result.status === 'denied') {
          lastFreshAt.current = null
          lastUpdatedRef.current = null
          rowsRef.current = []
          setView({ kind: 'denied' })
          return
        }
        if (result.status === 'ok') {
          applyMap(result.map.instances)
          return
        }
        if (lastFreshAt.current != null && Date.now() - lastFreshAt.current >= STALE_AFTER_MS) {
          setView((current) =>
            current.kind === 'ready'
              ? { ...current, unreachable: true }
              : current,
          )
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

  function applyMap(instances: Instance[]) {
    if (instances.length === 0) {
      rowsRef.current = []
      setView({ kind: 'empty' })
      return
    }
    const selectedId = pickSelected(instances, selectedRef.current)
    const selected = instances.find((item) => item.instanceId === selectedId) ?? instances[0]
    const unreachable = selected.stale
    if (!unreachable) {
      lastFreshAt.current = Date.now()
    }
    lastUpdatedRef.current = selected.lastUpdated
    const next = rowsFrom(selected.appliances, rowsRef.current)
    rowsRef.current = next
    setView({
      kind: 'ready',
      instances,
      selectedId: selected.instanceId,
      rows: next,
      unreachable,
      lastUpdated: selected.lastUpdated,
    })
  }

  function deny() {
    inFlight.current.forEach((controller) => controller.abort())
    inFlight.current.clear()
    rowsRef.current = []
    lastFreshAt.current = null
    lastUpdatedRef.current = null
    setView({ kind: 'denied' })
  }

  function revertSwitch(id: string, message: string) {
    const next = rowsRef.current.map((row) =>
      row.id === id ? { ...row, pending: false, error: message } : row,
    )
    rowsRef.current = next
    setView((current) =>
      current.kind === 'ready' && !current.unreachable ? { ...current, rows: next } : current,
    )
  }

  function finishCommand(id: string, result: CommandResult) {
    inFlight.current.delete(id)
    if (result.status === 'login') {
      return
    }
    if (result.status === 'denied') {
      deny()
      return
    }
    if (result.status === 'stale') {
      const instance = result.instance
      if (instance) {
        setView((current) => {
          if (current.kind !== 'ready') {
            return current
          }
          const instances = current.instances.map((item) =>
            item.instanceId === instance.instanceId ? instance : item,
          )
          return {
            ...current,
            instances,
            unreachable: true,
            rows: current.rows.map((row) => ({ ...row, pending: false })),
            lastUpdated: instance.lastUpdated,
          }
        })
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
      const instance = result.instance
      if (!instance) {
        revertSwitch(id, 'Command timed out')
        return
      }
      lastFreshAt.current = Date.now()
      lastUpdatedRef.current = instance.lastUpdated
      setView((current) => {
        if (current.kind !== 'ready') {
          return current
        }
        const instances = current.instances.map((item) =>
          item.instanceId === instance.instanceId ? instance : item,
        )
        const next = rowsFrom(instance.appliances, current.rows.map((row) =>
          row.id === id ? { ...row, pending: false, error: null } : row,
        ))
        rowsRef.current = next
        return {
          ...current,
          instances,
          rows: next,
          unreachable: instance.stale,
          lastUpdated: instance.lastUpdated,
        }
      })
      return
    }

    const message = result.status === 'timeout' ? 'Command timed out' : result.message
    revertSwitch(id, message)
  }

  function toggle(row: Row) {
    if (view.kind !== 'ready' || view.unreachable || row.pending) {
      return
    }
    if (inFlight.current.has(row.id)) {
      return
    }
    const selected = view.instances.find((item) => item.instanceId === view.selectedId)
    if (!selected) {
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

    const request = demo
      ? demoCommand(demo, selected, row.id, wanted, controller.signal)
      : postApplianceCommand(selected.instanceId, row.id, wanted, controller.signal)

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
            instance: { ...selected, timeout: true, appliances: selected.appliances },
          })
        }
      })
  }

  function selectInstance(instanceId: string) {
    if (view.kind !== 'ready' || view.selectedId === instanceId) {
      return
    }
    const selected = view.instances.find((item) => item.instanceId === instanceId)
    if (!selected) {
      return
    }
    const next = rowsFrom(selected.appliances)
    rowsRef.current = next
    selectedRef.current = instanceId
    lastUpdatedRef.current = selected.lastUpdated
    setView({
      ...view,
      selectedId: instanceId,
      rows: next,
      unreachable: selected.stale,
      lastUpdated: selected.lastUpdated,
    })
  }

  if (view.kind === 'denied') {
    return <Denied />
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
          <p className="empty-copy">No homes yet</p>
        </section>
      )}
      {view.kind === 'ready' && (
        <AppliancePanel
          instances={view.instances}
          selectedId={view.selectedId}
          rows={view.rows}
          unreachable={view.unreachable}
          lastUpdated={view.lastUpdated}
          now={now}
          onSelect={selectInstance}
          onToggle={toggle}
        />
      )}
    </main>
  )
}

function initialView(demo: DemoMode | null): View {
  if (demo === 'denied') {
    return { kind: 'denied' }
  }
  if (demo === 'waiting') {
    return { kind: 'waiting' }
  }
  if (demo === 'empty') {
    return { kind: 'empty' }
  }
  if (demo === 'unreachable') {
    const instance = demoInstance(true)
    return {
      kind: 'ready',
      instances: [instance],
      selectedId: instance.instanceId,
      rows: rowsFrom(instance.appliances),
      unreachable: true,
      lastUpdated: instance.lastUpdated,
    }
  }
  if (demo === 'live' || demo === 'timeout') {
    const instance = demoInstance(false)
    return {
      kind: 'ready',
      instances: [instance],
      selectedId: instance.instanceId,
      rows: rowsFrom(instance.appliances),
      unreachable: false,
      lastUpdated: instance.lastUpdated,
    }
  }
  return { kind: 'waiting' }
}

function Denied() {
  return (
    <main className="content status-page">
      <img className="mark-art" src="/assets/freedriver/pages/freedriver-denied.png" alt="" />
      <h1>Access denied</h1>
      <p className="lede">This account needs a dashboard or portal-admin role.</p>
    </main>
  )
}

function AppliancePanel({
  instances,
  selectedId,
  rows,
  unreachable,
  lastUpdated,
  now,
  onSelect,
  onToggle,
}: {
  instances: Instance[]
  selectedId: string
  rows: Row[]
  unreachable: boolean
  lastUpdated: string | null
  now: number
  onSelect: (instanceId: string) => void
  onToggle: (row: Row) => void
}) {
  const heard = unreachable ? formatLastHeard(lastUpdated, now) : null
  const selected = instances.find((item) => item.instanceId === selectedId)
  return (
    <section
      className={`card${unreachable ? ' is-unreachable' : ''}`}
      aria-labelledby="appliances-heading"
      aria-busy={unreachable ? undefined : rows.some((row) => row.pending) || undefined}
    >
      <h2 id="appliances-heading" className="visually-hidden">
        Appliances
      </h2>
      <div className="instance-tabs" role="tablist" aria-label="Homes">
        {instances.map((instance) => (
          <button
            key={instance.instanceId}
            type="button"
            role="tab"
            aria-selected={instance.instanceId === selectedId}
            className={`instance-tab${instance.instanceId === selectedId ? ' is-current' : ''}`}
            onClick={() => onSelect(instance.instanceId)}
          >
            {instance.instanceName}
          </button>
        ))}
      </div>
      {unreachable && (
        <div className="unreachable-banner" role="alert">
          <p>{selected?.instanceName ?? 'Home'} is unreachable</p>
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
