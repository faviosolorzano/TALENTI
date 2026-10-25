import { describe, expect, it } from 'vitest'
import { buildVacancyQueryString } from '../../api/vacancies'
import { publishedLabel } from './format'
import { pageItems } from './pageItems'
import {
  clearFilters,
  clearNarrowing,
  effectiveSort,
  EMPTY_SEARCH,
  hasActiveFilters,
  hasNarrowingFilters,
  needsCorrection,
  parseSearchParams,
  serializeSearch,
  toApiQuery,
} from './searchParams'

const parse = (search: string) => parseSearchParams(new URLSearchParams(search))

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
    expect(state.sort).toBeNull()
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
    const state = parse('?pagina=1&q=%20js&modalidad=remoto&zzz=1')
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

  it('reads "orden=relevancia" only when there is text, and corrects it otherwise (004-30)', () => {
    expect(parse('?q=java&orden=relevancia').sort).toBe('relevance')
    expect(parse('?orden=relevancia').sort).toBeNull()
    expect(parse('?q=%20&orden=relevancia').sort).toBeNull()

    const canonical = serializeSearch(parse('?orden=relevancia&modalidad=remoto'))
    expect(canonical).toBe('?modalidad=remoto')
    expect(needsCorrection('?orden=relevancia&modalidad=remoto', canonical)).toBe(true)
    expect(serializeSearch(parse(canonical))).toBe(canonical)
    expect(serializeSearch(parse('?q=java&orden=relevancia'))).toBe('?q=java&orden=relevancia')
  })

  it('keeps "orden=recientes" with text (where relevance is the default) and drops it without text', () => {
    expect(serializeSearch(parse('?q=java&orden=recientes'))).toBe('?q=java&orden=recientes')
    expect(serializeSearch(parse('?orden=recientes'))).toBe('')
    expect(serializeSearch(parse('?q=java&orden=empresa'))).toBe('?q=java&orden=empresa')
    expect(serializeSearch(parse('?orden=empresa'))).toBe('?orden=empresa')
  })

  it('shows the default order of the search when none was chosen (004-30)', () => {
    expect(effectiveSort(parse('?q=java'))).toBe('relevance')
    expect(effectiveSort(parse(''))).toBe('recent')
    expect(effectiveSort(parse('?q=java&orden=recientes'))).toBe('recent')
    expect(effectiveSort(parse('?orden=empresa'))).toBe('company')
    expect(effectiveSort(parse('?q=java&orden=relevancia'))).toBe('relevance')
  })

  it('removes place, modality and technology but keeps the text and the sort', () => {
    const state = parse(
      '?q=java&modalidad=remoto&ubicacion=Lima&tecnologia=sql&orden=empresa&pagina=3',
    )
    expect(hasNarrowingFilters(state)).toBe(true)
    expect(clearNarrowing(state)).toEqual({ ...EMPTY_SEARCH, q: 'java', sort: 'company' })
    expect(hasNarrowingFilters(parse('?q=java'))).toBe(false)
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

  it('does not send "sort" when the person has not chosen one, with or without text (004-30)', () => {
    expect(buildVacancyQueryString(toApiQuery(parse('?q=java')))).toBe('?q=java')
    expect(buildVacancyQueryString(toApiQuery(parse('?q=java&orden=relevancia')))).toBe(
      '?q=java&sort=relevance',
    )
  })

  it('sends "recent" with text (relevance is the server default there) and nothing without it', () => {
    expect(buildVacancyQueryString(toApiQuery(parse('?q=java&orden=recientes')))).toBe(
      '?q=java&sort=recent',
    )
    expect(buildVacancyQueryString(toApiQuery(parse('?orden=recientes')))).toBe('')
    expect(buildVacancyQueryString(toApiQuery(parse('?q=java&orden=empresa')))).toBe(
      '?q=java&sort=company',
    )
  })

  it('asks for the 3 newest vacancies with the existing call, with no text or filters (004-33)', () => {
    expect(
      buildVacancyQueryString({
        q: '',
        modality: [],
        location: [],
        skill: [],
        sort: 'recent',
        page: 1,
        size: 3,
      }),
    ).toBe('?sort=recent&size=3')
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
