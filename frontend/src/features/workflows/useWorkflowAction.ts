import { useEffect, useRef, useState } from 'react'
import { ApiError } from '../../api/client'
import { useSession } from '../../session/useSession'
import { t } from './workflowLabels'
export function useWorkflowAction() {
  const { refresh } = useSession()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const lock = useRef(false)
  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])
  async function perform(action: () => Promise<void>) {
    if (lock.current) return
    lock.current = true
    setBusy(true)
    setError('')
    try {
      await action()
    } catch (err) {
      if (mounted.current) {
        if (err instanceof ApiError && err.status === 401) void refresh()
        const code = err instanceof ApiError ? String(err.problem?.code ?? '') : ''
        setError(
          t.errors[code as keyof typeof t.errors] ??
            (err instanceof ApiError && err.status === 422 ? t.invalid : t.error),
        )
      }
    } finally {
      lock.current = false
      if (mounted.current) setBusy(false)
    }
  }
  return { busy, error, perform, setError }
}
