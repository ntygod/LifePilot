import type {
  BenchmarkScenario,
  EvalResultItem,
  EvalReportSummary,
  EvalRunRequest,
  ComparisonReport,
  EvalFeedback,
} from '@/types'
import { API_ORIGIN } from '@/api/config'

const BASE = API_ORIGIN + '/api'

/** ApiResponse 包装结构 */
interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

/** 统一 HTTP 请求封装，自动解包 ApiResponse */
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
  const json = JSON.parse(text)
  // 自动解包 ApiResponse 结构
  if (json && typeof json === 'object' && 'code' in json && 'data' in json) {
    return (json as ApiResponse<T>).data
  }
  return json as T
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

  /** 获取指定场景的标注答案 */
  getGoldenAnswers(scenarioId: string): Promise<EvalFeedback[]> {
    return request(`/eval/scenarios/${scenarioId}/golden-answers`)
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

  /** A/B 对比两次运行 */
  compareRuns(currentRunId: string, baselineRunId: string): Promise<ComparisonReport> {
    return request(`/eval/runs/${currentRunId}/compare/${baselineRunId}`)
  },

  /** 标记运行为基线 */
  markAsBaseline(evalRunId: string): Promise<void> {
    return request(`/eval/runs/${evalRunId}/baseline`, { method: 'POST' })
  },

  /** 按场景查询历史结果 */
  getResultsByScenario(scenarioId: string, limit = 10): Promise<EvalResultItem[]> {
    return request(`/eval/results?scenarioId=${encodeURIComponent(scenarioId)}&limit=${limit}`)
  },

  /** 提交评估结果反馈 */
  submitFeedback(evalId: string, scenarioId: string, req: {
    feedbackType: string
    comment?: string
    goldenAnswer?: string
  }): Promise<EvalFeedback> {
    return request(`/eval/results/${evalId}/feedback?scenarioId=${encodeURIComponent(scenarioId)}`, {
      method: 'POST',
      body: JSON.stringify(req),
    })
  },

  /** 查询评估结果的反馈 */
  getFeedback(evalId: string): Promise<EvalFeedback[]> {
    return request(`/eval/results/${evalId}/feedback`)
  },
}
