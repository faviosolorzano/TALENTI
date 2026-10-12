import './BrandMark.css'

/**
 * Large, faint "T with figure" symbol used as a watermark behind the hero. It takes the
 * current text color (`currentColor`), so the color lives in the CSS tokens. Decorative.
 */
export function BrandMark() {
  return (
    <svg className="brand-mark" viewBox="0 0 1254 1254" aria-hidden="true" focusable="false">
      <g fill="currentColor">
        <path d="M 222 418 H 505 C 530 418 545 432 570 436 C 640 446 720 436 800 430 L 915 617 C 840 622 780 660 780 715 V 1100 Q 780 1145 735 1145 H 600 A 170 170 0 0 1 430 975 V 715 C 430 680 400 650 360 650 H 222 A 116 116 0 0 1 222 418 Z" />
        <circle cx="627" cy="283" r="140" />
      </g>
    </svg>
  )
}
