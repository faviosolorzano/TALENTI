import { useState } from 'react'
import { useLocation, useParams } from 'react-router'
import { ApiError } from '../../api/client'
import { fetchVacancy } from '../../api/vacancies'
import { fetchProfile } from '../../api/profile'
import {
  fetchApplications,
  isActiveStatus,
  submitApplication,
  type ApplicationItem,
} from '../../api/applications'
import { useApiResource } from '../../hooks/useApiResource'
import { Button } from '../../components/Button'
import { WorkflowFrame, StudentWorkflow, Loading, ActionError } from './shared'
import { useWorkflowAction } from './useWorkflowAction'
import { t } from './workflowLabels'
export function ApplicationReviewPage() {
  const { id = '' } = useParams()
  return (
    <StudentWorkflow>
      <Review id={id} />
    </StudentWorkflow>
  )
}
function Review({ id }: { id: string }) {
  const location = useLocation()
  const resource = useApiResource('application-review:' + id, async (signal) => {
    const [vacancy, profile, applications] = await Promise.all([
      fetchVacancy(id, signal),
      fetchProfile(signal),
      fetchApplications({ vacancyId: id }, signal),
    ])
    return { vacancy, profile, active: applications.items.find((a) => isActiveStatus(a.status)) }
  })
  const action = useWorkflowAction()
  const [submitted, setSubmitted] = useState<ApplicationItem | null>(null)
  async function confirm() {
    try {
      setSubmitted(await submitApplication(id))
    } catch (error) {
      if (
        !(error instanceof ApiError) ||
        error.status >= 500 ||
        error.problem?.code === 'application-already-exists'
      ) {
        const existing = (await fetchApplications({ vacancyId: id })).items.find((a) =>
          isActiveStatus(a.status),
        )
        if (existing) {
          setSubmitted(existing)
          return
        }
      }
      throw error
    }
  }
  const data = resource.state.status === 'success' ? resource.state.data : null
  const success = submitted ?? data?.active
  return (
    <WorkflowFrame title={success ? t.applied : t.applyTitle} back={'/oportunidades/' + id}>
      <ActionError message={action.error} />
      {resource.state.status === 'loading' ? (
        <Loading />
      ) : resource.state.status === 'error' ? (
        <>
          <ActionError message={t.error} />
          <Button onClick={resource.retry}>{t.retry}</Button>
        </>
      ) : (
        data && (
          <>
            <p>{t.applyIntro}</p>
            <h2>{data.vacancy.title}</h2>
            <p>{data.vacancy.company.name}</p>
            {success ? (
              <>
                <p role="status">
                  {t.applied} · {new Date(success.submittedAt).toLocaleString('es-PE')}
                </p>
                <Button to={'/postulaciones/' + success.id}>{t.viewApplication}</Button>
              </>
            ) : (
              <>
                <section className="workflow__row">
                  <h2>{t.profileReview}</h2>
                  <dl>
                    <dt>{t.fullName}</dt>
                    <dd>
                      {[data.profile.about.firstNames, data.profile.about.lastNames]
                        .filter(Boolean)
                        .join(' ') || t.notProvided}
                    </dd>
                    <dt>{t.contact}</dt>
                    <dd>{data.profile.about.contactEmail ?? t.notProvided}</dd>
                    <dt>{t.academic}</dt>
                    <dd>
                      {[data.profile.academic.career, data.profile.academic.institute]
                        .filter(Boolean)
                        .join(' · ') || t.notProvided}
                    </dd>
                    <dt>{t.skills}</dt>
                    <dd>
                      {data.profile.skills
                        .map((skill) => skill.name + ' · ' + t.levels[skill.level - 1])
                        .join(', ') || t.notProvided}
                    </dd>
                  </dl>
                  <Button
                    variant="quiet"
                    to={
                      '/perfil?seccion=sobre-ti&returnTo=' + encodeURIComponent(location.pathname)
                    }
                    state={location.state}
                  >
                    {t.editProfile}
                  </Button>
                </section>
                <p className="workflow__notice">{t.applyDemo}</p>
                <Button
                  disabled={action.busy || data.vacancy.status !== 'OPEN'}
                  onClick={() => void action.perform(confirm)}
                >
                  {action.busy ? t.working : t.applyConfirm}
                </Button>
              </>
            )}
          </>
        )
      )}
    </WorkflowFrame>
  )
}
