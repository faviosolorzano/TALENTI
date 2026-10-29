import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../../api/client'
import type {
  HangupResult,
  RealtimeCall,
  RealtimeEvent,
  RealtimeEventNames,
} from '../../../api/realtime'
import {
  FAKE_ANSWER,
  FAKE_OFFER,
  FakePeer,
  fakeAudioContext,
  fakeStream,
} from '../../../test/fakeRealtime'
import {
  CONNECT_TIMEOUT_MS,
  DoraCall,
  MAX_RECONNECTS,
  TOOL_FOLLOW_UP_LIMIT,
  TOOL_OK,
} from './doraCall'

const MINUTE = 60_000

function fakeApi(events: Partial<RealtimeEventNames> = {}) {
  let calls = 0
  const sent: { callId: string; events: RealtimeEvent[]; keepalive: boolean }[] = []
  const api = {
    createCall: vi.fn<(session: string, sdp: string) => Promise<RealtimeCall>>(async () => {
      calls++
      return {
        callId: 'call-' + calls,
        sdp: FAKE_ANSWER,
        endsAt: new Date(Date.now() + 15 * MINUTE).toISOString(),
        warnAt: new Date(Date.now() + 13 * MINUTE).toISOString(),
        startPhase: calls === 1 ? 'GREETING' : 'RESUME',
        startPosition: null,
        events,
      }
    }),
    sendEvents: vi.fn(
      async (session: string, callId: string, list: RealtimeEvent[], keepalive = false) => {
        expect(session).toBe('session-1')
        sent.push({ callId, events: list, keepalive })
        return { accepted: list.length, duplicates: 0, rejected: 0 }
      },
    ),
    hangup: vi.fn<
      (
        session: string,
        callId: string,
        reason: string,
        keepalive?: boolean,
      ) => Promise<HangupResult>
    >(async () => ({ session: {} as never, nextPosition: 2 })),
    finish: vi.fn<(session: string) => Promise<unknown>>(async () => undefined),
  }
  return { api, sent }
}

function setup(options: { events?: Partial<RealtimeEventNames>; deny?: boolean } = {}) {
  const { api, sent } = fakeApi(options.events)
  const mic = fakeStream()
  const audio = fakeAudioContext(200)
  const element = { play: vi.fn(() => Promise.resolve()), pause: vi.fn(), srcObject: null }
  let id = 0
  const onEnded = vi.fn()
  const onFallback = vi.fn()
  const getUserMedia = vi.fn(() =>
    options.deny ? Promise.reject(new Error('denied')) : Promise.resolve(mic.stream),
  )
  const call = new DoraCall({
    sessionId: 'session-1',
    closingNotice: 'AVISO-DE-CIERRE',
    onEnded,
    onFallback,
    deps: {
      createPeer: () => new FakePeer() as unknown as RTCPeerConnection,
      getUserMedia,
      createAudio: () => element as unknown as HTMLAudioElement,
      createAudioContext: () => audio.context,
      api,
      newId: () => 'id-' + ++id,
    },
  })
  return { call, api, sent, mic, audio, element, onEnded, onFallback, getUserMedia }
}

async function connect(options: Parameters<typeof setup>[0] = {}) {
  const ctx = setup(options)
  await ctx.call.start()
  const peer = FakePeer.last
  peer.channel.open()
  return { ...ctx, peer }
}

beforeEach(() => {
  FakePeer.reset()
  vi.useFakeTimers()
})
afterEach(() => {
  vi.useRealTimers()
})

