import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './assets/styles/main.css'
import type { ThemeMode } from '@/composables/useTheme'

// FOUC 防护：在 Vue 挂载前同步应用主题 dark class，避免白屏闪烁
;(function initTheme() {
  const stored = localStorage.getItem('lifepilot_theme') as ThemeMode | null
  const mode = stored ?? 'system'
  const isDark = mode === 'dark'
    || (mode === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches)
  if (isDark) {
    document.documentElement.classList.add('dark')
  } else {
    document.documentElement.classList.remove('dark')
  }
})()

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
