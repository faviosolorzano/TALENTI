import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { es } from '../../i18n/es'
import { renderApp } from '../../test/renderApp'

const chip = (name: string) => screen.getByRole('button', { name: new RegExp(`^${name},`) })
const levelOf = (name: string) => chip(name).querySelector('small')?.textContent

function card() {
  return screen.getByRole('article', { name: es.card.title })
}

describe('Landing page (003-14)', () => {
  it('has a single h1', () => {
    renderApp('/')
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(
      'Tu primera práctica empieza por lo que ya sabes hacer.',
    )
  })

  it('orders hero, card, "Cómo funciona" and DORA', () => {
    renderApp('/')
    const h1 = screen.getByRole('heading', { level: 1 })
    const cardEl = card()
    const how = screen.getByRole('region', { name: es.how.title })
    const dora = screen.getByRole('region', { name: es.dora.title })
    const before = (a: Node, b: Node) =>
      Boolean(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING)
    expect(before(h1, cardEl)).toBe(true)
    expect(before(cardEl, how)).toBe(true)
    expect(before(how, dora)).toBe(true)
  })

  it('has one main action, "Ver mis vacantes", that leads to the card, and a discreet "Cómo funciona" link', () => {
    renderApp('/')
    const hero = screen.getByRole('heading', { level: 1 }).closest('section') as HTMLElement
    const primary = within(hero).getByRole('link', { name: es.hero.primaryAction })
    expect(primary).toHaveAttribute('href', '/#compatibilidad-card')
    expect(primary).toHaveClass('btn--primary')
    expect(
      within(hero)
        .getAllByRole('link')
        .filter((l) => l.classList.contains('btn--primary')),
    ).toHaveLength(1)
    expect(within(hero).getByRole('link', { name: /Cómo funciona/ })).toHaveAttribute(
      'href',
      '/#como-funciona',
    )
  })

  // Amendment 004-15: the home page now has the counter and the featured vacancies, but it
  // still has no "prácticas abiertas" dot and no "Para empresas" area.
  it('has no "prácticas abiertas" dot and no "Para empresas" area, and survives a dead API', async () => {
    renderApp('/')
    expect(screen.queryByText(/prácticas abiertas/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/para empresas/i)).not.toBeInTheDocument()
    // fetch is not mocked here: every section of vacancies fails by itself, the rest stays.
    expect(await screen.findAllByRole('alert')).not.toHaveLength(0)
    expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: es.how.title })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: es.dora.title })).toBeInTheDocument()
  })

  it('keeps the skip link and the main area', () => {
    renderApp('/')
    expect(screen.getByRole('link', { name: es.app.skipToContent })).toHaveAttribute(
      'href',
      '#contenido',
    )
    expect(screen.getByRole('main')).toHaveAttribute('id', 'contenido')
  })
})

describe('Compatibility card (003-15)', () => {
  it('starts with Java, Spring Boot and Git "Lo domino", SQL "Lo básico" and Docker "No lo sé"', () => {
    renderApp('/')
    expect(levelOf('Java')).toBe(es.levels.mastered)
    expect(levelOf('Spring Boot')).toBe(es.levels.mastered)
    expect(levelOf('Git')).toBe(es.levels.mastered)
    expect(levelOf('SQL')).toBe(es.levels.basic)
    expect(levelOf('Docker')).toBe(es.levels.none)
  })

  it('announces 82 % and says "Encajas muy bien"', () => {
    renderApp('/')
    expect(within(card()).getByText('82 % de compatibilidad')).toBeInTheDocument()
    expect(within(card()).getByRole('heading', { level: 3 })).toHaveTextContent('Encajas muy bien')
  })

  it('shows the groups Coinciden, Para reforzar, Te faltan and Aún no sabemos, each row with its evidence', () => {
    renderApp('/')
    const el = card()
    for (const title of ['Coinciden', 'Para reforzar', 'Te faltan', 'Aún no sabemos']) {
      expect(within(el).getByRole('heading', { level: 4, name: title })).toBeInTheDocument()
    }
    const group = (title: string) =>
      within(
        within(el).getByRole('heading', { level: 4, name: title }).parentElement as HTMLElement,
      )
    expect(group('Coinciden').getAllByRole('listitem')).toHaveLength(3)
    expect(group('Coinciden').getAllByText('declarado')).toHaveLength(3)
    expect(group('Para reforzar').getByText('SQL')).toBeInTheDocument()
    expect(group('Para reforzar').getByText('pide nivel intermedio')).toBeInTheDocument()
    expect(group('Te faltan').getByText('Docker')).toBeInTheDocument()
    expect(group('Aún no sabemos').getByText('Tu disponibilidad semanal')).toBeInTheDocument()
    expect(within(el).getAllByText('obligatorio').length).toBe(3)
    expect(within(el).getAllByText('deseable').length).toBe(2)
  })

  it('recalculates ring, verdict, groups and the explanation of DORA when a chip changes, without reloading', async () => {
    renderApp('/')
    const el = card()
    expect(within(el).getByText(/Para subir tu compatibilidad, refuerza SQL/)).toBeInTheDocument()

    await userEvent.click(chip('SQL')) // Lo básico -> Lo domino
    expect(levelOf('SQL')).toBe(es.levels.mastered)
    expect(
      within(el).queryByRole('heading', { level: 4, name: 'Para reforzar' }),
    ).not.toBeInTheDocument()
    expect(within(el).getByText(/Podrías sumar Docker como extra/)).toBeInTheDocument()
    expect(within(el).getByText('91 % de compatibilidad')).toBeInTheDocument()

    await userEvent.click(chip('Docker')) // No lo sé -> Lo básico
    expect(within(el).getByText(/No te falta nada para esta vacante./)).toBeInTheDocument()
  })

  it('labels the explanation of DORA as artificial intelligence', () => {
    renderApp('/')
    expect(within(card()).getByText('DORA · inteligencia artificial')).toBeInTheDocument()
  })
})

