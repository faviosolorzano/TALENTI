import { afterEach, describe, expect, it } from 'vitest'
import { TEST_CATALOG } from '../../test/testCatalog'
import { CATALOG, CATALOG_VERSION, COOKIE_MAX_AGE_SECONDS, COOKIE_NAME } from './catalog'
import {
  acceptAllChoices,
  forgetUnpersistedConsent,
  isAllowed,
  isAllowedIn,
  parseConsent,
  readConsent,
  rejectOptionalChoices,
  serializeConsent,
  writeConsent,
} from './consent'

const now = new Date('2026-10-12T12:00:00Z')
const stored = (value: object) => JSON.stringify(value)

afterEach(() => {
  document.cookie = `${COOKIE_NAME}=; Max-Age=0; Path=/`
})

describe('Cookie module: reading (002-11, 002-16)', () => {
  // 002-11
  it('002-11: treats a missing cookie as "no choice yet"', () => {
    expect(readConsent()).toBeNull()
    expect(parseConsent(null)).toBeNull()
    expect(parseConsent('')).toBeNull()
  })

  // 002-11 and edge case
  it.each([
    ['text that is not JSON', 'esto no es json'],
    ['a JSON number', '42'],
    ['a JSON array', '[1,2]'],
    ['an object without v', stored({ c: { necessary: true }, t: '2026-10-01T00:00:00Z' })],
    ['a v that is not a number', stored({ v: '1', c: {}, t: '2026-10-01T00:00:00Z' })],
    ['a c that is not an object', stored({ v: 1, c: 'si', t: '2026-10-01T00:00:00Z' })],
    ['a date that cannot be read', stored({ v: 1, c: { necessary: true }, t: 'ayer' })],
    ['no date', stored({ v: 1, c: { necessary: true } })],
  ])('002-11: treats a cookie with %s as unreadable, never throwing', (_name, raw) => {
    expect(parseConsent(raw, CATALOG, now)).toBeNull()
  })

  it('treats a cookie of a corrupt encoding in document.cookie as "no choice yet"', () => {
    document.cookie = `${COOKIE_NAME}=%E0%A4%A; Path=/`
    expect(readConsent()).toBeNull()
  })

  // 002-16
  it('002-16: asks again when the cookie is older than 12 months', () => {
    const old = new Date(now.getTime() - (COOKIE_MAX_AGE_SECONDS + 60) * 1000).toISOString()
    expect(parseConsent(stored({ v: 1, c: { necessary: true }, t: old }), CATALOG, now)).toBeNull()
    const recent = new Date(now.getTime() - 30 * 24 * 3600 * 1000).toISOString()
    expect(
      parseConsent(stored({ v: 1, c: { necessary: true }, t: recent }), CATALOG, now),
    ).not.toBeNull()
  })

  // 002-16
  it('002-16: asks again when v is lower than the current CATALOG_VERSION, and not when it is current', () => {
    const raw = (v: number) =>
      stored({ v, c: { necessary: true, analytics: true }, t: '2026-10-01T00:00:00Z' })

    expect(parseConsent(raw(CATALOG_VERSION), CATALOG, now)).not.toBeNull()
    expect(parseConsent(raw(1), TEST_CATALOG, now)).toBeNull()
    expect(parseConsent(raw(2), TEST_CATALOG, now)).not.toBeNull()
  })

  it('ignores unknown categories and values that are not true, and keeps "necessary" on', () => {
    const consent = parseConsent(
      stored({
        v: 2,
        c: { necessary: false, analytics: 'yes', marketing: true },
        t: '2026-10-01T00:00:00Z',
      }),
      TEST_CATALOG,
      now,
    )

    expect(consent?.c).toEqual({ necessary: true, analytics: false })
  })
})

