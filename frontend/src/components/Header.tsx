import { useEffect, useId, useRef, useState } from 'react'
import { Link, NavLink } from 'react-router'
import { es } from '../i18n/es'
import { Button } from './Button'
import { Logo } from './Logo'
import './Header.css'

/** Scroll distance (px) after which the header shows its bottom line. */
const SCROLLED_AFTER = 8

/**
 * Fixed header. It shows a bottom line once the page scrolls more than 8 px. Under tablet
 * width the menu folds into the "Menú" button (`aria-expanded`, `aria-controls`); it closes
 * when a link is chosen or with Esc, and then focus goes back to the button.
 */
export function Header() {
  const menuId = useId()
  const [open, setOpen] = useState(false)
  const [scrolled, setScrolled] = useState(() => window.scrollY > SCROLLED_AFTER)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const menuRef = useRef<HTMLElement>(null)
  const focusFirstLink = useRef(false)

  useEffect(() => {
    const update = () => setScrolled(window.scrollY > SCROLLED_AFTER)
    window.addEventListener('scroll', update, { passive: true })
    return () => window.removeEventListener('scroll', update)
  }, [])

  useEffect(() => {
    if (!open) return
    // Opening with the button moves focus to the first link, so the keyboard continues
    // inside the menu (the menu is drawn after the button has been pressed).
    if (focusFirstLink.current) {
      focusFirstLink.current = false
      menuRef.current?.querySelector('a')?.focus()
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      setOpen(false)
      buttonRef.current?.focus()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [open])

  function toggleMenu() {
    focusFirstLink.current = !open
    setOpen(!open)
  }

  const closeMenu = () => setOpen(false)

  return (
    <header className="header" data-scrolled={scrolled || undefined}>
      <div className="container header__bar">
        <Link className="header__brand" to="/" aria-label={es.app.homeLink} onClick={closeMenu}>
          <Logo />
        </Link>

        <nav
          ref={menuRef}
          id={menuId}
          className="header__menu"
          aria-label={es.app.mainNavLabel}
          data-open={open}
        >
          <ul className="header__list">
            <li>
              <NavLink className="header__link" to="/" end onClick={closeMenu}>
                {es.nav.home}
              </NavLink>
            </li>
            <li>
              <NavLink className="header__link" to="/oportunidades" onClick={closeMenu}>
                {es.nav.opportunities}
              </NavLink>
            </li>
            <li>
              <Link
                className="header__link"
                to={{ pathname: '/', hash: 'como-funciona' }}
                onClick={closeMenu}
              >
                {es.nav.how}
              </Link>
            </li>
            <li>
              <Link
                className="header__link"
                to={{ pathname: '/', hash: 'dora' }}
                onClick={closeMenu}
              >
                {es.nav.dora}
              </Link>
            </li>
            {/* Only in the folded menu: on wide screens "Ingresar" is a button on the right. */}
            <li className="header__list-login">
              <NavLink className="header__link" to="/ingresar" onClick={closeMenu}>
                {es.nav.login}
              </NavLink>
            </li>
          </ul>
        </nav>

        <div className="header__actions">
          <Button
            className="header__login"
            variant="quiet"
            size="sm"
            to="/ingresar"
            onClick={closeMenu}
          >
            {es.nav.login}
          </Button>
          <Button size="sm" to="/registro" onClick={closeMenu}>
            {es.nav.register}
          </Button>
        </div>

        <button
          ref={buttonRef}
          type="button"
          className="header__menu-button"
          aria-expanded={open}
          aria-controls={menuId}
          onClick={toggleMenu}
        >
          {es.nav.menu}
        </button>
      </div>
    </header>
  )
}
