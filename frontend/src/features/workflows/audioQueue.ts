/** One audio chunk at a time; release every Blob URL, including blocked/aborted playback. */
export class AudioQueue {
  private queued: Blob[] = []
  private player: HTMLAudioElement | null = null
  private url: string | null = null
  private changed: (playing: boolean, blocked: boolean) => void
  constructor(changed: (playing: boolean, blocked: boolean) => void) {
    this.changed = changed
  }
  enqueue(blob: Blob) {
    if (!blob.type.startsWith('audio/')) return
    this.queued.push(blob)
    if (!this.player) this.next()
  }
  private release() {
    if (this.player) {
      this.player.onended = null
      this.player.onerror = null
      this.player.pause()
      this.player = null
    }
    if (this.url) {
      URL.revokeObjectURL(this.url)
      this.url = null
    }
  }
  private next() {
    this.release()
    const blob = this.queued.shift()
    if (!blob) {
      this.changed(false, false)
      return
    }
    this.url = URL.createObjectURL(blob)
    this.player = new Audio(this.url)
    this.player.onended = () => this.next()
    this.player.onerror = () => {
      this.release()
      this.queued = []
      this.changed(false, false)
    }
    this.resume()
  }
  resume() {
    if (!this.player) return
    this.changed(true, false)
    void this.player.play().catch(() => {
      this.changed(false, true)
    })
  }
  stop() {
    this.queued = []
    this.release()
    this.changed(false, false)
  }
}
