import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { StepLog, WorkflowEvent, WorkflowExecution } from '@/types'

vi.mock('@/api/client', () => ({
  workflowApi: {
    list: vi.fn(),
    get: vi.fn(),
    enable: vi.fn(),
    disable: vi.fn(),
    trigger: vi.fn(),
    listExecutions: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    approve: vi.fn(),
    getInstance: vi.fn(),
    getEventTimeline: vi.fn(),
    getStepLogs: vi.fn(),
  },
}))

import { workflowApi } from '@/api/client'
import { useWorkflowStore } from './workflow'

const workflowApiMock = vi.mocked(workflowApi, { deep: true })

function makeExecution(id: string): WorkflowExecution {
  return {
    id,
    workflowId: 'workflow-1',
    state: 'FAILED',
    completedStepIds: [],
    failureReason: 'boom',
    createdAt: '2026-03-11T00:00:00.000Z',
    updatedAt: '2026-03-11T00:00:00.000Z',
  }
}

function makeEvent(id: string, instanceId: string): WorkflowEvent {
  return {
    id,
    instanceId,
    workflowId: 'workflow-1',
    type: 'STEP_FAILED',
    stepId: 'step-1',
    dataJson: JSON.stringify({ reason: 'boom' }),
    createdAt: '2026-03-11T00:00:00.000Z',
  }
}

function makeStepLog(id: string, instanceId: string): StepLog {
  return {
    id,
    instanceId,
    stepId: 'step-1',
    stepType: 'tool',
    state: 'FAILED',
    attempt: 1,
    inputJson: '{"foo":"bar"}',
    outputJson: undefined,
    errorMessage: 'boom',
    startedAt: '2026-03-11T00:00:00.000Z',
    completedAt: '2026-03-11T00:00:01.000Z',
    durationMs: 1000,
    createdAt: '2026-03-11T00:00:01.000Z',
  }
}

describe('useWorkflowStore execution detail cache', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('caches event timelines and step logs per instance', async () => {
    workflowApiMock.getEventTimeline.mockResolvedValueOnce([makeEvent('event-a', 'exec-a')])
    workflowApiMock.getStepLogs.mockResolvedValueOnce([makeStepLog('log-a', 'exec-a')])

    const store = useWorkflowStore()

    await store.fetchEventTimeline('exec-a')
    await store.fetchStepLogs('exec-a')
    await store.fetchEventTimeline('exec-a')
    await store.fetchStepLogs('exec-a')

    expect(workflowApiMock.getEventTimeline).toHaveBeenCalledTimes(1)
    expect(workflowApiMock.getStepLogs).toHaveBeenCalledTimes(1)
    expect(store.hasEventTimeline('exec-a')).toBe(true)
    expect(store.hasStepLogs('exec-a')).toBe(true)
    expect(store.getEventTimeline('exec-a')).toEqual([makeEvent('event-a', 'exec-a')])
    expect(store.getStepLogs('exec-a')).toEqual([makeStepLog('log-a', 'exec-a')])
  })

  it('keeps caches isolated by instance and clears them when executions refresh', async () => {
    workflowApiMock.getEventTimeline
      .mockResolvedValueOnce([makeEvent('event-a', 'exec-a')])
      .mockResolvedValueOnce([makeEvent('event-b', 'exec-b')])
    workflowApiMock.getStepLogs
      .mockResolvedValueOnce([makeStepLog('log-a', 'exec-a')])
      .mockResolvedValueOnce([makeStepLog('log-b', 'exec-b')])
    workflowApiMock.listExecutions.mockResolvedValueOnce([makeExecution('exec-a')])

    const store = useWorkflowStore()

    await store.fetchEventTimeline('exec-a')
    await store.fetchStepLogs('exec-a')
    await store.fetchEventTimeline('exec-b')
    await store.fetchStepLogs('exec-b')

    expect(store.getEventTimeline('exec-a')[0]?.instanceId).toBe('exec-a')
    expect(store.getEventTimeline('exec-b')[0]?.instanceId).toBe('exec-b')
    expect(store.getStepLogs('exec-a')[0]?.instanceId).toBe('exec-a')
    expect(store.getStepLogs('exec-b')[0]?.instanceId).toBe('exec-b')

    await store.fetchExecutions('workflow-1')

    expect(store.hasEventTimeline('exec-a')).toBe(false)
    expect(store.hasEventTimeline('exec-b')).toBe(false)
    expect(store.hasStepLogs('exec-a')).toBe(false)
    expect(store.hasStepLogs('exec-b')).toBe(false)
    expect(store.getEventTimeline('exec-a')).toEqual([])
    expect(store.getStepLogs('exec-a')).toEqual([])
  })
})
