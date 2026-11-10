import { apiFetch, signInRequired } from './api'

export const AGENT_JOBS_PATH = '/agent-jobs'

export const AGENT_JOBS_DENIED = 'This account needs a portal-admin role.'

export type AgentJob = {
  assignmentId: string
  jobId: string
  prompt: string
  channel: string
  status: string
  note: string
  document: Record<string, unknown>
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
