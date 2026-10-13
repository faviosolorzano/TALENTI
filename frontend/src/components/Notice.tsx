import type { HTMLAttributes, ReactNode, Ref } from 'react'
import './Notice.css'

type NoticeProps = Omit<HTMLAttributes<HTMLDivElement>, 'role'> & {
  /** `error` and `success` use the state colors; `info` is neutral. */
  tone?: 'error' | 'success' | 'info'
  /**
   * How it is announced: `alert` interrupts (a problem), `status` waits its turn (progress or
   * good news). Use `none` for a message that is already inside a live region.
   */
  announce?: 'alert' | 'status' | 'none'
  ref?: Ref<HTMLDivElement>
  children: ReactNode
}

/** A boxed message for a whole form or page. A mark besides the color tells the tone. */
export function Notice({
  tone = 'info',
  announce = 'status',
  className,
  ref,
  children,
  ...rest
}: NoticeProps) {
  return (
    <div
      {...rest}
      ref={ref}
      role={announce === 'none' ? undefined : announce}
      className={className ? `notice notice--${tone} ${className}` : `notice notice--${tone}`}
    >
      {children}
    </div>
  )
}
