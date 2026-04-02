import { onMounted, onUnmounted, ref } from 'vue'
import { getApiOrigin } from '@/api/config'
import { useNotificationStore } from '@/stores/notification'
import { useChatStore } from '@/stores/chat'
import { SSE_EVENT_TYPES } from '@/constants/sseEvents'
import type { NotificationItem, SseTranscriptionEvent } from '@/types'

/**
 * 通知 SSE 实时订阅 composable。
 *
 * 建立 EventSource 连接到 `/api/notifications/stream`，
 * 监听通知与语音转录事件，并同步更新本地 store。
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

    eventSource = new EventSource(`${getApiOrigin()}/api/notifications/stream?userId=default`)
    const notificationStore = useNotificationStore()
    const chatStore = useChatStore()

    eventSource.addEventListener(SSE_EVENT_TYPES.NOTIFICATION, (event: MessageEvent) => {
      try {
        const data = JSON.parse(event.data)

        if (data.type === 'unread-count-snapshot') {
          notificationStore.setUnreadCount(data.unreadCount)
        } else {
          notificationStore.addNotification(data as NotificationItem)
        }
      } catch (error) {
        console.error('通知事件解析失败:', error)
      }
    })

    eventSource.addEventListener(SSE_EVENT_TYPES.TRANSCRIPTION, (event: MessageEvent) => {
      try {
        const data = JSON.parse(event.data) as SseTranscriptionEvent
        if (!data.sessionId || data.sessionId !== chatStore.activeSessionId) {
          return
        }

        const targetId = data.entryId
          ?? [...chatStore.messages]
            .reverse()
            .find(message => message.role === 'user' && message.status === 'pending')
            ?.id
        if (!targetId || !data.text) {
          return
        }

        chatStore.updateMessage(targetId, { content: data.text })
      } catch (error) {
        console.error('转录事件解析失败:', error)
      }
    })

    eventSource.onopen = () => {
      connected.value = true
      reconnectAttempts = 0
    }

    eventSource.onerror = () => {
      connected.value = false
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
    reconnectAttempts = MAX_RECONNECT_ATTEMPTS
  }

  onMounted(() => connect())
  onUnmounted(() => disconnect())

  return { connected, connect, disconnect }
}
