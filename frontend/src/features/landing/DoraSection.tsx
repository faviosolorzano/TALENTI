import { useState } from 'react'
import { AiLabel } from '../../components/AiLabel'
import { Button } from '../../components/Button'
import { usePrefersReducedMotion } from '../../hooks/usePrefersReducedMotion'
import { es } from '../../i18n/es'
import { DoraOrb } from '../dora/DoraOrb'
import { DORA_MODES, type DoraMode } from '../dora/modes'
import { useMicrophone } from '../dora/useMicrophone'
import './DoraSection.css'

/**
 * The DORA section: the four states, the orb and the optional microphone test. The orb
 * reacts only to volume; it does not infer emotions or accent. The voice here is only visual:
 * no sound is played.
 */
export function DoraSection() {
  const [mode, setMode] = useState<DoraMode>('idle')
  const mic = useMicrophone()
  const reducedMotion = usePrefersReducedMotion()
  const micOn = mic.status === 'on'

  function chooseMode(next: DoraMode) {
    // Choosing another state ends the microphone test (as in the approved sample).
    if (micOn) mic.stop()
    setMode(next)
  }

  async function toggleMicrophone() {
    if (micOn) {
      mic.stop()
      setMode('idle')
      return
    }
    if (await mic.start()) setMode('listening')
  }

  return (
    <section className="dora-section" id="dora" aria-labelledby="dora-title" tabIndex={-1}>
      <div className="container dora-section__grid">
        <div>
          <h2 id="dora-title" className="dora-section__title">
            {es.dora.title}
          </h2>
          <p className="dora-section__lead">{es.dora.lead}</p>

          <div className="dora-section__states" role="group" aria-label={es.dora.statesLabel}>
            {DORA_MODES.map((state) => (
              <button
                key={state}
                type="button"
                aria-pressed={mode === state}
                onClick={() => chooseMode(state)}
              >
                {es.dora.states[state]}
              </button>
            ))}
          </div>

          <div className="dora-section__mic">
            <Button variant="quiet" size="sm" onDark onClick={toggleMicrophone}>
              {micOn ? es.dora.mic.stop : es.dora.mic.start}
            </Button>
            <span className="dora-section__mic-message" role="status">
              {micOn ? es.dora.mic.active : mic.status === 'failed' ? es.dora.mic.failed : ''}
            </span>
          </div>
          {reducedMotion && <p className="dora-section__note">{es.dora.reducedMotion}</p>}
        </div>

        <div>
          <DoraOrb mode={mode} getLevel={micOn ? mic.getLevel : undefined} />
          <div className="dora-section__caption" aria-live="polite">
            <AiLabel tone="dark" />
            <p>{es.dora.captions[mode]}</p>
          </div>
        </div>
      </div>
    </section>
  )
}
