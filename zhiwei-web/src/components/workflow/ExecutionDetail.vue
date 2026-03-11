<script setup lang="ts">
import { computed, ref } from 'vue'
import { ListOrdered, Timer } from 'lucide-vue-next'
import type { WorkflowExecution } from '@/types'
import { getStateConfig } from '@/constants/workflowState'
import { useWorkflowStore } from '@/stores/workflow'
import ApprovalPanel from '@/components/workflow/ApprovalPanel.vue'
import EventTimeline from '@/components/workflow/EventTimeline.vue'
import StepDagView from '@/components/workflow/StepDagView.vue'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'

const props = defineProps<{
  execution: WorkflowExecution
  totalSteps: number
  steps?: unknown[]
}>()

const store = useWorkflowStore()
const timelineLoading = ref(false)
const activeTab = ref('overview')

const currentState = computed(() => getStateConfig(props.execution.state))
const progressText = computed(() => `${props.execution.completedStepIds?.length ?? 0} / ${props.totalSteps}`)
const timeline = computed(() => store.getEventTimeline(props.execution.id))

const duration = computed(() => {
  const { startedAt, completedAt } = props.execution
  if (!startedAt || !completedAt) return null

  const ms = new Date(completedAt).getTime() - new Date(startedAt).getTime()
  if (ms < 1000) return `${ms}ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`
  return `${(ms / 60_000).toFixed(1)}min`
})

function formatTime(iso?: string) {
  if (!iso) return '-'
  return new Date(iso).toLocaleString()
}

async function onApproved() {
  await store.fetchInstance(props.execution.id)
}

async function loadTimeline() {
  if (store.hasEventTimeline(props.execution.id)) return

  timelineLoading.value = true
  try {
    await store.fetchEventTimeline(props.execution.id)
  } finally {
    timelineLoading.value = false
  }
}

function onTabChange(tab: string | number) {
  activeTab.value = String(tab)
  if (String(tab) === 'timeline') {
    void loadTimeline()
  }
}
</script>

<template>
  <div class="space-y-sm text-sm">
    <div class="space-y-sm rounded-lg border border-border bg-muted/30 p-md">
      <div class="flex flex-wrap items-center gap-md">
        <div class="flex items-center gap-xs">
          <component :is="currentState.icon" class="h-4 w-4" />
          <span class="rounded-full px-sm py-xs text-xs font-medium" :class="currentState.class">
            {{ currentState.label }}
          </span>
        </div>
        <div class="flex items-center gap-xs text-muted-foreground">
          <ListOrdered class="h-4 w-4" />
          <span>进度: {{ progressText }}</span>
        </div>
        <div v-if="duration" class="flex items-center gap-xs text-muted-foreground">
          <Timer class="h-4 w-4" />
          <span>耗时: {{ duration }}</span>
        </div>
      </div>

      <div class="flex flex-wrap items-center gap-md text-xs text-muted-foreground">
        <span v-if="execution.startedAt">开始: {{ formatTime(execution.startedAt) }}</span>
        <span v-if="execution.completedAt">完成: {{ formatTime(execution.completedAt) }}</span>
      </div>

      <div
        v-if="execution.state === 'FAILED' && execution.failureReason"
        class="rounded-lg bg-destructive/10 p-sm text-destructive"
      >
        <div class="mb-0.5 font-medium">失败原因:</div>
        <div>{{ execution.failureReason }}</div>
      </div>
    </div>

    <ApprovalPanel
      v-if="execution.state === 'PAUSED' && execution.pendingApprovalStepId && steps"
      :execution="execution"
      :steps="steps"
      @approved="onApproved"
    />

    <Tabs :model-value="activeTab" @update:model-value="onTabChange">
      <TabsList>
        <TabsTrigger value="overview">步骤视图</TabsTrigger>
        <TabsTrigger value="timeline">事件时间线</TabsTrigger>
      </TabsList>

      <TabsContent value="overview">
        <StepDagView
          v-if="steps && steps.length > 0"
          :steps="steps"
          :completed-step-ids="execution.completedStepIds ?? []"
          :pending-approval-step-id="execution.pendingApprovalStepId"
        />
        <div v-else class="py-4 text-center text-xs text-muted-foreground">
          暂无步骤信息
        </div>
      </TabsContent>

      <TabsContent value="timeline">
        <EventTimeline :events="timeline" :loading="timelineLoading" />
      </TabsContent>
    </Tabs>
  </div>
</template>
