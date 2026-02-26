import { ref, computed } from 'vue'
import { useSettingsStore } from '@/stores/settings'
import type { UserSettings } from '@/types'

/**
 * 设置 composable，封装设置读写逻辑。
 * 页面加载时调用 loadSettings()，修改后调用 saveSettings()。
 */
export function useSettings() {
  const store = useSettingsStore()
  const loading = ref(false)
  const error = ref<string | null>(null)

  /** 当前设置的只读快照 */
  const settings = computed<UserSettings>(() => ({
    theme: store.theme,
    language: store.language,
    llmProvider: store.llmProvider,
  }))

  /** 从后端加载设置 */
  async function loadSettings() {
    loading.value = true
    error.value = null
    try {
      await store.load()
    } catch (e: unknown) {
      error.value = e instanceof Error ? e.message : '加载设置失败'
    } finally {
      loading.value = false
    }
  }

  /** 保存设置到后端 */
  async function saveSettings(newSettings: UserSettings) {
    error.value = null
    // 先更新 store，再持久化
    store.theme = newSettings.theme
    store.language = newSettings.language
    store.llmProvider = newSettings.llmProvider
    try {
      await store.save()
    } catch (e: unknown) {
      error.value = e instanceof Error ? e.message : '保存设置失败'
      throw e
    }
  }

  return { settings, loading, error, loadSettings, saveSettings }
}
