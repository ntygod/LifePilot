<script setup lang="ts">
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

function getConfig(type: WorkflowEventType) {
  return eventTypeConfig[type] ?? { label: type, icon: PlusCircle, color: 'text-gray-500' }
}

function getEventColor(event: WorkflowEvent) {
  if (event.type === 'APPROVAL_DECIDED' && event.dataJson) {
    try {
      const data = JSON.parse(event.dataJson)
      if (data.decision === 'REJECTED') return 'text-red-500'
    } catch {
      return eventTypeConfig[event.type]?.color ?? 'text-gray-500'
    }
  }

  return eventTypeConfig[event.type]?.color ?? 'text-gray-500'
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

          <details v-if="event.dataJson" class="mt-1">
            <summary class="cursor-pointer text-xs text-muted-foreground hover:text-foreground">
              查看详情
            </summary>
            <pre class="mt-1 max-h-40 overflow-x-auto rounded bg-muted p-2 text-xs">{{ getEventDetails(event) }}</pre>
          </details>
        </div>
      </div>
    </div>
  </div>
</template>
