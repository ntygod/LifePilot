import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getApiOrigin } from '@/api/config'
import { parseCliStream, type CliParsedSummary } from '@/utils/cliStreamParser'
import { logger } from '@/utils/logger'
import { useUiStore } from '@/stores/ui'

/**
 * 后台进程任务的运行态。
 *
 * RUNNING：活跃进程；COMPLETED/FAILED/KILLED：终态，进入 3 分钟自动淡出倒计时。
 */
export type ProcessTaskState = 'RUNNING' | 'COMPLETED' | 'FAILED' | 'KILLED'

/** 单个后台任务的完整前端视图。 */
export interface ProcessTask {
  sessionId: string
  command: string
  state: ProcessTaskState
  exitCode?: number | null
  startTime: number
  workDir?: string
  stdoutBuffer: string
  stderrBuffer: string
  parsedSummary?: CliParsedSummary
  terminalAt?: number
}

/** 终态任务保留时间（3 分钟），到期自动从 store 移除。 */
const TERMINAL_KEEP_MS = 3 * 60 * 1000
/** 输出缓冲区上限（防止浏览器内存膨胀），超出截断头部。 */
const BUFFER_LIMIT = 200_000

/**
 * 后台进程任务 Pinia Store。
 *
 * 维护当前所有活跃和最近终止的后台进程，由 {@link useProcessStream} 驱动更新。
 * 前端气泡组件只从这里取数据，不直接访问 SSE。
 */
export const useProcessTaskStore = defineStore('processTask', () => {
  /** sessionId → ProcessTask 映射。 */
  const tasks = ref<Map<string, ProcessTask>>(new Map())

  /** 按启动时间倒序的任务数组，供胶囊排渲染。 */
  const tasksOrdered = computed(() => {
    return Array.from(tasks.value.values()).sort((a, b) => b.startTime - a.startTime)
  })

  /** 活跃任务数量（RUNNING 状态）。 */
  const runningCount = computed(() => {
    return Array.from(tasks.value.values()).filter(t => t.state === 'RUNNING').length
  })

  /** 终态自动清理计时器句柄。 */
  const cleanupTimers = new Map<string, ReturnType<typeof setTimeout>>()

  /** 用初始快照数据加载 store（SSE 连接建立时调用）。 */
  function hydrateFromSnapshot(processes: ProcessSnapshotEntry[]) {
    tasks.value.clear()
    processes.forEach(p => {
      const state = p.state as ProcessTaskState
      const task: ProcessTask = {
        sessionId: p.sessionId,
        command: p.command,
        state,
        exitCode: p.exitCode ?? null,
        startTime: Date.parse(p.startTime) || Date.now(),
        workDir: p.workDir,
        stdoutBuffer: '',
        stderrBuffer: '',
      }
      if (state !== 'RUNNING') {
        task.terminalAt = Date.now()
        scheduleCleanup(p.sessionId)
      }
      tasks.value.set(p.sessionId, task)
    })
  }

  /** 新任务启动时注册。 */
  function addTask(sessionId: string, command: string) {
    if (tasks.value.has(sessionId)) return
    tasks.value.set(sessionId, {
      sessionId,
      command,
      state: 'RUNNING',
      exitCode: null,
      startTime: Date.now(),
      stdoutBuffer: '',
      stderrBuffer: '',
    })
  }

  /** 增量输出到达时更新缓冲区并重新解析摘要。 */
  function appendOutput(sessionId: string, channel: 'stdout' | 'stderr', content: string) {
    const task = tasks.value.get(sessionId)
    if (!task) return

    if (channel === 'stdout') {
      task.stdoutBuffer = truncateHead(task.stdoutBuffer + content, BUFFER_LIMIT)
    } else {
      task.stderrBuffer = truncateHead(task.stderrBuffer + content, BUFFER_LIMIT)
    }

    try {
      task.parsedSummary = parseCliStream(task.command, task.stdoutBuffer, task.stderrBuffer)
    } catch (err) {
      logger.debug('CLI 输出解析失败', err)
    }
  }

  /** 状态变化时更新。 */
  function updateState(sessionId: string, state: ProcessTaskState, exitCode?: number | null) {
    const task = tasks.value.get(sessionId)
    if (!task) return
    task.state = state
    if (exitCode != null) task.exitCode = exitCode
    if (state !== 'RUNNING' && !task.terminalAt) {
      task.terminalAt = Date.now()
      scheduleCleanup(sessionId)
    }
  }

  /** 手动调用 DELETE /api/processes/{sessionId} 请求终止。 */
  async function stopTask(sessionId: string): Promise<void> {
    const uiStore = useUiStore()
    try {
      const res = await fetch(`${getApiOrigin()}/api/processes/${encodeURIComponent(sessionId)}`, {
        method: 'DELETE',
      })
      if (!res.ok) {
        let detail = `${res.status}`
        try {
          const body = await res.json()
          if (body?.message) detail = `${res.status} · ${body.message}`
        } catch { /* 非 JSON 响应忽略 */ }
        logger.warn('停止后台任务失败', { sessionId, status: res.status })
        uiStore.showToast('error', `停止任务失败：${detail}`)
        return
      }
      uiStore.showToast('success', '任务已停止')
    } catch (err) {
      logger.error('停止后台任务异常', err)
      uiStore.showToast('error', '停止任务失败：网络错误')
    }
  }

  /** 从 store 中移除任务（终态淡出到期时调用）。 */
  function removeTask(sessionId: string) {
    tasks.value.delete(sessionId)
    const timer = cleanupTimers.get(sessionId)
    if (timer) {
      clearTimeout(timer)
      cleanupTimers.delete(sessionId)
    }
  }

  /** 为终态任务安排 3 分钟后的自动移除。 */
  function scheduleCleanup(sessionId: string) {
    const existing = cleanupTimers.get(sessionId)
    if (existing) clearTimeout(existing)
    const timer = setTimeout(() => removeTask(sessionId), TERMINAL_KEEP_MS)
    cleanupTimers.set(sessionId, timer)
  }

  /** SSE 断线重连时重置（避免脏状态）。 */
  function reset() {
    cleanupTimers.forEach(t => clearTimeout(t))
    cleanupTimers.clear()
    tasks.value.clear()
  }

  return {
    tasks,
    tasksOrdered,
    runningCount,
    hydrateFromSnapshot,
    addTask,
    appendOutput,
    updateState,
    stopTask,
    removeTask,
    reset,
  }
})

/** SSE process-snapshot 事件 payload 中的单条任务。 */
export interface ProcessSnapshotEntry {
  sessionId: string
  command: string
  state: string
  exitCode?: number
  startTime: string
  workDir?: string
}

/** 保留尾部 limit 个字符，头部超出截断。 */
function truncateHead(text: string, limit: number): string {
  if (text.length <= limit) return text
  return text.slice(text.length - limit)
}
