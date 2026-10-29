import { useId } from 'react'
import type { DoraMode } from '../dora/modes'
/** Decorative state visual. Does not measure or classify the person's voice. */
/**
 * `levelDriven`: the waves and the core follow `--dora-level` (0 to 1), the real volume set by
 * the real-time call, instead of the looping animation.
 */
export function ConversationOrb({
  mode,
  levelDriven = false,
}: {
  mode: DoraMode
  levelDriven?: boolean
}) {
  const id = 'dora-light-' + useId().replace(/:/g, '')
  const shape =
    'M160 38 C205 27 224 66 244 102 C270 135 278 172 253 201 C224 240 211 268 171 266 C133 273 94 257 71 229 C41 204 43 170 51 138 C55 104 69 71 106 59 C128 50 139 40 160 38Z'
  const bars = [8, 16, 28, 48, 78, 104, 72, 44, 24, 12]
  return (
    <div
      className="conversation-orb"
      data-mode={mode}
      data-level-driven={levelDriven || undefined}
      aria-hidden="true"
    >
      <svg viewBox="0 0 640 440" fill="none">
        <defs>
          <radialGradient id={id + '-halo'}>
            <stop stopColor="var(--color-dora-cyan)" stopOpacity=".35" />
            <stop offset="1" stopColor="var(--color-dora-cyan)" stopOpacity="0" />
          </radialGradient>
          <radialGradient id={id + '-core'} cx=".3" cy=".08" r="1.05">
            <stop stopColor="var(--color-dora-ice)" />
            <stop offset=".18" stopColor="var(--color-dora-cyan)" />
            <stop offset=".52" stopColor="var(--color-action)" />
            <stop offset="1" stopColor="var(--color-dora-deep)" />
          </radialGradient>
          <linearGradient
            id={id + '-edge'}
            x1="40"
            y1="40"
            x2="270"
            y2="270"
            gradientUnits="userSpaceOnUse"
          >
            <stop stopColor="var(--color-dora-cyan)" />
            <stop offset=".45" stopColor="var(--color-dora-ice)" />
            <stop offset=".72" stopColor="var(--color-dora-violet)" />
            <stop offset="1" stopColor="var(--color-dora-cyan)" />
          </linearGradient>
          <linearGradient
            id={id + '-wave'}
            gradientUnits="userSpaceOnUse"
            x1="0"
            y1="0"
            x2="640"
            y2="0"
          >
            <stop stopColor="var(--color-dora-violet)" />
            <stop offset=".5" stopColor="var(--color-dora-cyan)" />
            <stop offset="1" stopColor="var(--color-action)" />
          </linearGradient>
          <filter id={id + '-glow'} x="-50%" y="-50%" width="200%" height="200%">
            <feGaussianBlur stdDeviation="12" />
          </filter>
          <filter id={id + '-soft'} x="-30%" y="-30%" width="160%" height="160%">
            <feGaussianBlur stdDeviation="3" />
          </filter>
        </defs>
        <circle cx="320" cy="220" r="205" fill={'url(#' + id + '-halo)'} />
        <circle cx="320" cy="220" r="184" stroke="var(--color-action)" strokeOpacity=".15" />
        <circle
          cx="320"
          cy="220"
          r="167"
          stroke="var(--color-dora-cyan)"
          strokeOpacity=".65"
          strokeDasharray="2 9"
          strokeLinecap="round"
        />
        <path
          d="M147 235A174 174 0 0 1 326 46M493 200A174 174 0 0 1 333 394"
          stroke="var(--color-action)"
          strokeOpacity=".45"
          strokeWidth="1.5"
        />
        <g transform="translate(160 60)">
          <path
            d={shape}
            fill="var(--color-dora-cyan)"
            opacity=".45"
            filter={'url(#' + id + '-glow)'}
          />
          <path
            className="conversation-orb__ribbon"
            d={shape}
            fill={'url(#' + id + '-edge)'}
            fillOpacity=".38"
            stroke={'url(#' + id + '-edge)'}
            strokeWidth="3"
          />
          <path
            className="conversation-orb__ribbon conversation-orb__ribbon--back"
            d={shape}
            fill={'url(#' + id + '-edge)'}
            fillOpacity=".25"
            stroke={'url(#' + id + '-edge)'}
            strokeWidth="2"
          />
          <path
            d={shape}
            fill={'url(#' + id + '-core)'}
            transform="translate(17 17) scale(.9)"
            stroke="var(--color-dora-ice)"
            strokeOpacity=".7"
            strokeWidth="1.5"
          />
          <path
            d="M78 125C105 65 180 40 235 93"
            stroke="var(--color-dora-ice)"
            strokeWidth="4"
            strokeLinecap="round"
            opacity=".75"
            filter={'url(#' + id + '-soft)'}
          />
        </g>
        {[0, 1].map((side) => (
          <g
            key={side}
            className="conversation-orb__wave"
            stroke={'url(#' + id + '-wave)'}
            strokeWidth="6"
            strokeLinecap="round"
          >
            {bars.map((height, i) => (
              <path
                key={i}
                d={`M${side ? 522 + i * 10 : 28 + i * 10} ${220 - height / 2}v${height}`}
              />
            ))}
          </g>
        ))}
      </svg>
    </div>
  )
}
export function CallIcon({
  kind,
}: {
  kind: 'mic' | 'micOff' | 'sound' | 'keyboard' | 'transcript' | 'close' | 'help'
}) {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {kind === 'mic' ? (
        <>
          <rect x="9" y="2" width="6" height="13" rx="3" />
          <path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3M8 22h8" />
        </>
      ) : kind === 'micOff' ? (
        <>
          <path d="M9 9v3a3 3 0 0 0 5.1 2.1M15 9.3V5a3 3 0 0 0-5.9-.8" />
          <path d="M5 10v2a7 7 0 0 0 11.9 5M19 10v2c0 .8-.1 1.5-.4 2.2M12 19v3M8 22h8M3 3l18 18" />
        </>
      ) : kind === 'sound' ? (
        <>
          <path d="m11 4-5 4H2v8h4l5 4ZM15 8a6 6 0 0 1 0 8m3-11a10 10 0 0 1 0 14" />
        </>
      ) : kind === 'keyboard' ? (
        <>
          <rect x="2" y="5" width="20" height="14" rx="2" />
          <path d="M6 9h.1M10 9h.1M14 9h.1M18 9h.1M6 12h.1M10 12h.1M14 12h.1M18 12h.1M7 16h10" />
        </>
      ) : kind === 'transcript' ? (
        <>
          <path d="M4 3h16v15H9l-5 4V3ZM8 7h8M8 11h8M8 15h4" />
        </>
      ) : kind === 'close' ? (
        <path d="m6 6 12 12M18 6 6 18" />
      ) : (
        <>
          <circle cx="12" cy="12" r="9" />
          <path d="M9.5 9a2.5 2.5 0 1 1 3.3 2.4c-.8.4-.8 1-.8 1.6M12 17h.01" />
        </>
      )}
    </svg>
  )
}
