import { describe, expect, it } from 'vitest'
import { loginPathWithReturn, safeReturnTo } from './returnTo'

const BACKSLASH = String.fromCharCode(92)

describe('returnTo accepts only internal routes (spec 001, edge cases)', () => {
  it.each([
    '/panel',
    '/oportunidades',
    '/oportunidades/abc-123',
    '/oportunidades?q=java&page=2',
    '/a#b',
  ])('accepts the internal route %s', (route) => {
    expect(safeReturnTo(route)).toBe(route)
  })

  it.each([
    ['nothing', null],
    ['undefined', undefined],
    ['an empty text', ''],
    ['a full address', 'https://malo.example/robo'],
    ['an address without scheme', 'malo.example/robo'],
    ['a relative path', 'oportunidades'],
    ['a protocol-relative address', '//malo.example'],
    ['a protocol-relative address with a path', '//malo.example/panel'],
    ['a javascript scheme', 'javascript:alert(1)'],
    ['a data scheme', 'data:text/html,hola'],
    ['a backslash after the slash', `/${BACKSLASH}malo.example`],
    ['a backslash anywhere', `/panel${BACKSLASH}..`],
    ['two slashes written with a backslash', `${BACKSLASH}${BACKSLASH}malo.example`],
    ['an encoded backslash', '/%5Cmalo.example'],
    ['an encoded second slash', '/%2Fmalo.example'],
    ['a badly encoded text', '/%E0%A4%A'],
    ['a line break', '/panel\n/otra'],
    ['a tab', '/\tmalo.example'],
    ['a null character', '/panel\u0000'],
    ['a very long route', `/${'a'.repeat(600)}`],
  ])('rejects %s', (_name, value) => {
    expect(safeReturnTo(value)).toBeNull()
  })

  it.each([
    '/ingresar',
    '/ingresar?returnTo=/panel',
    '/registro',
    '/registro/otra',
    '/a/../ingresar',
  ])('rejects %s because it would make a loop', (route) => {
    expect(safeReturnTo(route)).toBeNull()
  })

  it.each([
    '/ingresar/',
    '/Ingresar',
    '/INGRESAR/',
    '/%69ngresar',
    '/%49ngresar',
    '/%2569ngresar',
    '/./registro',
    '/./registro/',
    '/x/../registro',
    '/%2e/registro',
    '/%2e%2e/ingresar',
    '/Registro?x=1',
    '/ingresar#a',
    '/aceptar-documentos',
    '/aceptar-documentos/',
    '/Aceptar-Documentos',
    '/%61ceptar-documentos',
    '/aceptar-documentos?returnTo=%2Fpanel',
  ])('rejects the variant %s of a page that would make a loop', (route) => {
    expect(safeReturnTo(route)).toBeNull()
  })

  it('does not treat a similar name as a loop', () => {
    expect(safeReturnTo('/ingresaremos')).toBe('/ingresaremos')
  })
})

describe('loginPathWithReturn', () => {
  it('keeps a safe route in the returnTo parameter, encoded', () => {
    expect(loginPathWithReturn('/oportunidades/abc?x=1')).toBe(
      '/ingresar?returnTo=%2Foportunidades%2Fabc%3Fx%3D1',
    )
  })

  it('leaves out the parameter for the panel (it is where one goes anyway) and for unsafe routes', () => {
    expect(loginPathWithReturn('/panel')).toBe('/ingresar')
    expect(loginPathWithReturn('//malo.example')).toBe('/ingresar')
  })
})
