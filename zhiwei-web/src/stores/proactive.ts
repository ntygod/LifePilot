import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { QueuedAction } from '@/types'
import { proactiveApi } from '@/api/client'
import { logger } from '@/utils/logger'

/**
 * 主动引擎 Pinia Store。
 *
 * 管理待阅队列状态，供主界面和通知面板使用。
 * 浮窗因独立 window 不共享此 store，自行 fetch。
 */
export const useProactiveStore = defineStore('proactive', () => {
  const queue = ref<QueuedAction[]>([])
  const loading = ref(false)

  /** 未展示的待阅条目数量。 */
  const queueCount = computed(() => queue.value.filter(a => !a.shown).length)

  /** 加载待阅队列。 */
  async function fetchQueue(): Promise<void> {
    loading.value = true
    try {
      queue.value = await proactiveApi.getQueue()
    } catch (err) {
      logger.error('加载待阅队列失败:', err)
    } finally {
      loading.value = false
    }
  }

  /** 标记条目已展示。 */
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

  /** 删除条目。 */
  async function deleteItem(id: string): Promise<void> {
    try {
      await proactiveApi.deleteQueueItem(id)
      queue.value = queue.value.filter(a => a.id !== id)
    } catch (err) {
      logger.error('删除队列条目失败:', err)
    }
  }

  return { queue, loading, queueCount, fetchQueue, markShown, deleteItem }
})
