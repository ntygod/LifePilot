import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { UserSettings } from '@/types'
import { settingsApi, llmProviderApi, type LlmProvider } from '@/api/client'

export const useSettingsStore = defineStore('settings', () => {
  const theme = ref<'light' | 'dark' | 'system'>('system')
  const language = ref('zh-CN')
  const llmProvider = ref('')
  const enableStreaming = ref(true)
  const enableFunctionCall = ref(true)
  const enableKnowledgeBase = ref(true)
  const enableToolCall = ref(true)
  const providers = ref<LlmProvider[]>([])

  /** 从后端加载设置 */
  async function load() {
    const s = await settingsApi.getSettings()
    theme.value = s.theme
    language.value = s.language
    llmProvider.value = s.llmProvider
    enableStreaming.value = s.enableStreaming ?? true
    enableFunctionCall.value = s.enableFunctionCall ?? true
    enableKnowledgeBase.value = s.enableKnowledgeBase ?? true
    enableToolCall.value = s.enableToolCall ?? true
  }

  /** 保存设置到后端 */
  async function save() {
    const settings: UserSettings = {
      theme: theme.value,
      language: language.value,
      llmProvider: llmProvider.value,
      enableStreaming: enableStreaming.value,
      enableFunctionCall: enableFunctionCall.value,
      enableKnowledgeBase: enableKnowledgeBase.value,
      enableToolCall: enableToolCall.value
    }
    const saved = await settingsApi.updateSettings(settings)
    theme.value = saved.theme
    language.value = saved.language
    llmProvider.value = saved.llmProvider
    enableStreaming.value = saved.enableStreaming ?? true
    enableFunctionCall.value = saved.enableFunctionCall ?? true
    enableKnowledgeBase.value = saved.enableKnowledgeBase ?? true
    enableToolCall.value = saved.enableToolCall ?? true
  }

  /** 获取 Provider 列表 */
  async function fetchProviders() {
    try {
      providers.value = await settingsApi.getProviders()
    } catch (e: any) {
      console.error('加载 Provider 列表失败:', e)
    }
  }

  return { 
    theme, language, llmProvider, 
    enableStreaming, enableFunctionCall, enableKnowledgeBase, enableToolCall,
    providers,
    load, save, fetchProviders
  }
})
