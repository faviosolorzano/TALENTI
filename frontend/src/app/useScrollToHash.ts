import { useEffect } from 'react'
import { useLocation, useNavigationType } from 'react-router'

/**
 * Scrolls to the section named by the URL hash (`/#dora`) and moves focus to it, as a browser
 * does with a plain anchor, so the next Tab continues from there. Sections leave room for the
 * fixed header with `scroll-padding-top` (design/global.css). A navigation without a hash
 * starts at the top, except when the user goes back or forward (the browser restores the place).
 */
export function useScrollToHash(): void {
  const { hash, key } = useLocation()
  const navigationType = useNavigationType()

  useEffect(() => {
    if (hash) {
      const target = document.getElementById(decodeURIComponent(hash.slice(1)))
      if (!target) return
      target.scrollIntoView?.()
      target.focus({ preventScroll: true })
    } else if (navigationType !== 'POP') {
      window.scrollTo?.(0, 0)
    }
  }, [hash, key, navigationType])
}
