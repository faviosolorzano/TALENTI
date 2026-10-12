/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { act, render, screen, within } from '@testing-library/react'
import { useState } from 'react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { es } from '../i18n/es'
import { mockReducedMotion } from '../test/mocks'
import { AiLabel } from './AiLabel'
import { Badge } from './Badge'
import { Button } from './Button'
import { Card } from './Card'
import { CompatibilityRing } from './CompatibilityRing'
import { Header } from './Header'
import { LevelChip } from './LevelChip'
import type { ChipLevel } from './chipLevel'
import { Steps } from './Steps'

const css = (name: string) => readFileSync(join(import.meta.dirname, name), 'utf-8')

function renderInRouter(ui: React.ReactElement, path = '/') {
  return render(<MemoryRouter initialEntries={[path]}>{ui}</MemoryRouter>)
}

describe('Button (003-04)', () => {
  it('draws a link (<a>) when it navigates with href', () => {
    render(<Button href="/algo">Ir</Button>)
    expect(screen.getByRole('link', { name: 'Ir' })).toHaveAttribute('href', '/algo')
  })

  it('draws a link (<a>) when it navigates inside the app with `to`', () => {
    renderInRouter(<Button to="/registro">Crear</Button>)
    expect(screen.getByRole('link', { name: 'Crear' })).toHaveAttribute('href', '/registro')
  })

  it('draws a <button> when it acts, and it calls onClick', async () => {
    const onClick = vi.fn()
    render(<Button onClick={onClick}>Hacer</Button>)
    const button = screen.getByRole('button', { name: 'Hacer' })
    expect(button).toHaveAttribute('type', 'button')
    await userEvent.click(button)
    expect(onClick).toHaveBeenCalledTimes(1)
  })

  it('has primary and quiet variants, also on a dark background', () => {
    render(
      <>
        <Button>Uno</Button>
        <Button variant="quiet">Dos</Button>
        <Button variant="quiet" onDark>
          Tres
        </Button>
      </>,
    )
    expect(screen.getByRole('button', { name: 'Uno' })).toHaveClass('btn--primary')
    expect(screen.getByRole('button', { name: 'Dos' })).toHaveClass('btn--quiet')
    expect(screen.getByRole('button', { name: 'Tres' })).toHaveClass('btn--quiet', 'btn--on-dark')
  })

  it('takes its height (at least 44 px) from the --touch-target-min token', () => {
    expect(css('Button.css')).toMatch(/\.btn \{[^}]*min-height: var\(--touch-target-min\)/)
  })

  it('is disabled: a button does nothing and a link does not navigate', async () => {
    const onClick = vi.fn()
    render(
      <>
        <Button disabled onClick={onClick}>
          Botón
        </Button>
        <Button href="/algo" disabled onClick={onClick}>
          Enlace
        </Button>
      </>,
    )
    const button = screen.getByRole('button', { name: 'Botón' })
    expect(button).toBeDisabled()
    await userEvent.click(button)
    const link = screen.getByRole('link', { name: 'Enlace' })
    expect(link).toHaveAttribute('aria-disabled', 'true')
    await userEvent.click(link)
    expect(onClick).not.toHaveBeenCalled()
  })

  it('can receive keyboard focus', async () => {
    render(<Button>Foco</Button>)
    await userEvent.tab()
    expect(screen.getByRole('button', { name: 'Foco' })).toHaveFocus()
  })
})

