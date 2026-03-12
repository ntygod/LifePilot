<script setup lang="ts">
import { ref } from 'vue'
import {
  CheckCircle2,
  Play,
  PlusCircle,
  RefreshCw,
  ShieldCheck,
  ShieldQuestion,
  SkipForward,
  XCircle,
} from 'lucide-vue-next'
import type { WorkflowEvent, WorkflowEventType } from '@/types'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'

defineProps<{
  events: WorkflowEvent[]
  loading: boolean
}>()

const eventDetailsCache = new Map<string, string | null>()
const expandedEventIds = ref<Set<string>>(new Set())

const eventTypeConfig: Record<WorkflowEventType, { label: string; icon: typeof PlusCircle; color: string }> = {
  INSTANCE_CREATED: { label: '实例创建', icon: PlusCircle, color: 'text-gray-500' },
  INSTANCE_STATE_CHANGED: { label: '状态变化', icon: RefreshCw, color: 'text-blue-500' },
  STEP_STARTED: { label: '步骤开始', icon: Play, color: 'text-blue-500' },
  STEP_COMPLETED: { label: '步骤完成', icon: CheckCircle2, color: 'text-green-500' },
  STEP_FAILED: { label: '步骤失败', icon: XCircle, color: 'text-red-500' },
  STEP_SKIPPED: { label: '步骤跳过', icon: SkipForward, color: 'text-gray-400' },
  APPROVAL_REQUESTED: { label: '审批请求', icon: ShieldQuestion, color: 'text-amber-500' },
  APPROVAL_DECIDED: { label: '审批决定', icon: ShieldCheck, color: 'text-green-500' },
}

const stateLabels: Record<string, string> = {
  CREATED: '已创建',
  RUNNING: '执行中',
  PAUSED: '等待审批',
  WAITING: '等待唤醒',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
}

function getConfig(type: WorkflowEventType) {
  return eventTypeConfig[type] ?? { label: type, icon: PlusCircle, color: 'text-gray-500' }
}

function parseEventData(event: WorkflowEvent) {
  if (!event.dataJson) return null
  try {
    return JSON.parse(event.dataJson) as Record<string, unknown>
  } catch {
    return null
  }
}

function getEventColor(event: WorkflowEvent) {
  const data = parseEventData(event)
  if (event.type === 'APPROVAL_DECIDED' && data?.decision === 'REJECTED') {
    return 'text-red-500'
  }

  return eventTypeConfig[event.type]?.color ?? 'text-gray-500'
}

function getEventSummary(event: WorkflowEvent) {
  const data = parseEventData(event)
  if (!data) return null

  if (event.type === 'INSTANCE_STATE_CHANGED') {
    const oldState = typeof data.oldState === 'string' ? data.oldState : typeof data.from === 'string' ? data.from : undefined
    const newState = typeof data.newState === 'string' ? data.newState : typeof data.to === 'string' ? data.to : undefined
    if (oldState && newState) {
      return `${stateLabels[oldState] ?? oldState} -> ${stateLabels[newState] ?? newState}`
    }
  }

  if (event.type === 'STEP_STARTED') {
    const stepType = typeof data.stepType === 'string' ? data.stepType : null
    return stepType ? `开始执行 ${stepType} 节点` : '步骤开始执行'
  }

  if (event.type === 'STEP_COMPLETED') {
    const durationMs = typeof data.durationMs === 'number' ? data.durationMs : null
    return durationMs != null ? `执行完成，耗时 ${durationMs}ms` : '步骤执行完成'
  }

  if (event.type === 'STEP_FAILED') {
    const errorMessage = typeof data.errorMessage === 'string' ? data.errorMessage : null
    return errorMessage ?? '步骤执行失败'
  }

  if (event.type === 'STEP_SKIPPED') {
    const reason = typeof data.reason === 'string' ? data.reason : null
    return reason ?? '步骤已跳过'
  }

  if (event.type === 'APPROVAL_REQUESTED') {
    const approvers = Array.isArray(data.approvers) ? data.approvers.join(', ') : null
    return approvers ? `等待审批人: ${approvers}` : '等待人工审批'
  }

  if (event.type === 'APPROVAL_DECIDED') {
    const decision = typeof data.decision === 'string' ? data.decision : null
    const decidedBy = typeof data.decidedBy === 'string' ? data.decidedBy : null
    const decisionLabel = decision === 'APPROVED' ? '已通过' : decision === 'REJECTED' ? '已拒绝' : decision
    if (decisionLabel && decidedBy) {
      return `${decisionLabel}，处理人 ${decidedBy}`
    }
    return decisionLabel
  }

  return null
}

function getEventDetails(event: WorkflowEvent) {
  if (!event.dataJson) return null
  if (eventDetailsCache.has(event.id)) return eventDetailsCache.get(event.id) ?? null

  try {
    const formatted = JSON.stringify(JSON.parse(event.dataJson), null, 2)
    eventDetailsCache.set(event.id, formatted)
    return formatted
  } catch {
    eventDetailsCache.set(event.id, event.dataJson)
    return event.dataJson
  }
}

function formatTime(iso: string) {
  return new Date(iso).toLocaleString()
}

function isEventExpanded(eventId: string) {
  return expandedEventIds.value.has(eventId)
}

function toggleEventExpanded(eventId: string) {
  const next = new Set(expandedEventIds.value)
  if (next.has(eventId)) {
    next.delete(eventId)
  } else {
    next.add(eventId)
  }
  expandedEventIds.value = next
}
</script>

<template>
  <div class="space-y-0">
    <template v-if="loading">
      <div v-for="i in 4" :key="i" class="flex items-start gap-3 py-3">
        <Skeleton class="h-6 w-6 rounded-full" />
        <div class="flex-1 space-y-1">
          <Skeleton class="h-4 w-24" />
          <Skeleton class="h-3 w-40" />
        </div>
      </div>
    </template>

    <div v-else-if="events.length === 0" class="py-4 text-center text-sm text-muted-foreground">
      暂无事件记录
    </div>

    <div v-else class="relative">
      <div class="absolute bottom-3 left-3 top-3 w-px bg-border" />

      <div
        v-for="event in events"
        :key="event.id"
        class="relative flex items-start gap-3 py-2"
      >
        <div
          class="relative z-10 flex h-6 w-6 shrink-0 items-center justify-center rounded-full border-2 border-border bg-background"
        >
          <component
            :is="getConfig(event.type).icon"
            class="h-3.5 w-3.5"
            :class="getEventColor(event)"
          />
        </div>

        <div class="min-w-0 flex-1">
          <div class="flex flex-wrap items-center gap-2">
            <Badge variant="outline" class="text-xs">
              {{ getConfig(event.type).label }}
            </Badge>
            <span v-if="event.stepId" class="font-mono text-xs text-muted-foreground">
              {{ event.stepId }}
            </span>
          </div>

          <div class="mt-0.5 text-xs text-muted-foreground">
            {{ formatTime(event.createdAt) }}
          </div>

          <div v-if="getEventSummary(event)" class="mt-1 text-sm text-foreground">
            {{ getEventSummary(event) }}
          </div>

          <div v-if="event.dataJson" class="mt-1">
            <button
              type="button"
              class="cursor-pointer text-xs text-muted-foreground hover:text-foreground"
              @click="toggleEventExpanded(event.id)"
            >
              {{ isEventExpanded(event.id) ? '收起详情' : '查看详情' }}
            </button>
            <pre
              v-if="isEventExpanded(event.id)"
              class="mt-1 max-h-40 overflow-x-auto rounded bg-muted p-2 text-xs"
            >{{ getEventDetails(event) }}</pre>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
