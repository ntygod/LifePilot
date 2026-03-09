<script setup lang="ts">
/**
 * 工作流事件时间线组件。
 * 垂直时间线布局，按 createdAt 升序展示工作流审计事件。
 */
import { computed } from 'vue'
import type { WorkflowEvent, WorkflowEventType } from '@/types'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import {
  PlusCircle,
  RefreshCw,
  Play,
  CheckCircle2,
  XCircle,
  SkipForward,
  ShieldQuestion,
  ShieldCheck,
} from 'lucide-vue-next'

defineProps<{
  events: WorkflowEvent[]
  loading: boolean
}>()

// 事件类型 → 图标 + 颜色 + 标签映射
const eventTypeConfig: Record<WorkflowEventType, { label: string; icon: typeof PlusCircle; color: string }> = {
  INSTANCE_CREATED:       { label: '实例创建',   icon: PlusCircle,     color: 'text-gray-500' },
  INSTANCE_STATE_CHANGED: { label: '状态变更',   icon: RefreshCw,      color: 'text-blue-500' },
  STEP_STARTED:           { label: '步骤开始',   icon: Play,           color: 'text-blue-500' },
  STEP_COMPLETED:         { label: '步骤完成',   icon: CheckCircle2,   color: 'text-green-500' },
  STEP_FAILED:            { label: '步骤失败',   icon: XCircle,        color: 'text-red-500' },
  STEP_SKIPPED:           { label: '步骤跳过',   icon: SkipForward,    color: 'text-gray-400' },
  APPROVAL_REQUESTED:     { label: '审批请求',   icon: ShieldQuestion, color: 'text-amber-500' },
  APPROVAL_DECIDED:       { label: '审批决策',   icon: ShieldCheck,    color: 'text-green-500' },
}

/**
 * 获取事件的动态颜色。
 * APPROVAL_DECIDED 根据 dataJson 中 decision 区分绿色/红色。
 */
function getEventColor(event: WorkflowEvent): string {
  if (event.type === 'APPROVAL_DECIDED' && event.dataJson) {
    try {
      const data = JSON.parse(event.dataJson)
      if (data.decision === 'REJECTED') return 'text-red-500'
    } catch { /* 解析失败使用默认颜色 */ }
  }
  return eventTypeConfig[event.type]?.color ?? 'text-gray-500'
}

function getConfig(type: WorkflowEventType) {
  return eventTypeConfig[type] ?? { label: type, icon: PlusCircle, color: 'text-gray-500' }
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString()
}
</script>

<template>
  <div class="space-y-0">
    <!-- 加载状态 -->
    <template v-if="loading">
      <div v-for="i in 4" :key="i" class="flex items-start gap-3 py-3">
        <Skeleton class="w-6 h-6 rounded-full flex-shrink-0" />
        <div class="flex-1 space-y-1">
          <Skeleton class="h-4 w-24" />
          <Skeleton class="h-3 w-40" />
        </div>
      </div>
    </template>

    <!-- 空状态 -->
    <div v-else-if="events.length === 0" class="text-sm text-muted-foreground py-4 text-center">
      暂无事件记录
    </div>

    <!-- 事件列表 -->
    <div v-else class="relative">
      <!-- 垂直连线 -->
      <div class="absolute left-3 top-3 bottom-3 w-px bg-border" />

      <div
        v-for="(event, idx) in events"
        :key="event.id"
        class="relative flex items-start gap-3 py-2"
      >
        <!-- 图标节点 -->
        <div
          class="relative z-10 flex-shrink-0 w-6 h-6 rounded-full bg-background border-2 border-border flex items-center justify-center"
        >
          <component
            :is="getConfig(event.type).icon"
            class="w-3.5 h-3.5"
            :class="getEventColor(event)"
          />
        </div>

        <!-- 事件内容 -->
        <div class="flex-1 min-w-0">
          <div class="flex items-center gap-2 flex-wrap">
            <Badge variant="outline" class="text-xs">
              {{ getConfig(event.type).label }}
            </Badge>
            <span v-if="event.stepId" class="text-xs text-muted-foreground font-mono">
              {{ event.stepId }}
            </span>
          </div>
          <div class="text-xs text-muted-foreground mt-0.5">
            {{ formatTime(event.createdAt) }}
          </div>
          <!-- dataJson 折叠展开 -->
          <details v-if="event.dataJson" class="mt-1">
            <summary class="text-xs text-muted-foreground cursor-pointer hover:text-foreground">
              查看详情
            </summary>
            <pre class="mt-1 p-2 rounded bg-muted text-xs overflow-x-auto max-h-40">{{ JSON.stringify(JSON.parse(event.dataJson), null, 2) }}</pre>
          </details>
        </div>
      </div>
    </div>
  </div>
</template>
