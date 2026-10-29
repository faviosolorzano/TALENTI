/**
 * Presupuesto de eventos en el navegador. El servidor acepta 30 eventos por minuto por intento (014-51, 429); si
 * alguien aprieta Ctrl+C sin parar, no deben perderse los eventos que importan. Por eso:
 * - `SCREEN_LEFT` y `SCREEN_RETURNED` (los que aplican la regla de 014-15) siempre salen;
 * - episodios, cámara y detección salen mientras el total del último minuto sea menor que `important`;
 * - los demás (copiar, pegar, clic derecho, imprimir, foco, conexión) mientras sea menor que `minor`, y como mucho
 *   uno por tipo cada `repeatMs`.
 */
const ALWAYS = new Set(['SCREEN_LEFT', 'SCREEN_RETURNED'])
const IMPORTANT = new Set([
  'GAZE_AWAY',
  'FACE_MISSING',
  'MULTIPLE_FACES',
  'PHONE_VISIBLE',
  'CAMERA_LOST',
  'CAMERA_RESTORED',
  'DETECTION_UNAVAILABLE',
])

export class EventBudget {
  private sent: { at: number; kind: string }[] = []
  private readonly limits: { important: number; minor: number; windowMs: number; repeatMs: number }
  constructor(limits = { important: 26, minor: 16, windowMs: 60000, repeatMs: 2000 }) {
    this.limits = limits
  }

  allow(kind: string, now: number): boolean {
    if (ALWAYS.has(kind)) return true
    this.sent = this.sent.filter((e) => now - e.at < this.limits.windowMs)
    const total = this.sent.length
    if (IMPORTANT.has(kind)) {
      if (total >= this.limits.important) return false
    } else {
      if (total >= this.limits.minor) return false
      if (this.sent.some((e) => e.kind === kind && now - e.at < this.limits.repeatMs)) return false
    }
    this.sent.push({ at: now, kind })
    return true
  }
}
