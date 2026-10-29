/**
 * Cámara y micrófono elegidos en "Comprobar cámara y audio". Se guardan solo en este navegador para que DORA y el
 * examen usen el mismo dispositivo; si no hay elección (o el almacenamiento no está disponible) se usa el
 * predeterminado del sistema.
 */
export type MediaKind = 'camera' | 'microphone'

const KEYS: Record<MediaKind, string> = {
  camera: 'talenti.media.camera',
  microphone: 'talenti.media.microphone',
}

export function preferredDevice(kind: MediaKind): string | null {
  try {
    return window.localStorage.getItem(KEYS[kind]) || null
  } catch {
    return null
  }
}

export function savePreferredDevice(kind: MediaKind, deviceId: string | null) {
  try {
    if (deviceId) window.localStorage.setItem(KEYS[kind], deviceId)
    else window.localStorage.removeItem(KEYS[kind])
  } catch {
    // Sin almacenamiento (ventana privada, sitio bloqueado): se sigue con el predeterminado.
  }
}

/** Restricción de dispositivo: "ideal" para que, si el elegido ya no existe, el navegador use otro en vez de fallar. */
export function deviceConstraint(kind: MediaKind): { deviceId?: { ideal: string } } {
  const id = preferredDevice(kind)
  return id ? { deviceId: { ideal: id } } : {}
}

/** Dispositivos del tipo pedido. Los nombres solo llegan después de dar permiso; sin nombre se numeran. */
export async function listDevices(kind: MediaKind): Promise<{ id: string; label: string }[]> {
  if (!navigator.mediaDevices?.enumerateDevices) return []
  try {
    const wanted = kind === 'camera' ? 'videoinput' : 'audioinput'
    const devices = (await navigator.mediaDevices.enumerateDevices()).filter(
      (d) => d.kind === wanted && d.deviceId,
    )
    return devices.map((d, i) => ({ id: d.deviceId, label: d.label || String(i + 1) }))
  } catch {
    return []
  }
}
