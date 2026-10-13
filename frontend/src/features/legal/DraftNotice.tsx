import { es } from '../../i18n/es'
import './DraftNotice.css'

/** The legal texts are drafts: every page that shows them says so (002-05). */
export function DraftNotice() {
  return <p className="draft-notice">{es.legal.draftNotice}</p>
}
