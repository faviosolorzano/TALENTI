import { useEffect, useState, type RefObject } from 'react'

interface UseInViewOptions {
  /** Fraction of the element that must be visible (0 to 1). */
  threshold?: number
  /** Stay true after the first time the element becomes visible. */
  once?: boolean
}

/**
 * True while the element is on screen. Without `IntersectionObserver` it returns true from
 * the start, so whatever depends on it works right away (003, edge cases).
 */
export function useInView(
  ref: RefObject<Element | null>,
  { threshold = 0.35, once = false }: UseInViewOptions = {},
): boolean {
  const [inView, setInView] = useState(() => typeof IntersectionObserver === 'undefined')

  useEffect(() => {
    const element = ref.current
    if (!element || typeof IntersectionObserver === 'undefined') return

    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          setInView(entry.isIntersecting)
          if (entry.isIntersecting && once) observer.disconnect()
        }
      },
      { threshold },
    )
    observer.observe(element)
    return () => observer.disconnect()
  }, [ref, threshold, once])

  return inView
}
