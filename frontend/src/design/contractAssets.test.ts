/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, it } from 'vitest'
it('the bundled exam notice is exactly the approved backend v1 text', () => {
  const normalize = (text: string) => text.replace(/\r\n/g, '\n').trim()
  const source = readFileSync(
    resolve(import.meta.dirname, '../../../backend/src/main/resources/exam/notice-v1.md'),
    'utf-8',
  )
  const client = readFileSync(resolve(import.meta.dirname, '../content/exam-notice-v1.md'), 'utf-8')
  expect(normalize(client)).toBe(normalize(source))
})
