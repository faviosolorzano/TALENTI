import { companyInitials, companyTone } from './avatarTone'
import './CompanyAvatar.css'

interface CompanyAvatarProps {
  name: string
  size?: 'sm' | 'md' | 'lg'
}

/**
 * Company mark made of initials on one of three tones of the brand (there are no logos or
 * real brands). It is decorative: the company name is always written next to it.
 */
export function CompanyAvatar({ name, size = 'md' }: CompanyAvatarProps) {
  return (
    <span
      className={`company-avatar company-avatar--${size}`}
      data-tone={companyTone(name) + 1}
      aria-hidden="true"
    >
      {companyInitials(name)}
    </span>
  )
}
