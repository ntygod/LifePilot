import { defineStore } from 'pinia'
import { ref } from 'vue'
import type {
  ApprovalRequest,
  StepLog,
  WorkflowDetail,
  WorkflowEvent,
  WorkflowExecution,
  WorkflowItem,
} from '@/types'
import { workflowApi } from '@/api/client'

type WorkflowYamlPayload = { yaml: string } | { yamlContent: string }

function sortExecutions(items: WorkflowExecution[]) {
  return [...items].sort((left, right) => (
    new Date(right.createdAt).getTime() - new Date(left.createdAt).getTime()
  ))
}

function sortEvents(events: WorkflowEvent[]) {
  return [...events].sort((left, right) => (
    new Date(left.createdAt).getTime() - new Date(right.createdAt).getTime()
  ))
}

function sortStepLogs(stepLogs: StepLog[]) {
  return [...stepLogs].sort((left, right) => (
    new Date(left.createdAt).getTime() - new Date(right.createdAt).getTime()
  ))
}

export const useWorkflowStore = defineStore('workflow', () => {
  const list = ref<WorkflowItem[]>([])
  const current = ref<WorkflowDetail | null>(null)
  const executions = ref<WorkflowExecution[]>([])
  const currentInstance = ref<WorkflowExecution | null>(null)
  const eventTimelineByInstance = ref<Record<string, WorkflowEvent[]>>({})
  const stepLogsByInstance = ref<Record<string, StepLog[]>>({})
  const loading = ref(false)
  const error = ref<string | null>(null)

  function hasEventTimeline(instanceId: string) {
    return Object.prototype.hasOwnProperty.call(eventTimelineByInstance.value, instanceId)
  }

  function hasStepLogs(instanceId: string) {
    return Object.prototype.hasOwnProperty.call(stepLogsByInstance.value, instanceId)
  }

  function getEventTimeline(instanceId: string) {
    return eventTimelineByInstance.value[instanceId] ?? []
  }

  function getStepLogs(instanceId: string) {
    return stepLogsByInstance.value[instanceId] ?? []
  }

  function clearExecutionDetails(instanceId?: string) {
    if (!instanceId) {
      eventTimelineByInstance.value = {}
      stepLogsByInstance.value = {}
      return
    }

    const nextEventTimeline = { ...eventTimelineByInstance.value }
    delete nextEventTimeline[instanceId]
    eventTimelineByInstance.value = nextEventTimeline

    const nextStepLogs = { ...stepLogsByInstance.value }
    delete nextStepLogs[instanceId]
    stepLogsByInstance.value = nextStepLogs
  }

  function setExecutions(items: WorkflowExecution[]) {
    executions.value = sortExecutions(items)
  }

  function upsertExecution(execution: WorkflowExecution) {
    const index = executions.value.findIndex(item => item.id === execution.id)
    if (index === -1) {
      executions.value = sortExecutions([execution, ...executions.value])
    } else {
      const next = [...executions.value]
      next[index] = execution
      executions.value = sortExecutions(next)
    }

    if (currentInstance.value?.id === execution.id) {
      currentInstance.value = execution
    }
  }

  function setCurrentInstance(execution: WorkflowExecution | null) {
    currentInstance.value = execution
    if (execution) {
      upsertExecution(execution)
    }
  }

  function setEventTimeline(instanceId: string, events: WorkflowEvent[]) {
    const deduplicated = Array.from(new Map(events.map(event => [event.id, event])).values())
    eventTimelineByInstance.value = {
      ...eventTimelineByInstance.value,
      [instanceId]: sortEvents(deduplicated),
    }
  }

  function appendEventTimeline(instanceId: string, event: WorkflowEvent) {
    setEventTimeline(instanceId, [...getEventTimeline(instanceId), event])
  }

  function setStepLogs(instanceId: string, stepLogs: StepLog[]) {
    const deduplicated = Array.from(new Map(stepLogs.map(stepLog => [stepLog.id, stepLog])).values())
    stepLogsByInstance.value = {
      ...stepLogsByInstance.value,
      [instanceId]: sortStepLogs(deduplicated),
    }
  }

  function appendStepLog(instanceId: string, stepLog: StepLog) {
    setStepLogs(instanceId, [...getStepLogs(instanceId), stepLog])
  }

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
      const item = list.value.find(workflow => workflow.id === id)
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
      const item = list.value.find(workflow => workflow.id === id)
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
      upsertExecution(execution)
      return execution
    } catch (e: any) {
      error.value = e.message ?? '触发工作流失败'
      throw e
    }
  }

  async function fetchExecutions(id: string) {
    error.value = null
    clearExecutionDetails()
    try {
      setExecutions(await workflowApi.listExecutions(id))
    } catch (e: any) {
      error.value = e.message ?? '加载执行记录失败'
    }
  }

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
      list.value = list.value.filter(workflow => workflow.id !== id)
      if (current.value?.id === id) {
        current.value = null
        executions.value = []
        currentInstance.value = null
        clearExecutionDetails()
      }
    } catch (e: any) {
      error.value = e.message ?? '删除工作流失败'
      throw e
    }
  }

  async function approve(instanceId: string, stepId: string, req: ApprovalRequest) {
    error.value = null
    try {
      const updated = await workflowApi.approve(instanceId, stepId, req)
      upsertExecution(updated)
      currentInstance.value = updated
      return updated
    } catch (e: any) {
      error.value = e.message ?? '提交审批失败'
      throw e
    }
  }

  async function fetchInstance(instanceId: string) {
    error.value = null
    try {
      const execution = await workflowApi.getInstance(instanceId)
      setCurrentInstance(execution)
      return execution
    } catch (e: any) {
      error.value = e.message ?? '加载执行实例失败'
      return null
    }
  }

  async function fetchEventTimeline(instanceId: string) {
    error.value = null
    if (hasEventTimeline(instanceId)) {
      return getEventTimeline(instanceId)
    }

    try {
      const timeline = await workflowApi.getEventTimeline(instanceId)
      setEventTimeline(instanceId, timeline)
      return timeline
    } catch (e: any) {
      error.value = e.message ?? '加载事件时间线失败'
      return []
    }
  }

  async function fetchStepLogs(instanceId: string) {
    error.value = null
    if (hasStepLogs(instanceId)) {
      return getStepLogs(instanceId)
    }

    try {
      const logs = await workflowApi.getStepLogs(instanceId)
      setStepLogs(instanceId, logs)
      return logs
    } catch (e: any) {
      error.value = e.message ?? '加载步骤日志失败'
      return []
    }
  }

  return {
    list,
    current,
    executions,
    currentInstance,
    eventTimelineByInstance,
    stepLogsByInstance,
    loading,
    error,
    hasEventTimeline,
    hasStepLogs,
    getEventTimeline,
    getStepLogs,
    clearExecutionDetails,
    setExecutions,
    upsertExecution,
    setCurrentInstance,
    setEventTimeline,
    appendEventTimeline,
    setStepLogs,
    appendStepLog,
    fetchList,
    fetchDetail,
    enable,
    disable,
    trigger,
    fetchExecutions,
    create,
    update,
    remove,
    approve,
    fetchInstance,
    fetchEventTimeline,
    fetchStepLogs,
  }
})
