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

  async function fetchList() {
    loading.value = true
    error.value = null
    try {
      list.value = await workflowApi.list()
    } catch (e: any) {
      error.value = e.message ?? '鍔犺浇宸ヤ綔娴佸垪琛ㄥけ璐?'
    } finally {
      loading.value = false
    }
  }

  async function fetchDetail(id: string) {
    error.value = null
    try {
      current.value = await workflowApi.get(id)
    } catch (e: any) {
      error.value = e.message ?? '鍔犺浇宸ヤ綔娴佽鎯呭け璐?'
    }
  }

  async function enable(id: string) {
    error.value = null
    try {
      await workflowApi.enable(id)
      const item = list.value.find(w => w.id === id)
      if (item) item.enabled = true
      if (current.value?.id === id) current.value = { ...current.value, enabled: true }
    } catch (e: any) {
      error.value = e.message ?? '鍚敤宸ヤ綔娴佸け璐?'
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
      error.value = e.message ?? '绂佺敤宸ヤ綔娴佸け璐?'
    }
  }

  async function trigger(id: string, inputs?: Record<string, unknown>) {
    error.value = null
    try {
      const execution = await workflowApi.trigger(id, inputs)
      executions.value.unshift(execution)
      return execution
    } catch (e: any) {
      error.value = e.message ?? '瑙﹀彂宸ヤ綔娴佸け璐?'
    }
  }

  async function fetchExecutions(id: string) {
    error.value = null
    clearExecutionDetails()
    try {
      executions.value = await workflowApi.listExecutions(id)
    } catch (e: any) {
      error.value = e.message ?? '鍔犺浇鎵ц鍘嗗彶澶辫触'
    }
  }

  async function create(data: WorkflowYamlPayload) {
    error.value = null
    try {
      const workflow = await workflowApi.create(data)
      await fetchList()
      return workflow
    } catch (e: any) {
      error.value = e.message ?? '鍒涘缓宸ヤ綔娴佸け璐?'
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
      error.value = e.message ?? '鏇存柊宸ヤ綔娴佸け璐?'
      throw e
    }
  }

  async function remove(id: string) {
    error.value = null
    try {
      await workflowApi.delete(id)
      list.value = list.value.filter(w => w.id !== id)
      if (current.value?.id === id) {
        current.value = null
        executions.value = []
        clearExecutionDetails()
      }
    } catch (e: any) {
      error.value = e.message ?? '鍒犻櫎宸ヤ綔娴佸け璐?'
      throw e
    }
  }

  async function approve(instanceId: string, stepId: string, req: ApprovalRequest) {
    error.value = null
    try {
      const updated = await workflowApi.approve(instanceId, stepId, req)
      const idx = executions.value.findIndex(e => e.id === instanceId)
      if (idx !== -1) executions.value[idx] = updated
      if (currentInstance.value?.id === instanceId) currentInstance.value = updated
      return updated
    } catch (e: any) {
      error.value = e.message ?? '瀹℃壒鎿嶄綔澶辫触'
      throw e
    }
  }

  async function fetchInstance(instanceId: string) {
    error.value = null
    try {
      currentInstance.value = await workflowApi.getInstance(instanceId)
    } catch (e: any) {
      error.value = e.message ?? '鍔犺浇瀹炰緥璇︽儏澶辫触'
    }
  }

  async function fetchEventTimeline(instanceId: string) {
    error.value = null
    if (hasEventTimeline(instanceId)) {
      return getEventTimeline(instanceId)
    }

    try {
      const timeline = await workflowApi.getEventTimeline(instanceId)
      eventTimelineByInstance.value = {
        ...eventTimelineByInstance.value,
        [instanceId]: timeline,
      }
      return timeline
    } catch (e: any) {
      error.value = e.message ?? '鍔犺浇浜嬩欢鏃堕棿绾垮け璐?'
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
      stepLogsByInstance.value = {
        ...stepLogsByInstance.value,
        [instanceId]: logs,
      }
      return logs
    } catch (e: any) {
      error.value = e.message ?? '鍔犺浇姝ラ鏃ュ織澶辫触'
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