describe('LevelChip (003-05)', () => {
  function Harness({ start = 2 }: { start?: ChipLevel }) {
    const [level, setLevel] = useState<ChipLevel>(start)
    return <LevelChip name="Java" kind="required" level={level} onLevelChange={setLevel} />
  }

  it('names the level in words and in the accessible name', () => {
    render(<Harness />)
    expect(
      screen.getByRole('button', {
        name: 'Java, obligatorio, nivel actual: Lo domino. Tocar para cambiar',
      }),
    ).toBeInTheDocument()
    expect(screen.getByText(es.levels.mastered)).toBeInTheDocument()
  })

  it('cycles "No lo sé", "Lo básico", "Lo domino", "No lo sé" with clicks', async () => {
    render(<Harness start={0} />)
    const seen: string[] = []
    for (let i = 0; i < 4; i++) {
      const chip = screen.getByRole('button')
      seen.push(chip.querySelector('small')?.textContent ?? '')
      await userEvent.click(chip)
    }
    expect(seen).toEqual(['No lo sé', 'Lo básico', 'Lo domino', 'No lo sé'])
    expect(screen.getByRole('button').querySelector('small')).toHaveTextContent('Lo básico')
  })

  it('changes level with Enter and with Space', async () => {
    render(<Harness start={0} />)
    const chip = screen.getByRole('button')
    chip.focus()
    await userEvent.keyboard('{Enter}')
    expect(chip.querySelector('small')).toHaveTextContent('Lo básico')
    await userEvent.keyboard(' ')
    expect(chip.querySelector('small')).toHaveTextContent('Lo domino')
  })

  it('keeps focus on the chip after changing the level', async () => {
    render(<Harness start={1} />)
    const chip = screen.getByRole('button')
    await userEvent.click(chip)
    expect(screen.getByRole('button')).toHaveFocus()
    await userEvent.keyboard('{Enter}')
    expect(screen.getByRole('button')).toHaveFocus()
  })

  it('hides the bars from assistive technology (the words say the level)', () => {
    render(<Harness />)
    expect(screen.getByRole('button').querySelector('.level-chip__bars')).toHaveAttribute(
      'aria-hidden',
      'true',
    )
  })
})

describe('AiLabel, Card and Badge (003-07)', () => {
  it('AiLabel says "DORA · inteligencia artificial" in a light and a dark version', () => {
    const { rerender } = render(<AiLabel />)
    expect(screen.getByText('DORA · inteligencia artificial')).not.toHaveClass('ai-label--dark')
    rerender(<AiLabel tone="dark" />)
    expect(screen.getByText('DORA · inteligencia artificial')).toHaveClass('ai-label--dark')
  })

  it('Card renders its content in the element it is asked for', () => {
    render(
      <Card as="article" aria-label="Tarjeta">
        Contenido
      </Card>,
    )
    expect(screen.getByRole('article', { name: 'Tarjeta' })).toHaveTextContent('Contenido')
  })

  it('Badge shows "obligatorio" and "deseable"', () => {
    render(
      <>
        <Badge>{es.badge.required}</Badge>
        <Badge>{es.badge.desirable}</Badge>
      </>,
    )
    expect(screen.getByText('obligatorio')).toBeInTheDocument()
    expect(screen.getByText('deseable')).toBeInTheDocument()
  })

  it('Card, Badge, AiLabel and the other component styles only read tokens (no colors)', () => {
    for (const name of ['Card.css', 'Badge.css', 'AiLabel.css']) {
      expect(css(name)).not.toMatch(/#[0-9a-f]{3,8}(?![-a-z0-9])|rgba?\(/i)
      expect(css(name)).toContain('var(--')
    }
  })
})

describe('CompatibilityRing (003-06)', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  const arcOffset = () =>
    document.querySelector<SVGElement>('.ring__arc')?.style.getPropertyValue('--ring-offset')
  const CIRCUMFERENCE = 2 * Math.PI * 52

  it('counts the number up to the percentage in 0.9 s and fills the arc to it', () => {
    render(<CompatibilityRing value={82} />)

    act(() => {
      vi.advanceTimersByTime(450)
    })
    const halfway = Number(document.querySelector('.ring__number')?.textContent?.replace('%', ''))
    expect(halfway).toBeGreaterThan(0)
    expect(halfway).toBeLessThan(82)

    act(() => {
      vi.advanceTimersByTime(600)
    })
    expect(document.querySelector('.ring__number')).toHaveTextContent('82%')
    expect(Number(arcOffset())).toBeCloseTo(CIRCUMFERENCE * (1 - 0.82), 3)
  })

  it('announces only the final value in an aria-live="polite" region', () => {
    render(<CompatibilityRing value={82} />)
    const region = document.querySelector('[aria-live="polite"]')
    expect(region).toHaveTextContent('82 % de compatibilidad')

    // The steps of the count are not announced: the number is hidden from screen readers.
    expect(document.querySelector('.ring__label')).toHaveAttribute('aria-hidden', 'true')
    act(() => {
      vi.advanceTimersByTime(300)
    })
    expect(region).toHaveTextContent(/^82 % de compatibilidad$/)
  })

  it('shows an empty arc and "—" when the value is null', () => {
    render(<CompatibilityRing value={null} />)
    expect(document.querySelector('.ring__number')).toHaveTextContent('—')
    expect(Number(arcOffset())).toBeCloseTo(CIRCUMFERENCE, 3)
    expect(document.querySelector('[aria-live="polite"]')).toHaveTextContent(es.ring.announceNone)
  })

  it('turns the arc to the warning color when capped', () => {
    const { container } = render(<CompatibilityRing value={69} capped />)
    expect(container.querySelector('.ring')).toHaveAttribute('data-capped')
  })

  it('starts empty and fills only when it becomes active', () => {
    const { rerender } = render(<CompatibilityRing value={82} active={false} />)
    expect(document.querySelector('.ring__number')).toHaveTextContent('0%')
    expect(document.querySelector('[aria-live="polite"]')).toBeEmptyDOMElement()
    rerender(<CompatibilityRing value={82} active />)
    act(() => {
      vi.advanceTimersByTime(1000)
    })
    expect(document.querySelector('.ring__number')).toHaveTextContent('82%')
  })

  it('continues from the number it shows when the value changes in the middle of the count', () => {
    const { rerender } = render(<CompatibilityRing value={82} />)
    act(() => {
      vi.advanceTimersByTime(400)
    })
    const shown = () =>
      Number(document.querySelector('.ring__number')?.textContent?.replace('%', ''))
    const before = shown()
    rerender(<CompatibilityRing value={40} />)
    act(() => {
      vi.advanceTimersByTime(20)
    })
    expect(Math.abs(shown() - before)).toBeLessThan(10)
  })

  it('shows the final number at once with reduced motion', () => {
    mockReducedMotion(true)
    render(<CompatibilityRing value={82} active={false} />)
    expect(document.querySelector('.ring__number')).toHaveTextContent('82%')
  })
})

