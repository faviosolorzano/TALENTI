import { afterEach, expect, it, vi } from 'vitest'
import { AudioQueue } from './audioQueue'
afterEach(() => vi.unstubAllGlobals())
it('plays in order and revokes every URL on completion and stop', async () => {
  const players: {
    onended: (() => void) | null
    onerror: (() => void) | null
    play: ReturnType<typeof vi.fn>
    pause: ReturnType<typeof vi.fn>
  }[] = []
  vi.stubGlobal(
    'Audio',
    class {
      onended = null
      onerror = null
      play = vi.fn().mockResolvedValue(undefined)
      pause = vi.fn()
      constructor() {
        players.push(this)
      }
    },
  )
  const create = vi.fn().mockReturnValueOnce('blob:a').mockReturnValueOnce('blob:b')
  const revoke = vi.fn()
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: create, revokeObjectURL: revoke }))
  const queue = new AudioQueue(vi.fn())
  queue.enqueue(new Blob(['a'], { type: 'audio/mpeg' }))
  queue.enqueue(new Blob(['b'], { type: 'audio/mpeg' }))
  expect(players).toHaveLength(1)
  players[0]!.onended!()
  expect(players).toHaveLength(2)
  expect(revoke).toHaveBeenCalledWith('blob:a')
  queue.stop()
  expect(revoke).toHaveBeenCalledWith('blob:b')
  expect(players[1]!.pause).toHaveBeenCalled()
})
