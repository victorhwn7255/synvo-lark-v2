import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App.tsx'
import './styles/index.css'
import { startAppearance } from './appearance'

const disposeAppearance = startAppearance()
if (import.meta.hot) import.meta.hot.dispose(disposeAppearance)

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
