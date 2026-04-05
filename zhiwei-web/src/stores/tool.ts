import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ToolSummary, ToolDetail, ToolTestRequest, ToolTestResponse } from '@/types'
import { toolApi } from '@/api/client'

export const useToolStore = defineStore('tool', () => {
  const tools = ref<ToolSummary[]>([])
  const currentTool = ref<ToolDetail | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetchTools(params?: { source?: string; status?: string; name?: string }) {
    loading.value = true
    error.value = null
    try {
      tools.value = await toolApi.list(params)
    } catch (e: any) {
      error.value = e.message ?? '加载 Tool 列表失败'
      throw e
    } finally {
      loading.value = false
    }
  }

  async function fetchToolDetail(id: string) {
    error.value = null
    try {
      currentTool.value = await toolApi.get(id)
    } catch (e: any) {
      error.value = e.message ?? '加载 Tool 详情失败'
      throw e
    }
  }

  async function testTool(req: ToolTestRequest): Promise<ToolTestResponse> {
    error.value = null
    try {
      return await toolApi.test(req)
    } catch (e: any) {
      error.value = e.message ?? '测试 Tool 失败'
      throw e
    }
  }

  async function fetchToolUsage(id: string) {
    error.value = null
    try {
      return await toolApi.getUsage(id)
    } catch (e: any) {
      error.value = e.message ?? '查询 Tool 使用情况失败'
      throw e
    }
  }

  async function createTool(data: {
    id: string
    name: string
    description?: string
    inputSchema?: Record<string, any>
    outputSchema?: Record<string, any>
    budget?: {
      timeoutSeconds?: number
      maxRetries?: number
      maxCostCents?: number
    }
    riskLevel?: string
    idempotent?: boolean
    tags?: string[]
  }) {
    loading.value = true
    error.value = null
    try {
      const tool = await toolApi.create(data)
      await fetchTools() // 刷新列表
      return tool
    } catch (e: any) {
      error.value = e.message ?? '创建 Tool 失败'
      throw e
    } finally {
      loading.value = false
    }
  }

  async function updateTool(id: string, data: {
    name?: string
    description?: string
    inputSchema?: Record<string, any>
    outputSchema?: Record<string, any>
    budget?: {
      timeoutSeconds?: number
      maxRetries?: number
      maxCostCents?: number
    }
    riskLevel?: string
    idempotent?: boolean
    tags?: string[]
  }) {
    loading.value = true
    error.value = null
    try {
      const tool = await toolApi.update(id, data)
      await fetchTools() // 刷新列表
      if (currentTool.value?.id === id) {
        currentTool.value = tool
      }
      return tool
    } catch (e: any) {
      error.value = e.message ?? '更新 Tool 失败'
      throw e
    } finally {
      loading.value = false
    }
  }

  async function deleteTool(id: string) {
    loading.value = true
    error.value = null
    try {
      await toolApi.delete(id)
      await fetchTools() // 刷新列表
      if (currentTool.value?.id === id) {
        currentTool.value = null
      }
    } catch (e: any) {
      error.value = e.message ?? '删除 Tool 失败'
      throw e
    } finally {
      loading.value = false
    }
  }

  return {
    tools,
    currentTool,
    loading,
    error,
    fetchTools,
    fetchToolDetail,
    testTool,
    fetchToolUsage,
    createTool,
    updateTool,
    deleteTool
  }
})
