import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './assets/styles/main.css'
import type { ThemeMode } from '@/composables/useTheme'

const DISPLAY_PREFERENCES_KEY = 'zhiwei_display_preferences'

;(function initTheme() {
  const stored = localStorage.getItem('zhiwei_theme') as ThemeMode | null
  const mode = stored ?? 'system'
  const isDark = mode === 'dark'
    || (mode === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches)

  if (isDark) {
    document.documentElement.classList.add('dark')
  } else {
    document.documentElement.classList.remove('dark')
  }
})()

;(function initDisplayPreferences() {
  const root = document.documentElement
  root.lang = 'zh-CN'
  root.dataset.uiDensity = 'standard'
  root.dataset.fontSize = 'medium'
  root.dataset.timeFormat = '24h'
  root.dataset.showTokenUsage = 'true'

  const stored = localStorage.getItem(DISPLAY_PREFERENCES_KEY)
  if (!stored) return

  try {
    const preferences = JSON.parse(stored) as {
      layoutDensity?: 'compact' | 'standard'
      fontSize?: 'small' | 'medium' | 'large'
      timeFormat?: '12h' | '24h'
      showTokenUsage?: boolean
    }

    root.dataset.uiDensity = preferences.layoutDensity ?? 'standard'
    root.dataset.fontSize = preferences.fontSize ?? 'medium'
    root.dataset.timeFormat = preferences.timeFormat ?? '24h'
    root.dataset.showTokenUsage = String(preferences.showTokenUsage ?? true)
  } catch {
    localStorage.removeItem(DISPLAY_PREFERENCES_KEY)
  }
})()

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
