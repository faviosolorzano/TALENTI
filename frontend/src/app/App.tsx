import { createBrowserRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { SessionProvider } from '../session/SessionProvider'
import { routes } from './routes'

const router = createBrowserRouter(routes)

export function App() {
  return (
    <SessionProvider>
      <RouterProvider router={router} />
    </SessionProvider>
  )
}
