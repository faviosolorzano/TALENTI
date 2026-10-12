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
