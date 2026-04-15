import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { NotificationItem } from '@/types'
import { notificationApi } from '@/api/client'
import { logger } from '@/utils/logger'

/**
 * 通知中心 Pinia Store。
 *
 * 管理通知列表、未读数和加载状态，并承接通知 SSE 的实时更新。
 */
export const useNotificationStore = defineStore('notification', () => {
  /** 当前已加载的通知列表。 */
  const notifications = ref<NotificationItem[]>([])

  /** API 请求加载状态。 */
  const loading = ref(false)

  /**
   * SSE 快照未读数。
   *
   * 在通知列表尚未加载前，用它驱动通知角标；列表加载后再以列表计算值为准。
   */
  const sseUnreadCount = ref<number | null>(null)

  /** 未读通知数量。 */
  const unreadCount = computed(() => {
    const listCount = notifications.value.filter(n => n.readStatus === 'UNREAD').length
    if (notifications.value.length > 0) {
      return listCount
    }
    return sseUnreadCount.value ?? listCount
  })

  /** 加载通知列表，支持分页。 */
  async function fetchNotifications(page?: number): Promise<void> {
    loading.value = true
    try {
      const result = await notificationApi.listNotifications('default', page)
      if (page && page > 0) {
        notifications.value = [...notifications.value, ...result.items]
      } else {
        notifications.value = result.items
      }
      sseUnreadCount.value = null
    } catch (err) {
      logger.error('加载通知列表失败:', err)
    } finally {
      loading.value = false
    }
  }

  /** 标记单条通知为已读。 */
  async function markAsRead(id: string): Promise<void> {
    try {
      await notificationApi.markAsRead(id)
      const index = notifications.value.findIndex(n => n.id === id)
      if (index !== -1) {
        notifications.value[index] = { ...notifications.value[index], readStatus: 'READ' }
      }
    } catch (err) {
      logger.error('标记通知已读失败:', err)
    }
  }

  /** 标记全部通知为已读。 */
  async function markAllAsRead(): Promise<void> {
    try {
      await notificationApi.markAllAsRead('default')
      notifications.value = notifications.value.map(n => ({ ...n, readStatus: 'READ' as const }))
    } catch (err) {
      logger.error('标记全部已读失败:', err)
    }
  }

  /** 将实时通知插入到列表头部。 */
  function addNotification(notification: NotificationItem): void {
    notifications.value = [notification, ...notifications.value]
  }

  /** 设置 SSE 快照未读数。 */
  function setUnreadCount(count: number): void {
    sseUnreadCount.value = count
  }

  /** 提交主动提醒反馈。成功返回 true，失败抛异常以便 UI 保持按钮可重试。 */
  async function submitFeedback(id: string, feedbackType: string): Promise<void> {
    await notificationApi.submitFeedback(id, feedbackType)
    const index = notifications.value.findIndex(n => n.id === id)
    if (index !== -1) {
      notifications.value[index] = {
        ...notifications.value[index],
        readStatus: 'READ',
        feedbackType,
      }
    }
  }

  return {
    notifications,
    loading,
    unreadCount,
    fetchNotifications,
    markAsRead,
    markAllAsRead,
    addNotification,
    setUnreadCount,
    submitFeedback
  }
})
