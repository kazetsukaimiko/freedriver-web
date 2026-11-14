import { useEffect, useRef, useState } from 'react'
import DataTable, { type Api } from 'datatables.net-dt'
import 'datatables.net-dt/css/dataTables.dataTables.css'
import { Denied } from './Dashboard'
import { goToSignIn } from './api'
import { PHONE_RECORDS_DENIED, loadPhoneRecords, type PhoneRecord } from './phoneRecords'
import './agentJobs.css'

type View =
  | { kind: 'loading' }
  | { kind: 'denied' }
  | { kind: 'error' }
  | { kind: 'ready'; phones: PhoneRecord[] }

export function PhoneRecords() {
  const [view, setView] = useState<View>({ kind: 'loading' })
  const [open, setOpen] = useState<PhoneRecord | null>(null)
  const tableRef = useRef<HTMLTableElement>(null)
  const dialogRef = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const controller = new AbortController()
    loadPhoneRecords(controller.signal)
      .then((result) => {
        if (result.status === 'login') {
          goToSignIn()
          return
        }
        if (result.status === 'ok') {
          setView({ kind: 'ready', phones: result.phones })
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

  useEffect(() => {
    if (view.kind !== 'ready' || !tableRef.current) {
      return
    }
    let api: Api | undefined
    try {
      api = new DataTable(tableRef.current, {
        paging: true,
        searching: true,
        pageLength: 25,
        order: [],
      })
    } catch {
      return
    }
    return () => {
      api?.destroy()
    }
  }, [view])

  useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog) {
      return
    }
    if (open && !dialog.open) {
      dialog.showModal()
    }
    if (!open && dialog.open) {
      dialog.close()
    }
  }, [open])

  if (view.kind === 'loading') {
    return (
      <main className="content status-page">
        <p className="lede">Loading phone records.</p>
      </main>
    )
  }
  if (view.kind === 'denied') {
    return <Denied message={PHONE_RECORDS_DENIED} />
  }
  if (view.kind === 'error') {
    return (
      <main className="content status-page">
        <h1>Phone records</h1>
        <p className="lede">The phone list didn't load.</p>
      </main>
    )
  }

  return (
    <main className="content">
      <h1>Phone records</h1>
      <table ref={tableRef} className="agent-jobs-table display">
        <thead>
          <tr>
            <th>Number</th>
            <th>Name</th>
            <th>Username</th>
            <th>Document</th>
          </tr>
        </thead>
        <tbody>
          {view.phones.map((record) => (
            <tr key={record.id || record.phone}>
              <td>{record.phone}</td>
              <td>{record.name ?? ''}</td>
              <td>{record.username ?? ''}</td>
              <td>
                <button type="button" onClick={() => setOpen(record)}>
                  View document
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <dialog
        ref={dialogRef}
        className="agent-jobs-modal"
        aria-labelledby="phone-record-document-title"
        onClose={() => setOpen(null)}
      >
        <div className="agent-jobs-modal-card">
          <h2 id="phone-record-document-title">Phone document</h2>
          <pre className="agent-jobs-document">{open ? JSON.stringify(open, null, 2) : ''}</pre>
          <button type="button" onClick={() => setOpen(null)}>
            Close
          </button>
        </div>
      </dialog>
    </main>
  )
}
