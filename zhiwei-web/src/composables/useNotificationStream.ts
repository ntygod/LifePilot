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
        const item = data as NotificationItem
        // 主动提醒类通知由浮窗自己的 SSE 连接处理，主窗口不再转发，避免重复投递
        if (item.typeId !== 'proactive_reminder' && item.typeId !== 'proactive_action' && item.typeId !== 'clipboard_intent') {
          sendDesktopNotification(item)
        }
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

/** 主动提醒/剪贴板意图通知 → 投递到浮窗 + 系统通知双保险 */
async function sendToFloatWindow(item: NotificationItem) {
  // 非 Tauri 环境仅推送系统通知
  if (typeof window === 'undefined' || !window.__TAURI_INTERNALS__) {
    sendDesktopNotification(item)
    return
  }

  // 始终推送系统桌面通知（确保用户看到）
  sendDesktopNotification(item)

  // 尝试投递到浮窗
  try {
    const { invoke } = await import('@tauri-apps/api/core')
    const { summary } = parseNotificationContent(item.contentJson)
    const metadata = item.metadataJson ? JSON.parse(item.metadataJson) : {}
    const pushLevel = metadata.action ?? 'NORMAL_PUSH'
    const title = resolveNotificationTitle(item.typeId, metadata)
    await invoke('show_reminder_bubble', {
      notificationId: item.id,
      title,
      content: summary,
      pushLevel,
    })
  } catch (error) {
    logger.warn('浮窗投递失败，已降级为系统通知:', error)
  }
}

/** 根据通知类型和 metadata 生成语义化标题 */
function resolveNotificationTitle(typeId?: string, metadata?: Record<string, string>): string {
  if (typeId === 'clipboard_intent') {
    const intentLabels: Record<string, string> = {
      TRACKING_NUMBER: '快递查询',
      FLIGHT_NUMBER: '航班查询',
      TRAIN_NUMBER: '车次查询',
    }
    return intentLabels[metadata?.intentType ?? ''] ?? '剪贴板识别'
  }
  return metadata?.topicKey ?? '主动提醒'
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
