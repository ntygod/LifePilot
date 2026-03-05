import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { WorkflowItem, WorkflowDetail, WorkflowExecution } from '@/types'
import { workflowApi } from '@/api/client'

export const useWorkflowStore = defineStore('workflow', () => {
  const list = ref<WorkflowItem[]>([])
  const current = ref<WorkflowDetail | null>(null)
  const executions = ref<WorkflowExecution[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetchList() {
    loading.value = true
    error.value = null
    try {
      list.value = await workflowApi.list()
    } catch (e: any) {
      error.value = e.message ?? '加载工作流列表失败'
    } finally {
      loading.value = false
    }
  }

  async function fetchDetail(id: string) {
    error.value = null
    try {
      current.value = await workflowApi.get(id)
    } catch (e: any) {
      error.value = e.message ?? '加载工作流详情失败'
    }
  }

  async function enable(id: string) {
    error.value = null
    try {
      await workflowApi.enable(id)
      // 更新本地状态
      const item = list.value.find(w => w.id === id)
      if (item) item.enabled = true
      if (current.value?.id === id) current.value = { ...current.value, enabled: true }
    } catch (e: any) {
      error.value = e.message ?? '启用工作流失败'
    }
  }

  async function disable(id: string) {
    error.value = null
    try {
      await workflowApi.disable(id)
      const item = list.value.find(w => w.id === id)
      if (item) item.enabled = false
      if (current.value?.id === id) current.value = { ...current.value, enabled: false }
    } catch (e: any) {
      error.value = e.message ?? '禁用工作流失败'
    }
  }

  async function trigger(id: string, inputs?: Record<string, unknown>) {
    error.value = null
    try {
      const execution = await workflowApi.trigger(id, inputs)
      executions.value.unshift(execution)
      return execution
    } catch (e: any) {
      error.value = e.message ?? '触发工作流失败'
    }
  }

  async function fetchExecutions(id: string) {
    error.value = null
    try {
      executions.value = await workflowApi.listExecutions(id)
    } catch (e: any) {
      error.value = e.message ?? '加载执行历史失败'
    }
  }

  type WorkflowYamlPayload = { yaml: string } | { yamlContent: string }

  async function create(data: WorkflowYamlPayload) {
    error.value = null
    try {
      const workflow = await workflowApi.create(data)
      await fetchList()
      return workflow
    } catch (e: any) {
      error.value = e.message ?? '创建工作流失败'
      throw e
    }
  }

  async function update(id: string, data: WorkflowYamlPayload) {
    error.value = null
    try {
      const workflow = await workflowApi.update(id, data)
      await fetchList()
      if (current.value?.id === id) {
        current.value = workflow
      }
      return workflow
    } catch (e: any) {
      error.value = e.message ?? '更新工作流失败'
      throw e
    }
  }

  async function remove(id: string) {
    error.value = null
    try {
      await workflowApi.delete(id)
      // 更新本地列表
      list.value = list.value.filter(w => w.id !== id)
      // 如果当前详情正是被删除的工作流，清空 current / executions
      if (current.value?.id === id) {
        current.value = null
        executions.value = []
      }
    } catch (e: any) {
      error.value = e.message ?? '删除工作流失败'
      throw e
    }
  }

  return {
    list, current, executions, loading, error,
    fetchList, fetchDetail, enable, disable, trigger, fetchExecutions,
    create, update, remove
  }
})
