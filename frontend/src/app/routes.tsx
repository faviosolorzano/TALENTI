import type { RouteObject } from 'react-router'
import { DashboardPage } from '../pages/DashboardPage'
import { HomePage } from '../pages/HomePage'
import { OpportunitiesPage } from '../pages/OpportunitiesPage'
import { LoginPage } from '../pages/LoginPage'
import { NotFoundPage } from '../pages/NotFoundPage'
import { RecoverPage } from '../pages/RecoverPage'
import { RegisterPage } from '../pages/RegisterPage'
import { VacancyPage } from '../pages/VacancyPage'
import { es } from '../i18n/es'
import { AcceptDocumentsPage } from '../features/legal/AcceptDocumentsPage'
import { LegalDocumentPage } from '../features/legal/LegalDocumentPage'
import { TransparencyPage } from '../features/legal/TransparencyPage'
import { ACCEPT_DOCUMENTS_PATH, LEGAL_PATHS } from '../features/legal/paths'
import { sessionPaths } from '../session/sessionPaths'
import type { RouteHandle } from './routeHandle'
import { Layout } from './Layout'

export const routes: RouteObject[] = [
  {
    element: <Layout />,
    children: [
      {
        path: '/',
        element: <HomePage />,
        handle: { title: es.pages.home.title } satisfies RouteHandle,
      },
      {
        path: '/oportunidades',
        element: <OpportunitiesPage />,
        handle: { title: es.pages.opportunities.title } satisfies RouteHandle,
      },
      {
        path: '/oportunidades/:id',
        element: <VacancyPage />,
        handle: { title: es.pages.vacancy.title } satisfies RouteHandle,
      },
      {
        path: sessionPaths.register,
        element: <RegisterPage />,
        handle: { title: es.pages.register.title } satisfies RouteHandle,
      },
      {
        path: sessionPaths.login,
        element: <LoginPage />,
        handle: { title: es.pages.login.title } satisfies RouteHandle,
      },
      {
        path: sessionPaths.dashboard,
        element: <DashboardPage />,
        handle: { title: es.pages.dashboard.title } satisfies RouteHandle,
      },
      // ---- Legal framework (spec 002) ----
      {
        path: LEGAL_PATHS.terms,
        element: <LegalDocumentPage key="terms" documentKey="terms" />,
        handle: { title: es.legal.fallbackTitles.terms } satisfies RouteHandle,
      },
      {
        path: LEGAL_PATHS.privacy,
        element: <LegalDocumentPage key="privacy" documentKey="privacy" />,
        handle: { title: es.legal.fallbackTitles.privacy } satisfies RouteHandle,
      },
      {
        path: LEGAL_PATHS.cookies,
        element: <LegalDocumentPage key="cookies" documentKey="cookies" />,
        handle: { title: es.legal.fallbackTitles.cookies } satisfies RouteHandle,
      },
      {
        path: LEGAL_PATHS.transparency,
        element: <TransparencyPage />,
        handle: { title: es.footer.transparency } satisfies RouteHandle,
      },
      {
        path: ACCEPT_DOCUMENTS_PATH,
        element: <AcceptDocumentsPage />,
        handle: { title: es.acceptDocuments.title } satisfies RouteHandle,
      },
      // ---- S1 identity: recovery notice (001-23) ----
      {
        path: sessionPaths.recover,
        element: <RecoverPage />,
        handle: { title: es.identity.recover.title } satisfies RouteHandle,
      },
      {
        path: '*',
        element: <NotFoundPage />,
        handle: { title: es.pages.notFound.title } satisfies RouteHandle,
      },
    ],
  },
]
