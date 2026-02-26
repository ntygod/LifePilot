import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { TraceItem, TraceDetail, TraceStep, PageResult } from '@/types'
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
    list, current, steps, page, total, pageSize, loading, error,
    fetchList, fetchDetail, fetchSteps
  }
})
