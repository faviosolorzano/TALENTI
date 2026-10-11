import type { RouteObject } from 'react-router'
import { DashboardPage } from '../pages/DashboardPage'
import { HomePage } from '../pages/HomePage'
import { LoginPage } from '../pages/LoginPage'
import { NotFoundPage } from '../pages/NotFoundPage'
import { RegisterPage } from '../pages/RegisterPage'
import { Layout } from './Layout'

export const routes: RouteObject[] = [
  {
    element: <Layout />,
    children: [
      { path: '/', element: <HomePage /> },
      { path: '/registro', element: <RegisterPage /> },
      { path: '/ingresar', element: <LoginPage /> },
      { path: '/panel', element: <DashboardPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]
