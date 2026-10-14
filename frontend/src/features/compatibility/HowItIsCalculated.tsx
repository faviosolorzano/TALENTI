import type { CompatibilityFormula } from '../../api/compatibility'
import { es } from '../../i18n/es'
import './HowItIsCalculated.css'

const t = es.compatibility.how

/** "Cómo se calcula este porcentaje": weights, cap and minimum read from the server's `formula`. */
export function HowItIsCalculated({ formula }: { formula: CompatibilityFormula }) {
  const rows: Array<[string, number]> = [
    [t.required, formula.weights.required],
    [t.desirable, formula.weights.desirable],
    [t.conditions, formula.weights.conditions],
  ]
  return (
    <details className="how-calc">
      <summary>{t.summary}</summary>
      <div className="how-calc__body">
        <p>{t.intro}</p>
        <table>
          <caption className="visually-hidden">{t.weights}</caption>
          <tbody>
            {rows.map(([label, weight]) => (
              <tr key={label}>
                <th scope="row">{label}</th>
                <td>{t.points(weight)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p>{t.cap(formula.cap)}</p>
        <p>{t.minimum(formula.minEvaluableWeight)}</p>
        <p>
          {formula.similarityEnabled ? t.similarityOn(formula.weights.similarity) : t.similarityOff}
        </p>
        <p className="how-calc__version">{t.version(formula.version)}</p>
      </div>
    </details>
  )
}
