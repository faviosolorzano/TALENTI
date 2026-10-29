/** Lado mayor y peso máximos de una foto del examen (014-53, 014-54). */
export const SNAPSHOT_MAX_SIDE = 640
export const SNAPSHOT_MAX_BYTES = 150 * 1024
/** Lado mayor del cuadro que analiza el detector (014-46: 320×240 o menos). */
export const ANALYSIS_MAX_SIDE = 320

function fit(width: number, height: number, maxSide: number) {
  const scale = Math.min(1, maxSide / Math.max(width || 1, height || 1))
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
  }
}

function encode(canvas: HTMLCanvasElement, quality: number): Promise<Blob | null> {
  return new Promise((resolve) => {
    try {
      canvas.toBlob((blob) => resolve(blob), 'image/jpeg', quality)
    } catch {
      resolve(null)
    }
  })
}

/**
 * Foto JPEG del cuadro actual: lado mayor ≤ 640 px, calidad 0,7. Si pasara de 150 KB, se reduce calidad y tamaño;
 * si aun así no cabe, no hay foto (nunca se acumulan en el equipo).
 */
export async function captureSnapshot(video: HTMLVideoElement): Promise<Blob | null> {
  if (!video.videoWidth || !video.videoHeight) return null
  const attempts = [
    { side: SNAPSHOT_MAX_SIDE, quality: 0.7 },
    { side: SNAPSHOT_MAX_SIDE, quality: 0.5 },
    { side: 480, quality: 0.5 },
  ]
  for (const { side, quality } of attempts) {
    const size = fit(video.videoWidth, video.videoHeight, side)
    const canvas = document.createElement('canvas')
    canvas.width = size.width
    canvas.height = size.height
    const context = canvas.getContext('2d')
    if (!context) return null
    context.drawImage(video, 0, 0, size.width, size.height)
    const blob = await encode(canvas, quality)
    if (blob && blob.type === 'image/jpeg' && blob.size <= SNAPSHOT_MAX_BYTES) return blob
  }
  return null
}

/** Copia el cuadro actual a un lienzo pequeño para el análisis. Devuelve `null` si aún no hay imagen. */
export function analysisFrame(
  video: HTMLVideoElement,
  canvas: HTMLCanvasElement,
): HTMLCanvasElement | null {
  if (!video.videoWidth || !video.videoHeight) return null
  const size = fit(video.videoWidth, video.videoHeight, ANALYSIS_MAX_SIDE)
  if (canvas.width !== size.width) canvas.width = size.width
  if (canvas.height !== size.height) canvas.height = size.height
  const context = canvas.getContext('2d')
  if (!context) return null
  context.drawImage(video, 0, 0, size.width, size.height)
  return canvas
}
