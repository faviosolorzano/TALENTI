import { vi } from 'vitest'

/**
 * In-memory stand-ins for WebRTC and the microphone (012-68). They replay provider events so the
 * screen and the call logic can be tested without network. They do NOT prove that real WebRTC,
 * a real microphone or the real provider work: that is the manual test 012-69.
 */

export const FAKE_OFFER = 'v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n'
export const FAKE_ANSWER = 'v=0\r\no=- 3 4 IN IP4 127.0.0.1\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n'

export class FakeDataChannel {
  readyState: RTCDataChannelState = 'connecting'
  sent: Record<string, unknown>[] = []
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onclose: (() => void) | null = null
  closed = false
  readonly label: string
  constructor(label: string) {
    this.label = label
  }
  send(data: string) {
    if (this.readyState !== 'open') throw new Error('channel not open')
    this.sent.push(JSON.parse(data) as Record<string, unknown>)
  }
  close() {
    this.closed = true
    this.readyState = 'closed'
  }
  /** The provider opened the channel. */
  open() {
    this.readyState = 'open'
    this.onopen?.()
  }
  /** The provider sends an event. */
  emit(event: Record<string, unknown>) {
    this.onmessage?.({ data: JSON.stringify(event) })
  }
  /** The channel closes without warning. */
  drop() {
    this.readyState = 'closed'
    this.onclose?.()
  }
  sentTypes() {
    return this.sent.map((message) => message.type)
  }
}

export class FakePeer {
  static instances: FakePeer[] = []
  channels: FakeDataChannel[] = []
  tracks: unknown[] = []
  closed = false
  connectionState: RTCPeerConnectionState = 'new'
  local: RTCSessionDescriptionInit | null = null
  remote: RTCSessionDescriptionInit | null = null
  /** Order of calls, to check that the channel exists before the offer. */
  log: string[] = []
  ontrack: ((event: { streams: unknown[]; track: unknown }) => void) | null = null
  onconnectionstatechange: (() => void) | null = null
  constructor() {
    FakePeer.instances.push(this)
  }
  static get last() {
    return FakePeer.instances.at(-1)!
  }
  static reset() {
    FakePeer.instances = []
  }
  addTrack(track: unknown) {
    this.log.push('addTrack')
    this.tracks.push(track)
  }
  createDataChannel(label: string) {
    this.log.push('createDataChannel:' + label)
    const channel = new FakeDataChannel(label)
    this.channels.push(channel)
    return channel
  }
  async createOffer() {
    this.log.push('createOffer')
    return { type: 'offer' as const, sdp: FAKE_OFFER }
  }
  async setLocalDescription(description: RTCSessionDescriptionInit) {
    this.local = description
  }
  async setRemoteDescription(description: RTCSessionDescriptionInit) {
    this.log.push('setRemoteDescription')
    this.remote = description
  }
  close() {
    this.closed = true
    this.connectionState = 'closed'
  }
  get channel() {
    return this.channels.at(-1)!
  }
  setState(state: RTCPeerConnectionState) {
    this.connectionState = state
    this.onconnectionstatechange?.()
  }
  /** DORA's audio track arrives. */
  remoteAudio() {
    this.ontrack?.({ streams: [fakeStream().stream], track: {} })
  }
}

export function fakeStream() {
  const track = { kind: 'audio', enabled: true, stop: vi.fn() }
  const stream = {
    getTracks: () => [track],
    getAudioTracks: () => [track],
  } as unknown as MediaStream
  return { track, stream }
}

/** A fake browser with WebRTC and a microphone. Returns the handles and a restore function. */
export function installFakeRealtimeBrowser(
  options: { deny?: boolean; permission?: Promise<MediaStream> } = {},
) {
  FakePeer.reset()
  const mic = fakeStream()
  const getUserMedia = vi.fn(() =>
    options.deny
      ? Promise.reject(new DOMException('denied', 'NotAllowedError'))
      : (options.permission ?? Promise.resolve(mic.stream)),
  )
  const previousMedia = Object.getOwnPropertyDescriptor(navigator, 'mediaDevices')
  Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: { getUserMedia } })
  vi.stubGlobal('RTCPeerConnection', FakePeer)
  const play = vi
    .spyOn(HTMLMediaElement.prototype, 'play')
    .mockImplementation(() => Promise.resolve())
  const pause = vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => {})
  return {
    mic,
    getUserMedia,
    restore() {
      play.mockRestore()
      pause.mockRestore()
      if (previousMedia) Object.defineProperty(navigator, 'mediaDevices', previousMedia)
      else Reflect.deleteProperty(navigator, 'mediaDevices')
    },
  }
}

/** A fake AudioContext that reports a fixed volume, for the orb level. */
export function fakeAudioContext(byte = 128) {
  const close = vi.fn(() => Promise.resolve())
  const context = {
    createAnalyser: () => ({
      fftSize: 512,
      getByteTimeDomainData: (bytes: Uint8Array) => bytes.fill(byte),
    }),
    createMediaStreamSource: () => ({ connect: () => {} }),
    close,
  }
  return { context: context as unknown as AudioContext, close }
}