describe('012-59 connection', () => {
  it('012-59: asks for the microphone only on start, sends the offer to the backend and applies its answer', async () => {
    const { call, api, getUserMedia, mic } = setup()
    expect(getUserMedia).not.toHaveBeenCalled()
    const states: string[] = []
    call.subscribe(() => states.push(call.getSnapshot().status))
    await call.start()
    const peer = FakePeer.last
    expect(getUserMedia).toHaveBeenCalledTimes(1)
    expect(peer.tracks).toEqual([mic.track])
    // The channel exists before the offer, so the SDP carries it.
    expect(peer.log.slice(0, 3)).toEqual([
      'addTrack',
      'createDataChannel:oai-events',
      'createOffer',
    ])
    expect(api.createCall).toHaveBeenCalledWith('session-1', FAKE_OFFER)
    expect(peer.remote).toEqual({ type: 'answer', sdp: FAKE_ANSWER })
    expect(call.getSnapshot().status).toBe('connecting')
    peer.channel.open()
    expect(states).toEqual(['preparing', 'connecting', 'connecting', 'listening'])
    // DORA opens the conversation.
    expect(peer.channel.sentTypes()).toEqual(['response.create'])
  })

  it('012-59: uses the channel label and event names given by the backend, not fixed ones', async () => {
    const { call, peer, sent } = await connect({
      events: { channel: 'canal-propio', personTranscript: 'persona.dijo' },
    })
    expect(peer.channels.map((c) => c.label)).toEqual(['oai-events', 'canal-propio'])
    expect(peer.channels[0]!.closed).toBe(true)
    peer.channel.emit({ type: 'persona.dijo', item_id: 'item-1', transcript: 'Hola DORA' })
    peer.channel.emit({
      type: 'conversation.item.input_audio_transcription.completed',
      item_id: 'item-2',
      transcript: 'Nombre por defecto ignorado',
    })
    expect(call.getSnapshot().transcript.map((e) => e.text)).toEqual(['Hola DORA'])
    await vi.advanceTimersByTimeAsync(1000)
    expect(sent[0]!.events).toHaveLength(1)
  })

  it('012-59: without a connection in 10 s it offers retry or turns', async () => {
    const { call } = setup()
    await call.start()
    await vi.advanceTimersByTimeAsync(CONNECT_TIMEOUT_MS)
    expect(call.getSnapshot()).toMatchObject({
      status: 'error',
      problem: 'timeout',
      canRetry: true,
    })
    expect(FakePeer.last.closed).toBe(true)
  })

  it('012-58: a denied microphone stops before any call and offers turns, not retry', async () => {
    const { call, api } = setup({ deny: true })
    await call.start()
    expect(call.getSnapshot()).toMatchObject({
      status: 'error',
      problem: 'mic-denied',
      canRetry: false,
    })
    expect(api.createCall).not.toHaveBeenCalled()
    expect(FakePeer.instances).toHaveLength(0)
  })
})

describe('012-60 interruption, mute and level', () => {
  it('012-60: speech while DORA speaks shows "listening" at once; audio events drive "speaking"', async () => {
    const { call, peer } = await connect()
    peer.channel.emit({ type: 'output_audio_buffer.started' })
    expect(call.getSnapshot().status).toBe('speaking')
    peer.channel.emit({ type: 'input_audio_buffer.speech_started' })
    expect(call.getSnapshot().status).toBe('listening')
    peer.channel.emit({ type: 'output_audio_buffer.started' })
    peer.channel.emit({ type: 'output_audio_buffer.stopped' })
    expect(call.getSnapshot().status).toBe('listening')
  })

  it('012-60: mute disables the track (no new permission) and the orb level drops to zero', async () => {
    const { call, mic, getUserMedia } = await connect()
    expect(call.getLevel()).toBeGreaterThan(0)
    call.setMuted(true)
    expect(mic.track.enabled).toBe(false)
    expect(call.getSnapshot().muted).toBe(true)
    expect(call.getLevel()).toBe(0)
    call.setMuted(false)
    expect(mic.track.enabled).toBe(true)
    expect(getUserMedia).toHaveBeenCalledTimes(1)
  })

  it('012-60: the remote audio plays and feeds the level while DORA speaks', async () => {
    const { call, peer, element } = await connect()
    peer.remoteAudio()
    expect(element.play).toHaveBeenCalled()
    expect(element.srcObject).not.toBeNull()
    peer.channel.emit({ type: 'output_audio_buffer.started' })
    expect(call.getLevel()).toBeGreaterThan(0)
  })
})

