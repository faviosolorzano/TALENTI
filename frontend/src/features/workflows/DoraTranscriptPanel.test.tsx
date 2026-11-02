import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { useState } from 'react'
import { DoraTranscriptPanel } from './DoraTranscriptPanel'
import { es } from '../../i18n/es'

afterEach(() => vi.unstubAllGlobals())

function Harness({ message = 'Mensaje real de prueba' }: { message?: string }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <button onClick={() => setOpen(true)}>Abrir</button>
      <DoraTranscriptPanel id="transcript-test" open={open} onClose={() => setOpen(false)}>
        <p>{message}</p>
        <input aria-label="Corrección de prueba" defaultValue="Respuesta" />
      </DoraTranscriptPanel>
    </>
  )
}

it('preserves correction draft and node across close/reopen, live updates and desktop/mobile resize', async () => {
  let wide = true
  const listeners = new Set<() => void>()
  vi.stubGlobal('matchMedia', () => ({
    get matches() {
      return wide
    },
    addEventListener: (_event: string, fn: () => void) => listeners.add(fn),
    removeEventListener: (_event: string, fn: () => void) => listeners.delete(fn),
  }))
  const user = userEvent.setup()
  const view = render(<Harness />)
  expect(screen.queryByRole('dialog')).toBeNull()
  await user.click(screen.getByRole('button', { name: 'Abrir' }))
  expect(screen.getByRole('dialog')).not.toHaveAttribute('aria-modal')
  const input = screen.getByRole('textbox')
  await user.type(input, ' corregida')
  view.rerender(<Harness message="Nuevo mensaje del servicio" />)
  expect(input).toHaveFocus()
  expect(screen.getByText('Nuevo mensaje del servicio')).toBeVisible()
  await act(async () => {
    wide = false
    listeners.forEach((fn) => fn())
  })
  expect(screen.getByRole('dialog')).toHaveAttribute('aria-modal', 'true')
  expect(screen.getByRole('textbox')).toBe(input)
  expect(input).toHaveValue('Respuesta corregida')
  expect(document.body.style.overflow).toBe('hidden')
  await user.click(screen.getByRole('button', { name: es.doraCall.closeTranscript }))
  expect(screen.getByRole('button', { name: 'Abrir' })).toHaveFocus()
  expect(document.body.style.overflow).not.toBe('hidden')
  await user.click(screen.getByRole('button', { name: 'Abrir' }))
  expect(screen.getByRole('textbox')).toHaveValue('Respuesta corregida')
  fireEvent(screen.getByRole('dialog'), new Event('cancel', { cancelable: true }))
  expect(screen.queryByRole('dialog')).toBeNull()
  view.unmount()
  expect(listeners.size).toBe(0)
})

it('desktop Escape closes the panel and restores the opener', async () => {
  vi.stubGlobal('matchMedia', () => ({
    matches: true,
    addEventListener() {},
    removeEventListener() {},
  }))
  const user = userEvent.setup()
  render(<Harness />)
  await user.click(screen.getByRole('button', { name: 'Abrir' }))
  await user.keyboard('{Escape}')
  expect(screen.queryByRole('dialog')).toBeNull()
  expect(screen.getByRole('button', { name: 'Abrir' })).toHaveFocus()
})
