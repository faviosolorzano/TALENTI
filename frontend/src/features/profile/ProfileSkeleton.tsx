import { Card } from '../../components/Card'
import { es } from '../../i18n/es'
import '../vacancies/Skeletons.css'
import './ProfileSkeleton.css'

/** Placeholder of /perfil while the profile loads (006-20). The title is already real. */
export function ProfileSkeleton() {
  return (
    <div className="container profile" aria-busy="true">
      <div className="profile__head">
        <h1 className="profile__title">{es.profile.title}</h1>
        <p className="profile__lead">{es.profile.lead}</p>
      </div>
      <p className="visually-hidden" role="status">
        {es.profile.loading}
      </p>
      <div className="profile__layout" aria-hidden="true">
        <div className="profile__aside">
          <Card className="profile-skeleton__summary">
            <span className="skeleton skeleton--title" />
            <span className="skeleton skeleton--heading profile-skeleton__number" />
            <span className="skeleton skeleton--line" />
            <span className="skeleton skeleton--line skeleton--short" />
          </Card>
        </div>
        <div className="profile__sections">
          {[0, 1, 2, 3].map((index) => (
            <Card key={index} className="profile-skeleton__section">
              <span className="skeleton skeleton--tick" />
              <span className="skeleton skeleton--title" />
            </Card>
          ))}
        </div>
      </div>
    </div>
  )
}
