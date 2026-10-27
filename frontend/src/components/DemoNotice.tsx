import { es } from '../i18n/es'
import './DemoNotice.css'

/** This installation uses seeded, fictional companies and vacancies. */
export function DemoNotice() {
  return <p className="demo-notice">{es.demo.notice}</p>
}