describe('012-61 transcript, events and tools', () => {
  it('012-61: transcripts appear live (DORA as partial then final) and leave in one batch within 1 s', async () => {
    const { call, peer, sent } = await connect()
    peer.channel.emit({ type: 'input_audio_buffer.speech_stopped' })
    await vi.advanceTimersByTimeAsync(700)
    peer.channel.emit({ type: 'output_audio_buffer.started' })
    peer.channel.emit({
      type: 'response.output_audio_transcript.delta',
      item_id: 'd1',
      delta: 'Hola, ',
    })
    expect(call.getSnapshot().transcript).toEqual([
      { id: 'dora:d1', speaker: 'DORA', text: 'Hola, ', inputMode: 'VOICE', partial: true },
    ])
    peer.channel.emit({
      type: 'response.output_audio_transcript.done',
      item_id: 'd1',
      transcript: 'Hola, Ana. Soy DORA.',
    })
    peer.channel.emit({
      type: 'conversation.item.input_audio_transcription.completed',
      item_id: 'p1',
      transcript: 'Hola',
    })
    expect(sent).toHaveLength(0)
    await vi.advanceTimersByTimeAsync(1000)
    expect(sent).toHaveLength(1)
    expect(sent[0]!.callId).toBe('call-1')
    expect(sent[0]!.events).toEqual([
      expect.objectContaining({
        type: 'utterance',
        eventId: 'dora:d1',
        speaker: 'DORA',
        text: 'Hola, Ana. Soy DORA.',
        latencyMs: 700,
      }),
      expect.objectContaining({
        type: 'utterance',
        eventId: 'person:p1',
        speaker: 'PERSON',
        inputMode: 'VOICE',
        text: 'Hola',
      }),
    ])
    expect(call.getSnapshot().transcript.every((entry) => !entry.partial)).toBe(true)
  })

  it('012-61: a network failure resends the same events with the same eventId', async () => {
    const { peer, api, sent } = await connect()
    api.sendEvents.mockRejectedValueOnce(new ApiError(0, null))
    peer.channel.emit({
      type: 'conversation.item.input_audio_transcription.completed',
      item_id: 'p1',
      transcript: 'Mi respuesta',
    })
    await vi.advanceTimersByTimeAsync(1000)
    expect(api.sendEvents).toHaveBeenCalledTimes(1)
    expect(sent).toHaveLength(0)
    await vi.advanceTimersByTimeAsync(1000)
    expect(api.sendEvents).toHaveBeenCalledTimes(2)
    expect(api.sendEvents.mock.calls[0]![2]).toEqual(api.sendEvents.mock.calls[1]![2])
    expect(sent[0]!.events[0]).toMatchObject({ eventId: 'person:p1' })
  })

  it('012-61: set_stage answers "ok", a second follow-up of the same question gets the limit, and DORA continues', async () => {
    const { peer, sent } = await connect()
    const tool = (callId: string, args: object) =>
      peer.channel.emit({
        type: 'response.function_call_arguments.done',
        name: 'set_stage',
        call_id: callId,
        arguments: JSON.stringify(args),
      })
    tool('t1', { phase: 'QUESTION', position: 1 })
    tool('t2', { phase: 'FOLLOW_UP', position: 1 })
    tool('t3', { phase: 'FOLLOW_UP', position: 1 })
    tool('t3', { phase: 'FOLLOW_UP', position: 1 })
    const outputs = peer.channel.sent
      .filter((m) => (m.item as { type?: string } | undefined)?.type === 'function_call_output')
      .map((m) => m.item)
    expect(outputs).toEqual([
      { type: 'function_call_output', call_id: 't1', output: TOOL_OK },
      { type: 'function_call_output', call_id: 't2', output: TOOL_OK },
      { type: 'function_call_output', call_id: 't3', output: TOOL_FOLLOW_UP_LIMIT },
    ])
    const before = peer.channel.sentTypes().filter((t) => t === 'response.create').length
    peer.channel.emit({ type: 'response.done', response: { output: [] } })
    expect(peer.channel.sentTypes().filter((t) => t === 'response.create').length).toBe(before + 1)
    await vi.advanceTimersByTimeAsync(1000)
    expect(sent.flatMap((b) => b.events)).toEqual([
      { type: 'stage', eventId: 'stage:t1', phase: 'QUESTION', position: 1 },
      { type: 'stage', eventId: 'stage:t2', phase: 'FOLLOW_UP', position: 1 },
    ])
  })

  it('012-61: end_conversation lets DORA finish her goodbye, hangs up FINISHED and finishes the session', async () => {
    const { call, peer, api, onEnded } = await connect()
    peer.channel.emit({ type: 'output_audio_buffer.started' })
    peer.channel.emit({
      type: 'response.done',
      response: {
        output: [
          { type: 'function_call', name: 'end_conversation', call_id: 'e1', arguments: '{}' },
        ],
      },
    })
    expect(api.hangup).not.toHaveBeenCalled()
    peer.channel.emit({ type: 'output_audio_buffer.stopped' })
    await vi.advanceTimersByTimeAsync(0)
    expect(api.hangup).toHaveBeenCalledWith('session-1', 'call-1', 'FINISHED')
    expect(api.finish).toHaveBeenCalledWith('session-1')
    expect(onEnded).toHaveBeenCalledTimes(1)
    expect(call.getSnapshot().status).toBe('ended')
    expect(peer.closed).toBe(true)
  })

  it('012-61: written text goes to DORA over the channel and is reported as TEXT', async () => {
    const { call, peer, sent } = await connect()
    expect(call.sendText('  Prefiero escribir  ')).toBe(true)
    expect(peer.channel.sent.slice(-2)).toEqual([
      {
        type: 'conversation.item.create',
        item: {
          type: 'message',
          role: 'user',
          content: [{ type: 'input_text', text: 'Prefiero escribir' }],
        },
      },
      { type: 'response.create' },
    ])
    await vi.advanceTimersByTimeAsync(1000)
    expect(sent[0]!.events[0]).toMatchObject({
      speaker: 'PERSON',
      inputMode: 'TEXT',
      text: 'Prefiero escribir',
    })
  })
})

