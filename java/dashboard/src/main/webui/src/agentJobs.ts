import { apiFetch, signInRequired } from './api'

export const AGENT_JOBS_PATH = '/agent-jobs'

export const AGENT_JOBS_DENIED = 'This account needs a portal-admin role.'

/** One dispatch assignment, typed by the dispatcher (AssignmentView). */
export type AgentJob = {
  assignmentId: string
  jobId: string
  deviceId: string
  agentName: string
  channel: string
  prompt: string
  state: string
  grokSessionId: string | null
  note: string | null
  on_busy: string | null
}

export type JobsPage =
  | { status: 'login' }
  | { status: 'denied' }
  | { status: 'error' }
  | { status: 'ok'; jobs: AgentJob[] }

export async function loadAgentJobs(signal?: AbortSignal): Promise<JobsPage> {
  const response = await apiFetch('/api/jobs', { signal, headers: { Accept: 'application/json' } })
  if (signInRequired(response)) {
    return { status: 'login' }
  }
  if (response.status === 403) {
    return { status: 'denied' }
  }
  if (!response.ok) {
    return { status: 'error' }
  }
  const body = (await response.json()) as { jobs?: AgentJob[] }
  return { status: 'ok', jobs: Array.isArray(body.jobs) ? body.jobs : [] }
}
