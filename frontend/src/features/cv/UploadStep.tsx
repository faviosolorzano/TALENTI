import { useRef, useState, type DragEvent } from 'react'
import { ApiError } from '../../api/client'
import { uploadCv, type CvMode, type CvUsage } from '../../api/cv'
import { AiLabel } from '../../components/AiLabel'
import { Button } from '../../components/Button'
import { Notice } from '../../components/Notice'
import { es } from '../../i18n/es'
import { sessionPaths } from '../../session/sessionPaths'
import { useSession } from '../../session/useSession'
import { longDate, uploadErrorMessage } from './cvErrors'
import './UploadStep.css'

const t = es.cv.upload
const MAX_BYTES = 2 * 1024 * 1024

/** Quick check before sending: the server checks again with the real content (009-05). */
function localProblem(file: File): string | null {
  const isPdf = file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf')
  if (!isPdf) return es.cv.errors.notPdf
  if (file.size > MAX_BYTES) return es.cv.errors.tooLarge
  return null
}

interface UploadStepProps {
  /** `undefined` while the options are loading; the page works with only the basic reading. */
  usage: CvUsage | undefined
  /** The upload was accepted (or an analysis was already open): look at the analysis again. */
  onStarted: () => void
}

/**
 * First step: choose a PDF (button, keyboard or drag and drop) and it goes up at once.
 * The informative line sits next to "Subir mi CV": no checkbox and nothing blocks (009-37).
 */
export function UploadStep({ usage, onStarted }: UploadStepProps) {
  const { refresh } = useSession()
  const inputRef = useRef<HTMLInputElement>(null)
  const [mode, setMode] = useState<CvMode>('BASIC')
  const [uploading, setUploading] = useState(false)
  const [dragging, setDragging] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)

  const enhanced = usage?.enhanced
  const showChoice = enhanced !== undefined && enhanced.reason !== 'DISABLED'
  const enhancedOn = enhanced?.available === true
  const effectiveMode: CvMode = enhancedOn ? mode : 'BASIC'

  async function send(file: File) {
    const local = localProblem(file)
    if (local) {
      setProblem(local)
      return
    }
    setProblem(null)
    setUploading(true)
    try {
      await uploadCv(file, effectiveMode)
      onStarted()
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) void refresh()
      // Another analysis is already open: show it instead of an error.
      if (error instanceof ApiError && error.problem?.code === 'analysis-in-progress') {
        onStarted()
        return
      }
      setProblem(uploadErrorMessage(error))
    } finally {
      setUploading(false)
      if (inputRef.current) inputRef.current.value = ''
    }
  }

  function onDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    setDragging(false)
    const file = event.dataTransfer.files[0]
    if (file && !uploading) void send(file)
  }

  return (
    <div className="upload-step">
      {showChoice && enhanced && (
        <fieldset className="upload-step__modes" disabled={uploading}>
          <legend className="upload-step__legend">{t.modeLegend}</legend>
          <label className="upload-mode">
            <input
              type="radio"
              name="cv-mode"
              checked={effectiveMode === 'BASIC'}
              onChange={() => setMode('BASIC')}
            />
            <span className="upload-mode__text">
              <span className="upload-mode__title">{t.basic}</span>
              <span className="upload-mode__note">{t.basicNote}</span>
            </span>
          </label>
          <label className="upload-mode" data-disabled={enhancedOn ? undefined : ''}>
            <input
              type="radio"
              name="cv-mode"
              checked={effectiveMode === 'ENHANCED'}
              disabled={!enhancedOn}
              onChange={() => setMode('ENHANCED')}
            />
            <span className="upload-mode__text">
              <span className="upload-mode__title">
                {t.enhanced} <AiLabel />
              </span>
              <span className="upload-mode__note">
                {enhancedOn
                  ? t.enhancedLeft(Math.max(0, enhanced.limit - enhanced.used), enhanced.limit)
                  : enhanced.reason === 'PERSONAL_LIMIT'
                    ? t.limitUsed(enhanced.limit, longDate(enhanced.resetsAt))
                    : t.limitGlobal}
              </span>
              {enhancedOn && effectiveMode === 'ENHANCED' && (
                <span className="upload-mode__note">{t.enhancedSend}</span>
              )}
            </span>
          </label>
        </fieldset>
      )}

      <div
        className="upload-step__drop"
        data-dragging={dragging ? '' : undefined}
        onDragOver={(event) => {
          event.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
      >
        <input
          ref={inputRef}
          type="file"
          accept="application/pdf,.pdf"
          className="visually-hidden"
          tabIndex={-1}
          aria-hidden="true"
          data-testid="cv-file"
          onChange={(event) => {
            const file = event.target.files?.[0]
            if (file) void send(file)
          }}
        />
        <Button onClick={() => inputRef.current?.click()} disabled={uploading}>
          {uploading ? t.uploading : t.button}
        </Button>
        <p className="upload-step__hint">{dragging ? t.dropActive : t.dropHint}</p>
        <p className="upload-step__info">{t.info}</p>
        <p className="upload-step__help">{t.help}</p>
      </div>

      {uploading && (
        <p className="visually-hidden" role="status">
          {t.uploading}
        </p>
      )}

      {problem && (
        <Notice tone="error" announce="alert">
          <p>{problem}</p>
          <p>
            <Button to={sessionPaths.profile} variant="quiet" size="sm">
              {es.cv.manual}
            </Button>
          </p>
        </Notice>
      )}
    </div>
  )
}
