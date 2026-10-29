import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from './client'
import { streamDora } from './workflows'
vi.mock('./client', () => ({ apiFetch: vi.fn() }))
afterEach(() => vi.clearAllMocks())
describe('DORA streaming transport', () => {
  it('decodes split UTF-8, CRLF and several event frames without losing events', async () => {
    const bytes = new TextEncoder().encode(
      'event: reply.text\r\ndata: {"text":"práctica"}\r\n\r\nevent: turn.done\r\ndata: {"sessionStatus":"COMPLETED"}\r\n\r\n',
    )
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        for (const byte of bytes) controller.enqueue(new Uint8Array([byte]))
        controller.close()
      },
    })
    vi.mocked(apiFetch).mockResolvedValue(new Response(stream))
    const events: unknown[] = []
    const controller = new AbortController()
    await streamDora(
      '/dora/sessions/id/turns',
      { json: { clientTurnId: 'same-turn' }, signal: controller.signal },
      (name, data) => events.push([name, data]),
    )
    expect(events).toEqual([
      ['reply.text', { text: 'práctica' }],
      ['turn.done', { sessionStatus: 'COMPLETED' }],
    ])
    expect(apiFetch).toHaveBeenCalledOnce()
    expect(apiFetch).toHaveBeenCalledWith(
      '/dora/sessions/id/turns',
      expect.objectContaining({
        method: 'POST',
        rawResponse: true,
        signal: controller.signal,
        json: { clientTurnId: 'same-turn' },
      }),
    )
  })
  it('propagates server error events and cancels the unread stream', async () => {
    const cancel = vi.fn()
    const stream = new ReadableStream<Uint8Array>({
      start(c) {
        c.enqueue(new TextEncoder().encode('event: error\ndata: {"code":"quota"}\n\n'))
      },
      cancel,
    })
    vi.mocked(apiFetch).mockResolvedValue(new Response(stream))
    await expect(
      streamDora('/test', { signal: new AbortController().signal }, () => {
        throw Error('server-error')
      }),
    ).rejects.toThrow('server-error')
    expect(cancel).toHaveBeenCalledOnce()
  })
})
