import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { QueuedAction, TrustStatus, ProactiveConfig, ProactiveConfigUpdate } from '@/types'
import { proactiveApi } from '@/api/client'
import { logger } from '@/utils/logger'

/**
 * 主动引擎 Pinia Store。
 *
 * 管理待阅队列、信任状态和引擎配置。
 * 浮窗因独立 window 不共享此 store，自行 fetch。
 */
export const useProactiveStore = defineStore('proactive', () => {
  // ── 队列 ──
  const queue = ref<QueuedAction[]>([])
  const queueLoading = ref(false)
  const queueCount = computed(() => queue.value.filter(a => !a.shown).length)

  async function fetchQueue(): Promise<void> {
    queueLoading.value = true
    try {
      queue.value = await proactiveApi.getQueue()
    } catch (err) {
      logger.error('加载待阅队列失败:', err)
    } finally {
      queueLoading.value = false
    }
  }

  async function markShown(id: string): Promise<void> {
    try {
      await proactiveApi.markShown(id)
      const idx = queue.value.findIndex(a => a.id === id)
      if (idx !== -1) {
        queue.value[idx] = { ...queue.value[idx], shown: true, shownAt: new Date().toISOString() }
      }
    } catch (err) {
      logger.error('标记队列条目已展示失败:', err)
    }
  }

  async function deleteItem(id: string): Promise<void> {
    try {
      await proactiveApi.deleteQueueItem(id)
      queue.value = queue.value.filter(a => a.id !== id)
    } catch (err) {
      logger.error('删除队列条目失败:', err)
    }
  }

  // ── 信任状态 ──
  const trustStatuses = ref<TrustStatus[]>([])
  const trustLoading = ref(false)
  const pendingUpgrades = computed(() => trustStatuses.value.filter(t => t.upgradeSuggested))

  async function fetchTrustStatus(): Promise<void> {
    trustLoading.value = true
    try {
      trustStatuses.value = await proactiveApi.getTrustStatus()
    } catch (err) {
      logger.error('加载信任状态失败:', err)
    } finally {
      trustLoading.value = false
    }
  }

  async function confirmUpgrade(behavior: string): Promise<boolean> {
    try {
      const updated = await proactiveApi.confirmUpgrade(behavior)
      const idx = trustStatuses.value.findIndex(t => t.behaviorName === behavior)
      if (idx !== -1) {
        trustStatuses.value[idx] = updated
      }
      return true
    } catch (err) {
      logger.error('确认信任升级失败:', err)
      return false
    }
  }

  // ── 配置 ──
  const config = ref<ProactiveConfig | null>(null)
  const configLoading = ref(false)

  async function fetchConfig(): Promise<void> {
    configLoading.value = true
    try {
      config.value = await proactiveApi.getConfig()
    } catch (err) {
      logger.error('加载主动引擎配置失败:', err)
    } finally {
      configLoading.value = false
    }
  }

  async function updateConfig(data: ProactiveConfigUpdate): Promise<boolean> {
    try {
      config.value = await proactiveApi.updateConfig(data)
      return true
    } catch (err) {
      logger.error('更新主动引擎配置失败:', err)
      return false
    }
  }

  return {
    queue, queueLoading, queueCount, fetchQueue, markShown, deleteItem,
    trustStatuses, trustLoading, pendingUpgrades, fetchTrustStatus, confirmUpgrade,
    config, configLoading, fetchConfig, updateConfig,
  }
})
