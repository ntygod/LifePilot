import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { NotificationItem } from '@/types'
import { notificationApi } from '@/api/client'

/**
 * 通知中心 Pinia Store。
 *
 * 管理通知列表、未读数、加载状态，提供 CRUD 操作和 SSE 实时推送支持。
 */
export const useNotificationStore = defineStore('notification', () => {
  /** 当前已加载的通知列表 */
  const notifications = ref<NotificationItem[]>([])

  /** API 请求加载状态 */
  const loading = ref(false)

  /**
   * SSE 快照未读数（由 useNotificationStream 在连接建立时设置）。
   * 当通知列表尚未加载时，用此值作为 badge 显示的未读数。
   */
  const sseUnreadCount = ref<number | null>(null)

  /** 未读通知数量（优先使用列表计算值，列表为空时降级到 SSE 快照值） */
  const unreadCount = computed(() => {
    const listCount = notifications.value.filter(n => n.readStatus === 'UNREAD').length
    // 如果列表已加载（有数据），使用列表计算值；否则使用 SSE 快照值
    if (notifications.value.length > 0) {
      return listCount
    }
    return sseUnreadCount.value ?? listCount
  })

  /** 加载通知列表（分页） */
  async function fetchNotifications(page?: number): Promise<void> {
    loading.value = true
    try {
      const result = await notificationApi.listNotifications('default', page)
      if (page && page > 0) {
        // 追加分页数据
        notifications.value = [...notifications.value, ...result.items]
      } else {
        // 首页替换
        notifications.value = result.items
      }
      // 列表加载后清除 SSE 快照值，改用列表计算
      sseUnreadCount.value = null
    } catch (err) {
      console.error('加载通知列表失败:', err)
    } finally {
      loading.value = false
    }
  }

  /** 标记单条通知已读 */
  async function markAsRead(id: string): Promise<void> {
    try {
      await notificationApi.markAsRead(id)
      const index = notifications.value.findIndex(n => n.id === id)
      if (index !== -1) {
        notifications.value[index] = { ...notifications.value[index], readStatus: 'READ' }
      }
    } catch (err) {
      console.error('标记通知已读失败:', err)
    }
  }

  /** 标记所有通知已读 */
  async function markAllAsRead(): Promise<void> {
    try {
      await notificationApi.markAllAsRead('default')
      notifications.value = notifications.value.map(n => ({ ...n, readStatus: 'READ' as const }))
    } catch (err) {
      console.error('标记全部已读失败:', err)
    }
  }

  /** 添加实时推送的新通知（插入列表头部） */
  function addNotification(notification: NotificationItem): void {
    notifications.value = [notification, ...notifications.value]
  }

  /** 设置 SSE 快照未读数（由 useNotificationStream 调用） */
  function setUnreadCount(count: number): void {
    sseUnreadCount.value = count
  }

  return {
    notifications,
    loading,
    unreadCount,
    fetchNotifications,
    markAsRead,
    markAllAsRead,
    addNotification,
    setUnreadCount
  }
})
