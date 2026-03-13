<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, onUnmounted, ref, watch } from 'vue'
import * as yaml from 'yaml'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft,
  ChevronDown,
  ChevronRight,
  Clock3,
  Download,
  GitBranch,
  Play,
  Rows3,
  ShieldCheck,
  TimerReset,
  Upload,
  Workflow,
} from 'lucide-vue-next'
import { workflowApi } from '@/api/client'
import MetricCard from '@/components/common/MetricCard.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import { useWorkflowExecutionStream } from '@/composables/useWorkflowExecutionStream'
import Breadcrumb from '@/components/global/Breadcrumb.vue'
import type { BreadcrumbItem } from '@/components/global/Breadcrumb.vue'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import PageSection from '@/components/layout/PageSection.vue'
import EventTimeline from '@/components/workflow/EventTimeline.vue'
import WorkflowInputDialog from '@/components/workflow/WorkflowInputDialog.vue'
import DryRunDialog from '@/components/workflow/DryRunDialog.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { stateConfig as stateConfigMap } from '@/constants/workflowState'
import { useUiStore } from '@/stores/ui'
import { useWorkflowStore } from '@/stores/workflow'
import type { WorkflowInputParam } from '@/types'
import type { WorkflowStats, StepStats } from '@/types'

const YamlEditor = defineAsyncComponent(() => import('@/components/editor/YamlEditor.vue'))

const route = useRoute()
const router = useRouter()
const workflowStore = useWorkflowStore()
const uiStore = useUiStore()
const {
  connectWorkflow,
  connectInstance,
  disconnectInstance,
  disconnectAll,
} = useWorkflowExecutionStream()

const workflowId = computed(() => route.params.id as string)
const workflow = computed(() => workflowStore.current)

const pageLoading = ref(true)
const pageError = ref<string | null>(null)
const activeTab = ref<'detail' | 'executions'>('detail')
const triggerLoading = ref(false)
const yamlLoading = ref(false)
const yamlDefinition = ref('')
const showInputDialog = ref(false)
const showDryRunDialog = ref(false)
const exportLoading = ref(false)
const importLoading = ref(false)
const expandedExecutionId = ref<string | null>(null)
const executionDetailsLoading = ref<Record<string, boolean>>({})
const triggerFormError = ref<string | null>(null)
const triggerFieldErrors = ref<Record<string, string>>({})

// 执行统计
const workflowStats = ref<WorkflowStats | null>(null)
const stepStats = ref<StepStats[]>([])
const statsLoading = ref(false)

async function loadStats() {
  if (!workflowId.value) return
  statsLoading.value = true
  try {
    const [stats, steps] = await Promise.all([
      workflowApi.getStats(workflowId.value),
      workflowApi.getStepStats(workflowId.value)
    ])
    workflowStats.value = stats
    stepStats.value = steps
  } catch (e) {
    console.error('加载统计失败:', e)
  } finally {
    statsLoading.value = false
  }
}

const breadcrumbItems = computed<BreadcrumbItem[]>(() => [
  { label: '工作流', to: { name: 'workflows' } },
  { label: workflow.value?.name ?? '详情' },
])

const stateBadge = Object.fromEntries(
  Object.entries(stateConfigMap).map(([key, value]) => [
    key,
    { label: value.label, variant: value.badgeVariant },
  ]),
) as Record<string, { label: string; variant: 'default' | 'secondary' | 'outline' | 'destructive' }>

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

const stepCount = computed(() => workflow.value?.steps?.length ?? 0)
const triggerCount = computed(() => workflow.value?.triggerTypes?.length ?? 0)
const approvalStepCount = computed(() => (
  workflow.value?.steps?.filter(step => (step as any).type === 'ApprovalStep' || (step as any).message).length ?? 0
))
const inputTypes = new Set<WorkflowInputParam['type']>(['string', 'number', 'boolean', 'list', 'map'])

function normalizeWorkflowInputParam(key: string, value: unknown): WorkflowInputParam | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return null
  }

  const raw = value as Partial<Record<keyof WorkflowInputParam, unknown>>
  const rawType = typeof raw.type === 'string' ? raw.type : 'string'
  const type = inputTypes.has(rawType as WorkflowInputParam['type'])
    ? rawType as WorkflowInputParam['type']
    : 'string'

  return {
    name: typeof raw.name === 'string' && raw.name.trim() !== '' ? raw.name : key,
    type,
    required: Boolean(raw.required),
    defaultValue: raw.defaultValue,
    description: typeof raw.description === 'string' ? raw.description : undefined,
  }
}

