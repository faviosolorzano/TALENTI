/// <reference types="node" />
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const dir = import.meta.dirname
const read = (name: string) => readFileSync(join(dir, name), 'utf-8')

describe('Skeletons and motion (004-25)', () => {
  it('stop animating with "prefers-reduced-motion"', () => {
    const css = read('Skeletons.css')
    const reduced = css.split('@media (prefers-reduced-motion: reduce)')[1] ?? ''
    expect(reduced).toMatch(/\.skeleton\s*\{\s*animation:\s*none;/)
  })
})

describe('Colors and motion in the styles (004-25)', () => {
  const folders = [dir, join(dir, '..', 'landing')]
  const sheets = folders.flatMap((folder) =>
    readdirSync(folder)
      .filter((file) => file.endsWith('.css'))
      .map((file) => ({ file, css: readFileSync(join(folder, file), 'utf-8') })),
  )

  it('reads the stylesheets of vacancies and landing', () => {
    expect(sheets.length).toBeGreaterThan(10)
  })

  it('write no color by hand: no #hex, rgb( or hsl( (every color comes from a token)', () => {
    const literal = /#[0-9a-fA-F]{3,8}(?![\w-])|rgba?\(|hsla?\(/
    const offenders = sheets.filter(({ css }) => literal.test(css)).map(({ file }) => file)
    expect(offenders).toEqual([])
  })

  it('stop moving cards and arrows with "prefers-reduced-motion"', () => {
    const card = read('VacancyCard.css')
    expect(card).toContain('@media (prefers-reduced-motion: reduce)')
    expect(card.split('@media (prefers-reduced-motion: reduce)')[1]).toContain('animation: none;')
    expect(card).not.toContain('transform: translate')
  })

  it('keep no fixed width in rem inside the layout rules: sizes come from tokens', () => {
    const detail = read('VacancyDetailScreen.css')
    expect(detail).toContain('var(--detail-aside-width)')
    expect(detail).not.toMatch(/minmax\(0, 1fr\) \d/)
    expect(read('ErrorNotice.css')).not.toMatch(/flex:\s*1 1 \d/)
    expect(read('Skeletons.css')).not.toMatch(/minmax\(\d/)
  })
})

describe('Texts in the vacancy screens (004-25)', () => {
  it('no component writes Spanish text by hand: it all comes from src/i18n/es.ts', () => {
    const components = readdirSync(dir).filter(
      (file) => /\.tsx$/.test(file) && !/\.test\./.test(file),
    )
    expect(components.length).toBeGreaterThan(8)
    const literal = /<[^>]*>\s*[A-ZÁÉÍÓÚÑa-záéíóúñ¿¡][^<>{}]*[a-záéíóúñ][^<>{}]*\s*<\//
    const offenders = components.filter((file) => {
      const source = read(file)
        .split('\n')
        .filter((line) => !line.trim().startsWith('*') && !line.trim().startsWith('//'))
        .join('\n')
      return literal.test(source)
    })
    expect(offenders).toEqual([])
  })
})
