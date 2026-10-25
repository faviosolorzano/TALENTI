import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it } from 'vitest'
import { SiteFooter } from '../../components/SiteFooter'
import { es } from '../../i18n/es'
import { TEST_CATALOG } from '../../test/testCatalog'
import { COOKIE_NAME, type CookieCatalog } from './catalog'
import { CookieBanner } from './CookieBanner'
import { CookieConsentProvider } from './CookieConsentProvider'
import { CookiePanel } from './CookiePanel'
import { useCookieConsent } from './useCookieConsent'

function Probe({ id }: { id: string }) {
  const { isAllowed } = useCookieConsent()
  return <output data-testid={`allowed-${id}`}>{String(isAllowed(id))}</output>
}

/** The pieces of the cookie feature around a page, in the order Layout puts them. */
function renderCookies(catalog?: CookieCatalog) {
  return render(
    <MemoryRouter>
      <CookieConsentProvider catalog={catalog}>
        <main tabIndex={-1}>
          <button type="button">antes del aviso</button>
        </main>
        <CookieBanner />
        <SiteFooter />
        <CookiePanel />
        <Probe id="necessary" />
        <Probe id="analytics" />
      </CookieConsentProvider>
    </MemoryRouter>,
  )
}

const banner = () => screen.getByRole('region', { name: es.cookies.banner.label })
const cookieIsWritten = () => document.cookie.includes(`${COOKIE_NAME}=`)

afterEach(() => {
  document.cookie = `${COOKIE_NAME}=; Max-Age=0; Path=/`
})

