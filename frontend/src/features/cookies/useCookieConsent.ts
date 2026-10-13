import { useContext } from 'react'
import { CookieConsentContext, type CookieConsentValue } from './cookieConsentContext'

export function useCookieConsent(): CookieConsentValue {
  const value = useContext(CookieConsentContext)
  if (!value) throw new Error('useCookieConsent must be used inside <CookieConsentProvider>')
  return value
}
