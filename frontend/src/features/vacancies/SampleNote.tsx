import { es } from '../../i18n/es'
import './SampleNote.css'

/** Discreet label next to a title: the companies and vacancies are examples. */
export function SampleNote() {
  return <span className="sample-note">{es.vacancies.sampleData}</span>
}

/** Sentence at the bottom of the list and of the detail. */
export function SampleFootnote() {
  return <p className="sample-footnote">{es.vacancies.sampleFootnote}</p>
}