describe('Cookie module: writing (002-13)', () => {
  // 002-13
  it('002-13: writes talenti_cookies with Path=/, SameSite=Lax and Max-Age of 12 months', () => {
    const text = serializeConsent({ v: 1, c: { necessary: true }, t: now.toISOString() }, false)

    expect(text.startsWith(`${COOKIE_NAME}=`)).toBe(true)
    expect(text).toContain('Path=/')
    expect(text).toContain('SameSite=Lax')
    expect(text).toContain(`Max-Age=${COOKIE_MAX_AGE_SECONDS}`)
    expect(COOKIE_MAX_AGE_SECONDS).toBe(31_536_000)
    expect(text).not.toContain('Secure')
    expect(text).not.toContain('HttpOnly')
  })

  // 002-13
  it('002-13: adds Secure when the page is served over HTTPS (production)', () => {
    const text = serializeConsent({ v: 1, c: { necessary: true }, t: now.toISOString() }, true)
    expect(text).toContain('; Secure')
  })

  // 002-13
  it('002-13: holds only the version, the categories and the date: no personal data or identifiers', () => {
    const text = serializeConsent({ v: 1, c: { necessary: true }, t: now.toISOString() }, false)
    const value = decodeURIComponent((text.split(';')[0] as string).slice(COOKIE_NAME.length + 1))

    expect(JSON.parse(value)).toEqual({ v: 1, c: { necessary: true }, t: now.toISOString() })
  })

  // 002-13
  it('002-13: stores what was chosen and reads it back', () => {
    writeConsent(rejectOptionalChoices(), CATALOG, now)

    expect(readConsent()).toEqual({
      v: CATALOG_VERSION,
      c: { necessary: true },
      t: now.toISOString(),
    })
    expect(document.cookie).toContain(`${COOKIE_NAME}=`)
  })

  it('does not break the page when the browser blocks cookies', () => {
    Object.defineProperty(document, 'cookie', {
      configurable: true,
      get: () => '',
      set: () => {
        throw new Error('blocked')
      },
    })
    try {
      expect(() => writeConsent(acceptAllChoices())).not.toThrow()
      // Nothing was stored, but during this visit the page still knows the choice.
      expect(readConsent()?.c).toEqual({ necessary: true })
    } finally {
      delete (document as unknown as { cookie?: string }).cookie
      forgetUnpersistedConsent()
    }
  })

  // B2: the React state and isAllowed() agree when the browser blocks cookies
  it('isAllowed() gives the same answer as the choice just made when cookies are blocked', () => {
    Object.defineProperty(document, 'cookie', {
      configurable: true,
      get: () => '',
      set: () => {
        throw new Error('blocked')
      },
    })
    try {
      const accepted = writeConsent(acceptAllChoices(TEST_CATALOG), TEST_CATALOG)
      expect(isAllowed('analytics', TEST_CATALOG)).toBe(true)
      expect(isAllowedIn(accepted, 'analytics', TEST_CATALOG)).toBe(true)

      const rejected = writeConsent(rejectOptionalChoices(TEST_CATALOG), TEST_CATALOG)
      expect(isAllowed('analytics', TEST_CATALOG)).toBe(false)
      expect(isAllowedIn(rejected, 'analytics', TEST_CATALOG)).toBe(false)
      expect(isAllowed('necessary', TEST_CATALOG)).toBe(true)
    } finally {
      delete (document as unknown as { cookie?: string }).cookie
      forgetUnpersistedConsent()
    }
  })
})

describe('Cookie module: catalog and isAllowed (002-17)', () => {
  it('declares only the necessary category in this version', () => {
    expect(CATALOG.categories.map((category) => [category.id, category.required])).toEqual([
      ['necessary', true],
    ])
  })

  // 002-17
  it('002-17: "necessary" is always allowed, even without a choice', () => {
    expect(isAllowed('necessary')).toBe(true)
    expect(isAllowed('necessary', TEST_CATALOG)).toBe(true)
  })

  it('a category that is not in the catalog is never allowed', () => {
    expect(isAllowed('analytics')).toBe(false)
    expect(isAllowed('inventada', TEST_CATALOG)).toBe(false)
  })

  // 002-17
  it('002-17: an optional category is off until the visitor accepts it', () => {
    expect(isAllowed('analytics', TEST_CATALOG)).toBe(false)

    writeConsent(rejectOptionalChoices(TEST_CATALOG), TEST_CATALOG, new Date())
    expect(isAllowed('analytics', TEST_CATALOG)).toBe(false)

    writeConsent(acceptAllChoices(TEST_CATALOG), TEST_CATALOG, new Date())
    expect(isAllowed('analytics', TEST_CATALOG)).toBe(true)
    expect(isAllowed('necessary', TEST_CATALOG)).toBe(true)
  })

  // 002-17
  it('002-17: a choice made with an older catalog does not allow an optional category', () => {
    writeConsent(acceptAllChoices({ ...TEST_CATALOG, version: 1 }), { ...TEST_CATALOG, version: 1 })

    expect(isAllowed('analytics', TEST_CATALOG)).toBe(false)
  })
})
