<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft,
  ChevronDown,
  ChevronRight,
  Clock3,
  FileCode2,
  GitBranch,
  Play,
  Rows3,
  ShieldCheck,
  TimerReset,
  Workflow,
} from 'lucide-vue-next'
import { workflowApi } from '@/api/client'
import MetricCard from '@/components/common/MetricCard.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import Breadcrumb from '@/components/global/Breadcrumb.vue'
import type { BreadcrumbItem } from '@/components/global/Breadcrumb.vue'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import PageSection from '@/components/layout/PageSection.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useUiStore } from '@/stores/ui'
import { useWorkflowStore } from '@/stores/workflow'
import { stateConfig as stateConfigMap } from '@/constants/workflowState'

const YamlEditor = defineAsyncComponent(() => import('@/components/editor/YamlEditor.vue'))

const route = useRoute()
const router = useRouter()
const workflowStore = useWorkflowStore()
const uiStore = useUiStore()

const workflowId = computed(() => route.params.id as string)
const workflow = computed(() => workflowStore.current)

const pageLoading = ref(true)
const pageError = ref<string | null>(null)
const activeTab = ref<'detail' | 'executions'>('detail')
const triggerLoading = ref(false)
const yamlLoading = ref(false)
const yamlDefinition = ref('')

const breadcrumbItems = computed<BreadcrumbItem[]>(() => [
  { label: '工作流', to: { name: 'workflows' } },
  { label: workflow.value?.name ?? '详情' },
])

const stateConfig = stateConfigMap

/** 记录每个执行实例的展开状态 */
const expandedExecutions = ref<Set<string>>(new Set())

/** 切换执行实例的展开/折叠 */
async function toggleExecution(instanceId: string) {
  if (expandedExecutions.value.has(instanceId)) {
    expandedExecutions.value.delete(instanceId)
  } else {
    expandedExecutions.value.add(instanceId)
    // 并行加载事件时间线和步骤日志
    await Promise.all([
      workflowStore.fetchEventTimeline(instanceId),
      workflowStore.fetchStepLogs(instanceId),
    ])
  }
}

/** 解析事件 dataJson */
function parseEventData(dataJson?: string): Record<string, unknown> | null {
  if (!dataJson) return null
  try {
    return JSON.parse(dataJson)
  } catch {
    return null
  }
}

/** 事件类型中文映射 */
const eventTypeLabels: Record<string, string> = {
  INSTANCE_CREATED: '实例创建',
  INSTANCE_STATE_CHANGED: '状态变更',
  STEP_STARTED: '步骤开始',
  STEP_COMPLETED: '步骤完成',
  STEP_FAILED: '步骤失败',
  STEP_SKIPPED: '步骤跳过',
  APPROVAL_REQUESTED: '审批请求',
  APPROVAL_DECIDED: '审批决定',
}

/** 步骤日志状态样式 */
const stepLogStateClass: Record<string, string> = {
  COMPLETED: 'bg-green-100 text-green-800',
  FAILED: 'bg-red-100 text-red-800',
  SKIPPED: 'bg-gray-100 text-gray-800',
}

const stepLogStateLabel: Record<string, string> = {
  COMPLETED: '成功',
  FAILED: '失败',
  SKIPPED: '跳过',
}

const stateBadge = Object.fromEntries(
  Object.entries(stateConfig).map(([key, value]) => [key, { label: value.label, variant: value.badgeVariant }]),
) as Record<string, { label: string; variant: 'default' | 'secondary' | 'outline' | 'destructive' }>

const stepCount = computed(() => workflow.value?.steps?.length ?? 0)
const triggerCount = computed(() => workflow.value?.triggerTypes?.length ?? 0)
const approvalStepCount = computed(() => (
  workflow.value?.steps?.filter(step => (step as any).type === 'ApprovalStep' || (step as any).message).length ?? 0
))

onMounted(async () => {
  await loadData()
})

watch(() => route.params.id, async () => {
  activeTab.value = 'detail'
  await loadData()
})

