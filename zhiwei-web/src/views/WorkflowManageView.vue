<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ArrowLeft, ChevronDown, ChevronRight, Copy, Edit2, PauseCircle, Play, Plus, Sparkles, Trash2, Workflow } from 'lucide-vue-next'
import { useWorkflowExecutionStream } from '@/composables/useWorkflowExecutionStream'
import { useWorkflowStore } from '@/stores/workflow'
import { useUiStore } from '@/stores/ui'
import type { WorkflowDetail, WorkflowInputParam, WorkflowItem } from '@/types'
import { stateConfig } from '@/constants/workflowState'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import MetricCard from '@/components/common/MetricCard.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import Pagination from '@/components/common/Pagination.vue'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import PageSection from '@/components/layout/PageSection.vue'
import WorkflowForm from '@/components/workflow/WorkflowForm.vue'
import WorkflowInputDialog from '@/components/workflow/WorkflowInputDialog.vue'
import ExecutionDetail from '@/components/workflow/ExecutionDetail.vue'
import StepDetailCard from '@/components/workflow/StepDetailCard.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'

const store = useWorkflowStore()
const uiStore = useUiStore()
const {
  connectWorkflow,
  connectInstance,
  disconnectInstance,
  disconnectAll,
} = useWorkflowExecutionStream()

const activeTab = ref<'detail' | 'executions'>('detail')
const triggerLoading = ref(false)
const showForm = ref(false)
const showInputDialog = ref(false)
const formMode = ref<'create' | 'edit' | 'duplicate'>('create')
const selectedWorkflow = ref<WorkflowDetail | null>(null)
const showDeleteConfirm = ref(false)
const expandedExecId = ref<string | null>(null)
const triggerFormError = ref<string | null>(null)
const triggerFieldErrors = ref<Record<string, string>>({})

const EXEC_PAGE_SIZE = 10
const execPage = ref(0)

const enabledWorkflowCount = computed(() => store.list.filter(item => item.enabled).length)
const disabledWorkflowCount = computed(() => store.list.filter(item => !item.enabled).length)
const triggerTypeCount = computed(() => {
  const triggerTypes = new Set(store.list.flatMap(item => item.triggerTypes ?? []))
  return triggerTypes.size
})
const stepCount = computed(() => store.current?.steps?.length ?? 0)
const currentTriggerTypes = computed(() => store.current?.triggerTypes ?? [])
const currentSteps = computed(() => store.current?.steps ?? [])
const currentInputs = computed<Record<string, WorkflowInputParam>>(() => store.current?.inputs ?? {})
const currentInputEntries = computed(() => (
  Object.entries(currentInputs.value).sort(([leftKey, leftParam], [rightKey, rightParam]) => {
    if (leftParam.required !== rightParam.required) {
      return Number(rightParam.required) - Number(leftParam.required)
    }

    return leftKey.localeCompare(rightKey)
  })
))
const requiredInputCount = computed(() => currentInputEntries.value.filter(([, input]) => input.required).length)
const showTriggerInputGuide = computed(() => (
  Boolean(store.current)
  && currentTriggerTypes.value.includes('manual')
  && currentInputEntries.value.length > 0
))
const showWorkflowWarning = computed(() => {
  if (!store.current || !store.error) return false
  return !store.error.includes('缺少必填输入参数')
})

const pagedExecutions = computed(() => {
  const start = execPage.value * EXEC_PAGE_SIZE
  return store.executions.slice(start, start + EXEC_PAGE_SIZE)
})

const execPageCount = computed(() => Math.ceil(store.executions.length / EXEC_PAGE_SIZE))
const showExecPagination = computed(() => store.executions.length > EXEC_PAGE_SIZE)

const stateBadge = Object.fromEntries(
  Object.entries(stateConfig).map(([key, value]) => [
    key,
    { label: value.label, variant: value.badgeVariant },
  ]),
) as Record<string, { label: string; variant: 'default' | 'secondary' | 'outline' | 'destructive' }>

