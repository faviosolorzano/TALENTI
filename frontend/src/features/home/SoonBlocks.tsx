import { AiLabel } from '../../components/AiLabel'
import { es } from '../../i18n/es'
import { DoraOrb } from '../dora/DoraOrb'
import { HomeBlock, SoonTag } from './HomeBlock'

/**
 * "Práctica con DORA" (005-13). The text is fixed, but it speaks for DORA, so it carries the
 * AI label (003-07). No active button until the practice sessions exist (S8).
 */
export function PracticeBlock() {
  return (
    <HomeBlock title={es.home.practice.title} tag={<SoonTag>{es.home.soon}</SoonTag>}>
      <div className="home-practice">
        <div className="home-practice__orb">
          <DoraOrb mode="idle" />
        </div>
        <div className="home-practice__text">
          <AiLabel />
          <p>{es.home.practice.body}</p>
        </div>
      </div>
      <p className="home-practice__privacy">{es.home.practice.privacy}</p>
    </HomeBlock>
  )
}