describe('Steps (003-08)', () => {
  const items = [
    { title: 'Uno', description: 'Texto uno', panel: <p>Panel uno</p> },
    { title: 'Dos', description: 'Texto dos', panel: <p>Panel dos</p> },
    { title: 'Tres', description: 'Texto tres', panel: <p>Panel tres</p> },
  ]

  it('is a set of tabs: tablist, tab, tabpanel and aria-selected', () => {
    render(<Steps label="Pasos" items={items} />)
    expect(screen.getByRole('tablist', { name: 'Pasos' })).toBeInTheDocument()
    const tabs = screen.getAllByRole('tab')
    expect(tabs).toHaveLength(3)
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true')
    expect(tabs[1]).toHaveAttribute('aria-selected', 'false')
    expect(screen.getByRole('tabpanel', { name: /Uno/ })).toHaveTextContent('Panel uno')
  })

  it('puts only the active tab in the tab order', () => {
    render(<Steps label="Pasos" items={items} />)
    const tabs = screen.getAllByRole('tab')
    expect(tabs.map((tab) => tab.tabIndex)).toEqual([0, -1, -1])
  })

  it('moves to the next and the previous tab with the arrows and moves the focus', async () => {
    render(<Steps label="Pasos" items={items} />)
    const tabs = screen.getAllByRole('tab')
    tabs[0]?.focus()
    await userEvent.keyboard('{ArrowDown}')
    expect(tabs[1]).toHaveFocus()
    expect(tabs[1]).toHaveAttribute('aria-selected', 'true')
    await userEvent.keyboard('{ArrowRight}')
    expect(tabs[2]).toHaveFocus()
    await userEvent.keyboard('{ArrowUp}')
    expect(tabs[1]).toHaveFocus()
    await userEvent.keyboard('{ArrowLeft}')
    expect(tabs[0]).toHaveFocus()
  })

  it('wraps around at both ends', async () => {
    render(<Steps label="Pasos" items={items} />)
    const tabs = screen.getAllByRole('tab')
    tabs[0]?.focus()
    await userEvent.keyboard('{ArrowUp}')
    expect(tabs[2]).toHaveFocus()
    expect(tabs[2]).toHaveAttribute('aria-selected', 'true')
    await userEvent.keyboard('{ArrowDown}')
    expect(tabs[0]).toHaveFocus()
  })

  it('changes the panel with a click', async () => {
    render(<Steps label="Pasos" items={items} />)
    await userEvent.click(screen.getByRole('tab', { name: /Dos/ }))
    expect(screen.getByRole('tabpanel', { name: /Dos/ })).toHaveTextContent('Panel dos')
  })

  it('hides the panels that are not active from screen readers and from the keyboard', () => {
    render(<Steps label="Pasos" items={items} />)
    expect(screen.getAllByRole('tabpanel')).toHaveLength(1)
    const hidden = screen
      .getAllByRole('tabpanel', { hidden: true })
      .filter((p) => p.hasAttribute('inert'))
    expect(hidden).toHaveLength(2)
    for (const panel of hidden) expect(panel).toHaveAttribute('aria-hidden', 'true')
  })
})