describe('012-62 time limits', () => {
  it('012-62: at warnAt DORA gets the closing note and the person is warned; at endsAt it hangs up', async () => {
    const { call, peer, api } = await connect()
    await vi.advanceTimersByTimeAsync(13 * MINUTE)
    expect(call.getSnapshot().warned).toBe(true)
    expect(peer.channel.sent.at(-1)).toMatchObject({
      type: 'conversation.item.create',
      item: { role: 'system', content: [{ type: 'input_text', text: 'AVISO-DE-CIERRE' }] },
    })
    expect(api.hangup).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(2 * MINUTE)
    expect(api.hangup).toHaveBeenCalledWith('session-1', 'call-1', 'FINISHED')
    expect(call.getSnapshot().status).toBe('ended')
  })
})

describe('012-63 failures', () => {
  it('012-63: ICE failure offers retry; retry closes the old call with ERROR and reconnects with a new offer', async () => {
    const { call, peer, api } = await connect()
    peer.setState('failed')
    expect(call.getSnapshot()).toMatchObject({
      status: 'error',
      problem: 'connection',
      canRetry: true,
    })
    await call.retry()
    expect(api.hangup).toHaveBeenCalledWith('session-1', 'call-1', 'ERROR')
    expect(api.createCall).toHaveBeenCalledTimes(2)
    expect(FakePeer.instances).toHaveLength(2)
  })

  it('012-63: after the allowed reconnections only the turn-based fallback is offered', async () => {
    const { call } = await connect()
    for (let i = 0; i < MAX_RECONNECTS; i++) {
      FakePeer.last.channel.drop()
      expect(call.getSnapshot().canRetry).toBe(true)
      await call.retry()
      FakePeer.last.channel.open()
    }
    FakePeer.last.channel.drop()
    expect(call.getSnapshot()).toMatchObject({ status: 'error', canRetry: false })
  })

  it('012-63: "Seguir por turnos" sends pending events, hangs up FALLBACK and keeps the transcript', async () => {
    const { call, peer, api, sent, onFallback } = await connect()
    peer.channel.emit({
      type: 'conversation.item.input_audio_transcription.completed',
      item_id: 'p1',
      transcript: 'Lo que ya dije',
    })
    peer.setState('failed')
    await call.fallback()
    expect(sent.flatMap((b) => b.events)).toHaveLength(1)
    expect(api.hangup).toHaveBeenCalledWith('session-1', 'call-1', 'FALLBACK')
    expect(onFallback).toHaveBeenCalledWith({ session: {}, nextPosition: 2 }, 'connection', [
      expect.objectContaining({ text: 'Lo que ya dije' }),
    ])
    expect(call.getSnapshot().status).toBe('fallback')
  })

  it('012-63: a 503 from the backend is shown as unavailable; with SPEND_LIMIT there is no retry', async () => {
    const first = setup()
    first.api.createCall.mockRejectedValueOnce(
      new ApiError(503, { code: 'realtime-unavailable', fallback: 'TURN_BASED' }),
    )
    await first.call.start()
    expect(first.call.getSnapshot()).toMatchObject({ problem: 'unavailable', canRetry: true })
    const second = setup()
    second.api.createCall.mockRejectedValueOnce(
      new ApiError(503, { code: 'realtime-unavailable', reason: 'SPEND_LIMIT' }),
    )
    await second.call.start()
    expect(second.call.getSnapshot()).toMatchObject({ problem: 'spend-limit', canRetry: false })
  })

  it('012-63: a call open in another tab (409) is not retried', async () => {
    const { call, api } = setup()
    api.createCall.mockRejectedValueOnce(new ApiError(409, { code: 'realtime-call-open' }))
    await call.start()
    expect(call.getSnapshot()).toMatchObject({ problem: 'call-open', canRetry: false })
  })
})

