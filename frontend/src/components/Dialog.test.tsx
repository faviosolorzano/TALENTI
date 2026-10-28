import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it } from 'vitest'
import { Dialog } from './Dialog'
function Fixture() {
  const [first, setFirst] = useState(false)
  const [second, setSecond] = useState(false)
  return (
    <div id="root">
      <button onClick={() => setFirst(true)}>Abrir ventana</button>
      <Dialog open={first} title="Primera ventana" onClose={() => setFirst(false)}>
        <label>
          Borrador
          <input />
        </label>
        <button onClick={() => setSecond(true)}>Abrir segunda</button>
        <button onClick={() => setFirst(false)}>Cerrar primera</button>
      </Dialog>
      <Dialog open={second} title="Segunda ventana" onClose={() => setSecond(false)}>
        <button onClick={() => setSecond(false)}>Cerrar segunda</button>
      </Dialog>
    </div>
  )
}
it('012-F07: a closed floating window does not block the page; close preserves its editor and returns focus', async () => {
  const user = userEvent.setup()
  render(<Fixture />)
  expect(screen.queryByRole('dialog')).toBeNull()
  expect(document.getElementById('root')).not.toHaveAttribute('inert')
  await user.click(screen.getByRole('button', { name: 'Abrir ventana' }))
  expect(screen.getByRole('heading', { name: 'Primera ventana' })).toHaveFocus()
  expect(document.getElementById('root')).toHaveAttribute('inert')
  await user.type(screen.getByRole('textbox', { name: 'Borrador' }), 'Sin perder cambios')
  await user.keyboard('{Escape}')
  expect(screen.queryByRole('dialog')).toBeNull()
  expect(screen.getByRole('button', { name: 'Abrir ventana' })).toHaveFocus()
  expect(document.getElementById('root')).not.toHaveAttribute('inert')
  await user.click(screen.getByRole('button', { name: 'Abrir ventana' }))
  expect(screen.getByRole('textbox', { name: 'Borrador' })).toHaveValue('Sin perder cambios')
})
it('012-F07: Escape closes only the top window and keeps the background blocked until both close', async () => {
  const user = userEvent.setup()
  render(<Fixture />)
  await user.click(screen.getByRole('button', { name: 'Abrir ventana' }))
  await user.click(screen.getByRole('button', { name: 'Abrir segunda' }))
  expect(screen.getAllByRole('dialog')).toHaveLength(2)
  await user.keyboard('{Escape}')
  expect(screen.getAllByRole('dialog')).toHaveLength(1)
  expect(screen.getByRole('dialog')).toHaveAccessibleName('Primera ventana')
  expect(document.getElementById('root')).toHaveAttribute('inert')
  expect(screen.getByRole('button', { name: 'Abrir segunda' })).toHaveFocus()
  await user.keyboard('{Escape}')
  expect(screen.queryByRole('dialog')).toBeNull()
  expect(document.getElementById('root')).not.toHaveAttribute('inert')
})
