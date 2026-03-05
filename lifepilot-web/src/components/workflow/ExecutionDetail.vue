<script setup lang="ts">
import { computed } from 'vue'
import type { WorkflowExecution } from '@/types'
import { Clock, CheckCircle2, XCircle, AlertCircle, Timer, ListOrdered } from 'lucide-vue-next'

const props = defineProps<{
  execution: WorkflowExecution
  totalSteps: number
}>()

// 状态配置映射
const stateConfig: Record<string, { label: string; class: string; icon: typeof CheckCircle2 }> = {
  PENDING: { label: '等待中', class: 'bg-gray-100 text-gray-800', icon: Clock },
  RUNNING: { label: '运行中', class: 'bg-blue-100 text-blue-800', icon: Timer },
  COMPLETED: { label: '已完成', class: 'bg-green-100 text-green-800', icon: CheckCircle2 },
  FAILED: { label: '失败', class: 'bg-red-100 text-red-800', icon: XCircle },
  CANCELLED: { label: '已取消', class: 'bg-yellow-100 text-yellow-800', icon: AlertCircle },
}

const currentState = computed(() =>
  stateConfig[props.execution.state] ?? stateConfig.PENDING
)

// 进度文本：currentStepIndex + 1 / totalSteps
const progressText = computed(() =>
  `${props.execution.currentStepIndex + 1} / ${props.totalSteps}`
)

// 总耗时计算：startedAt 和 completedAt 均存在时计算
const duration = computed(() => {
  const { startedAt, completedAt } = props.execution
  if (!startedAt || !completedAt) return null
  const ms = new Date(completedAt).getTime() - new Date(startedAt).getTime()
  if (ms < 1000) return `${ms}ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`
  return `${(ms / 60_000).toFixed(1)}min`
})

function formatTime(iso?: string): string {
  if (!iso) return '-'
  return new Date(iso).toLocaleString()
}
</script>

<template>
  <div class="rounded-lg border border-border bg-muted/30 p-md space-y-sm text-sm">
    <!-- 状态 + 进度 -->
    <div class="flex items-center gap-md flex-wrap">
      <div class="flex items-center gap-xs">
        <component :is="currentState.icon" class="w-4 h-4" />
        <span class="px-sm py-xs rounded-full text-xs font-medium" :class="currentState.class">
          {{ currentState.label }}
        </span>
      </div>
      <div class="flex items-center gap-xs text-muted-foreground">
        <ListOrdered class="w-4 h-4" />
        <span>进度: {{ progressText }}</span>
      </div>
      <div v-if="duration" class="flex items-center gap-xs text-muted-foreground">
        <Timer class="w-4 h-4" />
        <span>耗时: {{ duration }}</span>
      </div>
    </div>

    <!-- 时间信息 -->
    <div class="flex items-center gap-md flex-wrap text-xs text-muted-foreground">
      <span v-if="execution.startedAt">开始: {{ formatTime(execution.startedAt) }}</span>
      <span v-if="execution.completedAt">完成: {{ formatTime(execution.completedAt) }}</span>
    </div>

    <!-- 失败原因 -->
    <div
      v-if="execution.state === 'FAILED' && execution.failureReason"
      class="p-sm rounded-lg bg-destructive/10 text-destructive"
    >
      <div class="font-medium mb-0.5">失败原因：</div>
      <div>{{ execution.failureReason }}</div>
    </div>
  </div>
</template>