describe('Cookie notice (002-11, 002-12, 002-13, 002-15)', () => {
  // 002-11
  it('002-11, 011-08: shows "Solo usamos cookies necesarias", a link to the policy and only Aceptar and Rechazar', () => {
    renderCookies()

    const region = banner()
    expect(within(region).getByText(/Solo usamos cookies necesarias/)).toBeInTheDocument()
    expect(within(region).getByRole('link', { name: 'Política de Cookies' })).toHaveAttribute(
      'href',
      '/cookies',
    )
    expect(
      within(region)
        .getAllByRole('button')
        .map((button) => button.textContent),
    ).toEqual(['Aceptar', 'Rechazar'])
    expect(region.querySelector('.btn--primary')).toBeNull()
  })

  // 002-11
  it('002-11: does not write talenti_cookies before the visitor chooses', () => {
    renderCookies()
    expect(cookieIsWritten()).toBe(false)
  })

  // 002-11
  it('002-11: shows the notice again when the cookie is unreadable', () => {
    document.cookie = `${COOKIE_NAME}=%7Bno-es-json; Path=/`
    renderCookies()
    expect(banner()).toBeInTheDocument()
  })

  // 002-12
  it('002-12: "Aceptar" and "Rechazar" are the same button: same element, size class and style, one click, no tick boxes, no X', () => {
    renderCookies()
    const region = banner()
    const accept = within(region).getByRole('button', { name: es.cookies.banner.accept })
    const reject = within(region).getByRole('button', { name: es.cookies.banner.reject })

    expect(accept.tagName).toBe(reject.tagName)
    expect(accept.className).toBe(reject.className)
    expect(accept.parentElement).toBe(reject.parentElement)
    expect(within(region).queryByRole('checkbox')).not.toBeInTheDocument()
    expect(within(region).queryByRole('switch')).not.toBeInTheDocument()
    expect(within(region).queryByRole('button', { name: /cerrar|×|x/i })).not.toBeInTheDocument()
  })

  // 002-13
  it.each([
    ['Aceptar', es.cookies.banner.accept],
    ['Rechazar', es.cookies.banner.reject],
  ])(
    '002-13: "%s" closes the notice, saves the cookie, announces it and moves focus to the content',
    async (_n, label) => {
      renderCookies()

      await userEvent.click(within(banner()).getByRole('button', { name: label }))

      expect(
        screen.queryByRole('region', { name: es.cookies.banner.label }),
      ).not.toBeInTheDocument()
      expect(cookieIsWritten()).toBe(true)
      expect(screen.getByText(es.cookies.saved)).toBeInTheDocument()
      expect(screen.getByText(es.cookies.saved).closest('[role="status"]')).not.toBeNull()
      expect(screen.getByRole('main')).toHaveFocus()
    },
  )

  // 002-13
  it('002-13: does not come back after reloading the page', async () => {
    const first = renderCookies()
    await userEvent.click(within(banner()).getByRole('button', { name: es.cookies.banner.reject }))
    first.unmount()

    renderCookies()

    expect(screen.queryByRole('region', { name: es.cookies.banner.label })).not.toBeInTheDocument()
  })

  // 002-16
  it('002-16: comes back when the cookie is expired, and not when it is current', () => {
    const old = new Date(Date.now() - 400 * 24 * 3600 * 1000).toISOString()
    document.cookie = `${COOKIE_NAME}=${encodeURIComponent(JSON.stringify({ v: 1, c: { necessary: true }, t: old }))}; Path=/`
    const expired = renderCookies()
    expect(banner()).toBeInTheDocument()
    expired.unmount()

    const fresh = new Date().toISOString()
    document.cookie = `${COOKIE_NAME}=${encodeURIComponent(JSON.stringify({ v: 1, c: { necessary: true }, t: fresh }))}; Path=/`
    renderCookies()
    expect(screen.queryByRole('region', { name: es.cookies.banner.label })).not.toBeInTheDocument()
  })

  // 002-16
  it('002-16: comes back when the cookie comes from an older catalog version', () => {
    const fresh = new Date().toISOString()
    document.cookie = `${COOKIE_NAME}=${encodeURIComponent(JSON.stringify({ v: 1, c: { necessary: true }, t: fresh }))}; Path=/`

    renderCookies(TEST_CATALOG) // version 2

    expect(banner()).toBeInTheDocument()
  })

  // 002-15
  it('002-15: is not modal: no dialog, no layer, and it does not take or trap focus', async () => {
    renderCookies()

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(banner()).not.toHaveAttribute('aria-modal')
    expect(document.querySelector('.cookie-panel__scrim')).toBeNull()
    expect(document.activeElement).toBe(document.body)

    // Tab goes through the page, the notice and then on to the footer: nothing is trapped.
    const region = banner()
    await userEvent.tab()
    expect(screen.getByRole('button', { name: 'antes del aviso' })).toHaveFocus()
    await userEvent.tab()
    expect(within(region).getByRole('link', { name: 'Política de Cookies' })).toHaveFocus()
    await userEvent.tab()
    expect(within(region).getByRole('button', { name: es.cookies.banner.accept })).toHaveFocus()
    await userEvent.tab()
    expect(within(region).getByRole('button', { name: es.cookies.banner.reject })).toHaveFocus()
    await userEvent.tab()
    expect(
      within(screen.getByRole('contentinfo')).getByRole('link', { name: es.footer.terms }),
    ).toHaveFocus()
  })

  // 002-15
  it('002-15: is a region named "Aviso de cookies" placed right before the footer', () => {
    renderCookies()

    const footer = screen.getByRole('contentinfo')
    expect(banner().compareDocumentPosition(footer) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(banner().nextElementSibling).toBe(footer)
  })

  // 002-15
  it('002-15: keeps the footer usable while the notice is visible', async () => {
    renderCookies()

    await userEvent.click(
      within(screen.getByRole('contentinfo')).getByRole('link', { name: es.footer.terms }),
    )

    expect(banner()).toBeInTheDocument()
  })
})

describe('Cookie preferences panel (002-14)', () => {
  // 002-14
  it('002-14: opens from "Cookies" in the footer with the categories of the catalog and "Guardar preferencias"', async () => {
    renderCookies()

    await userEvent.click(
      within(screen.getByRole('contentinfo')).getByRole('button', { name: es.footer.cookies }),
    )

    const dialog = screen.getByRole('dialog', { name: es.cookies.panel.title })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(within(dialog).getByText('Necesarias')).toBeInTheDocument()
    expect(within(dialog).getByText(es.cookies.panel.required)).toBeInTheDocument()
    // Necessary categories are always on and have no switch.
    expect(within(dialog).queryByRole('checkbox')).not.toBeInTheDocument()
    expect(within(dialog).queryByRole('switch')).not.toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: es.cookies.panel.save })).toBeInTheDocument()
  })

  // 002-14
  it('002-14: opens from the footer button "Cookies", also after a choice was made', async () => {
    renderCookies()
    await userEvent.click(within(banner()).getByRole('button', { name: es.cookies.banner.reject }))

    await userEvent.click(
      within(screen.getByRole('contentinfo')).getByRole('button', {
        name: es.footer.cookies,
      }),
    )

    expect(screen.getByRole('dialog', { name: es.cookies.panel.title })).toBeInTheDocument()
  })

  // 002-14
  it('002-14: moves focus into the dialog and keeps it inside with Tab and Shift+Tab', async () => {
    renderCookies()
    await userEvent.click(
      within(screen.getByRole('contentinfo')).getByRole('button', { name: es.footer.cookies }),
    )
    const dialog = screen.getByRole('dialog')

    expect(dialog).toHaveFocus()

    const save = within(dialog).getByRole('button', { name: es.cookies.panel.save })
    const policy = within(dialog).getByRole('link', { name: es.cookies.panel.policyLink })
    const close = within(dialog).getByRole('button', { name: es.cookies.panel.close })

    await userEvent.tab()
    expect(policy).toHaveFocus()
    await userEvent.tab()
    expect(save).toHaveFocus()
    await userEvent.tab()
    expect(close).toHaveFocus()
    await userEvent.tab() // from the last, back to the first
    expect(policy).toHaveFocus()
    await userEvent.tab({ shift: true }) // from the first, to the last
    expect(close).toHaveFocus()
  })

  // 002-14
  it('002-14: Esc closes it without saving and gives focus back to who opened it', async () => {
    renderCookies()
    const configure = within(screen.getByRole('contentinfo')).getByRole('button', {
      name: es.footer.cookies,
    })
    await userEvent.click(configure)

    await userEvent.keyboard('{Escape}')

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(configure).toHaveFocus()
    expect(cookieIsWritten()).toBe(false)
    expect(banner()).toBeInTheDocument()
  })

  // 002-14
  it('002-14: gives focus back to the footer button when it was the one that opened it', async () => {
    renderCookies()
    const button = within(screen.getByRole('contentinfo')).getByRole('button', {
      name: es.footer.cookies,
    })
    await userEvent.click(button)

    await userEvent.keyboard('{Escape}')

    expect(button).toHaveFocus()
  })

  // 002-14
  it('002-14: "Guardar preferencias" writes the cookie, closes the panel and the notice, announces it and gives focus back to "Cookies"', async () => {
    renderCookies()
    const opener = within(screen.getByRole('contentinfo')).getByRole('button', {
      name: es.footer.cookies,
    })
    await userEvent.click(opener)

    await userEvent.click(screen.getByRole('button', { name: es.cookies.panel.save }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: es.cookies.banner.label })).not.toBeInTheDocument()
    expect(cookieIsWritten()).toBe(true)
    expect(screen.getByText(es.cookies.saved)).toBeInTheDocument()
    expect(opener).toHaveFocus()
  })

  // 002-14
  it('002-14: "Cerrar sin guardar" leaves the notice and writes nothing', async () => {
    renderCookies()
    await userEvent.click(
      within(screen.getByRole('contentinfo')).getByRole('button', { name: es.footer.cookies }),
    )

    await userEvent.click(screen.getByRole('button', { name: es.cookies.panel.close }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(banner()).toBeInTheDocument()
    expect(cookieIsWritten()).toBe(false)
  })
})

