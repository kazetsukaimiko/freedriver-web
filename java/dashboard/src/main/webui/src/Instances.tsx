import { useEffect, useRef, useState, type FormEvent } from 'react'
import { apiFetch, goToSignIn } from './api'
import { Denied } from './Dashboard'
import {
  BROKER_REFUSED,
  COPY_CREDENTIALS,
  CREATE_HOUSE,
  EMPTY_HOUSES,
  OPEN_HOUSE,
  copyText,
  instanceFailureReason,
  loadInstancesPage,
  type InstanceSummary,
  type ProvisionedInstance,
} from './instances'
import './instances.css'

type View =
  | { kind: 'loading' }
  | { kind: 'denied' }
  | { kind: 'error' }
  | { kind: 'ready'; instances: InstanceSummary[]; csrfToken: string }

export function Instances() {
  const [view, setView] = useState<View>({ kind: 'loading' })
  const [label, setLabel] = useState('')
  const [formError, setFormError] = useState('')
  const [opened, setOpened] = useState<ProvisionedInstance | null>(null)
  const [copied, setCopied] = useState(false)
  const busy = useRef(false)

  function reload() {
    return loadInstancesPage().then((result) => {
      if (result.status === 'login') {
        goToSignIn()
        return
      }
      if (result.status === 'ok') {
        setView({ kind: 'ready', instances: result.instances, csrfToken: result.csrfToken })
        return
      }
      setView({ kind: result.status })
    })
  }

  useEffect(() => {
    const controller = new AbortController()
    loadInstancesPage(controller.signal)
      .then((result) => {
        if (result.status === 'login') {
          goToSignIn()
          return
        }
        if (result.status === 'ok') {
          setView({ kind: 'ready', instances: result.instances, csrfToken: result.csrfToken })
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

  if (view.kind === 'loading') {
    return (
      <main className="content status-page">
        <p className="lede">Loading instances.</p>
      </main>
    )
  }
  if (view.kind === 'denied') {
    return <Denied message="This account needs a portal-admin role." />
  }
  if (view.kind === 'error') {
    return (
      <main className="content status-page">
        <h1>Instances</h1>
        <p className="lede">The house list didn't load.</p>
      </main>
    )
  }

  async function readFailure(response: Response): Promise<string> {
    try {
      return instanceFailureReason(await response.json())
    } catch {
      return BROKER_REFUSED
    }
  }

  async function createHouse() {
    if (busy.current || view.kind !== 'ready') {
      return
    }
    busy.current = true
    setFormError('')
    setCopied(false)
    try {
      const trimmed = label.trim()
      const response = await apiFetch('/api/instances', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-CSRF-Token': view.csrfToken,
        },
        body: JSON.stringify(trimmed ? { label: trimmed } : {}),
      })
      if (signIn(response)) {
        return
      }
      if (!response.ok) {
        setFormError(await readFailure(response))
        await reload()
        return
      }
      const created = (await response.json()) as ProvisionedInstance
      setLabel('')
      setOpened(created)
      await reload()
    } finally {
      busy.current = false
    }
  }

  function submitCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    void createHouse()
  }

  async function openHouse(row: InstanceSummary) {
    if (busy.current || view.kind !== 'ready') {
      return
    }
    busy.current = true
    setFormError('')
    setCopied(false)
    try {
      const response = await apiFetch(`/api/instances/${row.instanceId}/open`, {
        method: 'POST',
        headers: { 'X-CSRF-Token': view.csrfToken },
      })
      if (signIn(response)) {
        return
      }
      if (!response.ok) {
        setFormError(await readFailure(response))
        return
      }
      setOpened((await response.json()) as ProvisionedInstance)
    } finally {
      busy.current = false
    }
  }

  async function copyOpened() {
    if (!opened) {
      return
    }
    try {
      await navigator.clipboard.writeText(copyText(opened))
      setCopied(true)
    } catch {
      setCopied(false)
    }
  }

  return (
    <main className="content instances">
      <div className="instances-head">
        <h1>Instances</h1>
      </div>

      <section className="card">
        <h2>Create house</h2>
        <form onSubmit={submitCreate}>
          <label>
            Name
            <input
              value={label}
              maxLength={64}
              autoComplete="off"
              placeholder="Optional list name"
              onChange={(event) => setLabel(event.target.value)}
            />
          </label>
          {formError && <p role="alert">{formError}</p>}
          <button type="submit">{CREATE_HOUSE}</button>
        </form>
      </section>

      {opened && (
        <section className="card">
          <h2>Opened house</h2>
          <div className="opened-credentials">
            <label>
              Uuid
              <input readOnly value={opened.instanceId} aria-label="House uuid" />
            </label>
            <label>
              Password
              <input readOnly value={opened.password} aria-label="House password" />
            </label>
            <button type="button" onClick={() => void copyOpened()}>
              {copied ? 'Copied' : COPY_CREDENTIALS}
            </button>
          </div>
        </section>
      )}

      <section className="card">
        <h2>Houses</h2>
        {view.instances.length === 0 ? (
          <p>{EMPTY_HOUSES}</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Uuid</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {view.instances.map((row) => (
                <tr key={row.instanceId}>
                  <td>{row.label || '—'}</td>
                  <td>{row.instanceId}</td>
                  <td>
                    <div className="row-actions">
                      <button type="button" onClick={() => void openHouse(row)}>
                        {OPEN_HOUSE}
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </main>
  )
}

function signIn(response: Response) {
  if (response.status === 499 || response.status === 401) {
    goToSignIn()
    return true
  }
  return false
}
