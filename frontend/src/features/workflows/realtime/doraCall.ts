import { ApiError } from '../../../api/client'
import {
  DEFAULT_EVENT_NAMES,
  realtimeApi,
  resolveEventNames,
  type HangupReason,
  type HangupResult,
  type InputMode,
  type RealtimeEvent,
  type RealtimeEventNames,
  type Speaker,
} from '../../../api/realtime'
import { levelFromTimeDomain } from '../../dora/micLevel'
import { EventOutbox } from './eventOutbox'

export type CallStatus =
  | 'idle'
  | 'preparing'
  | 'connecting'
  | 'listening'
  | 'speaking'
  | 'reconnecting'
  | 'ending'
  | 'fallback'
  | 'ended'
  | 'error'

export type CallProblem =
  | 'mic-denied'
  | 'unavailable'
  | 'spend-limit'
  | 'call-open'
  | 'session-closed'
  | 'rate-limit'
  | 'timeout'
  | 'connection'

export interface TranscriptEntry {
  id: string
  speaker: Speaker
  text: string
  inputMode: InputMode
  /** DORA is still saying it: the text grows. */
  partial: boolean
}

export interface CallSnapshot {
  status: CallStatus
  problem: CallProblem | null
  canRetry: boolean
  muted: boolean
  audioBlocked: boolean
  /** Server clock limits (012-49), in milliseconds since the epoch. */
  endsAt: number | null
  warnAt: number | null
  warned: boolean
  transcript: TranscriptEntry[]
}

/** Answers to the tools (012-61). They go to the model, not to the person. */
export const TOOL_OK = 'ok'
export const TOOL_FOLLOW_UP_LIMIT = 'límite de repregunta, avanza'
/** Server default of `max-reconnects` (012-52). The backend has the last word. */
export const MAX_RECONNECTS = 2
export const CONNECT_TIMEOUT_MS = 10_000
const DISCONNECTED_GRACE_MS = 5_000
const GOODBYE_WAIT_MS = 6_000
const MAX_TEXT = 4000

type Payload = Record<string, unknown>

export interface DoraCallDeps {
  createPeer?: () => RTCPeerConnection
  getUserMedia?: (constraints: MediaStreamConstraints) => Promise<MediaStream>
  createAudio?: () => HTMLAudioElement
  createAudioContext?: () => AudioContext | null
  api?: Pick<typeof realtimeApi, 'createCall' | 'sendEvents' | 'hangup' | 'finish'>
  now?: () => number
  newId?: () => string
}

export interface DoraCallHandlers {
  /** The conversation ended (hang up + `finish`): the page refreshes the session. */
  onEnded?: () => void
  /** Turn-based fallback accepted by the backend (012-53). */
  onFallback?: (
    result: HangupResult | null,
    problem: CallProblem | null,
    transcript: TranscriptEntry[],
  ) => void
}

export interface DoraCallOptions extends DoraCallHandlers {
  sessionId: string
  /** Message that asks DORA to close when `warnAt` arrives (012-62). */
  closingNotice: string
  deps?: DoraCallDeps
}

/** Does this browser have what the real-time call needs (012-58)? */
export function realtimeSupported(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.RTCPeerConnection === 'function' &&
    typeof window.RTCPeerConnection.prototype?.createDataChannel === 'function' &&
    typeof navigator !== 'undefined' &&
    typeof navigator.mediaDevices?.getUserMedia === 'function'
  )
}

function defaultAudioContext(): AudioContext | null {
  const legacy = window as unknown as { webkitAudioContext?: typeof AudioContext }
  const Context = window.AudioContext ?? legacy.webkitAudioContext
  try {
    return Context ? new Context() : null
  } catch {
    return null
  }
}

