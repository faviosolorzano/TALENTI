import { useState } from 'react'
import {
  workflowApi as api,
  type GithubLink,
  type GithubRepo,
  type GithubSuggestion,
} from '../../api/workflows'
import { useApiResource } from '../../hooks/useApiResource'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { WorkflowFrame, StudentWorkflow, Loading, ActionError } from './shared'
import { useWorkflowAction } from './useWorkflowAction'
import { t } from './workflowLabels'
export function GithubPage() {
  return (
    <StudentWorkflow>
      <GithubConnection />
    </StudentWorkflow>
  )
}
function GithubConnection() {
  const resource = useApiResource('github-link', (signal) =>
    api<GithubLink | undefined>('/github', { signal }),
  )
  const action = useWorkflowAction()
  return (
    <WorkflowFrame title={t.github}>
      <p>{t.githubIntro}</p>
      <ActionError message={action.error} />
      {resource.state.status === 'loading' ? (
        <Loading />
      ) : resource.state.status === 'error' ? (
        <>
          <ActionError message={t.error} />
          <Button onClick={resource.retry}>{t.retry}</Button>
        </>
      ) : resource.state.data ? (
        <GithubConnected link={resource.state.data} unlinked={resource.retry} />
      ) : (
        <Button
          disabled={action.busy}
          onClick={() =>
            void action.perform(async () => {
              const result = await api<{ authorizeUrl: string }>('/github/oauth/start', {
                method: 'POST',
              })
              const url = new URL(result.authorizeUrl)
              if (
                url.protocol !== 'https:' ||
                url.hostname !== 'github.com' ||
                url.pathname !== '/login/oauth/authorize'
              )
                throw Error('invalid-authorize-url')
              window.location.assign(url.href)
            })
          }
        >
          {action.busy ? t.working : t.connect}
        </Button>
      )}
    </WorkflowFrame>
  )
}
function GithubConnected({ link, unlinked }: { link: GithubLink; unlinked: () => void }) {
  const repos = useApiResource('github-repositories', (signal) =>
    api<{ items: GithubRepo[]; fetchedAt: string; stale: boolean }>('/github/repositories', {
      signal,
    }),
  )
  const suggestions = useApiResource('github-suggestions', (signal) =>
    api<{ suggestions: GithubSuggestion[]; unmatched: string[]; source: string }>(
      '/github/suggestions',
      { signal },
    ),
  )
  const action = useWorkflowAction()
  const [disconnect, setDisconnect] = useState(false)
  const [message, setMessage] = useState('')
  return (
    <>
      <p>
        {t.linked}: <strong>{link.login}</strong>
      </p>
      <ActionError message={action.error} />
      <p role="status">{message}</p>
      <div className="workflow__actions">
        <Button
          variant="quiet"
          disabled={action.busy}
          onClick={() =>
            void action.perform(async () => {
              await api('/github/refresh', { method: 'POST' })
              repos.retry()
              suggestions.retry()
              setMessage(t.updated)
            })
          }
        >
          {t.refresh}
        </Button>
        <Button variant="quiet" onClick={() => setDisconnect(true)}>
          {t.disconnect}
        </Button>
      </div>
      <h2>{t.repositories}</h2>
      {repos.state.status === 'loading' ? (
        <Loading />
      ) : repos.state.status === 'error' ? (
        <Button onClick={repos.retry}>{t.retry}</Button>
      ) : (
        <>
          <p>
            {t.updatedAt}: {new Date(repos.state.data.fetchedAt).toLocaleString('es-PE')}
            {repos.state.data.stale ? ' · ' + t.stale : ''}
          </p>
          <RepositorySelection
            key={JSON.stringify(repos.state.data.items)}
            items={repos.state.data.items}
            saved={() => {
              repos.retry()
              suggestions.retry()
              setMessage(t.saved)
            }}
          />
        </>
      )}
      <h2>{t.suggestions}</h2>
      {suggestions.state.status === 'loading' ? (
        <Loading />
      ) : suggestions.state.status === 'error' ? (
        <Button onClick={suggestions.retry}>{t.retry}</Button>
      ) : (
        <GithubDecisions
          key={JSON.stringify(suggestions.state.data)}
          items={suggestions.state.data.suggestions}
          saved={() => {
            suggestions.retry()
            setMessage(t.saved)
          }}
        />
      )}
      {disconnect && (
        <Dialog title={t.disconnect} onClose={() => setDisconnect(false)} busy={action.busy}>
          <p>{t.disconnectBody}</p>
          <ActionError message={action.error} />
          <Button
            disabled={action.busy}
            onClick={() =>
              void action.perform(async () => {
                await api('/github', { method: 'DELETE' })
                unlinked()
              })
            }
          >
            {t.disconnect}
          </Button>
          <Button variant="quiet" onClick={() => setDisconnect(false)}>
            {t.cancel}
          </Button>
        </Dialog>
      )}
    </>
  )
}
function RepositorySelection({ items, saved }: { items: GithubRepo[]; saved: () => void }) {
  const original = items.filter((r) => r.selected).map((r) => r.id)
  const [selected, setSelected] = useState(original)
  const action = useWorkflowAction()
  const dirty = JSON.stringify([...selected].sort()) !== JSON.stringify([...original].sort())
  return (
    <>
      <p>
        {t.selectRepos} · {selected.length}/6
      </p>
      {!items.length && <p>{t.noRepos}</p>}
      <ul className="workflow__list">
        {items.map((repo) => (
          <li key={repo.id} className="workflow__row">
            <label className="workflow__choice">
              <input
                type="checkbox"
                checked={selected.includes(repo.id)}
                disabled={!selected.includes(repo.id) && selected.length >= 6}
                onChange={(e) =>
                  setSelected(
                    e.target.checked
                      ? [...selected, repo.id]
                      : selected.filter((id) => id !== repo.id),
                  )
                }
              />
              <strong>{repo.name}</strong>
            </label>
            <p>{repo.description}</p>
            <a
              href={/^https:\/\/github\.com\//.test(repo.url) ? repo.url : undefined}
              target="_blank"
              rel="noopener noreferrer"
            >
              {t.viewRepository}
            </a>
            <p>
              {repo.languages
                .map((language) => language.name + ' ' + language.percent + '%')
                .join(' · ')}
            </p>
          </li>
        ))}
      </ul>
      <ActionError message={action.error} />
      <Button
        disabled={action.busy || !dirty}
        onClick={() =>
          void action.perform(async () => {
            await api('/github/repositories/selection', {
              method: 'PUT',
              json: { repoIds: selected },
            })
            saved()
          })
        }
      >
        {t.saveRepos}
      </Button>
    </>
  )
}
function GithubDecisions({ items, saved }: { items: GithubSuggestion[]; saved: () => void }) {
  const [decisions, setDecisions] = useState<Record<string, { decision: string; level: number }>>(
    {},
  )
  const action = useWorkflowAction()
  return !items.length ? (
    <p>{t.noSuggestions}</p>
  ) : (
    <>
      <p>{t.githubEvidence}</p>
      {items.map((item) => (
        <div className="workflow__row" key={item.slug}>
          <h3>{item.name}</h3>
          <small>{t.observed}</small>
          <ul>
            {item.evidence.map((e, i) => (
              <li key={i}>
                {e.repo} · {e.source}
                {e.share != null ? ' · ' + e.share + '%' : ''}
              </li>
            ))}
          </ul>
          <div className="workflow__actions">
            <label>
              {t.confirm}
              <select
                value={decisions[item.slug]?.decision ?? ''}
                onChange={(e) =>
                  setDecisions({
                    ...decisions,
                    [item.slug]: {
                      decision: e.target.value,
                      level: decisions[item.slug]?.level ?? item.currentLevel ?? 1,
                    },
                  })
                }
              >
                <option value="">{t.pending}</option>
                <option value="ACCEPT">{t.accept}</option>
                <option value="REJECT">{t.discard}</option>
              </select>
            </label>
            {decisions[item.slug]?.decision === 'ACCEPT' && (
              <label>
                {t.level}
                <select
                  value={decisions[item.slug]?.level ?? 1}
                  onChange={(e) =>
                    setDecisions({
                      ...decisions,
                      [item.slug]: {
                        decision: decisions[item.slug]?.decision ?? 'ACCEPT',
                        level: Number(e.target.value),
                      },
                    })
                  }
                >
                  {[1, 2, 3].map((level) => (
                    <option key={level} value={level}>
                      {t.levels[level - 1]}
                    </option>
                  ))}
                </select>
              </label>
            )}
          </div>
        </div>
      ))}
      <ActionError message={action.error} />
      <Button
        disabled={action.busy || !Object.values(decisions).some((d) => d.decision)}
        onClick={() =>
          void action.perform(async () => {
            await api('/github/suggestions/decisions', {
              method: 'POST',
              json: {
                items: Object.entries(decisions)
                  .filter(([, d]) => d.decision)
                  .map(([slug, d]) => ({
                    slug,
                    decision: d.decision,
                    level: d.decision === 'ACCEPT' ? d.level : undefined,
                  })),
              },
            })
            saved()
          })
        }
      >
        {t.applySuggestions}
      </Button>
    </>
  )
}
