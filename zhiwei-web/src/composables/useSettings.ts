import { computed, ref } from 'vue'
import { useSettingsStore } from '@/stores/settings'

export interface GeneralDisplaySettings {
  theme: 'light' | 'dark' | 'system'
  layoutDensity: 'compact' | 'standard'
  fontSize: 'small' | 'medium' | 'large'
  showTokenUsage: boolean
}

/**
 * 通用设置读写封装。
 *
 * 当前仅保留能在前端立即生效的本地显示偏好。
 */
export function useSettings() {
  const store = useSettingsStore()
  const loading = ref(false)
  const error = ref<string | null>(null)

  const settings = computed<GeneralDisplaySettings>(() => ({
    theme: store.theme,
    layoutDensity: store.layoutDensity,
    fontSize: store.fontSize,
    showTokenUsage: store.showTokenUsage,
  }))

  async function loadSettings() {
    loading.value = true
    error.value = null
    try {
      store.hydrate()
    } catch (cause: unknown) {
      error.value = cause instanceof Error ? cause.message : '加载设置失败'
    } finally {
      loading.value = false
    }
  }

  async function saveSettings(newSettings: GeneralDisplaySettings) {
    error.value = null
    store.theme = newSettings.theme
    store.layoutDensity = newSettings.layoutDensity
    store.fontSize = newSettings.fontSize
    store.showTokenUsage = newSettings.showTokenUsage

    try {
      store.saveDisplayPreferences()
    } catch (cause: unknown) {
      error.value = cause instanceof Error ? cause.message : '保存设置失败'
      throw cause
    }
  }

  return { settings, loading, error, loadSettings, saveSettings }
}
