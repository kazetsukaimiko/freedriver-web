import { apiFetch, signInRequired } from './api'

export const DISPATCHER_PATH = '/dispatcher'
export const EMPTY_JOBS = 'No dispatcher jobs yet.'
export const JOBS_FAILED = "The job list didn't load."
export const JOB_FAILED = "That job didn't load."

export type AssignmentRow = {
  assignmentId: string
  jobId: string
  deviceId: string
  agentName: string
  channel: string
  prompt: string
  state: string
  grokSessionId: string | null
  note: string | null
}

export type JobRow = {
  jobId: string
  dispatcherSubject: string
  channel: string
  prompt: string
  assignments: AssignmentRow[]
}

export type JobList =
  | { status: 'ok'; jobs: JobRow[] }
  | { status: 'login' }
  | { status: 'denied' }
  | { status: 'error' }

export type JobGet =
  | { status: 'ok'; job: JobRow }
  | { status: 'login' }
  | { status: 'denied' }
  | { status: 'missing' }
  | { status: 'error' }

function asJob(value: unknown): JobRow | null {
  if (!value || typeof value !== 'object') {
    return null
  }
  const record = value as Record<string, unknown>
  if (typeof record.jobId !== 'string' || typeof record.prompt !== 'string') {
    return null
  }
  const jobId = record.jobId
  const prompt = record.prompt
  const assignments = Array.isArray(record.assignments)
    ? record.assignments.flatMap((item) => {
        if (!item || typeof item !== 'object') {
          return []
        }
        const assignment = item as Record<string, unknown>
        if (typeof assignment.assignmentId !== 'string' || typeof assignment.state !== 'string') {
          return []
        }
        return [
          {
            assignmentId: assignment.assignmentId,
            jobId: typeof assignment.jobId === 'string' ? assignment.jobId : jobId,
            deviceId: typeof assignment.deviceId === 'string' ? assignment.deviceId : '',
            agentName: typeof assignment.agentName === 'string' ? assignment.agentName : '',
            channel: typeof assignment.channel === 'string' ? assignment.channel : '',
            prompt: typeof assignment.prompt === 'string' ? assignment.prompt : prompt,
            state: assignment.state,
            grokSessionId: typeof assignment.grokSessionId === 'string' ? assignment.grokSessionId : null,
            note: typeof assignment.note === 'string' ? assignment.note : null,
          },
        ]
      })
    : []
  return {
    jobId,
    dispatcherSubject: typeof record.dispatcherSubject === 'string' ? record.dispatcherSubject : '',
    channel: typeof record.channel === 'string' ? record.channel : '',
    prompt,
    assignments,
  }
}

export function jobPath(jobId: string) {
  return `${DISPATCHER_PATH}/${jobId}`
}

export function jobIdFromPath(pathname: string): string | null {
  if (!pathname.startsWith(DISPATCHER_PATH + '/')) {
    return null
  }
  const id = pathname.slice(DISPATCHER_PATH.length + 1)
  return id.length > 0 ? id : null
}

export function loadJobs(): Promise<JobList> {
  return apiFetch('/api/dispatcher/jobs').then(async (response) => {
    if (signInRequired(response)) {
      return { status: 'login' as const }
    }
    if (response.status === 403) {
      return { status: 'denied' as const }
    }
    if (!response.ok) {
      return { status: 'error' as const }
    }
    const body: unknown = await response.json()
    if (!Array.isArray(body)) {
      return { status: 'error' as const }
    }
    return { status: 'ok' as const, jobs: body.flatMap((item) => {
      const job = asJob(item)
      return job ? [job] : []
    }) }
  })
}

export function loadJob(jobId: string): Promise<JobGet> {
  return apiFetch(`/api/dispatcher/jobs/${jobId}`).then(async (response) => {
    if (signInRequired(response)) {
      return { status: 'login' as const }
    }
    if (response.status === 403) {
      return { status: 'denied' as const }
    }
    if (response.status === 404) {
      return { status: 'missing' as const }
    }
    if (!response.ok) {
      return { status: 'error' as const }
    }
    const job = asJob(await response.json())
    if (!job) {
      return { status: 'error' as const }
    }
    return { status: 'ok' as const, job }
  })
}