function parseWorkflowInputsFromYaml(content: string) {
  if (!content.trim()) return {} as Record<string, WorkflowInputParam>

  try {
    const parsed = yaml.parse(content) as { inputs?: Record<string, unknown> } | null
    const rawInputs = parsed?.inputs
    if (!rawInputs || typeof rawInputs !== 'object' || Array.isArray(rawInputs)) {
      return {} as Record<string, WorkflowInputParam>
    }

    return Object.entries(rawInputs).reduce<Record<string, WorkflowInputParam>>((result, [key, value]) => {
      const normalized = normalizeWorkflowInputParam(key, value)
      if (normalized) {
        result[key] = normalized
      }
      return result
    }, {})
  } catch {
    return {} as Record<string, WorkflowInputParam>
  }
}

const workflowInputs = computed<Record<string, WorkflowInputParam>>(() => {
  const detailInputs = workflow.value?.inputs
  if (detailInputs && Object.keys(detailInputs).length > 0) {
    return detailInputs
  }

  return parseWorkflowInputsFromYaml(yamlDefinition.value)
})

const workflowInputEntries = computed(() => (
  Object.entries(workflowInputs.value).sort(([leftKey, leftParam], [rightKey, rightParam]) => {
    if (leftParam.required !== rightParam.required) {
      return Number(rightParam.required) - Number(leftParam.required)
    }

    return leftKey.localeCompare(rightKey)
  })
))

const requiredInputCount = computed(() => workflowInputEntries.value.filter(([, param]) => param.required).length)
const hasManualTrigger = computed(() => workflow.value?.triggerTypes?.includes('manual') ?? false)
const showManualTriggerGuide = computed(() => hasManualTrigger.value && workflowInputEntries.value.length > 0)

function getInputLabel(key: string, param: WorkflowInputParam) {
  return param.name || key
}

function getInputTypeLabel(type: WorkflowInputParam['type']) {
  switch (type) {
    case 'number':
      return '数字'
    case 'boolean':
      return '开关'
    case 'list':
      return '列表'
    case 'map':
      return '对象'
    default:
      return '文本'
  }
}

