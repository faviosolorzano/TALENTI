import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './app/App'
import '@fontsource-variable/bricolage-grotesque/opsz.css'
import '@fontsource-variable/figtree/wght.css'
import './design/tokens.css'
import './design/global.css'

const rootElement = document.getElementById('root')
if (!rootElement) throw new Error('Root element #root not found')

createRoot(rootElement).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
