import { render } from '@testing-library/react'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { routes } from '../app/routes'
import { SessionProvider } from '../session/SessionProvider'

/** Renders the app routes in memory, starting at `path`. Also returns the router. */
export function renderApp(path = '/') {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  return { ...render(<RouterProvider router={router} />), router }
}

/**
 * Same, but inside the real `SessionProvider` (it asks `GET /auth/me`). Install a fake identity
 * backend first (`mockIdentityBackend`). Without the provider everybody is a visitor.
 */
export function renderAppWithSession(path = '/') {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  return {
    ...render(
      <SessionProvider>
        <RouterProvider router={router} />
      </SessionProvider>,
    ),
    router,
  }
}
