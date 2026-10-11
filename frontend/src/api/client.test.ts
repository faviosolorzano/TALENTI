import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch, CSRF_HEADER_NAME, readCookie } from './client'

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('API client', () => {
  const fetchMock = vi.fn<typeof fetch>()

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
    document.cookie = 'XSRF-TOKEN=token-de-prueba'
  })

  afterEach(() => {
    fetchMock.mockReset()
    vi.unstubAllGlobals()
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
  })

  it('calls /api/v1 with the session cookie included', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }))

    const result = await apiFetch<{ ok: boolean }>('/health')

    expect(result).toEqual({ ok: true })
    const [url, init] = fetchMock.mock.calls[0]!
    expect(url).toBe('/api/v1/health')
    expect(init?.credentials).toBe('include')
  })

  it('does not send the CSRF token on read requests', async () => {
    fetchMock.mockResolvedValue(jsonResponse({}))

    await apiFetch('/health')

    const headers = new Headers(fetchMock.mock.calls[0]![1]?.headers)
    expect(headers.has(CSRF_HEADER_NAME)).toBe(false)
  })

  it('sends the CSRF token read from the cookie on requests that change data', async () => {
    fetchMock.mockResolvedValue(jsonResponse({}, 201))

    await apiFetch('/things', { method: 'POST', json: { name: 'x' } })

    const init = fetchMock.mock.calls[0]![1]
    const headers = new Headers(init?.headers)
    expect(headers.get(CSRF_HEADER_NAME)).toBe('token-de-prueba')
    expect(headers.get('Content-Type')).toBe('application/json')
    expect(init?.body).toBe(JSON.stringify({ name: 'x' }))
  })

  it('rejects with ApiError carrying the status and the problem details', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ title: 'Prohibido', status: 403 }, 403))

    const failure = apiFetch('/secret').catch((error: unknown) => error)

    await expect(failure).resolves.toBeInstanceOf(ApiError)
    await expect(failure).resolves.toMatchObject({
      status: 403,
      problem: { title: 'Prohibido' },
    })
  })

  it('resolves with undefined when the response has no content', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))

    await expect(apiFetch('/things/1', { method: 'DELETE' })).resolves.toBeUndefined()
  })

  it('reads a cookie by name and returns null when it does not exist', () => {
    expect(readCookie('a', 'a=1; b=dos%20tres')).toBe('1')
    expect(readCookie('b', 'a=1; b=dos%20tres')).toBe('dos tres')
    expect(readCookie('c', 'a=1; b=2')).toBeNull()
  })
})
