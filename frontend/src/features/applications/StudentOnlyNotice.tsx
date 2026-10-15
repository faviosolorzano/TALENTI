import { Notice } from '../../components/Notice'
import { es } from '../../i18n/es'

/** A company or admin account has no applications (008-14). */
export function StudentOnlyNotice() {
  return (
    <div className="container applications">
      <Notice tone="info" announce="none">
        <h1 className="notice__title">{es.applications.card.notAllowed}</h1>
      </Notice>
    </div>
  )
}