describe('A category added to the catalog (002-17)', () => {
  const allowed = (id: string) => screen.getByTestId(`allowed-${id}`).textContent

  // 002-17
  it('002-17: shows it off by default in the notice and in the panel, with no component change', async () => {
    renderCookies(TEST_CATALOG)

    expect(within(banner()).getByText(/Analítica/)).toBeInTheDocument()
    await userEvent.click(
      within(screen.getByRole('contentinfo')).getByRole('button', { name: es.footer.cookies }),
    )
    const dialog = screen.getByRole('dialog')
    const analytics = within(dialog).getByRole('checkbox', { name: 'Analítica' })
    expect(analytics).not.toBeChecked()
    expect(within(dialog).getByText('Necesarias')).toBeInTheDocument()
    expect(within(dialog).getAllByRole('checkbox')).toHaveLength(1) // "necessary" never has one
    expect(allowed('analytics')).toBe('false')
  })

  // 002-17
  it('002-17: "Aceptar" turns it on and "necessary" stays on', async () => {
    renderCookies(TEST_CATALOG)

    await userEvent.click(within(banner()).getByRole('button', { name: es.cookies.banner.accept }))

    expect(allowed('analytics')).toBe('true')
    expect(allowed('necessary')).toBe('true')
  })

  // 002-17
  it('002-17: "Rechazar" leaves it off', async () => {
    renderCookies(TEST_CATALOG)

    await userEvent.click(within(banner()).getByRole('button', { name: es.cookies.banner.reject }))

    expect(allowed('analytics')).toBe('false')
    expect(allowed('necessary')).toBe('true')
  })

  // 002-17
  it('002-17: closing the panel without choosing leaves it off', async () => {
    renderCookies(TEST_CATALOG)
    await userEvent.click(
      within(screen.getByRole('contentinfo')).getByRole('button', { name: es.footer.cookies }),
    )
    await userEvent.click(screen.getByRole('checkbox', { name: 'Analítica' }))

    await userEvent.keyboard('{Escape}')

    expect(allowed('analytics')).toBe('false')
    expect(cookieIsWritten()).toBe(false)
  })

  // 002-17 and 002-14
  it('002-17/002-14: the panel saves what was ticked and shows the current choice when opened again', async () => {
    renderCookies(TEST_CATALOG)
    await userEvent.click(
      within(screen.getByRole('contentinfo')).getByRole('button', { name: es.footer.cookies }),
    )
    await userEvent.click(screen.getByRole('checkbox', { name: 'Analítica' }))
    await userEvent.click(screen.getByRole('button', { name: es.cookies.panel.save }))
    expect(allowed('analytics')).toBe('true')

    await userEvent.click(
      within(screen.getByRole('contentinfo')).getByRole('button', {
        name: es.footer.cookies,
      }),
    )

    expect(screen.getByRole('checkbox', { name: 'Analítica' })).toBeChecked()
  })
})
