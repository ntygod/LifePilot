<script setup lang="ts">
import { computed, ref } from 'vue'
import { ListOrdered, Timer, Eye } from 'lucide-vue-next'
import type { WorkflowExecution } from '@/types'
import { getStateConfig } from '@/constants/workflowState'
import { useWorkflowStore } from '@/stores/workflow'
import { workflowApi } from '@/api/client'
import ApprovalPanel from '@/components/workflow/ApprovalPanel.vue'
import EventTimeline from '@/components/workflow/EventTimeline.vue'
import StepDagView from '@/components/workflow/StepDagView.vue'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog'

const props = defineProps<{
  execution: WorkflowExecution
  totalSteps: number
  steps?: unknown[]
}>()

const store = useWorkflowStore()
const activeTab = ref('overview')
const showContextDialog = ref(false)
const executionContext = ref<Record<string, unknown> | null>(null)
const contextLoading = ref(false)

const currentState = computed(() => getStateConfig(props.execution.state))
const progressText = computed(() => `${props.execution.completedStepIds?.length ?? 0} / ${props.totalSteps}`)
const timeline = computed(() => store.getEventTimeline(props.execution.id))
const timelineLoading = computed(() => (
  activeTab.value === 'timeline' && !store.hasEventTimeline(props.execution.id)
))

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

function onTabChange(tab: string | number) {
  activeTab.value = String(tab)
}

async function loadContext() {
  contextLoading.value = true
  try {
    executionContext.value = await workflowApi.getExecutionContext(props.execution.id)
  } catch (e) {
    console.error('加载上下文失败:', e)
  } finally {
    contextLoading.value = false
  }
}

function openContextDialog() {
  showContextDialog.value = true
  loadContext()
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

      <!-- 查看上下文按钮 -->
      <div class="mt-2">
        <Button variant="outline" size="sm" @click="openContextDialog">
          <Eye class="size-4 mr-1" />
          查看上下文
        </Button>
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

    <!-- 上下文查看对话框 -->
    <Dialog v-model:open="showContextDialog">
      <DialogContent class="max-w-3xl max-h-[80vh] overflow-auto">
        <DialogHeader>
          <DialogTitle>执行上下文</DialogTitle>
          <DialogDescription>
            工作流实例 {{ execution.id }} 的完整上下文数据
          </DialogDescription>
        </DialogHeader>
        <div v-if="contextLoading" class="py-8 text-center text-muted-foreground">
          加载中...
        </div>
        <pre v-else-if="executionContext" class="rounded-lg bg-muted p-4 text-xs overflow-auto max-h-[60vh]">{{ JSON.stringify(executionContext, null, 2) }}</pre>
        <div v-else class="py-8 text-center text-muted-foreground">
          暂无上下文数据
        </div>
      </DialogContent>
    </Dialog>
  </div>
</template>