function text(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

/**
 * One real-time conversation with DORA over WebRTC (spec 012, amendment 2). The browser asks
 * for the microphone, makes the SDP offer and sends it to OUR backend; the backend answers with
 * the provider SDP. No key ever reaches the browser. The class owns the peer connection, the data
 * channel, the tracks and the audio context, and releases all of them in `dispose`.
 */
export class DoraCall {
  private snapshot: CallSnapshot = {
    status: 'idle',
    problem: null,
    canRetry: false,
    muted: false,
    audioBlocked: false,
    endsAt: null,
    warnAt: null,
    warned: false,
    transcript: [],
  }
  private listeners = new Set<() => void>()
  private readonly deps: Required<DoraCallDeps>
  private readonly options: DoraCallOptions
  private handlers: DoraCallHandlers
  private readonly outbox: EventOutbox
  private names: RealtimeEventNames = DEFAULT_EVENT_NAMES
  private peer: RTCPeerConnection | null = null
  private channel: RTCDataChannel | null = null
  private stream: MediaStream | null = null
  private audio: HTMLAudioElement | null = null
  private context: AudioContext | null = null
  private localAnalyser: AnalyserNode | null = null
  private remoteAnalyser: AnalyserNode | null = null
  private bytes: Uint8Array<ArrayBuffer> | null = null
  private timers = new Set<ReturnType<typeof setTimeout>>()
  private connectTimer: ReturnType<typeof setTimeout> | null = null
  private disconnectTimer: ReturnType<typeof setTimeout> | null = null
  private callId: string | null = null
  private callOpen = false
  private attempts = 0
  private disposed = false
  private closing = false
  private ending = false
  private speechStoppedAt: number | null = null
  private pendingLatency: number | null = null
  private followUps = new Set<number>()
  private lastPosition: number | null = null
  private handledCalls = new Set<string>()
  private responseNeeded = false
  private goodbye: (() => void) | null = null

  constructor(options: DoraCallOptions) {
    this.options = options
    this.handlers = { onEnded: options.onEnded, onFallback: options.onFallback }
    const api = options.deps?.api ?? realtimeApi
    this.deps = {
      createPeer: () => new RTCPeerConnection(),
      getUserMedia: (constraints) => navigator.mediaDevices.getUserMedia(constraints),
      createAudio: () => {
        const element = document.createElement('audio')
        element.autoplay = true
        return element
      },
      createAudioContext: defaultAudioContext,
      now: () => Date.now(),
      newId: () => crypto.randomUUID(),
      ...options.deps,
      api,
    }
    this.outbox = new EventOutbox(
      (callId, events, keepalive) =>
        this.deps.api.sendEvents(options.sessionId, callId, events, keepalive),
      { onClosed: () => this.fail('session-closed') },
    )
  }

  // ---- State for the interface (useSyncExternalStore) ----

  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  getSnapshot = () => this.snapshot

  private update(patch: Partial<CallSnapshot>) {
    this.snapshot = { ...this.snapshot, ...patch }
    for (const listener of this.listeners) listener()
  }

  /** Is there a call the page should not lose by leaving? */
  get active() {
    return ['preparing', 'connecting', 'listening', 'speaking', 'reconnecting', 'ending'].includes(
      this.snapshot.status,
    )
  }

  /** Volume (0 to 1) of whoever is speaking now: DORA while she speaks, otherwise the person. */
  getLevel = (): number => {
    const analyser = this.snapshot.status === 'speaking' ? this.remoteAnalyser : this.localAnalyser
    if (!analyser || !this.bytes) return 0
    if (analyser === this.localAnalyser && this.snapshot.muted) return 0
    analyser.getByteTimeDomainData(this.bytes)
    return levelFromTimeDomain(this.bytes)
  }

  // ---- Connection ----

  /** Asks for the microphone (only now, after the person pressed the button) and connects. */
  async start() {
    if (this.disposed || this.active) return
    this.attempts++
    this.closing = false
    this.update({ status: 'preparing', problem: null, canRetry: false })
    let stream: MediaStream
    try {
      stream = await this.deps.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      })
    } catch {
      if (!this.disposed) this.fail('mic-denied')
      return
    }
    // The person left while the permission was pending: release it (012-F03).
    if (this.disposed) {
      stream.getTracks().forEach((track) => track.stop())
      return
    }
    this.stream = stream
    const track = stream.getAudioTracks()[0]
    if (track) track.enabled = !this.snapshot.muted
    this.context ??= this.deps.createAudioContext()
    this.localAnalyser = this.analyse(stream)

    const peer = this.deps.createPeer()
    this.peer = peer
    for (const item of stream.getTracks()) peer.addTrack(item, stream)
    peer.ontrack = (event) => this.playRemote(event.streams[0] ?? new MediaStream([event.track]))
    peer.onconnectionstatechange = () => this.onPeerState(peer)
    this.openChannel(peer, this.names.channel)
    this.connectTimer = this.later(() => this.fail('timeout'), CONNECT_TIMEOUT_MS)

    try {
      const offer = await peer.createOffer()
      await peer.setLocalDescription(offer)
      if (this.peer !== peer) return
      this.update({ status: 'connecting' })
      const call = await this.deps.api.createCall(this.options.sessionId, offer.sdp ?? '')
      if (this.peer !== peer) {
        // Left or failed while waiting: the backend opened a call nobody will use.
        void this.deps.api
          .hangup(this.options.sessionId, call.callId, this.disposed ? 'USER_LEFT' : 'ERROR', true)
          .catch(() => {})
        return
      }
      this.callId = call.callId
      this.callOpen = true
      const names = resolveEventNames(call.events)
      const endsAt = Date.parse(call.endsAt)
      const warnAt = Date.parse(call.warnAt)
      this.update({
        endsAt: Number.isFinite(endsAt) ? endsAt : null,
        warnAt: Number.isFinite(warnAt) ? warnAt : null,
      })
      await peer.setRemoteDescription({ type: 'answer', sdp: call.sdp })
      if (this.peer !== peer) return
      // The channel must exist before the offer; if the backend names another one, a new
      // channel opens on the same connection (no renegotiation is needed for data channels).
      if (names.channel !== this.names.channel) {
        const first = this.channel
        this.names = names
        this.openChannel(peer, names.channel)
        if (first) {
          first.onclose = null
          first.close()
        }
      } else this.names = names
      this.scheduleLimits()
    } catch (error) {
      if (this.peer !== peer || this.disposed) return
      this.fail(this.problemOf(error))
    }
  }

  /** "Reintentar" after a failure (012-63): closes the old call and connects again. */
  async retry() {
    if (!this.snapshot.canRetry) return
    await this.outbox.flush()
    await this.closeCall('ERROR')
    await this.start()
  }

  /** "Seguir por turnos" (012-63, 012-53): the session continues in the turn-based screen. */
  async fallback() {
    const problem = this.snapshot.problem
    this.closing = true
    this.release()
    await this.outbox.flush()
    let result: HangupResult | null = null
    if (this.callId) {
      try {
        result = await this.deps.api.hangup(this.options.sessionId, this.callId, 'FALLBACK')
      } catch {
        result = null
      }
    }
    this.callOpen = false
    this.update({ status: 'fallback', problem: null, canRetry: false })
    this.handlers.onFallback?.(result, problem, this.snapshot.transcript)
  }

  /** Hang up and close the session (button, `end_conversation` or the time limit). */
  async finishConversation() {
    if (this.ending || this.disposed) return
    this.ending = true
    this.closing = true
    this.update({ status: 'ending' })
    this.release()
    await this.outbox.flush()
    await this.closeCall('FINISHED')
    try {
      await this.deps.api.finish(this.options.sessionId)
    } catch {
      // The server may have closed it already (time limit): the refresh shows the real state.
    }
    this.update({ status: 'ended', problem: null, canRetry: false })
    this.handlers.onEnded?.()
  }

  setMuted(muted: boolean) {
    this.stream?.getAudioTracks().forEach((track) => (track.enabled = !muted))
    this.update({ muted })
  }

  resumeAudio() {
    void this.audio
      ?.play()
      .then(() => this.update({ audioBlocked: false }))
      .catch(() => {})
  }

  /** Written alternative: goes to DORA over the data channel and is reported as TEXT (012-61). */
  sendText(value: string) {
    const content = value.trim().slice(0, MAX_TEXT)
    if (!content || !this.channelOpen()) return false
    this.sendRaw({
      type: this.names.itemCreate,
      item: { type: 'message', role: 'user', content: [{ type: 'input_text', text: content }] },
    })
    this.sendRaw({ type: this.names.responseCreate })
    const eventId = 'text:' + this.deps.newId()
    this.addEntry({
      id: eventId,
      speaker: 'PERSON',
      text: content,
      inputMode: 'TEXT',
      partial: false,
    })
    this.report({
      type: 'utterance',
      eventId,
      speaker: 'PERSON',
      text: content,
      inputMode: 'TEXT',
      occurredAt: new Date(this.deps.now()).toISOString(),
    })
    return true
  }

  /** The screen gives its latest callbacks. */
  setHandlers(handlers: DoraCallHandlers) {
    this.handlers = handlers
  }

  /** The screen is mounted (again, under React's strict mode before anything started). */
  attach() {
    if (!this.disposed || this.callId || this.snapshot.status !== 'idle') return
    this.disposed = false
    this.outbox.revive()
  }

  /**
   * Leaving the page, changing route or unmounting (012-65): closes everything and, if a call
   * is open, tells the backend with `keepalive` so the request survives the page.
   */
  dispose() {
    if (this.disposed) return
    this.disposed = true
    this.closing = true
    this.release()
    this.outbox.flushKeepalive()
    this.outbox.dispose()
    if (this.callId && this.callOpen)
      void this.deps.api
        .hangup(this.options.sessionId, this.callId, 'USER_LEFT', true)
        .catch(() => {})
    this.callOpen = false
    if (this.context) void this.context.close().catch(() => {})
    this.context = null
  }

  // ---- Internals ----

  private openChannel(peer: RTCPeerConnection, label: string) {
    const channel = peer.createDataChannel(label)
    this.channel = channel
    channel.onopen = () => {
      if (this.channel !== channel) return
      if (this.connectTimer) this.clear(this.connectTimer)
      this.connectTimer = null
      this.update({ status: 'listening', problem: null })
      // DORA opens the conversation: greeting, or "retomemos" after a reconnection (012-52).
      this.sendRaw({ type: this.names.responseCreate })
    }
    channel.onmessage = (event: MessageEvent) => {
      if (this.channel !== channel) return
      try {
        this.onProviderEvent(JSON.parse(String(event.data)) as Payload)
      } catch {
        // Not an event we understand: ignore it.
      }
    }
    channel.onclose = () => {
      if (this.channel === channel && !this.closing && !this.disposed) this.fail('connection')
    }
  }

  private onPeerState(peer: RTCPeerConnection) {
    if (this.peer !== peer || this.closing) return
    const state = peer.connectionState
    if (state === 'failed') this.fail('connection')
    else if (state === 'disconnected') {
      this.update({ status: 'reconnecting' })
      if (!this.disconnectTimer)
        this.disconnectTimer = this.later(() => this.fail('connection'), DISCONNECTED_GRACE_MS)
    } else if (state === 'connected' && this.disconnectTimer) {
      this.clear(this.disconnectTimer)
      this.disconnectTimer = null
      if (this.snapshot.status === 'reconnecting') this.update({ status: 'listening' })
    }
  }

  private onProviderEvent(event: Payload) {
    const n = this.names
    const type = text(event.type)
    if (type === n.speechStarted) {
      // The person talks: DORA is cut by the provider and the interface stops showing her (012-60).
      this.goodbye?.()
      this.update({ status: 'listening' })
    } else if (type === n.speechStopped) {
      this.speechStoppedAt = this.deps.now()
    } else if (type === n.outputAudioStarted) {
      if (this.speechStoppedAt !== null) {
        this.pendingLatency = Math.max(0, this.deps.now() - this.speechStoppedAt)
        this.speechStoppedAt = null
      }
      this.update({ status: 'speaking' })
    } else if (type === n.outputAudioStopped) {
      if (this.snapshot.status === 'speaking') this.update({ status: 'listening' })
      this.goodbye?.()
    } else if (type === n.personTranscript) {
      const said = text(event.transcript).trim().slice(0, MAX_TEXT)
      if (!said) return
      const eventId = 'person:' + (text(event.item_id) || this.deps.newId())
      this.addEntry({
        id: eventId,
        speaker: 'PERSON',
        text: said,
        inputMode: 'VOICE',
        partial: false,
      })
      this.report({
        type: 'utterance',
        eventId,
        speaker: 'PERSON',
        text: said,
        inputMode: 'VOICE',
        occurredAt: new Date(this.deps.now()).toISOString(),
      })
    } else if (type === n.doraTranscriptDelta) {
      const id = 'dora:' + (text(event.item_id) || text(event.response_id) || 'live')
      const current = this.snapshot.transcript.find((entry) => entry.id === id)
      this.addEntry({
        id,
        speaker: 'DORA',
        text: (current?.text ?? '') + text(event.delta),
        inputMode: 'VOICE',
        partial: true,
      })
    } else if (type === n.doraTranscript) {
      const said = text(event.transcript).trim().slice(0, MAX_TEXT)
      const id = 'dora:' + (text(event.item_id) || text(event.response_id) || this.deps.newId())
      if (!said) return
      this.addEntry({ id, speaker: 'DORA', text: said, inputMode: 'VOICE', partial: false })
      const latencyMs = this.pendingLatency ?? undefined
      this.pendingLatency = null
      this.report({
        type: 'utterance',
        eventId: id,
        speaker: 'DORA',
        text: said,
        inputMode: 'VOICE',
        occurredAt: new Date(this.deps.now()).toISOString(),
        ...(latencyMs !== undefined ? { latencyMs } : {}),
      })
    } else if (type === n.functionCall) {
      this.onTool(text(event.name), text(event.call_id), text(event.arguments), false)
    } else if (type === n.responseDone) {
      const output = (event.response as Payload | undefined)?.output
      if (Array.isArray(output))
        for (const item of output as Payload[])
          if (item.type === 'function_call')
            this.onTool(text(item.name), text(item.call_id), text(item.arguments), true)
      if (this.responseNeeded && !this.ending) {
        this.responseNeeded = false
        this.sendRaw({ type: n.responseCreate })
      }
    }
  }

  /** `set_stage(phase, position)` and `end_conversation()` (012-61). */
  private onTool(name: string, callId: string, rawArguments: string, responseFinished: boolean) {
    if (!name || !callId || this.handledCalls.has(callId)) return
    this.handledCalls.add(callId)
    if (name === 'end_conversation') {
      this.toolOutput(callId, TOOL_OK)
      this.endAfterGoodbye()
      return
    }
    if (name !== 'set_stage') return
    let args: Payload
    try {
      args = JSON.parse(rawArguments || '{}') as Payload
    } catch {
      args = {}
    }
    const phase = text(args.phase).toUpperCase()
    const position =
      typeof args.position === 'number' && Number.isInteger(args.position) ? args.position : null
    let output = TOOL_OK
    if (phase === 'FOLLOW_UP') {
      const question = position ?? this.lastPosition
      if (question === null || this.followUps.has(question)) output = TOOL_FOLLOW_UP_LIMIT
      else this.followUps.add(question)
    } else if (phase === 'QUESTION' && position !== null) this.lastPosition = position
    if (phase && output === TOOL_OK)
      this.report({
        type: 'stage',
        eventId: 'stage:' + callId,
        phase,
        ...(position !== null ? { position } : {}),
      })
    this.toolOutput(callId, output)
    // DORA continues after the tool: now if her response is over, or when it ends.
    if (responseFinished) this.sendRaw({ type: this.names.responseCreate })
    else this.responseNeeded = true
  }

  private toolOutput(callId: string, output: string) {
    this.sendRaw({
      type: this.names.itemCreate,
      item: { type: 'function_call_output', call_id: callId, output },
    })
  }

  /** Lets DORA finish her goodbye before hanging up, with a limit. */
  private endAfterGoodbye() {
    if (this.goodbye) return
    let done = false
    const end = () => {
      if (done) return
      done = true
      this.goodbye = null
      void this.finishConversation()
    }
    if (this.snapshot.status !== 'speaking') {
      this.later(end, 600)
      return
    }
    this.goodbye = end
    this.later(end, GOODBYE_WAIT_MS)
  }

  private scheduleLimits() {
    const { warnAt, endsAt } = this.snapshot
    const now = this.deps.now()
    if (warnAt !== null)
      this.later(
        () => {
          this.update({ warned: true })
          // DORA receives a note to start closing; the person sees the warning (012-62).
          this.sendRaw({
            type: this.names.itemCreate,
            item: {
              type: 'message',
              role: 'system',
              content: [{ type: 'input_text', text: this.options.closingNotice }],
            },
          })
        },
        Math.max(0, warnAt - now),
      )
    if (endsAt !== null) this.later(() => void this.finishConversation(), Math.max(0, endsAt - now))
  }

  private playRemote(remote: MediaStream) {
    this.audio ??= this.deps.createAudio()
    this.audio.srcObject = remote
    this.remoteAnalyser = this.analyse(remote)
    const playing = this.audio.play?.()
    if (playing && typeof playing.catch === 'function')
      playing.catch(() => !this.disposed && this.update({ audioBlocked: true }))
  }

  private analyse(stream: MediaStream): AnalyserNode | null {
    if (!this.context) return null
    try {
      const analyser = this.context.createAnalyser()
      analyser.fftSize = 512
      this.context.createMediaStreamSource(stream).connect(analyser)
      this.bytes ??= new Uint8Array(new ArrayBuffer(analyser.fftSize))
      return analyser
    } catch {
      return null
    }
  }

  private addEntry(entry: TranscriptEntry) {
    const list = this.snapshot.transcript
    const index = list.findIndex((item) => item.id === entry.id)
    const transcript =
      index === -1 ? [...list, entry] : list.map((item, i) => (i === index ? entry : item))
    this.update({ transcript })
  }

  private report(event: RealtimeEvent) {
    if (this.callId) this.outbox.push(this.callId, event)
  }

  private channelOpen() {
    return this.channel?.readyState === 'open'
  }

  private sendRaw(message: Payload) {
    if (!this.channelOpen()) return
    try {
      this.channel!.send(JSON.stringify(message))
    } catch {
      // The channel closed in between: its own handler reports it.
    }
  }

  private problemOf(error: unknown): CallProblem {
    if (!(error instanceof ApiError)) return 'connection'
    const code = String(error.problem?.code ?? '')
    if (error.status === 503)
      return error.problem?.reason === 'SPEND_LIMIT' ? 'spend-limit' : 'unavailable'
    if (code === 'realtime-call-open') return 'call-open'
    if (code === 'session-closed' || error.status === 404) return 'session-closed'
    if (error.status === 429) return 'rate-limit'
    return 'connection'
  }

  private fail(problem: CallProblem) {
    if (this.disposed || this.ending || ['fallback', 'ended'].includes(this.snapshot.status)) return
    this.closing = true
    this.release()
    const retryable = !['mic-denied', 'spend-limit', 'call-open', 'session-closed'].includes(
      problem,
    )
    this.update({
      status: 'error',
      problem,
      canRetry: retryable && this.attempts <= MAX_RECONNECTS,
    })
  }

  /** Closes the open call on the backend; failures do not block (the server also has a clock). */
  private async closeCall(reason: HangupReason) {
    if (!this.callId || !this.callOpen) return
    this.callOpen = false
    try {
      await this.deps.api.hangup(this.options.sessionId, this.callId, reason)
    } catch {
      // The server closes it by time or inactivity (012-49, 012-51).
    }
  }

  /** Releases connection, channel, tracks and timers. Keeps the transcript and the call id. */
  private release() {
    for (const timer of this.timers) clearTimeout(timer)
    this.timers.clear()
    this.connectTimer = null
    this.disconnectTimer = null
    this.goodbye = null
    const channel = this.channel
    this.channel = null
    if (channel) {
      channel.onopen = channel.onmessage = channel.onclose = null
      try {
        channel.close()
      } catch {
        // Already closed.
      }
    }
    const peer = this.peer
    this.peer = null
    if (peer) {
      peer.ontrack = peer.onconnectionstatechange = null
      try {
        peer.close()
      } catch {
        // Already closed.
      }
    }
    this.stream?.getTracks().forEach((track) => track.stop())
    this.stream = null
    if (this.audio) {
      this.audio.pause?.()
      this.audio.srcObject = null
    }
    this.localAnalyser = this.remoteAnalyser = null
  }

  private later(run: () => void, ms: number) {
    const timer = setTimeout(() => {
      this.timers.delete(timer)
      run()
    }, ms)
    this.timers.add(timer)
    return timer
  }

  private clear(timer: ReturnType<typeof setTimeout>) {
    clearTimeout(timer)
    this.timers.delete(timer)
  }
}