describe('Header (003-09)', () => {
  afterEach(() => {
    Object.defineProperty(window, 'scrollY', { value: 0, configurable: true })
  })

  const scrollTo = (y: number) => {
    Object.defineProperty(window, 'scrollY', { value: y, configurable: true })
    act(() => {
      window.dispatchEvent(new Event('scroll'))
    })
  }

  it('shows the bottom line only after scrolling more than 8 px', () => {
    renderInRouter(<Header />)
    const header = screen.getByRole('banner')
    expect(header).not.toHaveAttribute('data-scrolled')
    scrollTo(8)
    expect(header).not.toHaveAttribute('data-scrolled')
    scrollTo(9)
    expect(header).toHaveAttribute('data-scrolled')
    scrollTo(0)
    expect(header).not.toHaveAttribute('data-scrolled')
  })

  it('has the menu button with aria-expanded and aria-controls that opens and closes it', async () => {
    renderInRouter(<Header />)
    const button = screen.getByRole('button', { name: es.nav.menu })
    const menu = screen.getByRole('navigation', { name: es.app.mainNavLabel })
    expect(button).toHaveAttribute('aria-expanded', 'false')
    expect(button).toHaveAttribute('aria-controls', menu.id)
    expect(menu).toHaveAttribute('data-open', 'false')

    await userEvent.click(button)
    expect(button).toHaveAttribute('aria-expanded', 'true')
    expect(menu).toHaveAttribute('data-open', 'true')

    await userEvent.click(button)
    expect(button).toHaveAttribute('aria-expanded', 'false')
  })

  it('closes with Esc and returns the focus to the button', async () => {
    renderInRouter(<Header />)
    const button = screen.getByRole('button', { name: es.nav.menu })
    await userEvent.click(button)
    const menu = screen.getByRole('navigation', { name: es.app.mainNavLabel })
    expect(within(menu).getAllByRole('link')[0]).toHaveFocus()

    await userEvent.keyboard('{Escape}')
    expect(button).toHaveAttribute('aria-expanded', 'false')
    expect(button).toHaveFocus()
  })

  it('closes when a link of the menu is chosen', async () => {
    renderInRouter(<Header />)
    const button = screen.getByRole('button', { name: es.nav.menu })
    await userEvent.click(button)
    const menu = screen.getByRole('navigation', { name: es.app.mainNavLabel })
    await userEvent.click(within(menu).getByRole('link', { name: es.nav.how }))
    expect(button).toHaveAttribute('aria-expanded', 'false')
  })

  it('has Inicio, Oportunidades, Cómo funciona and DORA in the center (004-24), with Ingresar and Crear cuenta, and no "Para empresas"', () => {
    renderInRouter(<Header />)
    const menu = screen.getByRole('navigation', { name: es.app.mainNavLabel })
    const names = within(menu)
      .getAllByRole('link')
      .map((link) => link.textContent)
    expect(names.slice(0, 4)).toEqual([es.nav.home, es.nav.opportunities, es.nav.how, es.nav.dora])
    expect(screen.getAllByRole('link', { name: es.nav.login }).length).toBeGreaterThan(0)
    expect(screen.getByRole('link', { name: es.nav.register })).toHaveAttribute('href', '/registro')
    expect(screen.queryByText(/empresas/i)).not.toBeInTheDocument()
  })

  it('points the anchors to the sections of the home page from any page', () => {
    renderInRouter(<Header />, '/ingresar')
    expect(screen.getByRole('link', { name: es.nav.how })).toHaveAttribute(
      'href',
      '/#como-funciona',
    )
    expect(screen.getByRole('link', { name: es.nav.dora })).toHaveAttribute('href', '/#dora')
  })

  it('folds the menu below 860 px and opens it into a row above (CSS uses the 860 px breakpoint)', () => {
    const header = css('Header.css')
    expect(header).toContain('@media (min-width: 53.75rem)')
    expect(header).toMatch(/\.header__menu \{\s*display: none;/)
    expect(header).toMatch(/position: sticky;/)
  })
})
