import { defineStore } from 'pinia'
import { ref } from 'vue'
import type {
  TraceItem,
  TraceDetail,
  TraceStep,
  PageResult,
  OverviewStats,
  ToolUsageStats,
  EvaluationResult,
} from '@/types'
import { traceApi } from '@/api/client'

export const useTraceStore = defineStore('trace', () => {
  const list = ref<TraceItem[]>([])
  const current = ref<TraceDetail | null>(null)
  const steps = ref<TraceStep[]>([])
  const page = ref(0)
  const total = ref(0)
  const pageSize = ref(20)
  const loading = ref(false)
  const error = ref<string | null>(null)
  /**
   * 轨迹概览统计数据（顶部统计卡片）
   */
  const overviewStats = ref<OverviewStats | null>(null)
  /**
   * 工具使用统计列表
   */
  const toolStats = ref<ToolUsageStats[]>([])
  /**
   * 当前选中轨迹的离线评估结果
   */
  const evaluation = ref<EvaluationResult | null>(null)
  /**
   * 轨迹搜索结果列表
   */
  const searchResults = ref<TraceItem[]>([])

  /**
   * 搜索轨迹列表
   *
   * 使用关键字从后端检索最近的轨迹记录，并更新 searchResults 状态。
   * 若搜索关键字为空，则清空搜索结果，回退到默认分页列表。
   */
  async function search(keyword: string, limit = 20) {
    error.value = null
    // 空关键字时直接清空搜索结果，不触发请求
    if (!keyword.trim()) {
      searchResults.value = []
      return
    }
    loading.value = true
    try {
      searchResults.value = await traceApi.search(keyword, limit)
    } catch (e: any) {
      error.value = e.message ?? '搜索轨迹失败'
    } finally {
      loading.value = false
    }
  }

  /**
   * 导出单条轨迹为 JSON 文件
   *
   * 调用后端导出接口获取 JSON 字符串，并在浏览器中触发下载。
   */
  async function exportTrace(id: string) {
    error.value = null
    try {
      const json = await traceApi.export(id)
      const blob = new Blob([json], { type: 'application/json;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      const timestamp = new Date().toISOString().replace(/[:.]/g, '-')
      a.download = `trace-${id}-${timestamp}.json`
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      URL.revokeObjectURL(url)
    } catch (e: any) {
      error.value = e.message ?? '导出轨迹失败'
    }
  }

  /**
   * 获取轨迹概览统计信息
   *
   * 用于统计卡片区域展示整体运行情况。
   */
  async function fetchOverviewStats(window: '24h' | '7d' | '30d' = '7d') {
    error.value = null
    loading.value = true
    try {
      overviewStats.value = await traceApi.getOverviewStats(window)
    } catch (e: any) {
      error.value = e.message ?? '加载轨迹概览统计失败'
    } finally {
      loading.value = false
    }
  }

  /**
   * 获取工具使用统计信息
   *
   * 用于工具使用情况列表与图表展示。
   */
  async function fetchToolStats() {
    error.value = null
    loading.value = true
    try {
      toolStats.value = await traceApi.getToolStats()
    } catch (e: any) {
      error.value = e.message ?? '加载工具使用统计失败'
    } finally {
      loading.value = false
    }
  }

  /**
   * 获取单条轨迹的离线评估结果
   *
   * 404 时视为暂无评估结果，将 evaluation 置为 null；其他错误写入 error。
   */
  async function fetchEvaluation(id: string) {
    error.value = null
    try {
      evaluation.value = await traceApi.getEvaluation(id)
    } catch (e: any) {
      const status = (e as any)?.status ?? (e as any)?.response?.status
      const code = (e as any)?.code
      if (status === 404 || code === 404) {
        // 无评估结果不视为错误
        evaluation.value = null
        return
      }
      error.value = (e as any)?.message ?? '加载轨迹评估结果失败'
    }
  }

  async function fetchList(p = 0) {
    loading.value = true
    error.value = null
    try {
      const result: PageResult<TraceItem> = await traceApi.list(p, pageSize.value)
      list.value = result.items
      page.value = result.page
      total.value = result.total
    } catch (e: any) {
      error.value = e.message ?? '加载轨迹列表失败'
    } finally {
      loading.value = false
    }
  }

  async function fetchDetail(id: string) {
    error.value = null
    try {
      current.value = await traceApi.get(id)
    } catch (e: any) {
      error.value = e.message ?? '加载轨迹详情失败'
    }
  }

  async function fetchSteps(id: string) {
    error.value = null
    try {
      steps.value = await traceApi.getSteps(id)
    } catch (e: any) {
      error.value = e.message ?? '加载轨迹步骤失败'
    }
  }

  return {
    list,
    current,
    steps,
    page,
    total,
    pageSize,
    loading,
    error,
    overviewStats,
    toolStats,
    evaluation,
    searchResults,
    fetchList,
    fetchDetail,
    fetchSteps,
    search,
    exportTrace,
    fetchOverviewStats,
    fetchToolStats,
    fetchEvaluation,
  }
})
