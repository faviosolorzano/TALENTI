import { describe, expect, it } from 'vitest'
import { buildVacancyQueryString } from '../../api/vacancies'
import { companyInitials, companyTone } from './avatarTone'
import { publishedLabel } from './format'
import { pageItems } from './pageItems'
import {
  clearFilters,
  EMPTY_SEARCH,
  hasActiveFilters,
  needsCorrection,
  parseSearchParams,
  serializeSearch,
  toApiQuery,
} from './searchParams'

const parse = (search: string) => parseSearchParams(new URLSearchParams(search))

describe('Company avatar (004-16)', () => {
  it('makes the initials from the first two words, or the first two letters of a single word', () => {
    expect(companyInitials('Nexa Digital')).toBe('ND')
    expect(companyInitials('Cóndor Datos')).toBe('CD')
    expect(companyInitials('  quipu   systems ')).toBe('QS')
    expect(companyInitials('Misti')).toBe('MI')
    expect(companyInitials('')).toBe('?')
  })

  it('gives the same tone to the same company every time, one of three', () => {
    const names = ['Nexa Digital', 'Andes Tech', 'Cóndor Datos', 'Tambo Cloud', 'Punto Código']
    for (const name of names) {
      expect(companyTone(name)).toBe(companyTone(name))
      expect(companyTone(name)).toBeGreaterThanOrEqual(0)
      expect(companyTone(name)).toBeLessThan(3)
    }
    expect(companyTone('  nexa digital ')).toBe(companyTone('Nexa Digital'))
  })

  it('uses more than one tone across the example companies', () => {
    const tones = new Set(
      [
        'Nexa Digital',
        'Andes Tech',
        'Cóndor Datos',
        'Tambo Cloud',
        'Punto Código',
        'Ruta Labs',
        'Quipu Systems',
        'Misti Software',
      ].map(companyTone),
    )
    expect(tones.size).toBeGreaterThan(1)
  })
})

describe('Publication date (004 edge cases)', () => {
  const now = new Date(2026, 9, 12, 15, 0)

  it('says "hoy" for today and for a future date', () => {
    expect(publishedLabel(new Date(2026, 9, 12, 8, 0).toISOString(), now)).toBe('Publicada hoy')
    expect(publishedLabel(new Date(2026, 9, 20).toISOString(), now)).toBe('Publicada hoy')
  })

  it('says "ayer" for the day before', () => {
    expect(publishedLabel(new Date(2026, 9, 11, 23, 0).toISOString(), now)).toBe('Publicada ayer')
  })

  it('writes older dates with Intl in es-PE', () => {
    const label = publishedLabel(new Date(2026, 9, 3).toISOString(), now)
    expect(label).toMatch(/^Publicada el 3 de octubre de 2026$/)
  })

  it('returns nothing for an invalid date', () => {
    expect(publishedLabel('no es una fecha', now)).toBe('')
  })
})

describe('Search state in the URL (004-18)', () => {
  it('reads every parameter', () => {
    expect(
      parse(
        '?q=react&modalidad=remoto&modalidad=hibrido&ubicacion=Lima&tecnologia=java&tecnologia=sql&orden=empresa&pagina=2',
      ),
    ).toEqual({
      q: 'react',
      modality: ['REMOTE', 'HYBRID'],
      location: 'Lima',
      skill: ['java', 'sql'],
      sort: 'company',
      page: 2,
    })
  })

  it('ignores invalid values', () => {
    const state = parse(
      '?modalidad=volando&modalidad=remoto&tecnologia=Java!&tecnologia=ok-1&orden=raro&pagina=0',
    )
    expect(state.modality).toEqual(['REMOTE'])
    expect(state.skill).toEqual(['ok-1'])
    expect(state.sort).toBe('recent')
    expect(state.page).toBe(1)
    expect(parse('?pagina=abc').page).toBe(1)
    expect(parse('?pagina=-3').page).toBe(1)
    expect(parse('?pagina=2.5').page).toBe(1)
  })

  it('ignores a text longer than 80 characters and cleans spaces', () => {
    expect(parse(`?q=${'a'.repeat(81)}`).q).toBe('')
    expect(parse(`?q=${'a'.repeat(80)}`).q).toHaveLength(80)
    expect(parse('?q=%20%20java%20%20%20sql%20').q).toBe('java sql')
  })

  it('keeps at most 10 technologies and removes repeats', () => {
    const many = Array.from({ length: 14 }, (_, i) => `tecnologia=t${i}`).join('&')
    expect(parse(`?${many}`).skill).toHaveLength(10)
    expect(parse('?tecnologia=java&tecnologia=java').skill).toEqual(['java'])
  })

  it('writes a canonical address without defaults, so correcting it cannot loop', () => {
    const state = parse('?pagina=1&orden=recientes&q=%20js&modalidad=remoto&zzz=1')
    const canonical = serializeSearch(state)
    expect(canonical).toBe('?q=js&modalidad=remoto')
    expect(serializeSearch(parse(canonical))).toBe(canonical)
    expect(needsCorrection('?pagina=1&q=js&modalidad=remoto&zzz=1', canonical)).toBe(true)
    expect(needsCorrection('?q=js&modalidad=remoto', canonical)).toBe(false)
    expect(needsCorrection('', serializeSearch(EMPTY_SEARCH))).toBe(false)
  })

  it('does not treat a space written as %20 or + as a difference', () => {
    const canonical = serializeSearch(parse('?q=java%20sql'))
    expect(needsCorrection('?q=java%20sql', canonical)).toBe(false)
    expect(needsCorrection('?q=java+sql', canonical)).toBe(false)
  })

  it('clears every filter but keeps the sort', () => {
    const state = parse(
      '?q=java&modalidad=remoto&ubicacion=Lima&tecnologia=sql&orden=empresa&pagina=3',
    )
    expect(hasActiveFilters(state)).toBe(true)
    expect(clearFilters(state)).toEqual({ ...EMPTY_SEARCH, sort: 'company' })
    expect(hasActiveFilters(EMPTY_SEARCH)).toBe(false)
  })
})

describe('Query sent to the API', () => {
  it('uses the names of the contract and leaves out defaults', () => {
    const query = toApiQuery(
      parse('?q=JS&modalidad=remoto&ubicacion=Lima&tecnologia=java&pagina=2'),
    )
    expect(buildVacancyQueryString(query)).toBe(
      '?q=JS&modality=REMOTE&location=Lima&skill=java&page=2',
    )
    expect(buildVacancyQueryString(toApiQuery(EMPTY_SEARCH))).toBe('')
    expect(buildVacancyQueryString(toApiQuery(parse('?orden=empresa')))).toBe('?sort=company')
  })

  it('encodes special characters instead of sending them raw', () => {
    const query = toApiQuery(parse(`?q=${encodeURIComponent('100% & "x"_')}`))
    expect(buildVacancyQueryString(query)).toBe('?q=100%25+%26+%22x%22_')
  })
})

describe('Pagination items', () => {
  it('lists every page when there are few', () => {
    expect(pageItems(1, 3)).toEqual([1, 2, 3])
  })

  it('shows first, last, current and neighbours with gaps', () => {
    expect(pageItems(5, 10)).toEqual([1, null, 4, 5, 6, null, 10])
    expect(pageItems(2, 10)).toEqual([1, 2, 3, null, 10])
  })
})
