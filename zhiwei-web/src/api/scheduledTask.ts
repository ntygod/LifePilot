/**
 * 定时任务（Scheduled Task）REST 封装 —— Plan 2+3 Task A4。
 *
 * <p>沿用项目现有的 API client 模式（见 {@code src/api/project.ts}）：
 * 通过 {@code getApiOrigin() + '/api'} 作为基地址，{@code request<T>} 自动解包
 * {@code ApiResponse<T>} 响应结构（{code, message, data}），失败时抛出结构化错误对象。
 *
 * @author zsg
 * @since 2026-04-23
 */
import { getApiOrigin } from '@/api/config'

const getBase = () => getApiOrigin() + '/api'

/**
 * 定时任务 DTO —— 对应后端 {@code ScheduledTaskResponse}。
 *
 * <p>{@code createdAt} / {@code updatedAt} 为 ISO 8601 字符串；{@code skillIds}
 * 在持久化层以逗号分隔字符串存储，后端原样透传（可能为 null）。
 */
export interface ScheduledTaskDto {
  id: string
  name: string
  schedule: string
  instruction: string
  /** 状态：active / paused 等，后端以字符串返回 */
  status: string
  /** 逗号分隔的技能 ID 列表；无绑定技能时为 null */
  skillIds: string | null
  /** 绑定项目 ID；全局任务为 null */
  projectId: string | null
  createdAt: string
  updatedAt: string
  /**
   * 下次预计执行时间（ISO 8601）；仅 active 任务由后端填充，
   * paused / completed / 非法 cron 均为 null。
   */
  nextExecutionAt: string | null
}

/**
 * 定时任务执行日志 DTO —— 对应后端 {@code ScheduledTaskLogResponse}。
 *
 * <p>{@code executedAt} 为 ISO 8601 字符串；{@code status} 可能为
 * 'success' / 'failed' / 'timeout'；{@code summary} 为 Agent 回复摘要前 500 字符。</p>
 *
 * <p>{@code taskId} 在按日期聚合（listTodayLogs）时必填以便前端分桶到各任务，
 * 单任务日志端点也会返回（前端可忽略）。</p>
 */
export interface ScheduledTaskLogDto {
  id: string
  taskId: string
  executedAt: string
  status: string
  durationMs: number
  tokensUsed: number
  summary: string | null
  /** 触发来源：'cron' 定时器 / 'manual' 用户点「立即运行」API 触发 */
  triggerSource: 'cron' | 'manual'
}

/**
 * 更新定时任务请求 —— 字段全部可选，后端保留未提供字段的原值。
 *
 * <p>{@code status} 常用值：'active' / 'paused'。
 */
export interface UpdateScheduledTaskRequest {
  name?: string
  schedule?: string
  instruction?: string
  status?: string
}

/**
 * 统一 fetch 封装：强制要求后端走 ApiResponse<T> 包装（{code, message, data}），
 * 自动解包 .data；204 / 空 body / 非 JSON 三种边界单独处理。
 */
async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${getBase()}${url}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  })
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    let parsed: { code?: number; message?: string } | null = null
    if (text) {
      try {
        parsed = JSON.parse(text)
      } catch {
        parsed = null
      }
    }
    throw {
      code: parsed?.code ?? res.status,
      message: parsed?.message ?? text?.trim() ?? res.statusText ?? '请求失败',
      timestamp: new Date().toISOString(),
    }
  }
  if (res.status === 204) return undefined as T
  const contentType = res.headers.get('content-type') ?? ''
  const body = await res.text()
  if (!body || !body.trim()) return undefined as T
  if (!contentType.includes('application/json')) {
    try {
      return JSON.parse(body) as T
    } catch {
      throw {
        code: res.status,
        message: '响应格式错误',
        timestamp: new Date().toISOString(),
      }
    }
  }
  const json = JSON.parse(body) as { code?: number; message?: string; data?: T }
  // 自动解包 ApiResponse 结构：后端 delete 返回 data=null，此处直接透传为 undefined
  if (json && typeof json === 'object' && 'code' in json && 'data' in json) {
    return (json.data ?? undefined) as T
  }
  return json as T
}

/**
 * 列出定时任务。
 *
 * @param projectId 项目 ID；传入时仅返回该项目的任务，未传或为 null 时返回全部
 */
export async function listScheduledTasks(
  projectId?: string | null,
): Promise<ScheduledTaskDto[]> {
  const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : ''
  return request<ScheduledTaskDto[]>(`/scheduled-tasks${qs}`)
}

/** 更新定时任务（name / schedule / instruction / status 全部可选） */
export async function updateScheduledTask(
  id: string,
  req: UpdateScheduledTaskRequest,
): Promise<ScheduledTaskDto> {
  return request<ScheduledTaskDto>(`/scheduled-tasks/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(req),
  })
}

/** 删除定时任务 */
export async function deleteScheduledTask(id: string): Promise<void> {
  await request<void>(`/scheduled-tasks/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  })
}

/**
 * 立即执行一次指定任务（跳过 cron 等待）。
 *
 * <p>后端异步派发，API 立即返回；日志会以 {@code triggerSource='manual'} 入库，
 * 前端需要在调用后刷新日志列表才能看到新一条记录。</p>
 *
 * @param id 任务 ID
 */
export async function runScheduledTaskNow(id: string): Promise<void> {
  await request<void>(`/scheduled-tasks/${encodeURIComponent(id)}/run`, {
    method: 'POST',
  })
}

/**
 * 查询指定任务的最近执行日志（按 executed_at 倒序）。
 *
 * @param id    任务 ID
 * @param limit 返回条数上限；后端默认 5，上限 50
 */
export async function listScheduledTaskLogs(
  id: string,
  limit = 5,
): Promise<ScheduledTaskLogDto[]> {
  const qs = `?limit=${encodeURIComponent(String(limit))}`
  return request<ScheduledTaskLogDto[]>(
    `/scheduled-tasks/${encodeURIComponent(id)}/logs${qs}`,
  )
}

/**
 * 查询指定日期的全部执行日志（跨所有任务）。
 *
 * <p>用于定时任务总览页聚合当日数据（KPI、叙事、24h ribbon），一次性按 date
 * 过滤返回当日所有日志，前端按 taskId 自行分桶；避免对每个任务单独调用
 * {@link listScheduledTaskLogs} 造成 N+1 查询。</p>
 *
 * @param date ISO 8601 日期字符串（YYYY-MM-DD）
 */
export async function listTodayScheduledTaskLogs(
  date: string,
): Promise<ScheduledTaskLogDto[]> {
  const qs = `?date=${encodeURIComponent(date)}`
  return request<ScheduledTaskLogDto[]>(`/scheduled-tasks/logs${qs}`)
}
