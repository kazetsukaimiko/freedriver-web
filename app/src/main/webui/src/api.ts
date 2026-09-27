/**
 * Every call the dashboard makes to the portal API goes through apiFetch.
 * X-Requested-With: JavaScript tells Quarkus OIDC that the page handles sign-in itself,
 * so a request without a session gets 499 (java-script-auto-redirect=false) in place of
 * a redirect to the identity provider.
 */
export const REQUESTED_WITH = 'JavaScript'

/** Quarkus OIDC status for a JavaScript request that needs a session. */
export const SIGN_IN_REQUIRED = 499

/** Sign-in start. After sign-in, /login sends the browser to /, which is the dashboard. */
export const SIGN_IN_PATH = '/login'

export function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers)
  headers.set('X-Requested-With', REQUESTED_WITH)
  return fetch(path, { ...init, headers })
}

/** 499 comes from OIDC sign-in; 401 is the same answer while OIDC is off (dev and tests). */
export function signInRequired(response: Response): boolean {
  return response.status === SIGN_IN_REQUIRED || response.status === 401
}

/** Sends the whole page to sign-in. */
export function goToSignIn(): void {
  window.location.replace(SIGN_IN_PATH)
}
