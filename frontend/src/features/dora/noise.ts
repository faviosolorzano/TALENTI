/**
 * Gradient noise (Perlin, 2D). It gives the orb a soft, organic deformation that never repeats
 * in an obvious way, unlike a sum of sine waves. Pure and deterministic: the same seed gives
 * the same noise.
 */

export type Noise2D = (x: number, y: number) => number

/** Small seeded pseudo-random generator (mulberry32). Returns numbers in [0, 1). */
export function createRandom(seed: number): () => number {
  let state = seed >>> 0
  return () => {
    state = (state + 0x6d2b79f5) >>> 0
    let t = state
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

const fade = (k: number) => k * k * k * (k * (k * 6 - 15) + 10)
const lerp = (a: number, b: number, k: number) => a + (b - a) * k

/** Gradient of one of eight directions dotted with the offset (x, y). */
function grad(hash: number, x: number, y: number): number {
  switch (hash & 7) {
    case 0:
      return x + y
    case 1:
      return -x + y
    case 2:
      return x - y
    case 3:
      return -x - y
    case 4:
      return x
    case 5:
      return -x
    case 6:
      return y
    default:
      return -y
  }
}

/** Creates a noise function for a seed. The result is continuous and always within [-1, 1]. */
export function createNoise2D(seed: number): Noise2D {
  const random = createRandom(seed)
  const order = Array.from({ length: 256 }, (_, i) => i)
  for (let i = 255; i > 0; i--) {
    const j = Math.floor(random() * (i + 1))
    const a = order[i] ?? 0
    order[i] = order[j] ?? 0
    order[j] = a
  }
  const perm = new Uint8Array(512)
  for (let i = 0; i < 512; i++) perm[i] = order[i & 255] ?? 0
  const p = (i: number) => perm[i] ?? 0

  return (xIn, yIn) => {
    const floorX = Math.floor(xIn)
    const floorY = Math.floor(yIn)
    const X = floorX & 255
    const Y = floorY & 255
    const x = xIn - floorX
    const y = yIn - floorY
    const u = fade(x)
    const v = fade(y)
    const a = p(X) + Y
    const b = p(X + 1) + Y
    const value = lerp(
      lerp(grad(p(a), x, y), grad(p(b), x - 1, y), u),
      lerp(grad(p(a + 1), x, y - 1), grad(p(b + 1), x - 1, y - 1), u),
      v,
    )
    return Math.max(-1, Math.min(1, value))
  }
}
