import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { MemoryStats, MemorySearchResult, ErrorResponse } from '@/types'
import { memoryApi } from '@/api/client'
import { logger } from '@/utils/logger'

/**
 * 记忆管理 Pinia Store。
 *
 * 管理记忆页面的全局状态：统计数据、当前 Tab、搜索、巩固状态。
 * 各子面板的列表数据、分页、筛选状态由面板组件内部 ref 管理。
 */
export const useMemoryStore = defineStore('memory', () => {
  type EntityDetailRequest = {
    id: string
    requestedAt: number
  }

  /** 统计概览数据 */
  const stats = ref<MemoryStats | null>(null)

  /** 统计数据加载中 */
  const statsLoading = ref(false)

  /** 统计数据加载错误信息 */
  const statsError = ref<string | null>(null)

  /** 当前激活的 Tab */
  const activeTab = ref<string>('entities')

  /** 巩固操作执行中 */
  const consolidating = ref(false)

  /** 记忆系统是否未启用（503 时标记） */
  const memoryDisabled = ref(false)

  /** 待打开的实体详情请求 */
  const entityDetailRequest = ref<EntityDetailRequest | null>(null)

  /**
   * 加载统计概览数据。
   *
   * 调用 memoryApi.getStats()，处理 503 状态设置 memoryDisabled。
   */
  async function loadStats() {
    statsLoading.value = true
    statsError.value = null
    try {
      stats.value = await memoryApi.getStats()
      memoryDisabled.value = false
    } catch (err) {
      const error = err as ErrorResponse
      if (error.code === 503) {
        memoryDisabled.value = true
        statsError.value = null
      } else {
        statsError.value = error.message || '加载统计数据失败'
      }
    } finally {
      statsLoading.value = false
    }
  }

  /**
   * 跨层记忆搜索。
   *
   * @param query 搜索关键词
   * @param top_k 返回结果数量，默认 10
   * @returns 搜索结果列表
   */
  async function search(query: string, top_k?: number): Promise<MemorySearchResult[]> {
    return memoryApi.search(query, top_k)
  }

  /**
   * 触发手动巩固。
   *
   * 调用 memoryApi.triggerConsolidation()，处理 409 状态（巩固已在执行中）。
   */
  async function triggerConsolidation() {
    consolidating.value = true
    try {
      await memoryApi.triggerConsolidation()
    } catch (err) {
      const error = err as ErrorResponse
      if (error.code === 409) {
        // 巩固已在执行中，保持 consolidating 状态
        logger.warn('记忆巩固已在执行中')
      } else {
        logger.error('触发巩固失败:', error.message)
      }
    } finally {
      consolidating.value = false
    }
  }

  /**
   * 请求打开实体详情。
   *
   * 用于跨面板联动，例如全局搜索结果跳转到实体详情。
   */
  function requestEntityDetail(id: string) {
    activeTab.value = 'entities'
    entityDetailRequest.value = {
      id,
      requestedAt: Date.now(),
    }
  }

  /** 清除待处理的实体详情请求。 */
  function clearEntityDetailRequest() {
    entityDetailRequest.value = null
  }

  return {
    stats,
    statsLoading,
    statsError,
    activeTab,
    consolidating,
    memoryDisabled,
    entityDetailRequest,
    loadStats,
    search,
    triggerConsolidation,
    requestEntityDetail,
    clearEntityDetailRequest,
  }
})
