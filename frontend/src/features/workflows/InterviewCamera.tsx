import { useEffect, useId, useRef } from 'react'
import { Button } from '../../components/Button'
import { es } from '../../i18n/es'
import type { InterviewCameraState } from './useInterviewCamera'
import './InterviewCamera.css'

export function InterviewCamera({ camera }: { camera: InterviewCameraState }) {
  const t = es.interviewCamera
  const title = useId()
  const video = useRef<HTMLVideoElement>(null)
  useEffect(() => {
    const node = video.current
    if (node) node.srcObject = camera.stream
    return () => {
      if (node) node.srcObject = null
    }
  }, [camera.stream])
  return (
    <section className="interview-camera" aria-labelledby={title}>
      <div className="interview-camera__image">
        <video
          ref={video}
          autoPlay
          playsInline
          muted
          hidden={!camera.stream}
          aria-label={t.video}
        />
        {!camera.stream && <span>{camera.pending ? t.waiting : t.off}</span>}
      </div>
      <div className="interview-camera__body">
        <h2 id={title}>{t.title}</h2>
        <p className="interview-camera__privacy">{t.privacy}</p>
        {camera.problem && <p role="alert">{t.errors[camera.problem]}</p>}
        <Button
          size="sm"
          variant="quiet"
          disabled={camera.pending}
          onClick={camera.stream ? camera.stop : () => void camera.start()}
        >
          {camera.stream ? t.stop : camera.pending ? t.waiting : camera.problem ? t.retry : t.start}
        </Button>
      </div>
    </section>
  )
}
