<script setup lang="ts">
/**
 * 工作流执行详情组件。
 * 展示执行状态、进度、审批面板、DAG 可视化、事件时间线。
 */
import { computed, onMounted, ref } from 'vue'
import type { WorkflowExecution } from '@/types'
import { useWorkflowStore } from '@/stores/workflow'
import { getStateConfig } from '@/constants/workflowState'
import ApprovalPanel from '@/components/workflow/ApprovalPanel.vue'
import EventTimeline from '@/components/workflow/EventTimeline.vue'
import StepDagView from '@/components/workflow/StepDagView.vue'
import { ListOrdered, Timer } from 'lucide-vue-next'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'

const props = defineProps<{
  execution: WorkflowExecution
  totalSteps: number
  steps?: unknown[]
}>()

const store = useWorkflowStore()

// 事件时间线加载状态
const timelineLoading = ref(false)
const activeTab = ref('overview')

// 状态配置
const currentState = computed(() => getStateConfig(props.execution.state))

// 进度文本：completedStepIds.length / totalSteps
const progressText = computed(() =>
  `${props.execution.completedStepIds?.length ?? 0} / ${props.totalSteps}`
)

// 总耗时计算
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

// 审批完成后刷新实例
async function onApproved() {
  await store.fetchInstance(props.execution.id)
}

// 加载事件时间线
async function loadTimeline() {
  timelineLoading.value = true
  await store.fetchEventTimeline(props.execution.id)
  timelineLoading.value = false
}

// 切换到时间线 Tab 时加载数据
function onTabChange(tab: string | number) {
  activeTab.value = String(tab)
  if (String(tab) === 'timeline' && store.eventTimeline.length === 0) {
    loadTimeline()
  }
}
</script>

<template>
  <div class="space-y-sm text-sm">
    <!-- 状态 + 进度 -->
    <div class="rounded-lg border border-border bg-muted/30 p-md space-y-sm">
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

    <!-- 审批面板（PAUSED 状态时展示） -->
    <ApprovalPanel
      v-if="execution.state === 'PAUSED' && execution.pendingApprovalStepId && steps"
      :execution="execution"
      :steps="steps"
      @approved="onApproved"
    />

    <!-- Tab 区域：DAG 视图 / 事件时间线 -->
    <Tabs :model-value="activeTab" @update:model-value="onTabChange">
      <TabsList>
        <TabsTrigger value="overview">步骤视图</TabsTrigger>
        <TabsTrigger value="timeline">事件时间线</TabsTrigger>
      </TabsList>

      <!-- DAG 步骤视图 -->
      <TabsContent value="overview">
        <StepDagView
          v-if="steps && steps.length > 0"
          :steps="steps"
          :completed-step-ids="execution.completedStepIds ?? []"
          :pending-approval-step-id="execution.pendingApprovalStepId"
        />
        <div v-else class="text-xs text-muted-foreground py-4 text-center">
          无步骤信息
        </div>
      </TabsContent>

      <!-- 事件时间线 -->
      <TabsContent value="timeline">
        <EventTimeline
          :events="store.eventTimeline"
          :loading="timelineLoading"
        />
      </TabsContent>
    </Tabs>
  </div>
</template>
