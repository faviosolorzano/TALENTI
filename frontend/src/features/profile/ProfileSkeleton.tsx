import { Card } from '../../components/Card'
import { es } from '../../i18n/es'
import '../vacancies/Skeletons.css'
import './ProfileSkeleton.css'

/** Placeholder of /perfil while the profile loads (006-20). The title is already real. */
export function ProfileSkeleton() {
  return (
    <div className="container profile profile--soft" aria-busy="true">
      <div className="profile__head">
        <p className="profile__eyebrow">{es.profile.overview.eyebrow}</p>
        <h1 className="profile__title">{es.profile.title}</h1>
        <p className="profile__lead">{es.profile.overview.lead}</p>
      </div>
      <p className="visually-hidden" role="status">
        {es.profile.loading}
      </p>
      <div className="profile-skeleton__layout" aria-hidden="true">
        <div className="profile-identity">
          <span className="skeleton profile-identity__initials" />
          <div className="profile-skeleton__copy">
            <span className="skeleton skeleton--heading" />
            <span className="skeleton skeleton--line" />
            <span className="skeleton skeleton--line skeleton--short" />
          </div>
        </div>
        <div className="profile__workspace">
          <div className="profile-skeleton__nav">
            {[0, 1, 2, 3, 4].map((index) => (
              <span className="skeleton skeleton--line" key={index} />
            ))}
          </div>
          <div className="profile__sections">
            {[0, 1, 2, 3, 4].map((index) => (
              <Card key={index} className="profile-skeleton__section">
                <span className="skeleton skeleton--tick" />
                <span className="skeleton skeleton--title" />
              </Card>
            ))}
          </div>
        </div>
      </div>
    </div>
  )
}
