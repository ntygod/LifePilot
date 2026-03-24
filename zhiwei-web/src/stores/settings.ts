import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { UserSettings } from '@/types'
import { modelServiceApi, settingsApi, type ModelService } from '@/api/client'

export const useSettingsStore = defineStore('settings', () => {
  const theme = ref<'light' | 'dark' | 'system'>('system')
  const language = ref('zh-CN')
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
  const providers = ref<ModelService[]>([])

  async function load() {
    const settings = await settingsApi.getSettings()
    theme.value = settings.theme
    language.value = settings.language
    layoutDensity.value = settings.layoutDensity ?? 'standard'
    fontSize.value = settings.fontSize ?? 'medium'
    timeFormat.value = settings.timeFormat ?? '24h'
    showTokenUsage.value = settings.showTokenUsage ?? true
    autoExpandCodeBlocks.value = settings.autoExpandCodeBlocks ?? false
    collapseLongReplies.value = settings.collapseLongReplies ?? true
    collapseThreshold.value = settings.collapseThreshold ?? 1000
    enableStreaming.value = settings.enableStreaming ?? true
    enableFunctionCall.value = settings.enableFunctionCall ?? true
    enableKnowledgeBase.value = settings.enableKnowledgeBase ?? true
    enableToolCall.value = settings.enableToolCall ?? true
  }

  async function save() {
    const settings: UserSettings = {
      theme: theme.value,
      language: language.value,
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
      enableToolCall: enableToolCall.value,
    }
    const saved = await settingsApi.updateSettings(settings)
    theme.value = saved.theme
    language.value = saved.language
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

  async function fetchProviders() {
    try {
      providers.value = await modelServiceApi.listEnabledServices('GENERATION')
    } catch (error) {
      console.error('加载生成模型服务失败:', error)
    }
  }

  return {
    theme,
    language,
    layoutDensity,
    fontSize,
    timeFormat,
    showTokenUsage,
    autoExpandCodeBlocks,
    collapseLongReplies,
    collapseThreshold,
    enableStreaming,
    enableFunctionCall,
    enableKnowledgeBase,
    enableToolCall,
    providers,
    load,
    save,
    fetchProviders,
  }
})