function formatInputDefaultValue(value: unknown) {
  if (value === undefined || value === null || value === '') return null
  if (typeof value === 'string') return value

  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

function clearTriggerValidation() {
  triggerFormError.value = null
  triggerFieldErrors.value = {}
}

function openInputDialog() {
  clearTriggerValidation()
  showInputDialog.value = true
}

function handleTriggerFieldChange(key: string) {
  if (triggerFormError.value) {
    triggerFormError.value = null
  }

  if (triggerFieldErrors.value[key]) {
    const nextErrors = { ...triggerFieldErrors.value }
    delete nextErrors[key]
    triggerFieldErrors.value = nextErrors
  }
}

function extractMissingInputNames(message: string) {
  const segments = message.split(/[:：]/, 2)
  const rawNames = segments[1] ?? ''
  return rawNames
    .split(/[，,]/)
    .map(name => name.trim())
    .filter(Boolean)
}

function applyTriggerValidationError(message: string) {
  if (!message.includes('缺少必填输入参数')) return false
  if (workflowInputEntries.value.length === 0) return false

  const missingNames = extractMissingInputNames(message)
  triggerFormError.value = '还缺少必填参数，请补全后再触发。'
  triggerFieldErrors.value = missingNames.reduce<Record<string, string>>((result, key) => {
    const param = workflowInputs.value[key]
    result[key] = `${param?.name || key}为必填项`
    return result
  }, {})
  showInputDialog.value = true

  return true
}

function setExecutionLoading(instanceId: string, loading: boolean) {
  executionDetailsLoading.value = {
    ...executionDetailsLoading.value,
    [instanceId]: loading,
  }
}

function isExecutionLoading(instanceId: string) {
  return executionDetailsLoading.value[instanceId] ?? false
}

function getExecutionStepLogs(instanceId: string) {
  return workflowStore.getStepLogs(instanceId)
}

function getExecutionTimeline(instanceId: string) {
  return workflowStore.getEventTimeline(instanceId)
}

async function loadExecutionDetails(instanceId: string) {
  if (isExecutionLoading(instanceId)) return

  const hasTimeline = workflowStore.hasEventTimeline(instanceId)
  const hasStepLogs = workflowStore.hasStepLogs(instanceId)
  if (hasTimeline && hasStepLogs) {
    connectInstance(instanceId)
    return
  }

  setExecutionLoading(instanceId, true)
  connectInstance(instanceId)
}

function isExecutionExpanded(instanceId: string) {
  return expandedExecutionId.value === instanceId
}

async function toggleExecution(instanceId: string) {
  if (isExecutionExpanded(instanceId)) {
    expandedExecutionId.value = null
    disconnectInstance()
    return
  }

  expandedExecutionId.value = instanceId
  await loadExecutionDetails(instanceId)
}

function resetExecutionUiState() {
  expandedExecutionId.value = null
  executionDetailsLoading.value = {}
  disconnectInstance()
}

async function loadData() {
  pageLoading.value = true
  pageError.value = null
  yamlDefinition.value = ''
  disconnectAll()
  resetExecutionUiState()
  workflowStore.setExecutions([])
  workflowStore.clearExecutionDetails()
  clearTriggerValidation()

  try {
    await workflowStore.fetchDetail(workflowId.value)

    if (!workflow.value) {
      pageError.value = workflowStore.error || '加载工作流详情失败。'
      return
    }

    connectWorkflow(workflow.value.id)
    await loadYamlDefinition()
    await loadStats()
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
  if (workflow.value) {
    connectWorkflow(workflow.value.id)
  }
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
    uiStore.showToast('error', event?.message || workflowStore.error || '更新工作流状态失败')
  }
}

async function handleTrigger() {
  if (!workflow.value || triggerLoading.value) return

  if (workflowInputEntries.value.length > 0) {
    openInputDialog()
    return
  }

  await doTrigger()
}

async function doTrigger(inputs?: Record<string, unknown>) {
  if (!workflow.value) return

  triggerLoading.value = true
  clearTriggerValidation()

  try {
    const execution = await workflowStore.trigger(workflow.value.id, inputs)

    showInputDialog.value = false
    resetExecutionUiState()
    connectWorkflow(workflow.value.id)
    activeTab.value = 'executions'
    expandedExecutionId.value = execution.id
    setExecutionLoading(execution.id, true)
    uiStore.showToast('success', '工作流已触发')

    connectInstance(execution.id)
  } catch (event: any) {
    const message = event?.message || workflowStore.error || '触发工作流失败'
    if (!applyTriggerValidationError(message)) {
      uiStore.showToast('error', message)
    }
  } finally {
    triggerLoading.value = false
  }
}

async function showExecutions() {
  if (!workflow.value) return
  activeTab.value = 'executions'
  resetExecutionUiState()
  connectWorkflow(workflow.value.id)
}

function formatDuration(start?: string, end?: string) {
  if (!start || !end) return '-'
  const ms = new Date(end).getTime() - new Date(start).getTime()
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

function formatDurationMs(ms: number) {
  if (!ms || ms < 0) return '-'
  if (ms < 1000) return `${ms}ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
  return `${(ms / 60000).toFixed(1)}min`
}

function formatDate(dateStr?: string) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString('zh-CN')
}

function goBack() {
  router.push('/workflows')
}

/** 导出当前工作流为 YAML 文件 */
async function handleExport() {
  if (!workflowId.value) return
  exportLoading.value = true
  try {
    const result = await workflowApi.exportWorkflow(workflowId.value)
    const blob = new Blob([result.yamlContent], { type: 'text/yaml' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${result.id}.yaml`
    a.click()
    URL.revokeObjectURL(url)
    uiStore.showToast('success', '已导出工作流')
  } catch (e) {
    console.error('导出失败:', e)
    uiStore.showToast('error', '导出失败')
  } finally {
    exportLoading.value = false
  }
}

/** 导入工作流（选择文件后创建新工作流并跳转） */
function triggerImport() {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = '.yaml,.yml'
  input.onchange = handleImportFile
  input.click()
}

async function handleImportFile(event: Event) {
  const input = event.target as HTMLInputElement
  if (!input.files || input.files.length === 0) return
  importLoading.value = true
  try {
    const content = await input.files[0].text()
    const created = await workflowApi.importWorkflow(content)
    uiStore.showToast('success', '已导入工作流')
    await workflowStore.fetchDetail(created.id)
    router.push({ name: 'workflowDetail', params: { id: created.id } })
  } catch (e) {
    console.error('导入失败:', e)
    uiStore.showToast('error', '导入失败')
  } finally {
    importLoading.value = false
    input.value = ''
  }
}

onMounted(async () => {
  await loadData()
})

onUnmounted(() => disconnectAll())

watch(
  () => {
    const instanceId = expandedExecutionId.value
    return [
      instanceId,
      instanceId ? workflowStore.hasEventTimeline(instanceId) : false,
      instanceId ? workflowStore.hasStepLogs(instanceId) : false,
    ] as const
  },
  ([instanceId, hasTimeline, hasStepLogs]) => {
    if (instanceId && hasTimeline && hasStepLogs) {
      setExecutionLoading(instanceId, false)
    }
  },
  { immediate: true },
)

watch(() => route.params.id, async () => {
  activeTab.value = 'detail'
  await loadData()
})
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-6 sm:py-8">
      <div class="page-stack">
        <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <Breadcrumb :items="breadcrumbItems" class="min-w-0" />
          <Button type="button" variant="ghost" class="w-fit" @click="goBack">
            <ArrowLeft class="size-4" />
            返回列表
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
            eyebrow="工作流"
            :title="workflow.name"
            :description="workflow.description || '查看触发方式、步骤内容和最近执行记录。'"
          >
            <template #actions>
              <div class="flex flex-nowrap items-center gap-2 overflow-x-auto pb-1 md:gap-3">
                <Button variant="outline" size="sm" class="shrink-0" @click="handleToggle">
                  {{ workflow.enabled ? '禁用' : '启用' }}
                </Button>
                <Button variant="outline" size="sm" class="shrink-0" @click="showDryRunDialog = true">
                  <Play class="size-4" />
                  试运行
                </Button>
                <Button size="sm" class="shrink-0" :disabled="!workflow.enabled || triggerLoading" @click="handleTrigger">
                  <Play class="size-4" />
                  {{ triggerLoading ? '触发中...' : '立即触发' }}
                </Button>
                <Button variant="outline" size="sm" class="shrink-0" :disabled="exportLoading" @click="handleExport">
                  <Download class="size-4" />
                  {{ exportLoading ? '导出中...' : '导出' }}
                </Button>
                <Button variant="outline" size="sm" class="shrink-0" :disabled="importLoading" @click="triggerImport">
                  <Upload class="size-4" />
                  {{ importLoading ? '导入中...' : '导入' }}
                </Button>
              </div>
            </template>

            <template #meta>
              <MetricCard
                label="状态"
                :value="workflow.enabled ? '已启用' : '已禁用'"
                hint="关闭后不会再被新的触发器拉起。"
              >
                <template #icon>
                  <ShieldCheck class="size-5" />
                </template>
              </MetricCard>
              <MetricCard
                label="触发器"
                :value="triggerCount"
                hint="当前定义里已编排好的触发方式数量。"
              >
                <template #icon>
                  <GitBranch class="size-5" />
                </template>
              </MetricCard>
              <MetricCard
                label="步骤"
                :value="stepCount"
                hint="当前定义里已编排好的步骤数量。"
              >
                <template #icon>
                  <Rows3 class="size-5" />
                </template>
              </MetricCard>
              <MetricCard
                label="已加载记录"
                :value="workflowStore.executions.length"
                hint="当前页已经拉取到的执行历史数量。"
              >
                <template #icon>
                  <Clock3 class="size-5" />
                </template>
              </MetricCard>
              <!-- 执行统计卡片 -->
              <MetricCard
                v-if="workflowStats"
                label="执行统计"
                :value="`${workflowStats.successCount}/${workflowStats.totalExecutions}`"
                :hint="`成功率 ${((workflowStats.successCount / workflowStats.totalExecutions) * 100).toFixed(1)}%`"
              >
                <template #icon>
                  <TimerReset class="size-5" />
                </template>
              </MetricCard>
              <MetricCard
                v-if="workflowStats"
                label="平均耗时"
                :value="formatDurationMs(workflowStats.avgDurationMs)"
                hint="工作流平均执行时长。"
              >
                <template #icon>
                  <TimerReset class="size-5" />
                </template>
              </MetricCard>
            </template>
          </PageHeader>

          <StatePanel
            v-if="showManualTriggerGuide"
            title="手动触发前需要先填写参数"
            :description="`这个工作流包含 ${workflowInputEntries.length} 个输入参数，其中 ${requiredInputCount} 个为必填。点击“立即触发”会先打开参数表单。`"
            tone="warning"
          >
            <template #icon>
              <Play class="size-5" />
            </template>
            <template #actions>
              <Button :disabled="triggerLoading" @click="openInputDialog">
                填写参数并触发
              </Button>
            </template>

            <div class="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
              <div
                v-for="[key, param] in workflowInputEntries"
                :key="key"
                class="rounded-2xl border border-amber-200/70 bg-background/78 p-4"
              >
                <div class="flex flex-wrap items-center gap-2">
                  <span class="text-sm font-medium text-foreground">{{ getInputLabel(key, param) }}</span>
                  <Badge variant="outline">{{ getInputTypeLabel(param.type) }}</Badge>
                  <Badge v-if="param.required" variant="secondary">必填</Badge>
                  <Badge v-else variant="outline">可选</Badge>
                </div>
                <p v-if="param.description" class="mt-2 text-sm leading-6 text-muted-foreground">
                  {{ param.description }}
                </p>
                <p v-if="formatInputDefaultValue(param.defaultValue)" class="mt-2 text-xs text-muted-foreground">
                  默认值：{{ formatInputDefaultValue(param.defaultValue) }}
                </p>
              </div>
            </div>
          </StatePanel>

          <Tabs
            :model-value="activeTab"
            class="space-y-5"
            @update:model-value="(value) => { const nextValue = String(value); if (nextValue === 'executions') void showExecutions(); else activeTab = nextValue as 'detail' | 'executions' }"
          >
            <TabsList class="inline-flex h-auto flex-wrap rounded-full border border-border/70 bg-muted/55 p-1">
              <TabsTrigger value="detail">详情</TabsTrigger>
              <TabsTrigger value="executions">执行记录</TabsTrigger>
            </TabsList>

            <TabsContent value="detail" class="space-y-5">
              <PageSection
                eyebrow="基础信息"
                title="工作流信息"
                description="先看说明和触发方式，再决定要查看步骤定义还是执行记录。"
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
                        先看触发器和版本信息，再决定是继续看步骤定义，还是切到执行历史排查某一次运行。
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
                  description="可以手动触发工作流，或等待触发器运行后生成执行记录。"
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
                    <button
                      type="button"
                      class="flex w-full flex-col gap-4 p-4 text-left transition-colors hover:bg-muted/30"
                      @click="toggleExecution(execution.id)"
                    >
                      <div class="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                        <div class="space-y-2">
                          <div class="flex flex-wrap items-center gap-2">
                            <component
                              :is="isExecutionExpanded(execution.id) ? ChevronDown : ChevronRight"
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

                      <div
                        v-if="execution.failureReason"
                        class="rounded-[calc(var(--radius)+4px)] border border-destructive/20 bg-destructive/6 px-4 py-3 text-sm text-destructive"
                      >
                        {{ execution.failureReason }}
                      </div>
                    </button>

                    <div
                      v-if="isExecutionExpanded(execution.id)"
                      class="space-y-5 border-t border-border/60 bg-muted/20 p-4"
                    >
                      <div>
                        <div class="surface-label mb-3 text-[0.68rem]">步骤日志</div>
                        <div
                          v-if="isExecutionLoading(execution.id) && !workflowStore.hasStepLogs(execution.id)"
                          class="text-sm text-muted-foreground"
                        >
                          正在加载步骤日志...
                        </div>
                        <div
                          v-else-if="getExecutionStepLogs(execution.id).length === 0"
                          class="text-sm text-muted-foreground"
                        >
                          暂无步骤日志
                        </div>
                        <div v-else class="space-y-2">
                          <div
                            v-for="log in getExecutionStepLogs(execution.id)"
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
                              第 {{ log.attempt }} 次 · {{ log.durationMs ?? '-' }}ms
                            </span>
                            <span v-if="log.errorMessage" class="basis-full text-xs text-destructive">
                              {{ log.errorMessage }}
                            </span>
                          </div>
                        </div>
                      </div>

                      <div>
                        <div class="surface-label mb-3 text-[0.68rem]">事件时间线</div>
                        <EventTimeline
                          :events="getExecutionTimeline(execution.id)"
                          :loading="isExecutionLoading(execution.id) && !workflowStore.hasEventTimeline(execution.id)"
                        />
                      </div>
                    </div>
                  </article>
                </div>
              </PageSection>
            </TabsContent>
          </Tabs>

          <WorkflowInputDialog
            :open="showInputDialog"
            :inputs="workflowInputs"
            :loading="triggerLoading"
            :field-errors="triggerFieldErrors"
            :form-error="triggerFormError"
            @update:open="showInputDialog = $event"
            @confirm="doTrigger"
            @change="handleTriggerFieldChange"
          />

          <DryRunDialog
            :open="showDryRunDialog"
            :workflow-id="workflowId"
            :inputs="workflowInputs"
            @update:open="showDryRunDialog = $event"
          />
        </template>
      </div>
    </PageContainer>
  </div>
</template>
