import { ApiError } from '../../api/client'
import { fetchProfile } from '../../api/profile'
import { useApiResource } from '../../hooks/useApiResource'
import { useSession } from '../../session/useSession'

/** Loads the profile for the Home (one request: the greeting and the Perfil block share it). */
export function useProfileResource() {
  const { refresh } = useSession()
  return useApiResource('home-profile', async (signal) => {
    try {
      return await fetchProfile(signal)
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) void refresh()
      throw error
    }
  })
}

export type ProfileResource = ReturnType<typeof useProfileResource>
