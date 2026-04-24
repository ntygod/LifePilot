/**
 * 定时任务（Scheduled Task）状态管理 —— Plan 2+3 Task A4。
 *
 * <p>沿用项目既有的 setup-store 风格（见 {@code project.ts}）：
 * <ul>
 *   <li>通过 {@code ref} 暴露 {@code tasks / loading / error} 状态；</li>
 *   <li>异步方法捕获后端错误写入 {@code error}，失败时 {@code throw} 以便视图层处理；</li>
 *   <li>更新 / 删除仅在列表中就地替换 / 移除，避免全量刷新；</li>
 *   <li>pause / resume 为 status 字段的语义化快捷方法，底层复用 {@code updateTask}。</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-23
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type {
  ScheduledTaskDto,
  ScheduledTaskLogDto,
  UpdateScheduledTaskRequest,
} from '@/api/scheduledTask'
import * as scheduledTaskApi from '@/api/scheduledTask'

export const useScheduledTaskStore = defineStore('scheduledTask', () => {
  const tasks = ref<ScheduledTaskDto[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  /** 今日执行日志（跨所有任务）—— 供总览页 KPI / 叙事 / ribbon 一次性聚合 */
  const todayLogs = ref<ScheduledTaskLogDto[]>([])
  const todayLogsLoading = ref(false)
  const todayLogsError = ref<string | null>(null)

  /**
   * 拉取定时任务列表。
   *
   * @param projectId 可选项目 ID；传入时仅拉取该项目的任务
   */
  async function fetchAll(projectId?: string | null): Promise<ScheduledTaskDto[]> {
    loading.value = true
    error.value = null
    try {
      const items = await scheduledTaskApi.listScheduledTasks(projectId ?? null)
      tasks.value = items
      return items
    } catch (e: any) {
      error.value = e?.message ?? '加载定时任务失败'
      throw e
    } finally {
      loading.value = false
    }
  }

  /**
   * 拉取指定日期的全部执行日志（跨任务聚合）。
   *
   * <p>失败时保持 {@code todayLogs} 为上次成功值（避免闪烁为空态），
   * 错误信息写入 {@code todayLogsError} 由视图层按需提示；不 rethrow，
   * 视图挂载时与 fetchAll 并行调用，互不阻塞。</p>
   *
   * @param date ISO 8601 日期字符串（YYYY-MM-DD）；默认取今日本地日期
   */
  async function fetchTodayLogs(date?: string): Promise<ScheduledTaskLogDto[]> {
    const target = date ?? todayDateString()
    todayLogsLoading.value = true
    todayLogsError.value = null
    try {
      const items = await scheduledTaskApi.listTodayScheduledTaskLogs(target)
      todayLogs.value = items
      return items
    } catch (e: any) {
      todayLogsError.value = e?.message ?? '加载今日执行日志失败'
      return todayLogs.value
    } finally {
      todayLogsLoading.value = false
    }
  }

  /** 本地日期 → ISO 8601 日期字符串（YYYY-MM-DD） */
  function todayDateString(): string {
    const now = new Date()
    const y = now.getFullYear()
    const m = String(now.getMonth() + 1).padStart(2, '0')
    const d = String(now.getDate()).padStart(2, '0')
    return `${y}-${m}-${d}`
  }

  /** 更新定时任务 —— 成功后就地替换列表对应项 */
  async function updateTask(
    id: string,
    req: UpdateScheduledTaskRequest,
  ): Promise<ScheduledTaskDto> {
    error.value = null
    try {
      const updated = await scheduledTaskApi.updateScheduledTask(id, req)
      const index = tasks.value.findIndex(t => t.id === id)
      if (index !== -1) {
        const next = [...tasks.value]
        next[index] = updated
        tasks.value = next
      }
      return updated
    } catch (e: any) {
      error.value = e?.message ?? '更新定时任务失败'
      throw e
    }
  }

  /** 删除定时任务 —— 成功后从列表移除 */
  async function deleteTask(id: string): Promise<void> {
    error.value = null
    try {
      await scheduledTaskApi.deleteScheduledTask(id)
      tasks.value = tasks.value.filter(t => t.id !== id)
    } catch (e: any) {
      error.value = e?.message ?? '删除定时任务失败'
      throw e
    }
  }

  /** 暂停：把 status 改为 paused */
  async function pauseTask(id: string): Promise<ScheduledTaskDto> {
    return updateTask(id, { status: 'paused' })
  }

  /** 恢复：把 status 改回 active */
  async function resumeTask(id: string): Promise<ScheduledTaskDto> {
    return updateTask(id, { status: 'active' })
  }

  return {
    tasks,
    loading,
    error,
    todayLogs,
    todayLogsLoading,
    todayLogsError,
    fetchAll,
    fetchTodayLogs,
    updateTask,
    deleteTask,
    pauseTask,
    resumeTask,
  }
})
