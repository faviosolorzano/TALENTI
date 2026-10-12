import { expect, test } from '@playwright/test'

// 003-03: with the real CSP of nginx (default-src 'self'), `/` loads only from its own origin,
// raises no CSP violation, and shows the self-hosted fonts.
test.describe('Same-origin loading and fonts with the real CSP (003-03)', () => {
  test('every request goes to the same origin, with no CSP violation and no console error', async ({
    page,
    baseURL,
  }) => {
    const origin = new URL(baseURL ?? 'http://localhost:3001').origin
    const requests: string[] = []
    const consoleErrors: string[] = []
    page.on('request', (request) => requests.push(request.url()))
    page.on('console', (message) => {
      if (message.type() === 'error') consoleErrors.push(message.text())
    })
    await page.addInitScript(() => {
      ;(window as unknown as { __cspViolations: string[] }).__cspViolations = []
      document.addEventListener('securitypolicyviolation', (event) => {
        ;(window as unknown as { __cspViolations: string[] }).__cspViolations.push(
          `${event.violatedDirective} ${event.blockedURI}`,
        )
      })
    })

    await page.goto('/')
    await page.evaluate(() => document.fonts.ready)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()

    const external = requests.filter((url) => !url.startsWith(origin) && !url.startsWith('data:'))
    expect(external).toEqual([])
    expect(requests.some((url) => /googleapis|gstatic/.test(url))).toBe(false)
    expect(
      await page.evaluate(() => (window as never as { __cspViolations: string[] }).__cspViolations),
    ).toEqual([])
    expect(consoleErrors).toEqual([])
  })

  test('Bricolage Grotesque (h1 to h4) and Figtree (text) are loaded from files of the package', async ({
    page,
    baseURL,
  }) => {
    const origin = new URL(baseURL ?? 'http://localhost:3001').origin
    const fontResponses: Array<{ url: string; type: string | undefined }> = []
    page.on('response', (response) => {
      if (/\.woff2(\?|$)/.test(response.url())) {
        fontResponses.push({ url: response.url(), type: response.headers()['content-type'] })
      }
    })

    await page.goto('/')
    await page.evaluate(() => document.fonts.ready)

    const loaded = await page.evaluate(() =>
      [...document.fonts]
        .filter((face) => face.status === 'loaded')
        .map((face) => face.family.replace(/['"]/g, '')),
    )
    expect(loaded).toContain('Bricolage Grotesque Variable')
    expect(loaded).toContain('Figtree Variable')

    expect(
      await page.evaluate(() =>
        document.fonts.check('700 40px "Bricolage Grotesque Variable"', 'Tu'),
      ),
    ).toBe(true)
    expect(
      await page.evaluate(() => document.fonts.check('400 17px "Figtree Variable"', 'Tu')),
    ).toBe(true)

    // The headings and the body really use them.
    const families = await page.evaluate(() => {
      const family = (selector: string) =>
        getComputedStyle(document.querySelector(selector) as Element).fontFamily
      return {
        h1: family('h1'),
        h2: family('h2'),
        h3: family('h3'),
        h4: family('h4'),
        body: family('body'),
      }
    })
    for (const heading of [families.h1, families.h2, families.h3, families.h4]) {
      expect(heading).toContain('Bricolage Grotesque Variable')
    }
    expect(families.body).toContain('Figtree Variable')

    // Real files from the same origin with the right type (not data: URLs).
    expect(fontResponses.length).toBeGreaterThan(0)
    for (const font of fontResponses) {
      expect(font.url.startsWith(origin)).toBe(true)
      expect(font.type).toBe('font/woff2')
    }
  })

  test('the built CSS does not mention googleapis or gstatic and does not embed fonts as data:', async ({
    page,
    request,
  }) => {
    await page.goto('/')
    const stylesheets = await page.evaluate(() =>
      [...document.querySelectorAll<HTMLLinkElement>('link[rel="stylesheet"]')].map((l) => l.href),
    )
    expect(stylesheets.length).toBeGreaterThan(0)
    for (const href of stylesheets) {
      const css = await (await request.get(href)).text()
      expect(css).not.toMatch(/googleapis|gstatic/)
      expect(css).not.toMatch(/data:(font|application\/(font|x-font))/)
      expect(css).toContain('@font-face')
    }
    const html = await page.content()
    expect(html).not.toMatch(/googleapis|gstatic/)
  })
})
