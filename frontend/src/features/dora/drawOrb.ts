import { withAlpha } from '../../design/color'
import { orbColors } from '../../design/tokens'
import type { OrbFrame } from './orbEngine'

/** Pixel size of the (square) canvas. It is drawn at twice the largest on-screen size. */
export const ORB_CANVAS_SIZE = 880

/** The orb radius is a quarter of the canvas, leaving room for the glow, rings and dots. */
const RADIUS_RATIO = 0.25

function traceEdge(ctx: CanvasRenderingContext2D, frame: OrbFrame, size: number): void {
  const center = size / 2
  const radius = size * RADIUS_RATIO
  const count = frame.edge.length
  const points = frame.edge.map((factor, i) => {
    const angle = (i / count) * Math.PI * 2
    return [center + Math.cos(angle) * radius * factor, center + Math.sin(angle) * radius * factor]
  })
  const at = (i: number): [number, number] => {
    const p = points[((i % count) + count) % count]
    return [p?.[0] ?? center, p?.[1] ?? center]
  }

  // Smooth closed curve through the midpoints of consecutive points.
  ctx.beginPath()
  const [firstX, firstY] = at(0)
  const [secondX, secondY] = at(1)
  ctx.moveTo((firstX + secondX) / 2, (firstY + secondY) / 2)
  for (let i = 1; i <= count; i++) {
    const [px, py] = at(i)
    const [qx, qy] = at(i + 1)
    ctx.quadraticCurveTo(px, py, (px + qx) / 2, (py + qy) / 2)
  }
  ctx.closePath()
}

/** Draws one frame. All the logic is in the frame; this only turns it into pixels. */
export function drawOrb(ctx: CanvasRenderingContext2D, frame: OrbFrame, size: number): void {
  const center = size / 2
  const radius = size * RADIUS_RATIO
  const scale = size / ORB_CANVAS_SIZE
  const { level, glow } = frame

  ctx.clearRect(0, 0, size, size)

  for (const ring of frame.rings) {
    ctx.beginPath()
    ctx.arc(center, center, radius * ring.radius, 0, Math.PI * 2)
    ctx.strokeStyle = withAlpha(orbColors[300], ring.alpha)
    ctx.lineWidth = 3 * scale
    ctx.stroke()
  }

  // Body with glow
  ctx.shadowColor = withAlpha(orbColors[500], 0.35 + glow * 0.4 + level * 0.25)
  ctx.shadowBlur = (50 + glow * 60 + level * 50) * scale
  const body = ctx.createRadialGradient(
    center - radius * 0.3,
    center - radius * 0.4,
    radius * 0.1,
    center,
    center,
    radius * 1.25,
  )
  body.addColorStop(0, orbColors[100])
  body.addColorStop(0.38, orbColors[400])
  body.addColorStop(0.72, orbColors[500])
  body.addColorStop(1, orbColors[700])
  traceEdge(ctx, frame, size)
  ctx.fillStyle = body
  ctx.fill()
  ctx.shadowBlur = 0

  // Inner light that drifts a little, like light on a living surface
  const hx = center + radius * frame.highlight.x
  const hy = center + radius * frame.highlight.y
  const light = ctx.createRadialGradient(hx, hy, 0, hx, hy, radius * 0.7)
  light.addColorStop(0, withAlpha(orbColors.highlight, 0.55))
  light.addColorStop(1, withAlpha(orbColors.highlight, 0))
  traceEdge(ctx, frame, size)
  ctx.fillStyle = light
  ctx.fill()

  for (const dot of frame.dots) {
    ctx.beginPath()
    ctx.arc(center + dot.x * radius, center + dot.y * radius, dot.size * radius, 0, Math.PI * 2)
    ctx.fillStyle = withAlpha(orbColors[300], dot.alpha)
    ctx.fill()
  }
}