function formatDuration(start?: string, end?: string) {
  if (!start || !end) return '-'
  const durationMs = new Date(end).getTime() - new Date(start).getTime()
  if (durationMs < 1000) return `${durationMs}ms`
  return `${(durationMs / 1000).toFixed(1)}s`
}

function handleTabChange(value: string | number) {
  const nextValue = String(value)
  if (nextValue === 'executions' && store.current) {
    void showExecutions(store.current.id)
    return
  }

  disconnectInstance()
  activeTab.value = nextValue === 'executions' ? 'executions' : 'detail'
}

function toggleExecDetail(execId: string) {
  if (expandedExecId.value === execId) {
    expandedExecId.value = null
    disconnectInstance()
    return
  }

  expandedExecId.value = execId
  connectInstance(execId)
}

function getInputLabel(key: string, input: WorkflowInputParam) {
  return input.name || key
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
  store.error = null
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

  const missingNames = extractMissingInputNames(message)
  triggerFormError.value = '还缺少必填参数，请补全后再触发。'
  triggerFieldErrors.value = missingNames.reduce<Record<string, string>>((result, key) => {
    const input = currentInputs.value[key]
    result[key] = `${input?.name || key}为必填项`
    return result
  }, {})
  showInputDialog.value = true
  store.error = null

  return true
}

async function selectWorkflow(workflow: WorkflowItem) {
  disconnectInstance()
  store.setExecutions([])
  store.clearExecutionDetails()
  await store.fetchDetail(workflow.id)
  connectWorkflow(workflow.id)
  activeTab.value = 'detail'
  clearTriggerValidation()
}

function backToList() {
  store.current = null
  store.setExecutions([])
  store.clearExecutionDetails()
  expandedExecId.value = null
  execPage.value = 0
  showInputDialog.value = false
  clearTriggerValidation()
  disconnectAll()
}

async function handleToggle(id: string, enabled: boolean) {
  if (enabled) {
    await store.disable(id)
  } else {
    await store.enable(id)
  }
}

async function handleTrigger(id: string) {
  if (currentInputEntries.value.length > 0) {
    openInputDialog()
    return
  }

  await doTrigger(id)
}

async function doTrigger(id: string, inputs?: Record<string, unknown>) {
  triggerLoading.value = true
  clearTriggerValidation()

  try {
    const execution = await store.trigger(id, inputs)
    showInputDialog.value = false
    connectWorkflow(id)
    activeTab.value = 'executions'
    execPage.value = 0
    expandedExecId.value = execution.id
    connectInstance(execution.id)
    uiStore.showToast('success', '工作流已触发')
  } catch (error: any) {
    const message = error?.message || store.error || '触发工作流失败'
    if (!applyTriggerValidationError(message)) {
      uiStore.showToast('error', message)
    }
  } finally {
    triggerLoading.value = false
  }
}

async function showExecutions(id: string) {
  activeTab.value = 'executions'
  execPage.value = 0
  expandedExecId.value = null
  disconnectInstance()
  connectWorkflow(id)
}

function openCreate() {
  formMode.value = 'create'
  selectedWorkflow.value = null
  showForm.value = true
}

function openEdit() {
  if (!store.current) return
  formMode.value = 'edit'
  selectedWorkflow.value = store.current
  showForm.value = true
}

function openDuplicate() {
  if (!store.current) return
  formMode.value = 'duplicate'
  selectedWorkflow.value = store.current
  showForm.value = true
}

function requestDelete() {
  if (!store.current) return
  showDeleteConfirm.value = true
}

async function confirmDelete() {
  if (!store.current) return
  const workflow = store.current
  showDeleteConfirm.value = false
  await store.remove(workflow.id)
  backToList()
  await store.fetchList()
}

async function onWorkflowSaved(workflow: WorkflowDetail) {
  await store.fetchDetail(workflow.id)
  connectWorkflow(workflow.id)
  showForm.value = false
}

onMounted(() => {
  void store.fetchList()
})