async function loadData() {
  pageLoading.value = true
  pageError.value = null
  yamlDefinition.value = ''

  try {
    await workflowStore.fetchDetail(workflowId.value)

    if (!workflow.value) {
      pageError.value = workflowStore.error || '加载工作流详情失败。'
      return
    }

    await loadYamlDefinition()
  } finally {
    pageLoading.value = false
  }
}

async function loadYamlDefinition() {
  yamlLoading.value = true
  try {
    yamlDefinition.value = await workflowApi.getWorkflowYaml(workflowId.value)
  } finally {
    yamlLoading.value = false
  }
}

async function handleSaveYaml(content: string) {
  await workflowApi.updateWorkflowYaml(workflowId.value, content)
  await workflowStore.fetchDetail(workflowId.value)
}

async function handleToggle() {
  if (!workflow.value) return

  try {
    if (workflow.value.enabled) {
      await workflowStore.disable(workflow.value.id)
      uiStore.showToast('success', '工作流已禁用')
    } else {
      await workflowStore.enable(workflow.value.id)
      uiStore.showToast('success', '工作流已启用')
    }
  } catch (event: any) {
    uiStore.showToast('error', event?.message || workflowStore.error || '更新工作流状态失败。')
  }
}

async function handleTrigger() {
  if (!workflow.value || triggerLoading.value) return

  triggerLoading.value = true
  try {
    await workflowStore.trigger(workflow.value.id)
    await workflowStore.fetchExecutions(workflow.value.id)
    activeTab.value = 'executions'
    uiStore.showToast('success', '工作流已触发')
  } catch (event: any) {
    uiStore.showToast('error', event?.message || workflowStore.error || '触发工作流失败。')
  } finally {
    triggerLoading.value = false
  }
}

async function showExecutions() {
  if (!workflow.value) return
  activeTab.value = 'executions'
  await workflowStore.fetchExecutions(workflow.value.id)
}

function formatDuration(start?: string, end?: string) {
  if (!start || !end) return '-'
  const ms = new Date(end).getTime() - new Date(start).getTime()
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

function formatDate(dateStr?: string) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString('zh-CN')
}

