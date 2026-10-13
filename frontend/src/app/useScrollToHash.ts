import { useEffect, useRef } from 'react'
import { useLocation, useNavigationType } from 'react-router'

/**
 * Scrolls to the section named by the URL hash (`/#dora`) and moves focus to it, as a browser
 * does with a plain anchor, so the next Tab continues from there. Sections leave room for the
 * fixed header with `scroll-padding-top` (design/global.css). A navigation without a hash
 * starts at the top, except when the user goes back or forward (the browser restores the place)
 * or when only the query string changed on the same page (filters, sort or page of a list keep
 * the place; the list moves focus to its own title).
 */
export function useScrollToHash(): void {
  const { pathname, search, hash, key } = useLocation()
  const navigationType = useNavigationType()
  const previous = useRef({ pathname, search })

  useEffect(() => {
    const queryOnly = previous.current.pathname === pathname && previous.current.search !== search
    previous.current = { pathname, search }
    if (hash) {
      const target = document.getElementById(decodeURIComponent(hash.slice(1)))
      if (!target) {
        // A section that does not exist (or is not loaded yet) leaves the page at the top.
        if (navigationType !== 'POP') window.scrollTo?.(0, 0)
        return
      }
      target.scrollIntoView?.()
      target.focus({ preventScroll: true })
    } else if (navigationType !== 'POP' && !queryOnly) {
      window.scrollTo?.(0, 0)
    }
  }, [pathname, search, hash, key, navigationType])
}
