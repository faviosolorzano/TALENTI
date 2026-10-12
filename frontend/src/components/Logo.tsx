import { es } from '../i18n/es'
import './Logo.css'

export function Logo() {
  return (
    <img
      className="logo"
      src="/brand/logo-horizontal.svg"
      alt={es.app.logoAlt}
      width={2728}
      height={960}
    />
  )
}
