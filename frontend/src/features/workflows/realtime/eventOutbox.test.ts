import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ApiError } from '../../../api/client'
import type { RealtimeEvent } from '../../../api/realtime'
import { EventOutbox, type SendEvents } from './eventOutbox'
import { chooseChannel } from './channel'

const stage = (n: number): RealtimeEvent => ({
  type: 'stage',
  eventId: 'stage:' + n,
  phase: 'QUESTION',
  position: n,
})

beforeEach(() => vi.useFakeTimers())
afterEach(() => vi.useRealTimers())

it('012-61: batches hold at most 50 events and never mix two calls', async () => {
  const send = vi.fn<SendEvents>(async () => ({}))
  const outbox = new EventOutbox(send)
  for (let i = 0; i < 60; i++) outbox.push('call-1', stage(i))
  outbox.push('call-2', stage(99))
  await vi.advanceTimersByTimeAsync(1000)
  expect(send.mock.calls.map(([callId, events]) => [callId, events.length])).toEqual([
    ['call-1', 50],
    ['call-1', 10],
    ['call-2', 1],
  ])
  expect(outbox.pending).toBe(0)
})

it('012-61: a 409 from a closed call drops its events, reports it and does not loop', async () => {
  const onClosed = vi.fn()
  const send = vi.fn<SendEvents>(async () => {
    throw new ApiError(409, { code: 'session-closed' })
  })
  const outbox = new EventOutbox(send, { onClosed })
  outbox.push('call-1', stage(1))
  outbox.push('call-1', stage(2))
  await vi.advanceTimersByTimeAsync(10_000)
  expect(send).toHaveBeenCalledTimes(1)
  expect(onClosed).toHaveBeenCalledTimes(1)
  expect(outbox.pending).toBe(0)
})

it('012-61: retries after network failures wait longer each time, up to a limit', async () => {
  const send = vi.fn<SendEvents>(async () => {
    throw new ApiError(0, null)
  })
  const outbox = new EventOutbox(send, { maxRetryMs: 4000 })
  outbox.push('call-1', stage(1))
  await vi.advanceTimersByTimeAsync(400)
  expect(send).toHaveBeenCalledTimes(1)
  await vi.advanceTimersByTimeAsync(1000)
  expect(send).toHaveBeenCalledTimes(2)
  await vi.advanceTimersByTimeAsync(2000)
  expect(send).toHaveBeenCalledTimes(3)
  await vi.advanceTimersByTimeAsync(4000)
  expect(send).toHaveBeenCalledTimes(4)
  await vi.advanceTimersByTimeAsync(4000)
  expect(send).toHaveBeenCalledTimes(5)
  expect(outbox.pending).toBe(1)
  outbox.dispose()
})

const open = { status: 'IN_PROGRESS', voice: true, channel: 'REALTIME' }
const created = { status: 'CREATED', voice: true }
const available = { provider: 'OPENAI' as const, realtime: { available: true } }

it('012-58: real time only when the backend offers it, the browser supports it and the session allows it', () => {
  expect(chooseChannel(available, created, true)).toEqual({ channel: 'REALTIME' })
  expect(chooseChannel(available, open, true)).toEqual({ channel: 'REALTIME' })
  // A session already going by turns (started so, or after a fallback) stays by turns.
  expect(chooseChannel(available, { ...open, channel: 'TURN_BASED' }, true)).toEqual({
    channel: 'TURN_BASED',
    reason: null,
  })
  expect(chooseChannel(available, { ...created, voice: false }, true).channel).toBe('TURN_BASED')
  expect(chooseChannel(available, { ...created, status: 'COMPLETED' }, true).channel).toBe(
    'TURN_BASED',
  )
  expect(chooseChannel(undefined, created, true)).toEqual({ channel: 'TURN_BASED', reason: null })
})

it('012-58: each reason for going by turns is named', () => {
  const off = (reason?: 'DISABLED' | 'NOT_CONFIGURED' | 'SPEND_LIMIT') => ({
    provider: 'OPENAI' as const,
    realtime: { available: false, reason },
  })
  const reason = (choice: ReturnType<typeof chooseChannel>) =>
    choice.channel === 'TURN_BASED' ? choice.reason : 'REALTIME'
  expect(
    reason(chooseChannel({ provider: 'SIMULATED', realtime: { available: false } }, created, true)),
  ).toBe('simulated')
  expect(reason(chooseChannel(off('DISABLED'), created, true))).toBe('disabled')
  expect(reason(chooseChannel(off('NOT_CONFIGURED'), created, true))).toBe('not-configured')
  expect(reason(chooseChannel(off('SPEND_LIMIT'), created, true))).toBe('spend-limit')
  expect(reason(chooseChannel(available, created, false))).toBe('unsupported')
})
