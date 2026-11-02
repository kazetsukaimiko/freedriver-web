import { useEffect, useState } from 'react'
import { goToSignIn } from './api'
import { Denied } from './Dashboard'
import {
  DISPATCHER_PATH,
  EMPTY_JOBS,
  JOBS_FAILED,
  JOB_FAILED,
  jobIdFromPath,
  jobPath,
  loadJob,
  loadJobs,
  type JobRow,
} from './dispatcher'

type View =
  | { kind: 'loading' }
  | { kind: 'denied' }
  | { kind: 'error'; message: string }
  | { kind: 'list'; jobs: JobRow[] }
  | { kind: 'job'; job: JobRow }

export function DispatcherJobs({ path, onOpen }: { path: string; onOpen: (to: string) => void }) {
  const [view, setView] = useState<View>({ kind: 'loading' })
  const selectedId = jobIdFromPath(path)

  useEffect(() => {
    let cancelled = false
    const request = selectedId ? loadJob(selectedId) : loadJobs()
    request.then((result) => {
      if (cancelled) {
        return
      }
      if (result.status === 'login') {
        goToSignIn()
        return
      }
      if (result.status === 'denied') {
        setView({ kind: 'denied' })
        return
      }
      if (result.status === 'ok' && 'jobs' in result) {
        setView({ kind: 'list', jobs: result.jobs })
        return
      }
      if (result.status === 'ok' && 'job' in result) {
        setView({ kind: 'job', job: result.job })
        return
      }
      if (result.status === 'missing') {
        setView({ kind: 'error', message: JOB_FAILED })
        return
      }
      setView({ kind: 'error', message: selectedId ? JOB_FAILED : JOBS_FAILED })
    })
    return () => {
      cancelled = true
    }
  }, [selectedId])

  if (view.kind === 'loading') {
    return (
      <main className="content status-page">
        <p className="lede">Loading dispatcher jobs.</p>
      </main>
    )
  }
  if (view.kind === 'denied') {
    return <Denied message="This account needs a dispatcher role." />
  }
  if (view.kind === 'error') {
    return (
      <main className="content status-page">
        <h1>Dispatcher MCP</h1>
        <p className="lede">{view.message}</p>
      </main>
    )
  }
  if (view.kind === 'job') {
    return (
      <main className="content">
        <p>
          <a href={DISPATCHER_PATH} onClick={(event) => { event.preventDefault(); onOpen(DISPATCHER_PATH) }}>
            All jobs
          </a>
        </p>
        <h1>Dispatcher MCP</h1>
        <JobCard job={view.job} />
      </main>
    )
  }

  return (
    <main className="content">
      <h1>Dispatcher MCP</h1>
      {view.jobs.length === 0 ? (
        <p className="lede">{EMPTY_JOBS}</p>
      ) : (
        <ul className="job-list">
          {view.jobs.map((job) => (
            <li key={job.jobId}>
              <a
                href={jobPath(job.jobId)}
                onClick={(event) => {
                  event.preventDefault()
                  onOpen(jobPath(job.jobId))
                }}
              >
                {job.prompt}
              </a>
              <span className="job-meta">{job.channel}</span>
            </li>
          ))}
        </ul>
      )}
    </main>
  )
}

function JobCard({ job }: { job: JobRow }) {
  return (
    <section className="card" aria-labelledby="job-heading">
      <h2 id="job-heading">{job.prompt}</h2>
      <p className="lede">
        {job.channel} · {job.jobId}
      </p>
      <ul>
        {job.assignments.map((assignment) => (
          <li key={assignment.assignmentId}>
            {assignment.deviceId} {assignment.agentName} {assignment.state}
            {assignment.note ? ` · ${assignment.note}` : ''}
          </li>
        ))}
      </ul>
    </section>
  )
}
