/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { es } from './es'

const source = readFileSync(join(import.meta.dirname, 'es.ts'), 'utf-8')

describe('Texts of the interface (spec 011)', () => {
  // 011-02: what does not exist is not shown, so no text announces it.
  it.each(['Próximamente', 'disponible pronto', 'En construcción'])(
    '011-02: es.ts no longer keeps "%s"',
    (text) => {
      expect(source.toLowerCase()).not.toContain(text.toLowerCase())
    },
  )

  it('"Oportunidades" is now "Vacantes" in the navigation and in the title of the list', () => {
    expect(es.nav.opportunities).toBe('Vacantes')
    expect(es.pages.opportunities.title).toBe('Vacantes')
    expect(es.opportunities.title).toBe('Vacantes')
    expect(source).not.toMatch(/[Oo]portunidades'/)
  })

  it('the tabs of a student use short names', () => {
    expect([es.nav.home, es.nav.opportunities, es.nav.myApplications, es.nav.myProfile]).toEqual([
      'Inicio',
      'Vacantes',
      'Postulaciones',
      'Perfil',
    ])
  })
})
