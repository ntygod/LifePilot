import { onMounted, onUnmounted, ref, type Ref } from 'vue'
import { getApiOrigin } from '@/api/config'
import { useNotificationStore } from '@/stores/notification'
import { useChatStore } from '@/stores/chat'
import { SSE_EVENT_TYPES } from '@/constants/sseEvents'
import { logger } from '@/utils/logger'
import { parseNotificationContent } from '@/utils/notificationContent'
import type { NotificationItem, SseTranscriptionEvent } from '@/types'

/**
 * 通知 SSE 实时订阅 composable（单例模式）。
 *
 * 多个组件调用 useNotificationStream() 共享同一个 EventSource 连接。
 * 通过引用计数管理生命周期：首个组件挂载时建连，最后一个组件卸载时断连。
 */

// ── 模块级单例状态 ──
let eventSource: EventSource | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let reconnectAttempts = 0
let refCount = 0
const MAX_RECONNECT_ATTEMPTS = 10
const BASE_RECONNECT_DELAY = 3000
const connected: Ref<boolean> = ref(false)

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
        sendDesktopNotification(data as NotificationItem)
      }
    } catch (error) {
      logger.error('通知事件解析失败:', error)
    }
  })

  eventSource.addEventListener(SSE_EVENT_TYPES.TITLE_GENERATED, (event: MessageEvent) => {
    try {
      const data = JSON.parse(event.data) as { sessionId: string; title: string }
      chatStore.updateSessionTitle(data.sessionId, data.title)
    } catch (error) {
      logger.error('标题生成事件解析失败:', error)
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
      logger.error('转录事件解析失败:', error)
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

    // 仍有组件存活时才重连
    if (refCount > 0 && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
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

/** Tauri 桌面端收到通知时推送系统级桌面通知 */
async function sendDesktopNotification(item: NotificationItem) {
  if (typeof window === 'undefined' || !window.__TAURI_INTERNALS__) return
  try {
    const { sendNotification, isPermissionGranted, requestPermission } =
      await import('@tauri-apps/plugin-notification')
    let permitted = await isPermissionGranted()
    if (!permitted) {
      const result = await requestPermission()
      permitted = result === 'granted'
    }
    if (!permitted) return

    const { summary } = parseNotificationContent(item.contentJson)
    const typeLabel = item.typeId ?? '通知'
    sendNotification({ title: `知微 · ${typeLabel}`, body: summary })
  } catch {
    // 非 Tauri 环境或插件不可用，静默忽略
  }
}

export function useNotificationStream() {
  onMounted(() => {
    refCount++
    if (refCount === 1) {
      connect()
    }
  })

  onUnmounted(() => {
    refCount--
    if (refCount <= 0) {
      refCount = 0
      disconnect()
    }
  })

  return { connected, connect, disconnect }
}
