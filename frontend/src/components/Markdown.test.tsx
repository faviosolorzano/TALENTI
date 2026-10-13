import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { Markdown } from './Markdown'

const renderMarkdown = (text: string) =>
  render(
    <MemoryRouter>
      <Markdown>{text}</Markdown>
    </MemoryRouter>,
  )

describe('Markdown links (002-03)', () => {
  it('turns an internal link into a router link', () => {
    renderMarkdown('[Cookies](/cookies) y [sección](/privacidad#seccion-2)')
    expect(screen.getByRole('link', { name: 'Cookies' })).toHaveAttribute('href', '/cookies')
    expect(screen.getByRole('link', { name: 'sección' })).toHaveAttribute(
      'href',
      '/privacidad#seccion-2',
    )
  })

  it('opens an http(s) link in a new tab with rel noopener and says so', () => {
    renderMarkdown('[guía](https://example.org/guia)')
    const link = screen.getByRole('link', { name: /guía/ })
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    expect(link).toHaveAccessibleName('guía (se abre en otra pestaña)')
  })

  it.each([
    ['protocol-relative', '[mala](//evil.example/robo)'],
    ['script scheme', '[mala](javascript:alert(1))'],
  ])('does not make a link out of an ambiguous address (%s)', (_name, text) => {
    const { container } = renderMarkdown(text)
    expect(container.querySelector('a')).toBeNull()
    expect(container).toHaveTextContent('mala')
  })

  // In Markdown two backslashes are one. The parser already writes it as %5C, which a browser
  // reads as a character of the path, never as a "/": the link cannot leave the site.
  const backslash = String.fromCharCode(92)
  it.each([
    `[mala](/${backslash}${backslash}evil.example)`,
    `[mala](/ruta${backslash}${backslash}evil)`,
  ])('a backslash never makes a link that leaves the site (%s)', (text) => {
    const { container } = renderMarkdown(text)
    const href = container.querySelector('a')?.getAttribute('href') ?? null
    if (href !== null) {
      expect(href).not.toContain(backslash)
      expect(new URL(href, 'http://localhost').origin).toBe('http://localhost')
    }
  })
})
