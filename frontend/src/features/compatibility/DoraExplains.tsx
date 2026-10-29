import { useEffect, useId, useRef, useState } from 'react'
import { ApiError } from '../../api/client'
import { fetchDoraExplanation, type DoraExplanation } from '../../api/compatibility'
import { Button } from '../../components/Button'
import { AiLabel } from '../../components/AiLabel'
import { es } from '../../i18n/es'
import { DoraOrb } from '../dora/DoraOrb'
import { useSessionLost } from './useSessionLost'
import './DoraExplains.css'

const t = es.doraExplains

/** Only an explicit request generates text. Collapsing retains it for this visit. */
export function DoraExplains({
  vacancyId,
  available,
  onResult,
}: {
  vacancyId: string
  available: boolean
  onResult: (result: DoraExplanation) => void
}) {
  const id = useId()
  const button = useRef<HTMLButtonElement>(null)
  const controller = useRef<AbortController | null>(null)
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [data, setData] = useState<DoraExplanation | null>(null)
  const [error, setError] = useState('')
  const sessionLost = useSessionLost()
  useEffect(() => () => controller.current?.abort(), [])

  async function load() {
    if (controller.current) return
    const request = new AbortController()
    controller.current = request
    setBusy(true)
    setError('')
    try {
      const result = await fetchDoraExplanation(vacancyId, request.signal)
      if (request.signal.aborted) return
      setData(result)
      onResult(result)
    } catch (cause) {
      if (request.signal.aborted) return
      sessionLost(cause, true)
      setError(
        cause instanceof ApiError && cause.status === 404
          ? t.closed
          : cause instanceof ApiError && cause.status === 409
            ? t.insufficient
            : t.error,
      )
    } finally {
      if (!request.signal.aborted) setBusy(false)
      if (controller.current === request) controller.current = null
    }
  }
  function collapse() {
    setOpen(false)
    button.current?.focus()
  }
  return (
    <div className="dora-explains">
      <button
        type="button"
        ref={button}
        className="dora-explains__trigger btn btn--quiet"
        aria-expanded={open}
        aria-controls={id}
        onClick={() => {
          if (open) collapse()
          else {
            setOpen(true)
            if (!data && !busy) void load()
          }
        }}
      >
        <span className="dora-explains__orb" aria-hidden="true">
          <DoraOrb mode="idle" />
        </span>
        <span>{available ? t.trigger : t.templateTrigger}</span>
        <span aria-hidden="true">{open ? '⌃' : '›'}</span>
      </button>
      <section
        id={id}
        hidden={!open}
        role="region"
        aria-label={t.title}
        aria-busy={busy}
        className="dora-explains__body"
      >
        {busy && (
          <>
            <p role="status">{t.loading}</p>
            <div aria-hidden="true" className="dora-explains__skeleton">
              {[0, 1, 2].map((n) => (
                <span key={n} className="skeleton skeleton--line" />
              ))}
            </div>
          </>
        )}
        {error && (
          <div role="alert">
            <p>{error}</p>
            <Button variant="quiet" onClick={() => void load()}>
              {t.retry}
            </Button>
          </div>
        )}
        {data && (
          <>
            {data.source === 'AI' ? (
              <AiLabel />
            ) : (
              <p className="dora-explains__label">{t.template}</p>
            )}
            {data.fallbackReason === 'PERSONAL_LIMIT' && <p role="note">{t.personalLimit}</p>}
            {(['fit', 'gaps', 'unknown'] as const).map((key) =>
              data.sections[key] ? (
                <div key={key}>
                  <h3>{t[key]}</h3>
                  <p>{data.sections[key]}</p>
                </div>
              ) : null,
            )}
            {data.practice.length > 0 && (
              <div>
                <h3>{t.practice}</h3>
                <ul className="dora-explains__practice">
                  {data.practice.map((item) => (
                    <li key={item.skill.slug}>
                      <h4>
                        {item.skill.name} <small>{t.kind[item.requirementKind]}</small>
                      </h4>
                      <ul>
                        {item.actions.map((action, index) => (
                          <li key={index}>{action}</li>
                        ))}
                      </ul>
                    </li>
                  ))}
                </ul>
              </div>
            )}
            <p className="dora-explains__footnote">{t.disclosure}</p>
          </>
        )}
        <Button variant="quiet" onClick={collapse}>
          {t.hide}
        </Button>
      </section>
    </div>
  )
}
