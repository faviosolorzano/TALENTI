import { Steps, type StepItem } from '../../components/Steps'
import { cssVars } from '../../components/cssVars'
import { es } from '../../i18n/es'
import './HowItWorks.css'

/** Which of the example skills are marked in the first panel. */
const MARKED_CHIPS = [true, true, false, true, false, true] as const
/** Width (percent) of each bar in the second panel. */
const BAR_WIDTHS = [83, 50, 100] as const

function SkillsPanel() {
  return (
    <div className="how__chips">
      {es.how.chips.map((name, index) => (
        <span key={name} data-on={MARKED_CHIPS[index] || undefined}>
          {name}
        </span>
      ))}
    </div>
  )
}

function BarsPanel() {
  return (
    <div className="how__bars">
      {es.how.bars.map((label, index) => (
        <div key={label}>
          {label}
          <i>
            <b style={cssVars({ '--bar-width': `${BAR_WIDTHS[index] ?? 0}%` })} />
          </i>
        </div>
      ))}
    </div>
  )
}

function FlowPanel() {
  return (
    <div className="how__flow">
      {es.how.flow.map((step) => (
        <div key={step.label}>
          {step.label}
          <span>{step.state}</span>
        </div>
      ))}
    </div>
  )
}

const items: readonly StepItem[] = es.how.steps.map((step, index) => ({
  ...step,
  panel: [<SkillsPanel key="skills" />, <BarsPanel key="bars" />, <FlowPanel key="flow" />][index],
}))

/** "Cómo funciona": three steps that change the visual panel with a click or the arrows. */
export function HowItWorks() {
  return (
    <section className="how" id="como-funciona" aria-labelledby="how-title" tabIndex={-1}>
      <div className="container">
        <h2 id="how-title" className="how__title">
          {es.how.title}
        </h2>
        <div className="how__body">
          <Steps label={es.how.tablistLabel} items={items} />
        </div>
      </div>
    </section>
  )
}
