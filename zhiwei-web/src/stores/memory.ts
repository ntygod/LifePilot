import { defineStore } from 'pinia'
import { ref } from 'vue'
import { memoryApi } from '@/api/client'
import type { MemoryStats, MemorySearchResult } from '@/types'

export const useMemoryStore = defineStore('memory', () => {
  const stats = ref<MemoryStats | null>(null)
  const statsLoading = ref(false)
  const statsError = ref<string | null>(null)
  const activeTab = ref<string>('entities')
  const consolidating = ref(false)
  const memoryDisabled = ref(false)

  async function loadStats() {
    statsLoading.value = true
    statsError.value = null
    try {
      stats.value = await memoryApi.getStats()
      memoryDisabled.value = false
    } catch (e: any) {
      if (e?.code === 503) {
        memoryDisabled.value = true
      } else {
        statsError.value = e?.message || '加载统计数据失败'
      }
    } finally {
      statsLoading.value = false
    }
  }

  async function search(query: string, topK?: number): Promise<MemorySearchResult[]> {
    try {
      return await memoryApi.search(query, topK)
    } catch (e: any) {
      console.error('记忆搜索失败:', e)
      return []
    }
  }

  async function triggerConsolidation() {
    if (consolidating.value) return
    consolidating.value = true
    try {
      await memoryApi.triggerConsolidation()
    } catch (e: any) {
      if (e?.code === 409) {
        // 巩固已在进行中，保持 consolidating 状态
        return
      }
      console.error('触发巩固失败:', e)
    } finally {
      consolidating.value = false
    }
  }

  return {
    stats,
    statsLoading,
    statsError,
    activeTab,
    consolidating,
    memoryDisabled,
    loadStats,
    search,
    triggerConsolidation,
  }
})
