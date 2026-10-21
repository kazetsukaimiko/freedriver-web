import { useEffect, useState } from 'react'
import { WELCOME_KEY } from './invitePhone'

/** One-time note after an invite finishes and the person lands on the dashboard. */
export function InviteWelcome() {
  const [show] = useState(() => sessionStorage.getItem(WELCOME_KEY) === '1')

  useEffect(() => {
    if (show) {
      sessionStorage.removeItem(WELCOME_KEY)
    }
  }, [show])

  if (!show) {
    return null
  }
  return (
    <div className="info-banner" role="status">
      <p>You're set up. Next time, sign in with your phone number.</p>
    </div>
  )
}
