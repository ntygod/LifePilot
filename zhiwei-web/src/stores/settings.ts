import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { UserSettings } from '@/types'
import { settingsApi, llmProviderApi, type LlmProvider } from '@/api/client'

export const useSettingsStore = defineStore('settings', () => {
  const theme = ref<'light' | 'dark' | 'system'>('system')
  const language = ref('zh-CN')
  const llmProvider = ref('')
  const layoutDensity = ref<'compact' | 'standard'>('standard')
  const fontSize = ref<'small' | 'medium' | 'large'>('medium')
  const timeFormat = ref<'12h' | '24h'>('24h')
  const showTokenUsage = ref(true)
  const autoExpandCodeBlocks = ref(false)
  const collapseLongReplies = ref(true)
  const collapseThreshold = ref(1000)
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
    layoutDensity.value = s.layoutDensity ?? 'standard'
    fontSize.value = s.fontSize ?? 'medium'
    timeFormat.value = s.timeFormat ?? '24h'
    showTokenUsage.value = s.showTokenUsage ?? true
    autoExpandCodeBlocks.value = s.autoExpandCodeBlocks ?? false
    collapseLongReplies.value = s.collapseLongReplies ?? true
    collapseThreshold.value = s.collapseThreshold ?? 1000
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
      layoutDensity: layoutDensity.value,
      fontSize: fontSize.value,
      timeFormat: timeFormat.value,
      showTokenUsage: showTokenUsage.value,
      autoExpandCodeBlocks: autoExpandCodeBlocks.value,
      collapseLongReplies: collapseLongReplies.value,
      collapseThreshold: collapseThreshold.value,
      enableStreaming: enableStreaming.value,
      enableFunctionCall: enableFunctionCall.value,
      enableKnowledgeBase: enableKnowledgeBase.value,
      enableToolCall: enableToolCall.value
    }
    const saved = await settingsApi.updateSettings(settings)
    theme.value = saved.theme
    language.value = saved.language
    llmProvider.value = saved.llmProvider
    layoutDensity.value = saved.layoutDensity ?? 'standard'
    fontSize.value = saved.fontSize ?? 'medium'
    timeFormat.value = saved.timeFormat ?? '24h'
    showTokenUsage.value = saved.showTokenUsage ?? true
    autoExpandCodeBlocks.value = saved.autoExpandCodeBlocks ?? false
    collapseLongReplies.value = saved.collapseLongReplies ?? true
    collapseThreshold.value = saved.collapseThreshold ?? 1000
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
    layoutDensity, fontSize, timeFormat, showTokenUsage, autoExpandCodeBlocks, collapseLongReplies, collapseThreshold,
    enableStreaming, enableFunctionCall, enableKnowledgeBase, enableToolCall,
    providers,
    load, save, fetchProviders
  }
})
