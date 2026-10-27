import { es } from '../i18n/es'
import './Logo.css'

export function Logo() {
  return (
    <img
      className="logo"
      src="/brand/talenti-wordmark-navy.svg"
      alt={es.app.logoAlt}
      width={4398.6}
      height={697}
    />
  )
}