describe('012-65 cleanup', () => {
  it('012-65: dispose closes channel, connection, tracks and audio context and hangs up USER_LEFT with keepalive', async () => {
    const { call, peer, mic, audio, api, sent } = await connect()
    peer.channel.emit({
      type: 'conversation.item.input_audio_transcription.completed',
      item_id: 'p1',
      transcript: 'Pendiente',
    })
    call.dispose()
    expect(peer.closed).toBe(true)
    expect(peer.channel.closed).toBe(true)
    expect(mic.track.stop).toHaveBeenCalled()
    expect(audio.close).toHaveBeenCalled()
    expect(api.hangup).toHaveBeenCalledWith('session-1', 'call-1', 'USER_LEFT', true)
    await vi.advanceTimersByTimeAsync(0)
    expect(sent).toEqual([expect.objectContaining({ keepalive: true })])
    call.dispose()
    expect(api.hangup).toHaveBeenCalledTimes(1)
  })

  it('012-65: a permission granted after leaving is released at once and no call is created', async () => {
    let grant!: (stream: MediaStream) => void
    const mic = fakeStream()
    const { api } = fakeApi()
    const call = new DoraCall({
      sessionId: 'session-1',
      closingNotice: '',
      deps: {
        createPeer: () => new FakePeer() as unknown as RTCPeerConnection,
        getUserMedia: () => new Promise<MediaStream>((resolve) => (grant = resolve)),
        createAudioContext: () => null,
        api,
      },
    })
    const started = call.start()
    call.dispose()
    grant(mic.stream)
    await started
    expect(mic.track.stop).toHaveBeenCalled()
    expect(api.createCall).not.toHaveBeenCalled()
    expect(FakePeer.instances).toHaveLength(0)
  })
})
