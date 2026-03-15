import { onMounted, onUnmounted, ref } from 'vue'
import { useNotificationStore } from '@/stores/notification'
import { SSE_EVENT_TYPES } from '@/constants/sseEvents'
import type { NotificationItem } from '@/types'

/**
 * 通知 SSE 实时订阅 composable。
 *
 * 建立 EventSource 连接到 /api/notifications/stream，
 * 监听 notification 事件，自动更新 notificationStore。
 * 支持初始未读数快照事件和实时通知推送。
 * 组件卸载时自动关闭连接。
 */
export function useNotificationStream() {
  const connected = ref(false)
  let eventSource: EventSource | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let reconnectAttempts = 0
  const MAX_RECONNECT_ATTEMPTS = 10
  const BASE_RECONNECT_DELAY = 3000

  function connect() {
    if (eventSource) return

    eventSource = new EventSource('/api/notifications/stream?userId=default')
    const store = useNotificationStore()

    eventSource.addEventListener(SSE_EVENT_TYPES.NOTIFICATION, (e: MessageEvent) => {
      try {
        const data = JSON.parse(e.data)

        if (data.type === 'unread-count-snapshot') {
          store.setUnreadCount(data.unreadCount)
        } else {
          store.addNotification(data as NotificationItem)
        }
      } catch (err) {
        console.error('通知事件解析失败:', err)
      }
    })

    eventSource.onopen = () => {
      connected.value = true
      reconnectAttempts = 0
    }

    eventSource.onerror = () => {
      connected.value = false
      // 清理旧连接，允许重连
      eventSource?.close()
      eventSource = null

      if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
        const delay = BASE_RECONNECT_DELAY * Math.pow(2, Math.min(reconnectAttempts, 4))
        reconnectAttempts++
        reconnectTimer = setTimeout(() => connect(), delay)
      }
    }
  }

  function disconnect() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (eventSource) {
      eventSource.close()
      eventSource = null
      connected.value = false
    }
    reconnectAttempts = MAX_RECONNECT_ATTEMPTS // 阻止自动重连
  }

  onMounted(() => connect())
  onUnmounted(() => disconnect())

  return { connected, connect, disconnect }
}
