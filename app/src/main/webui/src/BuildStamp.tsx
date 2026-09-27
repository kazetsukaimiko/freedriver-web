import { useEffect, useState, type MouseEvent } from 'react'
import { apiFetch } from './api'
import { demoBuild, publishedBuild } from './build.ts'
import { fetchChangelog } from './changelog'

type BuildStampProps = {
  /** Where the stamp links when the changelog is readable. */
  changelogHref: string
  onOpenChangelog?: (event: MouseEvent<HTMLAnchorElement>) => void
}

/**
 * The build stamp. It links to the changelog only when a GET /api/changelog probe answers 200.
 * On any other answer it stays plain text, and the probe never navigates.
 */
export function BuildStamp({ changelogHref, onOpenChangelog }: BuildStampProps) {
  const [build, setBuild] = useState<string | null>(() => demoBuild())
  const [linked, setLinked] = useState(false)

  useEffect(() => {
    if (build) {
      return
    }
    const controller = new AbortController()

    apiFetch('/api/build', { signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) {
          return null
        }
        return (await response.json()) as { build?: unknown }
      })
      .then((data) => {
        if (!data) {
          return
        }
        setBuild(publishedBuild(data.build))
      })
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') {
          return
        }
      })

    return () => controller.abort()
  }, [build])

  useEffect(() => {
    if (!build) {
      return
    }
    const controller = new AbortController()
    fetchChangelog(controller.signal)
      .then((result) => setLinked(result.status === 'ok'))
      .catch(() => {})
    return () => controller.abort()
  }, [build])

  if (!build) {
    return null
  }

  return (
    <p className="build-stamp" aria-label="Build">
      {linked ? (
        <a href={changelogHref} onClick={onOpenChangelog}>
          {build}
        </a>
      ) : (
        build
      )}
    </p>
  )
}
