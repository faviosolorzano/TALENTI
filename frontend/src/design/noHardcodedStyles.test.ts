/// <reference types="node" />
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import { describe, expect, it } from 'vitest'

const srcDir = join(import.meta.dirname, '..')
const frontendDir = join(srcDir, '..')

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    return statSync(path).isDirectory() ? walk(path) : [path]
  })
}

const isTest = (file: string) => /\.test\.(ts|tsx)$/.test(file)
const inDesign = (file: string) => relative(srcDir, file).split(sep)[0] === 'design'

/** Style and component files outside src/design (tokens live only there). */
const styled = walk(srcDir).filter(
  (file) => /\.(css|tsx|ts)$/.test(file) && !inDesign(file) && !isTest(file),
)

function offenders(pattern: RegExp, files = styled): string[] {
  return files
    .filter((file) => pattern.test(readFileSync(file, 'utf-8')))
    .map((file) => relative(frontendDir, file))
}

describe('Styles live in tokens (003-01)', () => {
  it('finds files to check', () => {
    expect(styled.length).toBeGreaterThan(5)
  })

  it('no .css, .tsx or .ts outside src/design writes a hex color', () => {
    expect(offenders(/#[0-9a-f]{3,8}\b(?![-\w])/i)).toEqual([])
  })

  it('no file outside src/design writes rgb(), rgba(), hsl() or hsla()', () => {
    expect(offenders(/\b(rgba?|hsla?)\(/i)).toEqual([])
  })

  it('no file outside src/design writes a font family or the font shorthand', () => {
    expect(offenders(/font-family\s*:\s*(?!var\(|inherit)\S/i)).toEqual([])
    expect(offenders(/(^|[\s;{])font\s*:/m)).toEqual([])
    expect(offenders(/fontFamily/)).toEqual([])
  })

  it('no HTML has a <style> element or a style attribute written by hand', () => {
    const html = [join(frontendDir, 'index.html')]
    expect(offenders(/<style[\s>]/i, html)).toEqual([])
    expect(offenders(/\sstyle\s*=\s*"/i, html)).toEqual([])
    expect(offenders(/<style[\s>]/i)).toEqual([])
  })
})

describe('Fonts and third parties (003-03)', () => {
  it('nothing in src or in index.html mentions googleapis or gstatic', () => {
    const files = [...walk(srcDir).filter((file) => !isTest(file)), join(frontendDir, 'index.html')]
    expect(offenders(/googleapis|gstatic/i, files)).toEqual([])
  })

  it('Hanken is served locally with its license and variable weights', () => {
    const css = readFileSync(join(srcDir, 'design/fonts.css'), 'utf-8')
    expect(css).toContain("font-family: 'Hanken Grotesk'")
    expect(css).toContain('./fonts/HankenGrotesk-Latin-Variable.woff2')
    expect(css).toContain('font-weight: 100 900')
    expect(css).not.toMatch(/https?:\/\/|data:/)
    expect(
      statSync(join(srcDir, 'design/fonts/HankenGrotesk-Latin-Variable.woff2')).size,
    ).toBeGreaterThan(30000)
    expect(readFileSync(join(srcDir, 'design/fonts/OFL.txt'), 'utf-8')).toContain(
      'SIL OPEN FONT LICENSE',
    )
  })
})
