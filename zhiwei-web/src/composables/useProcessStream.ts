import { onMounted, onUnmounted, ref, type Ref } from 'vue'
import { getApiOrigin } from '@/api/config'
import { SSE_EVENT_TYPES } from '@/constants/sseEvents'
import { useProcessTaskStore, type ProcessSnapshotEntry, type ProcessTaskState } from '@/stores/processTask'
import { logger } from '@/utils/logger'

/**
 * 后台进程 SSE 实时订阅 composable（单例模式）。
 *
 * 订阅 `/api/processes/stream`，将事件映射到 {@link useProcessTaskStore}。
 * 采用和 useNotificationStream 一致的单例 + 引用计数 + 指数退避重连模式。
 */

// ── 模块级单例状态 ──
let eventSource: EventSource | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let reconnectAttempts = 0
let refCount = 0
const MAX_RECONNECT_ATTEMPTS = 10
const BASE_RECONNECT_DELAY = 3000
const connected: Ref<boolean> = ref(false)

interface ProcessEventPayload {
  sessionId: string
  channel: string
  content: string
  state: string
  command?: string
  exitCode?: number
}

interface ProcessSnapshotPayload {
  processes: ProcessSnapshotEntry[]
}

function connect() {
  if (eventSource) return

  const store = useProcessTaskStore()
  eventSource = new EventSource(`${getApiOrigin()}/api/processes/stream`)

  eventSource.addEventListener(SSE_EVENT_TYPES.PROCESS_SNAPSHOT, (event: MessageEvent) => {
    try {
      const data = JSON.parse(event.data) as ProcessSnapshotPayload
      store.hydrateFromSnapshot(data.processes ?? [])
    } catch (err) {
      logger.error('后台进程快照解析失败', err)
    }
  })

  eventSource.addEventListener(SSE_EVENT_TYPES.PROCESS_STARTED, (event: MessageEvent) => {
    try {
      const data = JSON.parse(event.data) as ProcessEventPayload
      if (data.command) {
        store.addTask(data.sessionId, data.command)
      }
    } catch (err) {
      logger.error('后台进程启动事件解析失败', err)
    }
  })

  eventSource.addEventListener(SSE_EVENT_TYPES.PROCESS_OUTPUT, (event: MessageEvent) => {
    try {
      const data = JSON.parse(event.data) as ProcessEventPayload
      const channel = data.channel === 'stderr' ? 'stderr' : 'stdout'
      store.appendOutput(data.sessionId, channel, data.content)
    } catch (err) {
      logger.error('后台进程输出事件解析失败', err)
    }
  })

  eventSource.addEventListener(SSE_EVENT_TYPES.PROCESS_STATE_CHANGE, (event: MessageEvent) => {
    try {
      const data = JSON.parse(event.data) as ProcessEventPayload
      const state = data.state as ProcessTaskState
      // content 形如 "exitCode=0"，尝试提取
      let exitCode: number | undefined
      const match = /exitCode=(-?\d+)/.exec(data.content ?? '')
      if (match) exitCode = Number(match[1])
      store.updateState(data.sessionId, state, exitCode)
    } catch (err) {
      logger.error('后台进程状态事件解析失败', err)
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

/**
 * 在 AppLayout 或其他全局组件中调用，建立/复用后台进程 SSE 连接。
 * 基于引用计数自动管理连接生命周期。
 */
export function useProcessStream() {
  onMounted(() => {
    refCount++
    if (refCount === 1) {
      // 新一轮订阅：复位重连计数器（防止上轮 disconnect 把它拉满后无法重连），
      // 不能放 connect() 入口，否则每次自动重连也会误复位、让指数退避失效
      reconnectAttempts = 0
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

  return { connected }
}
