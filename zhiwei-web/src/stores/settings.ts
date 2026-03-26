import { defineStore } from 'pinia'
import { ref } from 'vue'
import { modelServiceApi, type ModelService } from '@/api/client'

type ThemePreference = 'light' | 'dark' | 'system'
type LayoutDensity = 'compact' | 'standard'
type FontSize = 'small' | 'medium' | 'large'

const THEME_STORAGE_KEY = 'zhiwei_theme'
const DISPLAY_PREFERENCES_KEY = 'zhiwei_display_preferences'

interface DisplayPreferencesSnapshot {
  layoutDensity: LayoutDensity
  fontSize: FontSize
  showTokenUsage: boolean
}

const DEFAULT_DISPLAY_PREFERENCES: DisplayPreferencesSnapshot = {
  layoutDensity: 'standard',
  fontSize: 'medium',
  showTokenUsage: true,
}

function readThemePreference(): ThemePreference {
  if (typeof window === 'undefined') {
    return 'system'
  }

  const stored = localStorage.getItem(THEME_STORAGE_KEY)
  if (stored === 'light' || stored === 'dark' || stored === 'system') {
    return stored
  }
  return 'system'
}

function readDisplayPreferences(): DisplayPreferencesSnapshot {
  if (typeof window === 'undefined') {
    return { ...DEFAULT_DISPLAY_PREFERENCES }
  }

  const stored = localStorage.getItem(DISPLAY_PREFERENCES_KEY)
  if (!stored) {
    return { ...DEFAULT_DISPLAY_PREFERENCES }
  }

  try {
    const parsed = JSON.parse(stored) as Partial<DisplayPreferencesSnapshot>
    return {
      layoutDensity: parsed.layoutDensity === 'compact' ? 'compact' : 'standard',
      fontSize: parsed.fontSize === 'small' || parsed.fontSize === 'large' ? parsed.fontSize : 'medium',
      showTokenUsage: parsed.showTokenUsage ?? true,
    }
  } catch {
    localStorage.removeItem(DISPLAY_PREFERENCES_KEY)
    return { ...DEFAULT_DISPLAY_PREFERENCES }
  }
}

export const useSettingsStore = defineStore('settings', () => {
  const initialDisplayPreferences = readDisplayPreferences()

  const theme = ref<ThemePreference>(readThemePreference())
  const layoutDensity = ref<LayoutDensity>(initialDisplayPreferences.layoutDensity)
  const fontSize = ref<FontSize>(initialDisplayPreferences.fontSize)
  const showTokenUsage = ref(initialDisplayPreferences.showTokenUsage)
  const providers = ref<ModelService[]>([])

  function hydrate() {
    const displayPreferences = readDisplayPreferences()
    theme.value = readThemePreference()
    layoutDensity.value = displayPreferences.layoutDensity
    fontSize.value = displayPreferences.fontSize
    showTokenUsage.value = displayPreferences.showTokenUsage
  }

  function saveDisplayPreferences() {
    if (typeof window === 'undefined') {
      return
    }

    localStorage.setItem(THEME_STORAGE_KEY, theme.value)
    localStorage.setItem(DISPLAY_PREFERENCES_KEY, JSON.stringify({
      layoutDensity: layoutDensity.value,
      fontSize: fontSize.value,
      showTokenUsage: showTokenUsage.value,
    } satisfies DisplayPreferencesSnapshot))
  }

  async function fetchProviders() {
    try {
      providers.value = await modelServiceApi.listEnabledServices('GENERATION')
    } catch (error) {
      console.error('加载生成模型服务失败:', error)
    }
  }

  return {
    theme,
    layoutDensity,
    fontSize,
    showTokenUsage,
    providers,
    hydrate,
    saveDisplayPreferences,
    fetchProviders,
  }
})
