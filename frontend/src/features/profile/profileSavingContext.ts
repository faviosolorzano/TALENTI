import { createContext, type Dispatch, type SetStateAction } from 'react'
export const ProfileSavingContext = createContext<Dispatch<SetStateAction<boolean>> | null>(null)
