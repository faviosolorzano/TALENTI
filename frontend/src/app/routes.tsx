import type { RouteObject } from 'react-router'
import { DashboardPage } from '../pages/DashboardPage'
import { HomePage } from '../pages/HomePage'
import { LoginPage } from '../pages/LoginPage'
import { NotFoundPage } from '../pages/NotFoundPage'
import { RegisterPage } from '../pages/RegisterPage'
import { es } from '../i18n/es'
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
        path: '/registro',
        element: <RegisterPage />,
        handle: { title: es.pages.register.title } satisfies RouteHandle,
      },
      {
        path: '/ingresar',
        element: <LoginPage />,
        handle: { title: es.pages.login.title } satisfies RouteHandle,
      },
      {
        path: '/panel',
        element: <DashboardPage />,
        handle: { title: es.pages.dashboard.title } satisfies RouteHandle,
      },
      {
        path: '*',
        element: <NotFoundPage />,
        handle: { title: es.pages.notFound.title } satisfies RouteHandle,
      },
    ],
  },
]