onBeforeUnmount(() => {
  disconnectAll()
})
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-6 sm:py-8">
      <div class="page-stack">
        <template v-if="!store.current">
          <PageHeader
            eyebrow="工作流"
            title="工作流目录"
            description="先判断哪些流程正在可用、哪些已经停用，再进入详情页继续编辑步骤、触发器和执行记录。"
          >
            <template #actions>
              <Button @click="openCreate">
                <Plus class="size-4" />
                新建工作流
              </Button>
            </template>

            <template #meta>
              <MetricCard label="全部工作流" :value="store.list.length" hint="当前目录中可继续维护和查看的全部流程。">
                <template #icon>
                  <Workflow class="size-5" />
                </template>
              </MetricCard>
              <MetricCard label="已启用" :value="enabledWorkflowCount" hint="当前可以直接触发或进入自动执行的流程。">
                <template #icon>
                  <Play class="size-5" />
                </template>
              </MetricCard>
              <MetricCard label="已停用" :value="disabledWorkflowCount" hint="暂时下线或等待重新整理的流程。">
                <template #icon>
                  <PauseCircle class="size-5" />
                </template>
              </MetricCard>
              <MetricCard label="触发器类型" :value="triggerTypeCount" hint="目录里当前正在使用的触发方式种类。">
                <template #icon>
                  <Sparkles class="size-5" />
                </template>
              </MetricCard>
            </template>
          </PageHeader>

          <StatePanel
            v-if="store.error && !store.loading"
            title="工作流加载失败"
            :description="store.error"
            tone="danger"
          >
            <template #icon>
              <Workflow class="size-5" />
            </template>
            <template #actions>
              <Button variant="outline" @click="store.fetchList()">
                重试
              </Button>
            </template>
          </StatePanel>

          <PageSection
            v-else
            eyebrow="目录"
            title="可用工作流"
            description="浏览工作流定义和触发器类型，选择要继续处理的流程。"
          >
            <div v-if="store.loading" class="grid gap-4 xl:grid-cols-2">
              <div
                v-for="index in 4"
                :key="index"
                class="rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/72 p-5"
              >
                <div class="space-y-3">
                  <Skeleton class="h-5 w-40" />
                  <Skeleton class="h-4 w-full" />
                  <Skeleton class="h-4 w-2/3" />
                  <div class="flex gap-2">
                    <Skeleton class="h-6 w-20 rounded-full" />
                    <Skeleton class="h-6 w-16 rounded-full" />
                  </div>
                </div>
              </div>
            </div>

            <StatePanel
              v-else-if="store.list.length === 0"
              title="还没有工作流"
              description="先创建一个工作流，再补充步骤、触发器和执行设置。"
            >
              <template #icon>
                <Workflow class="size-5" />
              </template>
              <template #actions>
                <Button @click="openCreate">
                  新建工作流
                </Button>
              </template>
            </StatePanel>

            <div v-else class="grid gap-4 xl:grid-cols-2">
              <article
                v-for="workflow in store.list"
                :key="workflow.id"
                class="list-card cursor-pointer p-5"
                @click="selectWorkflow(workflow)"
              >
                <div class="flex items-start justify-between gap-4">
                  <div class="space-y-3">
                    <div class="flex flex-wrap items-center gap-2">
                      <h3 class="text-base font-semibold text-foreground">
                        {{ workflow.name }}
                      </h3>
                      <Badge :variant="workflow.enabled ? 'default' : 'secondary'">
                        {{ workflow.enabled ? '已启用' : '已禁用' }}
                      </Badge>
                      <Badge variant="outline">
                        v{{ workflow.version }}
                      </Badge>
                    </div>
                    <p class="text-sm leading-6 text-muted-foreground">
                      {{ workflow.description || '这个工作流还没有说明。' }}
                    </p>
                    <div class="flex flex-wrap gap-2 text-xs text-muted-foreground">
                      <span class="surface-chip">触发器 {{ workflow.triggerTypes?.length ?? 0 }}</span>
                      <span class="surface-chip">版本 v{{ workflow.version }}</span>
                      <span class="surface-chip">{{ workflow.enabled ? '可直接触发' : '需先启用' }}</span>
                    </div>
                    <div v-if="workflow.triggerTypes?.length" class="flex flex-wrap gap-2">
                      <Badge
                        v-for="trigger in workflow.triggerTypes ?? []"
                        :key="`${workflow.id}-${trigger}`"
                        variant="outline"
                      >
                        {{ trigger }}
                      </Badge>
                    </div>
                  </div>
                </div>

                <div class="mt-4 flex items-center justify-between gap-3 border-t border-border/60 pt-4">
                  <div class="text-xs text-muted-foreground">
                    从目录进入后，可继续查看步骤定义和执行历史。
                  </div>
                  <Button variant="ghost" size="sm">
                    查看详情
                  </Button>
                </div>
              </article>
            </div>
          </PageSection>
        </template>

        <template v-else>
          <header class="space-y-5 border-b border-border/70 pb-6">
            <div class="flex flex-col gap-5 xl:flex-row xl:items-end xl:justify-between">
              <div class="space-y-3">
                <div class="surface-label">工作流</div>
                <div class="space-y-2">
                  <div class="flex flex-wrap items-center gap-2">
                    <h1 class="text-3xl font-semibold tracking-tight text-foreground">
                      {{ store.current.name }}
                    </h1>
                    <Badge :variant="store.current.enabled ? 'default' : 'secondary'">
                      {{ store.current.enabled ? '已启用' : '已禁用' }}
                    </Badge>
                    <Badge variant="outline">
                      v{{ store.current.version }}
                    </Badge>
                  </div>
                  <p class="max-w-[50rem] text-sm leading-7 text-muted-foreground">
                    {{ store.current.description || '查看工作流步骤、触发器和执行记录。' }}
                  </p>
                </div>
              </div>

              <div class="flex flex-wrap items-center gap-3">
                <Button variant="outline" @click="backToList">
                  <ArrowLeft class="size-4" />
                  返回列表
                </Button>
                <Button variant="outline" @click="handleToggle(store.current.id, store.current.enabled)">
                  {{ store.current.enabled ? '禁用' : '启用' }}
                </Button>
                <Button :disabled="!store.current.enabled || triggerLoading" @click="handleTrigger(store.current.id)">
                  <Play class="size-4" />
                  {{ triggerLoading ? '触发中...' : '立即触发' }}
                </Button>
                <Button variant="outline" @click="openEdit">
                  <Edit2 class="size-4" />
                  编辑
                </Button>
                <Button variant="outline" @click="openDuplicate">
                  <Copy class="size-4" />
                  复制
                </Button>
                <Button variant="destructive" @click="requestDelete">
                  <Trash2 class="size-4" />
                  删除
                </Button>
              </div>
            </div>

            <section class="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
              <MetricCard label="版本" :value="`v${store.current.version}`" hint="当前正在查看的工作流版本。">
                <template #icon>
                  <Copy class="size-5" />
                </template>
              </MetricCard>
              <MetricCard label="步骤" :value="stepCount" hint="当前定义里已经编排好的步骤数量。">
                <template #icon>
                  <Workflow class="size-5" />
                </template>
              </MetricCard>
              <MetricCard label="触发器" :value="currentTriggerTypes.length" hint="这条流程现在可接收的触发方式。">
                <template #icon>
                  <Sparkles class="size-5" />
                </template>
                <div v-if="currentTriggerTypes.length > 0" class="flex flex-wrap gap-2">
                  <span
                    v-for="trigger in currentTriggerTypes.slice(0, 3)"
                    :key="`${store.current.id}-metric-${trigger}`"
                    class="surface-chip"
                  >
                    {{ trigger }}
                  </span>
                </div>
              </MetricCard>
              <MetricCard label="已加载记录" :value="store.executions.length" hint="当前页已经拉取到的执行历史数量。">
                <template #icon>
                  <Play class="size-5" />
                </template>
              </MetricCard>
            </section>
          </header>

          <StatePanel
            v-if="showWorkflowWarning"
            title="工作流数据存在警告"
            :description="store.error || undefined"
            tone="warning"
          >
            <template #icon>
              <Workflow class="size-5" />
            </template>
          </StatePanel>

          <StatePanel
            v-if="showTriggerInputGuide"
            title="手动触发前需要先填写参数"
            :description="`这个工作流包含 ${currentInputEntries.length} 个输入参数，其中 ${requiredInputCount} 个为必填。点击“立即触发”会先打开参数表单。`"
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
                v-for="[key, input] in currentInputEntries"
                :key="key"
                class="rounded-2xl border border-amber-200/70 bg-background/78 p-4"
              >
                <div class="flex flex-wrap items-center gap-2">
                  <span class="text-sm font-medium text-foreground">{{ getInputLabel(key, input) }}</span>
                  <Badge variant="outline">{{ getInputTypeLabel(input.type) }}</Badge>
                  <Badge v-if="input.required" variant="secondary">必填</Badge>
                  <Badge v-else variant="outline">可选</Badge>
                </div>
                <p v-if="input.description" class="mt-2 text-sm leading-6 text-muted-foreground">
                  {{ input.description }}
                </p>
                <p v-if="formatInputDefaultValue(input.defaultValue)" class="mt-2 text-xs text-muted-foreground">
                  默认值：{{ formatInputDefaultValue(input.defaultValue) }}
                </p>
              </div>
            </div>
          </StatePanel>

          <Tabs :model-value="activeTab" @update:model-value="handleTabChange">
            <TabsList>
              <TabsTrigger value="detail">
                详情
              </TabsTrigger>
              <TabsTrigger value="executions">
                执行记录
              </TabsTrigger>
            </TabsList>

            <TabsContent value="detail" class="mt-5 space-y-5">
              <PageSection
                eyebrow="信息"
                title="定义摘要"
                description="先看说明、标识信息和触发器，再进入步骤细节。"
              >
                <div class="grid gap-4 xl:grid-cols-[minmax(0,1.15fr)_minmax(280px,0.85fr)]">
                  <div class="detail-card p-4">
                    <div class="surface-label mb-2 text-[0.68rem]">说明</div>
                    <p class="text-sm leading-7 text-foreground">
                      {{ store.current.description || '这个工作流还没有说明。' }}
                    </p>
                  </div>

                  <div class="grid gap-3">
                    <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/58 px-4 py-3">
                      <div class="mb-1 text-sm font-medium text-foreground">工作流 ID</div>
                      <p class="break-all font-mono text-sm text-muted-foreground">{{ store.current.id }}</p>
                    </div>
                    <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/58 px-4 py-3">
                      <div class="mb-1 text-sm font-medium text-foreground">状态</div>
                      <p class="text-sm text-muted-foreground">{{ store.current.enabled ? '已启用' : '已禁用' }}</p>
                    </div>
                    <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/58 px-4 py-3">
                      <div class="mb-1 text-sm font-medium text-foreground">触发器</div>
                      <div class="flex flex-wrap gap-2">
                        <Badge
                          v-for="trigger in currentTriggerTypes"
                          :key="`${store.current.id}-${trigger}`"
                          variant="outline"
                        >
                          {{ trigger }}
                        </Badge>
                      </div>
                    </div>
                  </div>
                </div>
              </PageSection>

              <PageSection
                eyebrow="步骤"
                :title="`工作流步骤（${currentSteps.length}）`"
                description="逐步查看工作流里的每个节点和配置。"
              >
                <StatePanel
                  v-if="currentSteps.length === 0"
                  title="还没有定义步骤"
                  description="当前工作流详情中还没有可执行步骤。"
                >
                  <template #icon>
                    <Sparkles class="size-5" />
                  </template>
                </StatePanel>

                <div v-else class="space-y-3">
                  <StepDetailCard
                    v-for="(step, index) in currentSteps"
                    :key="(step as any).id || index"
                    :step="step"
                    :index="index"
                  />
                </div>
              </PageSection>
            </TabsContent>

            <TabsContent value="executions" class="mt-5 space-y-5">
              <PageSection
                eyebrow="历史记录"
                title="执行历史"
                description="查看最近执行记录，按需展开某次执行的详细信息。"
              >
                <StatePanel
                  v-if="store.executions.length === 0"
                  title="暂无执行历史"
                  description="可以手动触发工作流，或等待已配置的触发器自动运行。"
                >
                  <template #icon>
                    <Play class="size-5" />
                  </template>
                </StatePanel>

                <div v-else class="space-y-4">
                  <article
                    v-for="execution in pagedExecutions"
                    :key="execution.id"
                    class="overflow-hidden rounded-[calc(var(--radius)+6px)] border border-border/70 bg-background/72"
                  >
                    <button
                      type="button"
                      class="flex w-full items-start justify-between gap-4 p-5 text-left"
                      @click="toggleExecDetail(execution.id)"
                    >
                      <div class="flex min-w-0 items-start gap-3">
                        <component
                          :is="expandedExecId === execution.id ? ChevronDown : ChevronRight"
                          class="mt-0.5 size-4 shrink-0 text-muted-foreground"
                        />
                        <div class="space-y-2">
                          <div class="flex flex-wrap items-center gap-2">
                            <Badge :variant="stateBadge[execution.state]?.variant ?? 'secondary'">
                              {{ stateBadge[execution.state]?.label ?? execution.state }}
                            </Badge>
                            <Badge v-if="execution.state === 'PAUSED'" variant="outline">
                              等待审批
                            </Badge>
                          </div>
                          <div class="text-sm text-muted-foreground">
                            步骤 {{ execution.completedStepIds?.length ?? 0 }} / {{ currentSteps.length }}
                          </div>
                        </div>
                      </div>
                      <div class="space-y-1 text-right text-xs text-muted-foreground">
                        <div>{{ new Date(execution.createdAt).toLocaleString() }}</div>
                        <div v-if="execution.startedAt && execution.completedAt">
                          {{ formatDuration(execution.startedAt, execution.completedAt) }}
                        </div>
                      </div>
                    </button>

                    <div v-if="expandedExecId === execution.id" class="border-t border-border/60 p-5 pt-4">
                      <ExecutionDetail
                        :execution="execution"
                        :total-steps="currentSteps.length"
                        :steps="store.current?.steps"
                      />
                    </div>
                  </article>

                  <Pagination
                    v-if="showExecPagination"
                    :page="execPage"
                    :page-count="execPageCount"
                    size="sm"
                    @change="execPage = $event"
                  />
                </div>
              </PageSection>
            </TabsContent>
          </Tabs>
        </template>
      </div>
    </PageContainer>

    <WorkflowInputDialog
      :open="showInputDialog"
      :inputs="currentInputs"
      :loading="triggerLoading"
      :field-errors="triggerFieldErrors"
      :form-error="triggerFormError"
      @update:open="showInputDialog = $event"
      @confirm="doTrigger(store.current!.id, $event)"
      @change="handleTriggerFieldChange"
    />

    <WorkflowForm
      v-if="showForm"
      :workflow="selectedWorkflow"
      :mode="formMode"
      @close="showForm = false"
      @saved="onWorkflowSaved"
    />

    <ConfirmDialog
      v-if="showDeleteConfirm"
      :show="showDeleteConfirm"
      title="删除工作流"
      :message="`确定删除工作流 ${store.current?.name} 吗？此操作不可恢复。`"
      confirm-label="删除"
      confirm-variant="destructive"
      @confirm="confirmDelete"
      @cancel="showDeleteConfirm = false"
      @update:show="showDeleteConfirm = $event"
    />
  </div>
</template>
