import { onBeforeUnmount, ref } from 'vue'
import { API_ORIGIN } from '@/api/config'
import { SSE_EVENT_TYPES } from '@/constants/sseEvents'
import { useWorkflowStore } from '@/stores/workflow'
import type {
  StepLog,
  WorkflowEvent,
  WorkflowExecution,
  WorkflowExecutionsSnapshot,
  WorkflowStepLogsSnapshot,
  WorkflowTimelineSnapshot,
} from '@/types'

export function useWorkflowExecutionStream() {
  const store = useWorkflowStore()
  const workflowConnected = ref(false)
  const instanceConnected = ref(false)

  let workflowSource: EventSource | null = null
  let instanceSource: EventSource | null = null
  let currentWorkflowId: string | null = null
  let currentInstanceId: string | null = null

  function disconnectWorkflow() {
    if (workflowSource) {
      workflowSource.close()
      workflowSource = null
    }
    workflowConnected.value = false
    currentWorkflowId = null
  }

  function disconnectInstance() {
    if (instanceSource) {
      instanceSource.close()
      instanceSource = null
    }
    instanceConnected.value = false
    currentInstanceId = null
  }

  function disconnectAll() {
    disconnectInstance()
    disconnectWorkflow()
  }

  function connectWorkflow(workflowId: string) {
    if (workflowSource && currentWorkflowId === workflowId) {
      return
    }

    disconnectWorkflow()
    currentWorkflowId = workflowId
    workflowSource = new EventSource(`${API_ORIGIN}/api/workflows/${workflowId}/executions/stream`)

    workflowSource.onopen = () => {
      workflowConnected.value = true
    }

    workflowSource.onerror = () => {
      workflowConnected.value = false
    }

    workflowSource.addEventListener(SSE_EVENT_TYPES.HEARTBEAT, () => {
      workflowConnected.value = true
    })

    workflowSource.addEventListener(SSE_EVENT_TYPES.WORKFLOW_EXECUTIONS_SNAPSHOT, (event) => {
      try {
        const payload = JSON.parse((event as MessageEvent).data) as WorkflowExecutionsSnapshot
        store.setExecutions(payload.executions ?? [])
        workflowConnected.value = true
      } catch (error) {
        console.warn('Failed to parse workflow executions snapshot', error)
      }
    })

    workflowSource.addEventListener(SSE_EVENT_TYPES.WORKFLOW_EXECUTION_UPDATED, (event) => {
      try {
        const execution = JSON.parse((event as MessageEvent).data) as WorkflowExecution
        store.upsertExecution(execution)
      } catch (error) {
        console.warn('Failed to parse workflow execution update', error)
      }
    })
  }

  function connectInstance(instanceId: string) {
    if (instanceSource && currentInstanceId === instanceId) {
      return
    }

    disconnectInstance()
    currentInstanceId = instanceId
    instanceSource = new EventSource(`${API_ORIGIN}/api/workflows/executions/${instanceId}/stream`)

    instanceSource.onopen = () => {
      instanceConnected.value = true
    }

    instanceSource.onerror = () => {
      instanceConnected.value = false
    }

    instanceSource.addEventListener(SSE_EVENT_TYPES.HEARTBEAT, () => {
      instanceConnected.value = true
    })

    instanceSource.addEventListener(SSE_EVENT_TYPES.WORKFLOW_EXECUTION_SNAPSHOT, (event) => {
      try {
        const execution = JSON.parse((event as MessageEvent).data) as WorkflowExecution
        store.setCurrentInstance(execution)
        instanceConnected.value = true
      } catch (error) {
        console.warn('Failed to parse workflow execution snapshot', error)
      }
    })

    instanceSource.addEventListener(SSE_EVENT_TYPES.WORKFLOW_EXECUTION_UPDATED, (event) => {
      try {
        const execution = JSON.parse((event as MessageEvent).data) as WorkflowExecution
        store.setCurrentInstance(execution)
      } catch (error) {
        console.warn('Failed to parse workflow execution update', error)
      }
    })

    instanceSource.addEventListener(SSE_EVENT_TYPES.WORKFLOW_TIMELINE_SNAPSHOT, (event) => {
      try {
        const payload = JSON.parse((event as MessageEvent).data) as WorkflowTimelineSnapshot
        store.setEventTimeline(instanceId, payload.events ?? [])
      } catch (error) {
        console.warn('Failed to parse workflow timeline snapshot', error)
      }
    })

    instanceSource.addEventListener(SSE_EVENT_TYPES.WORKFLOW_EVENT_CREATED, (event) => {
      try {
        const workflowEvent = JSON.parse((event as MessageEvent).data) as WorkflowEvent
        store.appendEventTimeline(instanceId, workflowEvent)
      } catch (error) {
        console.warn('Failed to parse workflow event update', error)
      }
    })

    instanceSource.addEventListener(SSE_EVENT_TYPES.WORKFLOW_STEP_LOGS_SNAPSHOT, (event) => {
      try {
        const payload = JSON.parse((event as MessageEvent).data) as WorkflowStepLogsSnapshot
        store.setStepLogs(instanceId, payload.stepLogs ?? [])
      } catch (error) {
        console.warn('Failed to parse workflow step log snapshot', error)
      }
    })

    instanceSource.addEventListener(SSE_EVENT_TYPES.WORKFLOW_STEP_LOG_CREATED, (event) => {
      try {
        const stepLog = JSON.parse((event as MessageEvent).data) as StepLog
        store.appendStepLog(instanceId, stepLog)
      } catch (error) {
        console.warn('Failed to parse workflow step log update', error)
      }
    })
  }

  onBeforeUnmount(() => {
    disconnectAll()
  })

  return {
    workflowConnected,
    instanceConnected,
    connectWorkflow,
    disconnectWorkflow,
    connectInstance,
    disconnectInstance,
    disconnectAll,
  }
}
