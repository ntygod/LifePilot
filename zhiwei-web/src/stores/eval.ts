import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type {
  BenchmarkScenario,
  EvalResultItem,
  EvalReportSummary,
  EvalRunRequest,
} from '@/types'
import { evalApi } from '@/api/eval'

export const useEvalStore = defineStore('eval', () => {
  // 场景列表
  const scenarios = ref<BenchmarkScenario[]>([])
  // 评估运行报告列表（历史运行）
  const runs = ref<EvalReportSummary[]>([])
  // 当前查看运行的结果列表
  const currentRunResults = ref<EvalResultItem[]>([])
  // 当前运行的报告汇总
  const currentReport = ref<EvalReportSummary | null>(null)
  // 场景历史结果缓存（scenarioId → 历史结果列表）
  const scenarioHistory = ref<Record<string, EvalResultItem[]>>({})
  const loading = ref(false)
  const error = ref<string | null>(null)

  /** 从 scenarios 提取去重排序的标签列表 */
  const allTags = computed(() => {
    const tagSet = new Set<string>()
    scenarios.value.forEach(s => s.tags.forEach(t => tagSet.add(t)))
    return Array.from(tagSet).sort()
  })

  /** 获取场景列表 */
  async function fetchScenarios(tag?: string) {
    loading.value = true
    error.value = null
    try {
      scenarios.value = await evalApi.listScenarios(tag)
    } catch (e: any) {
      error.value = e.message ?? '加载场景列表失败'
    } finally {
      loading.value = false
    }
  }

  /** 触发评估运行 */
  async function triggerRun(req: EvalRunRequest) {
    loading.value = true
    error.value = null
    try {
      const report = await evalApi.triggerRun(req)
      // 将新运行插入列表头部
      runs.value.unshift(report)
      return report
    } catch (e: any) {
      error.value = e.message ?? '触发评估运行失败'
      return null
    } finally {
      loading.value = false
    }
  }

  /** 获取运行结果列表 */
  async function fetchRunResults(evalRunId: string) {
    loading.value = true
    error.value = null
    try {
      currentRunResults.value = await evalApi.getRunResults(evalRunId)
    } catch (e: any) {
      error.value = e.message ?? '加载运行结果失败'
    } finally {
      loading.value = false
    }
  }

  /** 获取运行报告 */
  async function fetchReport(evalRunId: string) {
    error.value = null
    try {
      currentReport.value = await evalApi.getRunReport(evalRunId)
    } catch (e: any) {
      error.value = e.message ?? '加载运行报告失败'
    }
  }

  /** 获取场景历史结果并缓存 */
  async function fetchScenarioHistory(scenarioId: string, limit = 20) {
    loading.value = true
    error.value = null
    try {
      const results = await evalApi.getResultsByScenario(scenarioId, limit)
      scenarioHistory.value[scenarioId] = results
    } catch (e: any) {
      error.value = e.message ?? '加载场景历史失败'
    } finally {
      loading.value = false
    }
  }

  return {
    scenarios,
    runs,
    currentRunResults,
    currentReport,
    scenarioHistory,
    loading,
    error,
    allTags,
    fetchScenarios,
    triggerRun,
    fetchRunResults,
    fetchReport,
    fetchScenarioHistory,
  }
})