function goBack() {
  router.push('/workflows')
}
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-6 sm:py-8">
      <div class="page-stack">
        <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <Breadcrumb :items="breadcrumbItems" class="min-w-0" />
          <Button type="button" variant="ghost" class="w-fit" @click="goBack">
            <ArrowLeft class="size-4" />
            返回工作流列表
          </Button>
        </div>

        <template v-if="pageLoading">
          <div class="space-y-5">
            <div class="space-y-3 border-b border-border/70 pb-6">
              <Skeleton class="h-5 w-20" />
              <Skeleton class="h-10 w-72" />
              <Skeleton class="h-5 w-full max-w-[42rem]" />
            </div>
            <div class="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
              <Skeleton v-for="index in 4" :key="index" class="h-28 rounded-[calc(var(--radius)+6px)]" />
            </div>
            <Skeleton class="h-[240px] rounded-[calc(var(--radius)+6px)]" />
            <Skeleton class="h-[320px] rounded-[calc(var(--radius)+6px)]" />
          </div>
        </template>

        <StatePanel
          v-else-if="pageError || !workflow"
          title="工作流详情暂时不可用"
          :description="pageError || workflowStore.error || '没有找到对应的工作流。'"
          tone="danger"
        >
          <template #icon>
            <Workflow class="size-5" />
          </template>
          <template #actions>
            <Button type="button" variant="outline" @click="goBack">
              返回列表
            </Button>
            <Button type="button" @click="loadData">
              重试
            </Button>
          </template>
        </StatePanel>

        <template v-else>
          <PageHeader
            eyebrow="工作流详情"
            :title="workflow.name"
            :description="workflow.description || '查看触发方式、步骤内容和最近执行历史。'"
          >
            <template #actions>
              <Button variant="outline" @click="handleToggle">
                {{ workflow.enabled ? '禁用工作流' : '启用工作流' }}
              </Button>
              <Button :disabled="!workflow.enabled || triggerLoading" @click="handleTrigger">
                <Play class="size-4" />
                {{ triggerLoading ? '触发中...' : '立即触发' }}
              </Button>
            </template>

            <template #meta>
              <MetricCard label="状态" :value="workflow.enabled ? '已启用' : '已禁用'" hint="关闭后将不会被新的触发器拉起。">
                <template #icon>
                  <ShieldCheck class="size-5" />
                </template>
              </MetricCard>
              <MetricCard label="触发器" :value="triggerCount" hint="当前声明的触发器类型数量。">
                <template #icon>
                  <GitBranch class="size-5" />
                </template>
              </MetricCard>
              <MetricCard label="步骤数" :value="stepCount" hint="用来衡量当前编排规模和复杂度。">
                <template #icon>
                  <Rows3 class="size-5" />
                </template>
              </MetricCard>
              <MetricCard label="已加载执行记录" :value="workflowStore.executions.length" hint="当前页面已经拉到本地的执行历史数量。">
                <template #icon>
                  <Clock3 class="size-5" />
                </template>
              </MetricCard>
            </template>
          </PageHeader>

          <Tabs
            :model-value="activeTab"
            class="space-y-5"
            @update:model-value="(value) => { const nextValue = String(value); if (nextValue === 'executions') showExecutions(); else activeTab = nextValue as 'detail' | 'executions' }"
          >
            <TabsList class="inline-flex h-auto flex-wrap rounded-full border border-border/70 bg-muted/55 p-1">
              <TabsTrigger value="detail">定义</TabsTrigger>
              <TabsTrigger value="executions">执行历史</TabsTrigger>
            </TabsList>

            <TabsContent value="detail" class="space-y-5">
              <PageSection
                eyebrow="基础信息"
                title="工作流信息"
                description="先看说明和触发方式，再决定查看步骤还是执行记录。"
              >
                <div class="grid gap-4 lg:grid-cols-[minmax(0,1.15fr)_minmax(260px,0.85fr)]">
                  <div class="detail-card p-4 sm:p-5">
                    <div class="space-y-4">
                      <div>
                        <div class="surface-label mb-2 text-[0.68rem]">说明</div>
                        <p class="text-sm leading-7 text-foreground">
                          {{ workflow.description || '这个工作流还没有说明。' }}
                        </p>
                      </div>

                      <div class="border-t border-border/60 pt-4">
                        <div class="surface-label mb-3 text-[0.68rem]">触发器类型</div>
                        <div class="flex flex-wrap gap-2">
                          <Badge
                            v-for="trigger in workflow.triggerTypes"
                            :key="trigger"
                            variant="outline"
                          >
                            {{ trigger }}
                          </Badge>
                        </div>
                      </div>
                    </div>
                  </div>

                  <div class="rounded-[calc(var(--radius)+2px)] border border-dashed border-border/60 bg-background/48 p-4">
                    <div class="space-y-4">
                      <div>
                        <div class="surface-label text-[0.68rem]">当前版本</div>
                        <p class="mt-2 break-all font-mono text-sm text-muted-foreground">{{ workflow.id }}</p>
                      </div>

                      <div class="flex flex-wrap gap-2 text-xs text-muted-foreground">
                        <span class="surface-chip">版本 v{{ workflow.version }}</span>
                        <span class="surface-chip">审批步骤 {{ approvalStepCount }}</span>
                        <span class="surface-chip">{{ workflow.enabled ? '已启用' : '已禁用' }}</span>
                      </div>

                      <p class="text-sm leading-6 text-muted-foreground">
                        先看触发器和版本信息，再决定是继续读步骤定义，还是切到执行历史排查一次运行。
                      </p>
                    </div>
                  </div>
                </div>
              </PageSection>

              <PageSection
                eyebrow="步骤"
                :title="`编排步骤（${stepCount}）`"
                description="逐步查看每个步骤的类型、依赖和关键配置。"
              >
                <StatePanel
                  v-if="stepCount === 0"
                  title="当前没有步骤"
                  description="这个工作流暂时还没有可执行步骤。"
                >
                  <template #icon>
                    <Rows3 class="size-5" />
                  </template>
                </StatePanel>

                <div v-else class="space-y-3">
                  <article
                    v-for="(step, index) in workflow.steps"
                    :key="index"
                    class="detail-card p-4"
                  >
                    <div class="flex items-start gap-4">
                      <div class="flex size-10 shrink-0 items-center justify-center rounded-2xl border border-border/70 bg-primary text-primary-foreground shadow-sm">
                        {{ index + 1 }}
                      </div>

                      <div class="min-w-0 flex-1 space-y-3">
                        <div class="space-y-1">
                          <div class="text-sm font-medium text-foreground">
                            {{ (step as any).name || (step as any).type || `步骤 ${index + 1}` }}
                          </div>
                          <p v-if="(step as any).description" class="text-sm leading-6 text-muted-foreground">
                            {{ (step as any).description }}
                          </p>
                        </div>

                        <div class="flex flex-wrap gap-2">
                          <Badge v-if="(step as any).type" variant="outline">
                            {{ (step as any).type }}
                          </Badge>
                          <Badge v-if="(step as any).agentId" variant="secondary">
                            智能体 {{ (step as any).agentId }}
                          </Badge>
                          <Badge v-if="(step as any).toolId" variant="secondary">
                            工具 {{ (step as any).toolId }}
                          </Badge>
                          <Badge v-if="(step as any).condition" variant="outline">
                            条件
                          </Badge>
                        </div>

                        <div class="space-y-2 text-sm text-muted-foreground">
                          <div v-if="(step as any).condition">
                            条件：<span class="font-mono text-foreground">{{ (step as any).condition }}</span>
                          </div>
                          <div v-if="(step as any).dependsOn?.length">
                            依赖：<span class="text-foreground">{{ (step as any).dependsOn.join(', ') }}</span>
                          </div>
                          <div v-if="(step as any).message">
                            审批消息：<span class="text-foreground">{{ (step as any).message }}</span>
                          </div>
                          <div v-if="(step as any).approvers?.length">
                            审批人：<span class="text-foreground">{{ (step as any).approvers.join(', ') }}</span>
                          </div>
                          <div v-if="(step as any).timeoutSeconds">
                            超时：<span class="text-foreground">{{ (step as any).timeoutSeconds }}s</span>
                          </div>
                        </div>

                        <details class="rounded-[calc(var(--radius)+4px)] border border-border/60 bg-muted/30 px-4 py-3">
                          <summary class="cursor-pointer text-xs font-medium uppercase tracking-[0.16em] text-muted-foreground">
                            步骤详情数据
                          </summary>
                          <pre class="mt-3 overflow-x-auto text-xs leading-6 text-foreground">{{ JSON.stringify(step, null, 2) }}</pre>
                        </details>
                      </div>
                    </div>
                  </article>
                </div>
              </PageSection>

              <PageSection
                eyebrow="定义"
                title="工作流 YAML"
                description="查看或编辑工作流定义。"
              >
                <Skeleton v-if="yamlLoading" class="h-72 w-full rounded-[calc(var(--radius)+6px)]" />
                <YamlEditor
                  v-else
                  v-model="yamlDefinition"
                  title="工作流 YAML"
                  :on-save="handleSaveYaml"
                />
              </PageSection>
            </TabsContent>

            <TabsContent value="executions" class="space-y-5">
              <PageSection
                eyebrow="执行"
                title="最近执行历史"
                description="查看最近的工作流执行记录。"
              >
                <StatePanel
                  v-if="workflowStore.executions.length === 0"
                  title="还没有执行记录"
                  description="可以手动触发工作流，或等待触发器运行后生成执行历史。"
                >
                  <template #icon>
                    <TimerReset class="size-5" />
                  </template>
                  <template #actions>
                    <Button :disabled="!workflow.enabled || triggerLoading" @click="handleTrigger">
                      触发工作流
                    </Button>
                  </template>
                </StatePanel>

                <div v-else class="space-y-3">
                  <article
                    v-for="execution in workflowStore.executions"
                    :key="execution.id"
                    class="list-card overflow-hidden"
                  >
                    <!-- 执行摘要（可点击展开） -->
                    <div
                      class="flex cursor-pointer flex-col gap-4 p-4 transition-colors hover:bg-muted/30"
                      @click="toggleExecution(execution.id)"
                    >
                      <div class="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                        <div class="space-y-2">
                          <div class="flex flex-wrap items-center gap-2">
                            <component
                              :is="expandedExecutions.has(execution.id) ? ChevronDown : ChevronRight"
                              class="size-4 shrink-0 text-muted-foreground"
                            />
                            <Badge :variant="stateBadge[execution.state]?.variant ?? 'secondary'">
                              {{ stateBadge[execution.state]?.label ?? execution.state }}
                            </Badge>
                            <Badge variant="outline">
                              {{ execution.completedStepIds?.length ?? 0 }} / {{ stepCount }} 步
                            </Badge>
                          </div>
                          <p class="text-sm text-muted-foreground">
                            创建于 {{ formatDate(execution.createdAt) }}
                          </p>
                        </div>

                        <div class="grid gap-1 text-sm text-muted-foreground sm:text-right">
                          <span>开始：{{ formatDate(execution.startedAt) }}</span>
                          <span>完成：{{ formatDate(execution.completedAt) }}</span>
                          <span>耗时：{{ formatDuration(execution.startedAt, execution.completedAt) }}</span>
                        </div>
                      </div>

                      <div v-if="execution.failureReason" class="rounded-[calc(var(--radius)+4px)] border border-destructive/20 bg-destructive/6 px-4 py-3 text-sm text-destructive">
                        {{ execution.failureReason }}
                      </div>
                    </div>

                    <!-- 展开详情：事件时间线 + 步骤日志 -->
                    <div v-if="expandedExecutions.has(execution.id)" class="border-t border-border/60 bg-muted/20 p-4 space-y-5">
                      <!-- 步骤日志 -->
                      <div>
                        <div class="surface-label mb-3 text-[0.68rem]">步骤日志</div>
                        <div v-if="workflowStore.stepLogs.length === 0" class="text-sm text-muted-foreground">
                          暂无步骤日志
                        </div>
                        <div v-else class="space-y-2">
                          <div
                            v-for="log in workflowStore.stepLogs"
                            :key="log.id"
                            class="flex flex-wrap items-center gap-2 rounded-lg border border-border/50 bg-background px-3 py-2 text-sm"
                          >
                            <span class="font-mono text-xs text-muted-foreground">{{ log.stepId }}</span>
                            <Badge variant="outline" class="text-xs">{{ log.stepType }}</Badge>
                            <span
                              class="inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium"
                              :class="stepLogStateClass[log.state] ?? 'bg-gray-100 text-gray-800'"
                            >
                              {{ stepLogStateLabel[log.state] ?? log.state }}
                            </span>
                            <span class="text-xs text-muted-foreground">
                              第 {{ log.attempt }} 次 · {{ log.durationMs }}ms
                            </span>
                            <span v-if="log.errorMessage" class="basis-full text-xs text-destructive">
                              {{ log.errorMessage }}
                            </span>
                          </div>
                        </div>
                      </div>

                      <!-- 事件时间线 -->
                      <div>
                        <div class="surface-label mb-3 text-[0.68rem]">事件时间线</div>
                        <div v-if="workflowStore.eventTimeline.length === 0" class="text-sm text-muted-foreground">
                          暂无事件记录
                        </div>
                        <div v-else class="space-y-1">
                          <div
                            v-for="event in workflowStore.eventTimeline"
                            :key="event.id"
                            class="flex flex-wrap items-baseline gap-2 rounded-md px-3 py-1.5 text-sm odd:bg-muted/30"
                          >
                            <span class="shrink-0 text-xs text-muted-foreground">{{ formatDate(event.createdAt) }}</span>
                            <Badge variant="outline" class="text-xs">{{ eventTypeLabels[event.type] ?? event.type }}</Badge>
                            <span v-if="event.stepId" class="font-mono text-xs text-muted-foreground">{{ event.stepId }}</span>
                            <span v-if="parseEventData(event.dataJson)" class="basis-full font-mono text-xs text-muted-foreground">
                              {{ JSON.stringify(parseEventData(event.dataJson)) }}
                            </span>
                          </div>
                        </div>
                      </div>
                    </div>
                  </article>
                </div>
              </PageSection>
            </TabsContent>
          </Tabs>
        </template>
      </div>
    </PageContainer>
  </div>
</template>
