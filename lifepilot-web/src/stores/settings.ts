import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { UserSettings } from '@/types'
import { settingsApi } from '@/api/client'

export const useSettingsStore = defineStore('settings', () => {
  const theme = ref<'light' | 'dark' | 'system'>('system')
  const language = ref('zh-CN')
  const llmProvider = ref('')

  /** 从后端加载设置 */
  async function load() {
    const s = await settingsApi.getSettings()
    theme.value = s.theme
    language.value = s.language
    llmProvider.value = s.llmProvider
  }

  /** 保存设置到后端 */
  async function save() {
    const settings: UserSettings = {
      theme: theme.value,
      language: language.value,
      llmProvider: llmProvider.value
    }
    const saved = await settingsApi.updateSettings(settings)
    theme.value = saved.theme
    language.value = saved.language
    llmProvider.value = saved.llmProvider
  }

  return { theme, language, llmProvider, load, save }
})
