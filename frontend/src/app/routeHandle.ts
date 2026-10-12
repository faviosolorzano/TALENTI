/** Data attached to each route through `handle`. The layout reads it on every navigation. */
export interface RouteHandle {
  /** Page title (without the app name). */
  title: string
}

export function isRouteHandle(value: unknown): value is RouteHandle {
  return (
    typeof value === 'object' &&
    value !== null &&
    'title' in value &&
    typeof (value as { title: unknown }).title === 'string'
  )
}
