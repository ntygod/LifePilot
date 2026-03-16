import type {
  BenchmarkScenario,
  EvalResultItem,
  EvalReportSummary,
  EvalRunRequest,
} from '@/types'

const BASE = '/api'

/** 统一 HTTP 请求封装（复用 client.ts 同款逻辑） */
async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${url}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  })
  if (!res.ok) {
    let error: { code: number; message: string; timestamp: string }
    try {
      error = await res.json()
    } catch {
      error = { code: res.status, message: res.statusText, timestamp: new Date().toISOString() }
    }
    throw error
  }
  if (res.status === 204) return undefined as T
  const text = await res.text()
  if (!text || text.trim() === '') return undefined as T
  return JSON.parse(text) as T
}

/** 评估模块 API */
export const evalApi = {
  /** 获取所有场景（可选 tag 过滤） */
  listScenarios(tag?: string): Promise<BenchmarkScenario[]> {
    const query = tag ? `?tag=${encodeURIComponent(tag)}` : ''
    return request(`/eval/scenarios${query}`)
  },

  /** 获取单个场景 */
  getScenario(id: string): Promise<BenchmarkScenario> {
    return request(`/eval/scenarios/${id}`)
  },

  /** 触发评估运行 */
  triggerRun(req: EvalRunRequest): Promise<EvalReportSummary> {
    return request('/eval/runs', {
      method: 'POST',
      body: JSON.stringify(req),
    })
  },

  /** 获取运行结果列表 */
  getRunResults(evalRunId: string): Promise<EvalResultItem[]> {
    return request(`/eval/runs/${evalRunId}/results`)
  },

  /** 获取运行报告 */
  getRunReport(evalRunId: string): Promise<EvalReportSummary> {
    return request(`/eval/runs/${evalRunId}/report`)
  },

  /** 按场景查询历史结果 */
  getResultsByScenario(scenarioId: string, limit = 10): Promise<EvalResultItem[]> {
    return request(`/eval/results?scenarioId=${encodeURIComponent(scenarioId)}&limit=${limit}`)
  },
}
