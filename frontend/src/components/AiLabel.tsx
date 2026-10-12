import { es } from '../i18n/es'
import './AiLabel.css'

interface AiLabelProps {
  /** `dark` is for text placed on a navy background. */
  tone?: 'light' | 'dark'
}

/**
 * Marks content written by DORA as artificial intelligence (constitution, principle 2).
 * Every block with DORA text includes it.
 */
export function AiLabel({ tone = 'light' }: AiLabelProps) {
  return (
    <span className={tone === 'dark' ? 'ai-label ai-label--dark' : 'ai-label'}>{es.ai.label}</span>
  )
}
