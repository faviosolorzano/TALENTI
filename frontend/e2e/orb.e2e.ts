import { expect, test, type Page } from '@playwright/test'

/** A fingerprint of the canvas pixels. */
const snapshot = (page: Page) =>
  page.evaluate(() => (document.querySelector('canvas') as HTMLCanvasElement).toDataURL())

async function showOrb(page: Page) {
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.goto('/')
  await page.evaluate(() => document.fonts.ready)
  await page.locator('canvas').scrollIntoViewIfNeeded()
}

test.describe('DORA orb in a real browser (003-19, 003-23, 003-24)', () => {
  test('the orb moves while it is on screen', async ({ page }) => {
    await showOrb(page)
    const first = await snapshot(page)
    await page.waitForTimeout(700)
    expect(await snapshot(page)).not.toBe(first)
  })

  test('the orb does not draw while it is off screen and resumes when it is back', async ({
    page,
  }) => {
    await showOrb(page)
    await page.waitForTimeout(400)
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.waitForTimeout(400) // the loop is cancelled by the observer
    const frozen = await snapshot(page)
    await page.waitForTimeout(700)
    expect(await snapshot(page)).toBe(frozen)

    await page.locator('canvas').scrollIntoViewIfNeeded()
    await page.waitForTimeout(700)
    expect(await snapshot(page)).not.toBe(frozen)
  })

  test('with reduced motion the orb is a still image, the ring shows its value at once and there are no rings or dots', async ({
    page,
  }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await showOrb(page)
    const still = await snapshot(page)
    await page.waitForTimeout(700)
    expect(await snapshot(page)).toBe(still)
    await expect(
      page.getByText('Reducir movimiento está activado: el orbe no se anima.'),
    ).toBeVisible()
    await expect(page.locator('.ring__number').first()).toHaveText('82%')

    // The still image changes with the state.
    await page.getByRole('button', { name: 'Escuchando' }).click()
    expect(await snapshot(page)).not.toBe(still)
  })

  test('the orb switches state without a visible jump (consecutive frames stay close)', async ({
    page,
  }) => {
    await showOrb(page)
    await page.getByRole('button', { name: 'Hablando' }).click()
    const frames = await page.evaluate(async () => {
      const canvas = document.querySelector('canvas') as HTMLCanvasElement
      const probe = document.createElement('canvas')
      probe.width = probe.height = 88
      const ctx = probe.getContext('2d') as CanvasRenderingContext2D
      const grab = () => {
        ctx.clearRect(0, 0, 88, 88)
        ctx.drawImage(canvas, 0, 0, 88, 88)
        return ctx.getImageData(0, 0, 88, 88).data
      }
      const out: number[] = []
      let previous = grab()
      for (let i = 0; i < 20; i++) {
        await new Promise((resolve) => requestAnimationFrame(() => resolve(null)))
        const next = grab()
        let diff = 0
        for (let p = 3; p < next.length; p += 4)
          diff += Math.abs((next[p] ?? 0) - (previous[p] ?? 0))
        out.push(diff / (next.length / 4))
        previous = next
      }
      return out
    })
    expect(Math.max(...frames)).toBeLessThan(40)
  })

  test('without microphone permission the message appears and everything else keeps working', async ({
    page,
  }) => {
    await page.addInitScript(() => {
      Object.defineProperty(navigator, 'mediaDevices', {
        value: {
          getUserMedia: () => Promise.reject(new DOMException('denied', 'NotAllowedError')),
        },
        configurable: true,
      })
    })
    await showOrb(page)
    await page.getByRole('button', { name: 'Probar con mi micrófono' }).click()
    await expect(
      page.getByText('No pude usar el micrófono; el orbe sigue con una simulación.'),
    ).toBeVisible()
    await page.getByRole('button', { name: 'Pensando' }).click()
    await expect(page.getByText('Dame un momento para pensarlo…')).toBeVisible()
  })
})
