import { computed, ref } from 'vue'
import { useSettingsStore } from '@/stores/settings'
import type { UserSettings } from '@/types'

/**
 * 通用设置读写封装。
 */
export function useSettings() {
  const store = useSettingsStore()
  const loading = ref(false)
  const error = ref<string | null>(null)

  const settings = computed<UserSettings>(() => ({
    theme: store.theme,
    language: store.language,
    layoutDensity: store.layoutDensity,
    fontSize: store.fontSize,
    timeFormat: store.timeFormat,
    showTokenUsage: store.showTokenUsage,
    autoExpandCodeBlocks: store.autoExpandCodeBlocks,
    collapseLongReplies: store.collapseLongReplies,
    collapseThreshold: store.collapseThreshold,
    enableStreaming: store.enableStreaming,
    enableFunctionCall: store.enableFunctionCall,
    enableKnowledgeBase: store.enableKnowledgeBase,
    enableToolCall: store.enableToolCall,
  }))

  async function loadSettings() {
    loading.value = true
    error.value = null
    try {
      await store.load()
    } catch (cause: unknown) {
      error.value = cause instanceof Error ? cause.message : '加载设置失败'
    } finally {
      loading.value = false
    }
  }

  async function saveSettings(newSettings: UserSettings) {
    error.value = null
    store.theme = newSettings.theme
    store.language = newSettings.language
    if (newSettings.layoutDensity) store.layoutDensity = newSettings.layoutDensity
    if (newSettings.fontSize) store.fontSize = newSettings.fontSize
    if (newSettings.timeFormat) store.timeFormat = newSettings.timeFormat
    if (newSettings.showTokenUsage !== undefined) store.showTokenUsage = newSettings.showTokenUsage
    if (newSettings.autoExpandCodeBlocks !== undefined) store.autoExpandCodeBlocks = newSettings.autoExpandCodeBlocks
    if (newSettings.collapseLongReplies !== undefined) store.collapseLongReplies = newSettings.collapseLongReplies
    if (newSettings.collapseThreshold !== undefined) store.collapseThreshold = newSettings.collapseThreshold
    if (newSettings.enableStreaming !== undefined) store.enableStreaming = newSettings.enableStreaming
    if (newSettings.enableFunctionCall !== undefined) store.enableFunctionCall = newSettings.enableFunctionCall
    if (newSettings.enableKnowledgeBase !== undefined) store.enableKnowledgeBase = newSettings.enableKnowledgeBase
    if (newSettings.enableToolCall !== undefined) store.enableToolCall = newSettings.enableToolCall
    try {
      await store.save()
    } catch (cause: unknown) {
      error.value = cause instanceof Error ? cause.message : '保存设置失败'
      throw cause
    }
  }

  return { settings, loading, error, loadSettings, saveSettings }
}
