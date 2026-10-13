import { useId, useState } from 'react'
import { es } from '../i18n/es'
import { TextField, type TextFieldProps } from './TextField'
import './PasswordField.css'

type PasswordFieldProps = Omit<TextFieldProps, 'type' | 'labelAction'>

/**
 * A password field with a "Mostrar" / "Ocultar" button. The button is a real button reachable
 * with the keyboard; its name says what it does ("Mostrar contraseña").
 */
export function PasswordField(props: PasswordFieldProps) {
  const [visible, setVisible] = useState(false)
  const generatedId = useId()
  const inputId = props.id ?? generatedId

  return (
    <TextField
      {...props}
      id={inputId}
      type={visible ? 'text' : 'password'}
      autoCapitalize="off"
      spellCheck={false}
      labelAction={
        <button
          type="button"
          className="password-toggle"
          aria-label={visible ? es.identity.form.hideLabel : es.identity.form.showLabel}
          aria-controls={inputId}
          onClick={() => setVisible((value) => !value)}
        >
          {visible ? es.identity.form.hide : es.identity.form.show}
        </button>
      }
    />
  )
}
