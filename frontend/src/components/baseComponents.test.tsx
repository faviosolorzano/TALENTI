/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { es } from '../i18n/es'
import { ActionBar } from './ActionBar'
import { Button } from './Button'
import { GroupedList, ListRow } from './GroupedList'
import { StatusMark, type StatusKind } from './StatusMark'
import { TabBar } from './TabBar'

const css = (name: string) => readFileSync(join(import.meta.dirname, name), 'utf-8')

describe('StatusMark (spec 011)', () => {
  const kinds: ReadonlyArray<readonly [StatusKind, string]> = [
    ['have', 'Cumples'],
    ['reinforce', 'Refuerza'],
    ['missing', 'Te falta'],
    ['done', 'Hecho'],
    ['pending', 'Pendiente'],
  ]

  it.each(kinds)('011-05: "%s" has the hidden text "%s" for screen readers', (kind, text) => {
    render(
      <p>
        <StatusMark kind={kind} /> Java
      </p>,
    )
    expect(screen.getByText(text)).toHaveClass('visually-hidden')
    expect(screen.getByText(text).closest('p')).toHaveTextContent(`${text} Java`)
  })

  it('011-05: the symbol is hidden from screen readers and the text can be changed', () => {
    const { container } = render(<StatusMark kind="missing" label="No está en tu perfil" />)
    expect(container.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
    expect(screen.getByText('No está en tu perfil')).toBeInTheDocument()
    expect(screen.queryByText(es.statusMark.missing)).toBeNull()
  })

  it('011-05: each state has its own shape, so it never depends on color alone', () => {
    const shapes = kinds.map(([kind]) => {
      const { container, unmount } = render(<StatusMark kind={kind} />)
      const svg = container.querySelector('svg')?.innerHTML ?? ''
      unmount()
      return svg
    })
    expect(new Set(shapes).size).toBe(kinds.length)
  })

  it('011-05: the colors come from the tokens of the marks', () => {
    const styles = css('StatusMark.css')
    for (const [kind] of kinds) {
      expect(styles).toContain(`[data-kind='${kind}']`)
      expect(styles).toContain(`var(--color-mark-${kind})`)
    }
  })
})

describe('GroupedList and ListRow (spec 011)', () => {
  it('draws a list with its title as name, one item per row', () => {
    render(
      <MemoryRouter>
        <GroupedList title="Mi cuenta">
          <ListRow title="Datos personales" detail="Ana Prueba" />
          <ListRow title="Habilidades" detail="Sin completar" />
        </GroupedList>
      </MemoryRouter>,
    )
    const list = screen.getByRole('list', { name: 'Mi cuenta' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(2)
    expect(screen.getByRole('heading', { level: 2, name: 'Mi cuenta' })).toBeInTheDocument()
    expect(within(list).getByText('Ana Prueba')).toHaveClass('list-row__detail')
  })

  it('a row with `to` is one link (whole row) with a decorative chevron', () => {
    render(
      <MemoryRouter>
        <GroupedList label="Secciones">
          <ListRow title="Habilidades" mark={<StatusMark kind="have" />} to="/perfil" />
        </GroupedList>
      </MemoryRouter>,
    )
    const link = screen.getByRole('link', { name: `${es.statusMark.have} Habilidades` })
    expect(link).toHaveAttribute('href', '/perfil')
    expect(link.querySelector('.list-row__chevron')).toHaveAttribute('aria-hidden', 'true')
  })

  it('a row with `onClick` is one button; disabled, it does not act', async () => {
    const onClick = vi.fn()
    const { rerender } = render(
      <GroupedList label="Cuenta">
        <ListRow title="Cerrar sesión" tone="action" onClick={onClick} />
      </GroupedList>,
    )
    await userEvent.click(screen.getByRole('button', { name: 'Cerrar sesión' }))
    expect(onClick).toHaveBeenCalledTimes(1)

    rerender(
      <GroupedList label="Cuenta">
        <ListRow title="Cerrar sesión" tone="action" onClick={onClick} disabled />
      </GroupedList>,
    )
    expect(screen.getByRole('button', { name: 'Cerrar sesión' })).toBeDisabled()
  })

  it('011-05: rows are at least 44 px tall and separated by a hairline, without a shadow', () => {
    const styles = css('GroupedList.css')
    expect(styles).toMatch(/\.list-row__inner \{[^}]*min-height: var\(--touch-target-min\)/)
    expect(styles).toMatch(/\.list-row \+ \.list-row \{\s*border-top: var\(--border-width\)/)
    expect(styles).not.toContain('box-shadow')
  })
})

describe('ActionBar (spec 011)', () => {
  it('holds the main action of the screen', () => {
    render(
      <ActionBar>
        <Button>Postular</Button>
      </ActionBar>,
    )
    const bar = screen.getByRole('button', { name: 'Postular' }).parentElement
    expect(bar).toHaveClass('action-bar')
  })

  it('is fixed at the bottom above the tab bar under 860 px and in its place from 860 px', () => {
    const styles = css('ActionBar.css')
    const [phone, desktop] = styles.split('@media (min-width: 53.75rem)')
    expect(phone).toMatch(/position: fixed;/)
    expect(phone).toMatch(/bottom: var\(--tabbar-space, 0px\);/)
    expect(desktop).toMatch(/position: static;/)
  })

  it('tells the page how much room it takes, and gives it back when it leaves', () => {
    const observers: Array<() => void> = []
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(callback: () => void) {
          observers.push(callback)
        }
        observe() {}
        disconnect() {}
      },
    )
    const { unmount } = render(
      <ActionBar>
        <Button>Postular</Button>
      </ActionBar>,
    )
    observers.forEach((callback) => callback())
    expect(document.documentElement.style.getPropertyValue('--action-bar-space')).toMatch(/px$/)
    unmount()
    expect(document.documentElement.style.getPropertyValue('--action-bar-space')).toBe('')
    vi.unstubAllGlobals()
  })
})

describe('TabBar (011-06)', () => {
  function renderAt(path: string) {
    return render(
      <MemoryRouter initialEntries={[path]}>
        <TabBar />
      </MemoryRouter>,
    )
  }

  it('is the "Principal" navigation with Inicio, Vacantes, Postulaciones and Perfil', () => {
    renderAt('/')
    const nav = screen.getByRole('navigation', { name: es.app.mainNavLabel })
    expect(
      within(nav)
        .getAllByRole('link')
        .map((link) => [link.textContent, link.getAttribute('href')]),
    ).toEqual([
      ['Inicio', '/'],
      ['Vacantes', '/oportunidades'],
      ['Postulaciones', '/postulaciones'],
      ['Perfil', '/perfil'],
    ])
  })

  it.each([
    ['/', 'Inicio'],
    ['/oportunidades', 'Vacantes'],
    ['/oportunidades/vac-123', 'Vacantes'],
    ['/postulaciones', 'Postulaciones'],
    ['/postulaciones/app-9', 'Postulaciones'],
    ['/perfil', 'Perfil'],
    ['/perfil/cv', 'Perfil'],
  ])('on %s only "%s" carries aria-current', (path, current) => {
    renderAt(path)
    const marked = screen
      .getAllByRole('link')
      .filter((link) => link.getAttribute('aria-current') === 'page')
      .map((link) => link.textContent)
    expect(marked).toEqual([current])
  })

  it('the icons are decorative: each tab is named by its text', () => {
    const { container } = renderAt('/')
    for (const svg of container.querySelectorAll('svg')) {
      expect(svg).toHaveAttribute('aria-hidden', 'true')
    }
  })

  it('011-05: every tab is at least 44 px tall', () => {
    expect(css('TabBar.css')).toMatch(/\.tabbar__link \{[^}]*min-height: var\(--touch-target-min\)/)
  })
})
