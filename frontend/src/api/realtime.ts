import { apiFetch } from './client'
import type { DoraSession } from './workflows'

/**
 * Real-time channel of DORA (spec 012, amendment 2). The browser never talks to the AI
 * provider with a key: it sends its SDP offer to our backend, which answers with the provider
 * SDP. Every event name used on the data channel comes from the backend (`events`), with the
 * spec values only as a fallback for names the backend leaves out.
 */

export type DoraProvider = 'OPENAI' | 'SIMULATED'
export type RealtimeReason = 'DISABLED' | 'NOT_CONFIGURED' | 'SPEND_LIMIT'

/** `GET /practice/options` → `dora` (012-38). */
export interface DoraAvailability {
  provider: DoraProvider
  realtime: { available: boolean; reason?: RealtimeReason | null }
}

/** Names of the data channel and of its events, as configured in the backend (012-39). */
export interface RealtimeEventNames {
  /** Label of the data channel. */
  channel: string
  /** Final transcription of what the person said. */
  personTranscript: string
  /** Final transcription of what DORA said. */
  doraTranscript: string
  /** Partial transcription of DORA, for the live view. */
  doraTranscriptDelta: string
  /** The person started speaking (also interrupts DORA). */
  speechStarted: string
  /** The person stopped speaking (start of the latency measure). */
  speechStopped: string
  /** DORA's audio started playing. */
  outputAudioStarted: string
  /** DORA's audio stopped or was cut. */
  outputAudioStopped: string
  /** A tool call is complete. */
  functionCall: string
  /** A response of the model finished (it may contain a tool call). */
  responseDone: string
  /** Client → provider: add an item (text, tool output, notice). */
  itemCreate: string
  /** Client → provider: ask for a response. */
  responseCreate: string
}

/** Values of the spec (012-40); used only for names the backend does not send. */
export const DEFAULT_EVENT_NAMES: RealtimeEventNames = {
  channel: 'oai-events',
  personTranscript: 'conversation.item.input_audio_transcription.completed',
  doraTranscript: 'response.output_audio_transcript.done',
  doraTranscriptDelta: 'response.output_audio_transcript.delta',
  speechStarted: 'input_audio_buffer.speech_started',
  speechStopped: 'input_audio_buffer.speech_stopped',
  outputAudioStarted: 'output_audio_buffer.started',
  outputAudioStopped: 'output_audio_buffer.stopped',
  functionCall: 'response.function_call_arguments.done',
  responseDone: 'response.done',
  itemCreate: 'conversation.item.create',
  responseCreate: 'response.create',
}

export function resolveEventNames(
  events: Partial<RealtimeEventNames> | null | undefined,
): RealtimeEventNames {
  const names = { ...DEFAULT_EVENT_NAMES }
  for (const key of Object.keys(names) as (keyof RealtimeEventNames)[]) {
    const value = events?.[key]
    if (typeof value === 'string' && value) names[key] = value
  }
  return names
}

export type RealtimePhase =
  'GREETING' | 'INTRO' | 'QUESTION' | 'FOLLOW_UP' | 'CANDIDATE_QUESTIONS' | 'CLOSING' | 'RESUME'

/** `POST /dora/sessions/{id}/realtime` → 201 (012-39). */
export interface RealtimeCall {
  callId: string
  sdp: string
  endsAt: string
  warnAt: string
  startPhase: RealtimePhase | string
  startPosition: number | null
  events: Partial<RealtimeEventNames>
}

export type Speaker = 'PERSON' | 'DORA'
export type InputMode = 'VOICE' | 'TEXT'

/** One event of `POST .../realtime/events` (012-44). */
export type RealtimeEvent =
  | {
      type: 'utterance'
      eventId: string
      speaker: Speaker
      text: string
      inputMode: InputMode
      occurredAt: string
      latencyMs?: number
    }
  | { type: 'stage'; eventId: string; phase: string; position?: number; occurredAt?: string }

export interface RealtimeEventsResult {
  accepted: number
  duplicates: number
  rejected: number
}

export type HangupReason = 'FINISHED' | 'FALLBACK' | 'USER_LEFT' | 'ERROR'

export interface HangupResult {
  session: DoraSession
  nextPosition: number | null
}

/** Summary of the open session in a 409 `session-open` and in `GET /practice/sessions/open`. */
export interface OpenSession {
  id: string
  mode: 'PRACTICE' | 'INTERVIEW' | string
  status: string
  channel?: string
  applicationId?: string | null
}

const base = (sessionId: string) => '/dora/sessions/' + encodeURIComponent(sessionId) + '/realtime'

export const realtimeApi = {
  createCall(sessionId: string, sdp: string, signal?: AbortSignal) {
    return apiFetch<RealtimeCall>(base(sessionId), { method: 'POST', json: { sdp }, signal })
  },
  sendEvents(sessionId: string, callId: string, events: RealtimeEvent[], keepalive = false) {
    return apiFetch<RealtimeEventsResult>(base(sessionId) + '/events', {
      method: 'POST',
      json: { callId, events },
      keepalive,
    })
  },
  hangup(sessionId: string, callId: string, reason: HangupReason, keepalive = false) {
    return apiFetch<HangupResult>(base(sessionId) + '/hangup', {
      method: 'POST',
      json: { callId, reason },
      keepalive,
    })
  },
  finish(sessionId: string) {
    return apiFetch('/dora/sessions/' + encodeURIComponent(sessionId) + '/finish', {
      method: 'POST',
    })
  },
  /** 200 with the summary, or `null` (204) when there is none. */
  async openSession(signal?: AbortSignal) {
    return (await apiFetch<OpenSession | undefined>('/practice/sessions/open', { signal })) ?? null
  },
}
