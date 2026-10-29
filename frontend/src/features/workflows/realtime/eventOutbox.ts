import { ApiError } from '../../../api/client'
import type { RealtimeEvent } from '../../../api/realtime'

export type SendEvents = (
  callId: string,
  events: RealtimeEvent[],
  keepalive: boolean,
) => Promise<unknown>

interface OutboxOptions {
  /** A pushed event leaves in at most this time (012-61: lots of ≤ 1 s). */
  delayMs?: number
  /** Backend limit per request (012-44). */
  maxBatch?: number
  /** Longest wait between retries after a network failure. */
  maxRetryMs?: number
  /** The backend says the call or the session is closed: nothing else will be accepted. */
  onClosed?: () => void
}

/** Answers that will never change by sending the same batch again. */
const FINAL_STATUSES = new Set([400, 404, 409, 413, 422])

/**
 * Sends the conversation events to the backend in batches. Each event keeps its `eventId`, so a
 * batch that failed because of the network is sent again as it was and the backend counts the
 * repeated ones as duplicates (012-44). Batches never mix calls.
 */
export class EventOutbox {
  private queue: { callId: string; event: RealtimeEvent }[] = []
  private timer: ReturnType<typeof setTimeout> | null = null
  private inFlight: Promise<boolean> | null = null
  private retryMs = 0
  private disposed = false
  private readonly delayMs: number
  private readonly maxBatch: number
  private readonly maxRetryMs: number
  private readonly send: SendEvents
  private readonly onClosed?: () => void

  constructor(send: SendEvents, options: OutboxOptions = {}) {
    this.send = send
    this.delayMs = options.delayMs ?? 400
    this.maxBatch = options.maxBatch ?? 50
    this.maxRetryMs = options.maxRetryMs ?? 8000
    this.onClosed = options.onClosed
  }

  get pending(): number {
    return this.queue.length
  }

  push(callId: string, event: RealtimeEvent) {
    if (this.disposed) return
    this.queue.push({ callId, event })
    this.schedule(this.retryMs || this.delayMs)
  }

  /** Sends everything now. Resolves `true` when nothing is left to send. */
  flush(): Promise<boolean> {
    if (this.timer) {
      clearTimeout(this.timer)
      this.timer = null
    }
    if (!this.inFlight) {
      this.inFlight = this.drain().then((done) => {
        this.inFlight = null
        if (!done && this.queue.length) this.schedule(this.retryMs)
        return done
      })
    }
    return this.inFlight
  }

  /** Last attempt when the page goes away: `keepalive` lets the request outlive the page. */
  flushKeepalive() {
    this.stopTimer()
    const groups = new Map<string, RealtimeEvent[]>()
    for (const item of this.queue)
      groups.set(item.callId, [...(groups.get(item.callId) ?? []), item.event])
    this.queue = []
    for (const [callId, events] of groups)
      for (let i = 0; i < events.length; i += this.maxBatch)
        void this.send(callId, events.slice(i, i + this.maxBatch), true).catch(() => {})
  }

  revive() {
    this.disposed = false
  }

  dispose() {
    this.disposed = true
    this.stopTimer()
  }

  private stopTimer() {
    if (this.timer) clearTimeout(this.timer)
    this.timer = null
  }

  private schedule(delay: number) {
    if (this.timer || this.inFlight || this.disposed) return
    this.timer = setTimeout(() => {
      this.timer = null
      void this.flush()
    }, delay)
  }

  private async drain(): Promise<boolean> {
    while (this.queue.length) {
      const callId = this.queue[0]!.callId
      let size = 0
      while (
        size < this.queue.length &&
        size < this.maxBatch &&
        this.queue[size]!.callId === callId
      )
        size++
      const batch = this.queue.slice(0, size)
      try {
        await this.send(
          callId,
          batch.map((item) => item.event),
          false,
        )
        this.queue.splice(0, size)
        this.retryMs = 0
      } catch (error) {
        if (error instanceof ApiError && FINAL_STATUSES.has(error.status)) {
          this.queue.splice(0, size)
          if (error.status === 409) {
            this.queue = this.queue.filter((item) => item.callId !== callId)
            this.onClosed?.()
          }
          continue
        }
        // Network failure, 429 or 5xx: keep the same events (same eventId) and try later.
        this.retryMs = Math.min(this.maxRetryMs, Math.max(1000, this.retryMs * 2))
        return false
      }
    }
    return true
  }
}