describe('Compatibility card: honest states (003-16)', () => {
  it('says "Información insuficiente", shows no percentage and gives the reason when every chip is "No lo sé"', async () => {
    renderApp('/')
    const el = card()
    await userEvent.click(chip('Java')) // 2 -> 0
    await userEvent.click(chip('Spring Boot'))
    await userEvent.click(chip('Git'))
    await userEvent.click(chip('SQL')) // 1 -> 2
    await userEvent.click(chip('SQL')) // 2 -> 0

    expect(within(el).getByRole('heading', { level: 3 })).toHaveTextContent(
      'Información insuficiente',
    )
    expect(
      within(el).getByText(/No mostramos un porcentaje que no podamos respaldar/),
    ).toBeInTheDocument()
    expect(within(el).queryByText(/% de compatibilidad/)).not.toBeInTheDocument()
    expect(el.querySelector('.ring__number')).toHaveTextContent('—')
    expect(within(el).getByText(es.ring.announceNone)).toBeInTheDocument()
  })

  it('says "Te falta un requisito obligatorio" and "el máximo es 69 %" when a required skill is at zero', async () => {
    renderApp('/')
    const el = card()
    await userEvent.click(chip('Java')) // Lo domino -> No lo sé

    expect(within(el).getByRole('heading', { level: 3 })).toHaveTextContent(
      'Te falta un requisito obligatorio',
    )
    expect(within(el).getAllByText(/el máximo es 69 %/).length).toBeGreaterThan(0)
    expect(el.querySelector('.ring')).toHaveAttribute('data-capped')
    expect(
      within(el).getByText(/Para esta vacante Java es obligatorio y todavía no lo marcas/),
    ).toBeInTheDocument()
  })
})

describe('Compatibility card: how it is calculated (003-17)', () => {
  it('always says the data are examples', () => {
    renderApp('/')
    expect(within(card()).getByText('Datos y explicación de ejemplo.')).toBeInTheDocument()
  })

  it('has a "Cómo se calcula este porcentaje" disclosure with the weights and the cap from the configuration', async () => {
    renderApp('/')
    const el = card()
    const summary = within(el).getByText('Cómo se calcula este porcentaje')
    expect(summary.tagName).toBe('SUMMARY')

    await userEvent.click(summary)
    const details = summary.closest('details') as HTMLDetailsElement
    expect(details.open).toBe(true)

    expect(within(details).getByText(/no la inteligencia artificial/)).toBeInTheDocument()
    const rows = within(details).getAllByRole('row')
    expect(rows.map((row) => row.textContent)).toEqual([
      `${es.card.how.required}50`,
      `${es.card.how.desirable}15`,
      `${es.card.how.conditions}20`,
      `${es.card.how.similarity}15`,
    ])
    expect(within(details).getByText(/el máximo es 69 %/)).toBeInTheDocument()
  })
})

describe('"Cómo funciona" (003-18)', () => {
  it('has three steps that change the visual panel with a click or the arrows', async () => {
    renderApp('/')
    const section = screen.getByRole('region', { name: es.how.title })
    const tabs = within(section).getAllByRole('tab')
    expect(tabs).toHaveLength(3)
    expect(within(section).getByRole('tabpanel')).toHaveTextContent('Spring Boot')

    await userEvent.click(tabs[1] as HTMLElement)
    expect(within(section).getByRole('tabpanel')).toHaveTextContent('Requisitos obligatorios')

    tabs[1]?.focus()
    await userEvent.keyboard('{ArrowDown}')
    expect(tabs[2]).toHaveFocus()
    expect(within(section).getByRole('tabpanel')).toHaveTextContent('Sesión de práctica')
  })

  it('says in step 3 that the company decides', () => {
    renderApp('/')
    const section = screen.getByRole('region', { name: es.how.title })
    expect(within(section).getAllByRole('tab')[2]).toHaveAccessibleDescription(
      /La empresa decide\./,
    )
  })
})
