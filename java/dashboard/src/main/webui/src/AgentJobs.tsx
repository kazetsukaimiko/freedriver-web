import { useEffect, useRef, useState } from 'react'
import DataTable, { type Api } from 'datatables.net-dt'
import 'datatables.net-dt/css/dataTables.dataTables.css'
import { Denied } from './Dashboard'
import { goToSignIn } from './api'
import { AGENT_JOBS_DENIED, loadAgentJobs, type AgentJob } from './agentJobs'
import './agentJobs.css'

type View =
  | { kind: 'loading' }
  | { kind: 'denied' }
  | { kind: 'error' }
  | { kind: 'ready'; jobs: AgentJob[] }

export function AgentJobs() {
  const [view, setView] = useState<View>({ kind: 'loading' })
  const [open, setOpen] = useState<AgentJob | null>(null)
  const tableRef = useRef<HTMLTableElement>(null)
  const dialogRef = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const controller = new AbortController()
    loadAgentJobs(controller.signal)
      .then((result) => {
        if (result.status === 'login') {
          goToSignIn()
          return
        }
        if (result.status === 'ok') {
          setView({ kind: 'ready', jobs: result.jobs })
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
        <p className="lede">Loading agent jobs.</p>
      </main>
    )
  }
  if (view.kind === 'denied') {
    return <Denied message={AGENT_JOBS_DENIED} />
  }
  if (view.kind === 'error') {
    return (
      <main className="content status-page">
        <h1>Agent jobs</h1>
        <p className="lede">The job list didn't load.</p>
      </main>
    )
  }

  return (
    <main className="content">
      <h1>Agent jobs</h1>
      <table ref={tableRef} className="agent-jobs-table display">
        <thead>
          <tr>
            <th>Job input</th>
            <th>Channel</th>
            <th>Status</th>
            <th>Response</th>
            <th>Document</th>
          </tr>
        </thead>
        <tbody>
          {view.jobs.map((job) => (
            <tr key={job.assignmentId || job.jobId}>
              <td>{job.prompt}</td>
              <td>{job.channel}</td>
              <td>{job.status}</td>
              <td>{job.note}</td>
              <td>
                <button type="button" onClick={() => setOpen(job)}>
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
        aria-labelledby="agent-job-document-title"
        onClose={() => setOpen(null)}
      >
        <div className="agent-jobs-modal-card">
          <h2 id="agent-job-document-title">Job document</h2>
          <pre className="agent-jobs-document">{open ? JSON.stringify(open.document, null, 2) : ''}</pre>
          <button type="button" onClick={() => setOpen(null)}>
            Close
          </button>
        </div>
      </dialog>
    </main>
  )
}
