import { ref } from 'vue'
import { useSettingsStore } from '@/stores/settings'

/**
 * 设置 composable，封装设置读写逻辑。
 * 页面加载时调用 load()，修改后调用 save()。
 */
export function useSettings() {
  const settingsStore = useSettingsStore()
  const loading = ref(false)
  const saving = ref(false)
  const error = ref<string | null>(null)

  /** 加载设置 */
  async function load() {
    loading.value = true
    error.value = null
    try {
      await settingsStore.load()
    } catch (e: unknown) {
      error.value = e instanceof Error ? e.message : '加载设置失败'
    } finally {
      loading.value = false
    }
  }

  /** 保存设置 */
  async function save() {
    saving.value = true
    error.value = null
    try {
      await settingsStore.save()
    } catch (e: unknown) {
      error.value = e instanceof Error ? e.message : '保存设置失败'
    } finally {
      saving.value = false
    }
  }

  return { loading, saving, error, load, save }
}
