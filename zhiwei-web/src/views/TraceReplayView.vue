<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  Activity,
  ArrowLeft,
  ArrowRight,
  BarChart3,
  Download,
  MessageCircle,
  ServerOff,
  Shield,
  Wrench,
} from 'lucide-vue-next'
import { getApiOrigin } from '@/api/config'
import { SSE_EVENT_TYPES } from '@/constants/sseEvents'
import type { TraceItem, TraceStep } from '@/types'
import SearchBar from '@/components/common/SearchBar.vue'
import FilterChips from '@/components/common/FilterChips.vue'
import MetricCard from '@/components/common/MetricCard.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import StepDurationChart from '@/components/trace/StepDurationChart.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { useTraceStore } from '@/stores/trace'

type StatusFilter = 'all' | 'success' | 'failure'
type TimeRangeFilter = 'all' | '24h' | '7d' | '30d'
type OverviewWindow = '24h' | '7d' | '30d'
type InternalStepType = 'llm' | 'tool' | 'guardrail' | 'state' | 'evaluation'

const TIME_RANGE_OPTIONS = [
  { value: 'all', label: '全部时间' },
  { value: '24h', label: '24h' },
  { value: '7d', label: '7d' },
  { value: '30d', label: '30d' },
] satisfies { value: TimeRangeFilter; label: string }[]

const OVERVIEW_WINDOW_OPTIONS = [
  { value: '24h', label: '24h' },
  { value: '7d', label: '7d' },
  { value: '30d', label: '30d' },
] satisfies { value: OverviewWindow; label: string }[]

const TIME_RANGE_MS: Record<Exclude<TimeRangeFilter, 'all'>, number> = {
  '24h': 24 * 60 * 60 * 1000,
  '7d': 7 * 24 * 60 * 60 * 1000,
  '30d': 30 * 24 * 60 * 60 * 1000,
}

const stepTypeConfig: Record<InternalStepType, {
  label: string
  badgeClass: string
  cardClass: string
  icon: any
}> = {
  llm: {
    label: 'LLM',
    badgeClass: 'border-sky-200/80 bg-sky-50/80 text-sky-700 dark:border-sky-500/20 dark:bg-sky-500/10 dark:text-sky-200',
    cardClass: 'border-sky-200/70 bg-sky-50/40 dark:border-sky-500/20 dark:bg-sky-500/10',
    icon: MessageCircle,
  },
  tool: {
    label: '工具',
    badgeClass: 'border-violet-200/80 bg-violet-50/80 text-violet-700 dark:border-violet-500/20 dark:bg-violet-500/10 dark:text-violet-200',
    cardClass: 'border-violet-200/70 bg-violet-50/40 dark:border-violet-500/20 dark:bg-violet-500/10',
    icon: Wrench,
  },
  guardrail: {
    label: '护栏',
    badgeClass: 'border-amber-200/80 bg-amber-50/80 text-amber-700 dark:border-amber-500/20 dark:bg-amber-500/10 dark:text-amber-200',
    cardClass: 'border-amber-200/70 bg-amber-50/40 dark:border-amber-500/20 dark:bg-amber-500/10',
    icon: Shield,
  },
  state: {
    label: '状态',
    badgeClass: 'border-slate-200/80 bg-slate-50/80 text-slate-700 dark:border-slate-500/20 dark:bg-slate-500/10 dark:text-slate-200',
    cardClass: 'border-slate-200/70 bg-slate-50/40 dark:border-slate-500/20 dark:bg-slate-500/10',
    icon: ArrowRight,
  },
  evaluation: {
    label: '评估',
    badgeClass: 'border-emerald-200/80 bg-emerald-50/80 text-emerald-700 dark:border-emerald-500/20 dark:bg-emerald-500/10 dark:text-emerald-200',
    cardClass: 'border-emerald-200/70 bg-emerald-50/40 dark:border-emerald-500/20 dark:bg-emerald-500/10',
    icon: BarChart3,
  },
}

const store = useTraceStore()
const route = useRoute()
const expandedSteps = ref<Set<string>>(new Set())
const searchKeyword = ref('')
const lastSearchedKeyword = ref('')
const statusFilter = ref<StatusFilter>('all')
const timeRangeFilter = ref<TimeRangeFilter>('all')
const timeWindow = ref<OverviewWindow>('7d')
const exporting = ref(false)
const serviceUnavailable = ref(false)
const liveConnected = ref(false)
const liveError = ref<string | null>(null)
let liveSource: EventSource | null = null

const isSearching = computed(() => lastSearchedKeyword.value.trim().length > 0)

const filteredTraces = computed<TraceItem[]>(() => {
  const baseList = isSearching.value ? store.searchResults : store.list

  return baseList.filter((trace) => {
    if (statusFilter.value === 'success' && !trace.success) return false
    if (statusFilter.value === 'failure' && trace.success) return false

    if (timeRangeFilter.value !== 'all') {
      const cutoff = Date.now() - TIME_RANGE_MS[timeRangeFilter.value]
      if (new Date(trace.createdAt).getTime() < cutoff) return false
    }

    return true
  })
})

const totalPages = computed(() => Math.ceil(store.total / store.pageSize))
const filteredCount = computed(() => filteredTraces.value.length)
const successLabel = computed(() => store.overviewStats ? `${(store.overviewStats.successRate * 100).toFixed(1)}%` : '—')
const avgDurationLabel = computed(() => store.overviewStats ? formatDuration(store.overviewStats.avgDurationMs) : '—')
const currentOverviewLabel = computed(() => (
  OVERVIEW_WINDOW_OPTIONS.find(option => option.value === timeWindow.value)?.label ?? '7d'
))
const detailTitle = computed(() => displayUserMessage(store.current?.userMessage))
const detailStatus = computed(() => store.current?.success ? '成功' : '失败')
const currentStepCount = computed(() => store.steps.length || store.current?.totalSteps || 0)
const currentTokens = computed(() => store.current?.totalTokens ?? 0)
const currentDuration = computed(() => store.current ? formatDuration(store.current.durationMs) : '—')
const liveStatus = computed(() => {
  if (liveConnected.value) return '实时流已连接'
  if (liveError.value) return liveError.value
  return '等待实时更新'
})
const overviewItems = computed(() => [
  {
    label: '轨迹数量',
    value: store.overviewStats?.totalTraces ?? store.total,
    description: '当前统计范围内的运行记录。',
  },
  {
    label: '成功率',
    value: successLabel.value,
    description: '当前统计范围内的成功占比。',
  },
  {
    label: '平均步骤数',
    value: store.overviewStats?.avgSteps?.toFixed(1) ?? '—',
    description: '每条轨迹平均捕获的步骤数量。',
  },
  {
    label: '平均耗时',
    value: avgDurationLabel.value,
    description: '当前统计范围内单条轨迹的平均时长。',
  },
])
const detailItems = computed(() => [
  {
    label: '状态',
    value: detailStatus.value,
    description: '本次运行最终状态。',
  },
  {
    label: '步骤',
    value: currentStepCount.value,
    description: '当前已捕获的回放步骤。',
  },
  {
    label: 'token数',
    value: currentTokens.value.toLocaleString(),
    description: '本次运行的总token用量。',
  },
  {
    label: '耗时',
    value: currentDuration.value,
    description: '端到端运行时长。',
  },
])
const detailMetaItems = computed(() => [
  {
    label: '创建时间',
    value: formatDate(store.current?.createdAt),
    mono: false,
  },
  {
    label: '会话 ID',
    value: store.current?.sessionId ?? '未记录',
    mono: true,
  },
  {
    label: '模型',
    value: store.current?.modelId || '未记录',
    mono: false,
  },
  {
    label: '终止原因',
    value: store.current?.terminationReason || '未知',
    mono: false,
  },
])
const evaluationItems = computed(() => {
  if (!store.evaluation) return []
  return [
    { key: 'toolSelectionScore', label: '工具选择', value: store.evaluation.toolSelectionScore },
    { key: 'parameterValidityScore', label: '参数有效性', value: store.evaluation.parameterValidityScore },
    { key: 'stepEfficiencyScore', label: '步骤效率', value: store.evaluation.stepEfficiencyScore },
    { key: 'policyComplianceScore', label: '策略合规性', value: store.evaluation.policyComplianceScore },
    { key: 'tokenEfficiencyScore', label: 'token效率', value: store.evaluation.tokenEfficiencyScore },
  ]
})

onMounted(async () => {
  try {
    await Promise.all([store.fetchList(), store.fetchOverviewStats(timeWindow.value)])
  } catch {
    // store handles user-facing errors
  }

  if (store.error && store.list.length === 0) {
    serviceUnavailable.value = true
    return
  }

  const initialId = route.query.id as string | undefined
  if (initialId) {
    await selectTrace(initialId)
  }
})

onBeforeUnmount(() => {
  stopLiveStream()
})

function stopLiveStream() {
  liveSource?.close()
  liveSource = null
  liveConnected.value = false
  liveError.value = null
}

function startLiveStream(traceId: string) {
  stopLiveStream()

  try {
    liveSource = new EventSource(`${getApiOrigin()}/api/traces/${traceId}/stream`)

    liveSource.addEventListener(SSE_EVENT_TYPES.TRACE_START, () => {
      liveConnected.value = true
    })

    liveSource.addEventListener(SSE_EVENT_TYPES.TRACE_STEP, (event) => {
      try {
        const step = JSON.parse((event as MessageEvent).data) as TraceStep
        const index = store.steps.findIndex(existing => existing.id === step.id)

        if (index >= 0) {
          store.steps[index] = step
        } else {
          store.steps.push(step)
        }

        store.steps.sort((left, right) => left.stepIndex - right.stepIndex)
      } catch (error) {
        console.warn('Failed to parse trace step event', error)
      }
    })

    liveSource.addEventListener(SSE_EVENT_TYPES.TRACE_END, () => {
      stopLiveStream()
      if (store.current?.id) {
        void Promise.all([store.fetchDetail(store.current.id), store.fetchSteps(store.current.id)])
      }
    })

    liveSource.onerror = () => {
      liveConnected.value = false
      liveError.value = '实时流已断开'
    }
  } catch (error: any) {
    liveError.value = error?.message ?? '无法建立实时流连接'
  }
}

async function handleSearch() {
  lastSearchedKeyword.value = searchKeyword.value.trim()
  await store.search(searchKeyword.value, 20)
}

function handleClearSearch() {
  searchKeyword.value = ''
  lastSearchedKeyword.value = ''
  store.searchResults = []
}

async function handleChangeTimeWindow(window: OverviewWindow) {
  if (timeWindow.value === window) return
  timeWindow.value = window
  await store.fetchOverviewStats(window)
}

async function selectTrace(id: string) {
  await Promise.all([store.fetchDetail(id), store.fetchSteps(id), store.fetchEvaluation(id)])
  expandedSteps.value.clear()
  startLiveStream(id)
}

function backToList() {
  stopLiveStream()
  store.current = null
  store.steps = []
  store.evaluation = null
}

function toggleStep(stepId: string) {
  if (expandedSteps.value.has(stepId)) {
    expandedSteps.value.delete(stepId)
  } else {
    expandedSteps.value.add(stepId)
  }
}

function isExpanded(stepId: string) {
  return expandedSteps.value.has(stepId)
}

function expandToolOutput(stepId: string) {
  expandedSteps.value.add(`${stepId}-full`)
}

function isToolOutputExpanded(stepId: string) {
  return expandedSteps.value.has(`${stepId}-full`)
}

function truncate(text: string, max = 500): string {
  return text.length > max ? `${text.slice(0, max)}...` : text
}

function displayUserMessage(text: string | undefined | null): string {
  const raw = (text ?? '').trim()
  if (!raw || raw === '[[content_not_persisted]]') return '（内容未持久化）'
  return raw
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

function formatDate(value: string | undefined): string {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN')
}

function scorePercent(score: number | undefined): string {
  if (score == null || Number.isNaN(score)) return '0%'
  const clamped = Math.max(0, Math.min(1, score))
  return `${(clamped * 100).toFixed(0)}%`
}

function scoreColor(score: number | undefined): string {
  if (score == null || Number.isNaN(score)) return 'bg-muted'
  if (score < 0.5) return 'bg-red-500'
  if (score < 0.7) return 'bg-amber-500'
  return 'bg-emerald-500'
}

function scoreValue(score: number | undefined): string {
  if (score == null || Number.isNaN(score)) return '0.00'
  return score.toFixed(2)
}

function resolveStepType(step: TraceStep): InternalStepType {
  if (step.toolId) return 'tool'
  if (step.blocked || step.blockReason) return 'guardrail'
  if (step.actionType?.toLowerCase().includes('evaluation')) return 'evaluation'
  if (step.phaseBefore !== step.phaseAfter) return 'state'
  return 'llm'
}

function getStepConfig(step: TraceStep) {
  return stepTypeConfig[resolveStepType(step)]
}

function formatJson(value?: string | null): string {
  if (!value) return '未捕获结构化载荷。'
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}

async function handleExportCurrent() {
  if (!store.current) return
  exporting.value = true
  try {
    await store.exportTrace(store.current.id)
  } finally {
    exporting.value = false
  }
}

async function handleRetryServiceCheck() {
  serviceUnavailable.value = false
  store.error = null
  await store.fetchList()

  if (store.error && store.list.length === 0) {
    serviceUnavailable.value = true
  } else {
    await store.fetchOverviewStats(timeWindow.value)
  }
}
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-4 sm:py-5">
      <div class="page-stack">
        <template v-if="!store.current">
          <PageHeader
            eyebrow="运行轨迹"
            title="最近运行记录"
            description="查看运行记录和详情。"
          >
            <template #actions>
              <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 px-4 py-4 text-sm">
                <div class="surface-label mb-2 text-[0.68rem]">当前视图</div>
                <div class="font-medium text-foreground">
                  {{ filteredCount }} 条可见记录
                </div>
                <p class="mt-1 max-w-[18rem] leading-6 text-muted-foreground">
                  统计窗口 {{ currentOverviewLabel }}，可继续用筛选条件收窄列表。
                </p>
              </div>
            </template>
            <template #meta>
              <MetricCard
                v-for="item in overviewItems"
                :key="item.label"
                :label="item.label"
                :value="item.value"
                :hint="item.description"
                class="h-full"
              />
            </template>
          </PageHeader>

          <StatePanel
            v-if="serviceUnavailable"
            title="运行轨迹服务暂时不可用"
            description="当前无法读取运行轨迹，请稍后再试。"
            tone="warning"
          >
            <template #icon>
              <ServerOff class="size-5" />
            </template>
            <template #actions>
              <Button variant="outline" size="sm" @click="handleRetryServiceCheck">
                重试
              </Button>
            </template>
          </StatePanel>

          <template v-else>
            <section class="detail-card p-4">
              <div class="flex flex-col gap-5">
                <div class="flex flex-col gap-3 xl:flex-row xl:items-end xl:justify-between">
                  <div class="space-y-1">
                    <h2 class="section-title text-foreground">
                      查找与筛选
                    </h2>
                    <p class="text-sm text-muted-foreground">
                      按用户消息、运行结果和时间范围收窄列表，再进入单条回放。
                    </p>
                  </div>
                  <div class="text-sm text-muted-foreground">
                    当前显示 {{ filteredCount }} 条
                  </div>
                </div>

                <div class="flex flex-col gap-4 xl:flex-row xl:items-center xl:justify-between">
                  <div class="flex min-w-0 flex-1 flex-col gap-3 lg:flex-row lg:items-center">
                    <div class="min-w-0 flex-1">
                      <SearchBar
                        v-model="searchKeyword"
                        placeholder="按轨迹 ID 或用户消息搜索..."
                        aria-label="搜索运行轨迹"
                        @search="handleSearch"
                      />
                    </div>
                    <div class="flex flex-wrap items-center gap-2">
                      <Button size="sm" @click="handleSearch">
                        搜索
                      </Button>
                      <Button
                        v-if="isSearching"
                        variant="outline"
                        size="sm"
                        @click="handleClearSearch"
                      >
                        清空
                      </Button>
                    </div>
                  </div>

                  <div class="flex flex-wrap items-center gap-2">
                    <Button
                      size="sm"
                      :variant="statusFilter === 'all' ? 'secondary' : 'ghost'"
                      @click="statusFilter = 'all'"
                    >
                      全部
                    </Button>
                    <Button
                      size="sm"
                      :variant="statusFilter === 'success' ? 'secondary' : 'ghost'"
                      @click="statusFilter = 'success'"
                    >
                      成功
                    </Button>
                    <Button
                      size="sm"
                      :variant="statusFilter === 'failure' ? 'secondary' : 'ghost'"
                      @click="statusFilter = 'failure'"
                    >
                      失败
                    </Button>
                  </div>
                </div>

                <div class="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
                  <div class="flex flex-wrap items-center gap-3">
                    <span class="surface-label text-[0.68rem]">筛选范围</span>
                    <FilterChips v-model="timeRangeFilter" :options="TIME_RANGE_OPTIONS" />
                  </div>

                  <div class="flex flex-wrap items-center gap-2">
                    <span class="surface-label text-[0.68rem]">统计范围</span>
                    <Button
                      v-for="option in OVERVIEW_WINDOW_OPTIONS"
                      :key="option.value"
                      size="sm"
                      :variant="timeWindow === option.value ? 'secondary' : 'ghost'"
                      class="rounded-full"
                      @click="handleChangeTimeWindow(option.value)"
                    >
                      {{ option.label }}
                    </Button>
                  </div>
                </div>
              </div>
            </section>

            <section class="space-y-4">
              <div class="flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
                <div class="space-y-1">
                  <div class="surface-label">运行列表</div>
                  <h2 class="section-title text-foreground">
                    已记录的执行
                  </h2>
                </div>
                <div class="text-sm text-muted-foreground">
                  {{ isSearching
                    ? `搜索“${lastSearchedKeyword}”共匹配 ${filteredCount} 条结果。`
                    : `筛选后共显示 ${filteredCount} 条运行轨迹。` }}
                </div>
              </div>

              <div v-if="store.loading" class="grid gap-4">
                <div
                  v-for="index in 5"
                  :key="index"
                  class="detail-card space-y-4 p-5"
                >
                  <div class="flex items-center gap-3">
                    <Skeleton class="h-3 w-3 rounded-full" />
                    <Skeleton class="h-4 flex-1" />
                    <Skeleton class="h-4 w-20" />
                  </div>
                  <div class="grid gap-3 md:grid-cols-4">
                    <Skeleton class="h-3 w-20" />
                    <Skeleton class="h-3 w-24" />
                    <Skeleton class="h-3 w-28" />
                    <Skeleton class="h-3 w-32 md:justify-self-end" />
                  </div>
                </div>
              </div>

              <StatePanel
                v-else-if="store.error"
                title="无法加载运行轨迹列表"
                :description="store.error"
                tone="danger"
              >
                <template #icon>
                  <Activity class="size-5" />
                </template>
                <template #actions>
                  <Button variant="outline" size="sm" @click="store.fetchList()">
                    重试
                  </Button>
                </template>
              </StatePanel>

              <StatePanel
                v-else-if="isSearching && lastSearchedKeyword && store.searchResults.length === 0"
                title="没有匹配搜索条件的运行轨迹"
                description="可以换个关键词、清空筛选条件，或回到默认列表。"
              >
                <template #icon>
                  <MessageCircle class="size-5" />
                </template>
              </StatePanel>

              <StatePanel
                v-else-if="!isSearching && filteredTraces.length === 0 && store.list.length === 0"
                title="暂时还没有运行轨迹"
                description="有新的运行记录后，会显示执行过程、耗时和结果。"
              >
                <template #icon>
                  <Activity class="size-5" />
                </template>
              </StatePanel>

              <StatePanel
                v-else-if="filteredTraces.length === 0"
                title="没有符合当前筛选条件的运行轨迹"
                description="可以调整状态筛选或放宽时间范围，把运行记录重新显示出来。"
              >
                <template #icon>
                  <Shield class="size-5" />
                </template>
              </StatePanel>

              <div v-else class="space-y-3">
                <button
                  v-for="trace in filteredTraces"
                  :key="trace.id"
                  type="button"
                  class="list-card w-full p-4 text-left"
                  @click="selectTrace(trace.id)"
                >
                  <div class="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
                    <div class="min-w-0 space-y-3">
                      <div class="flex flex-wrap items-center gap-2">
                        <Badge
                          variant="outline"
                          :class="trace.success
                            ? 'border-emerald-200/80 bg-emerald-50/80 text-emerald-700 dark:border-emerald-500/20 dark:bg-emerald-500/10 dark:text-emerald-200'
                            : 'border-red-200/80 bg-red-50/80 text-red-700 dark:border-red-500/20 dark:bg-red-500/10 dark:text-red-200'"
                        >
                          {{ trace.success ? '成功' : '失败' }}
                        </Badge>
                        <Badge variant="outline" class="font-mono text-[0.72rem]">
                          {{ trace.id }}
                        </Badge>
                        <span class="text-xs text-muted-foreground">
                          {{ formatDate(trace.createdAt) }}
                        </span>
                      </div>

                      <div class="text-lg font-semibold tracking-tight text-foreground">
                        {{ displayUserMessage(trace.userMessage) }}
                      </div>

                      <div class="flex flex-wrap items-center gap-4 text-sm text-muted-foreground">
                        <span>{{ trace.totalSteps }} 步</span>
                        <span>{{ trace.totalTokens.toLocaleString() }} token</span>
                        <span>{{ formatDuration(trace.durationMs) }}</span>
                        <span class="font-mono">{{ trace.sessionId }}</span>
                      </div>
                    </div>

                    <div class="flex items-center gap-2 text-sm text-muted-foreground">
                      <span>打开回放</span>
                      <ArrowRight class="size-4" />
                    </div>
                  </div>
                </button>

                <div
                  v-if="totalPages > 1 && !isSearching"
                  class="flex flex-col gap-3 border-t border-border/70 pt-4 sm:flex-row sm:items-center sm:justify-between"
                >
                  <div class="text-sm text-muted-foreground">
                    第 {{ store.page + 1 }} / {{ totalPages }} 页
                  </div>
                  <div class="flex items-center gap-2">
                    <Button
                      variant="outline"
                      size="sm"
                      :disabled="store.page <= 0"
                      @click="store.fetchList(store.page - 1)"
                    >
                      上一页
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      :disabled="store.page >= totalPages - 1"
                      @click="store.fetchList(store.page + 1)"
                    >
                      下一页
                    </Button>
                  </div>
                </div>
              </div>
            </section>
          </template>
        </template>

        <template v-else>
          <div class="space-y-4 border-b border-border/70 pb-6">
            <PageHeader
              eyebrow="运行轨迹"
              :title="detailTitle"
              description="查看这次运行的状态、步骤、评估结果和最终输出。"
              class="border-b-0 pb-0"
            >
              <template #actions>
                <div class="flex flex-col gap-3 lg:items-end">
                  <div class="flex flex-wrap items-center gap-2 lg:justify-end">
                    <Badge
                      variant="outline"
                      :class="store.current.success
                        ? 'border-emerald-200/80 bg-emerald-50/80 text-emerald-700 dark:border-emerald-500/20 dark:bg-emerald-500/10 dark:text-emerald-200'
                        : 'border-red-200/80 bg-red-50/80 text-red-700 dark:border-red-500/20 dark:bg-red-500/10 dark:text-red-200'"
                    >
                      {{ detailStatus }}
                    </Badge>
                    <Badge
                      v-if="liveConnected"
                      variant="outline"
                      class="border-emerald-200/80 bg-emerald-50/80 text-emerald-700 dark:border-emerald-500/20 dark:bg-emerald-500/10 dark:text-emerald-200"
                    >
                      实时
                    </Badge>
                    <Badge
                      v-else-if="liveError"
                      variant="outline"
                      class="border-amber-200/80 bg-amber-50/80 text-amber-700 dark:border-amber-500/20 dark:bg-amber-500/10 dark:text-amber-200"
                    >
                      流已暂停
                    </Badge>
                  </div>

                  <div class="flex flex-wrap items-center gap-3 lg:justify-end">
                    <Button variant="outline" @click="backToList">
                      <ArrowLeft class="size-4" />
                      返回列表
                    </Button>
                    <Button variant="outline" :disabled="exporting" @click="handleExportCurrent">
                      <Download class="size-4" />
                      {{ exporting ? '导出中...' : '导出 JSON' }}
                    </Button>
                  </div>
                </div>
              </template>

              <template #meta>
                <MetricCard
                  v-for="item in detailItems"
                  :key="item.label"
                  :label="item.label"
                  :value="item.value"
                  :hint="item.description"
                  class="h-full"
                />
              </template>
            </PageHeader>

            <section class="grid gap-4 xl:grid-cols-[minmax(0,1.15fr)_minmax(280px,0.85fr)]">
              <article class="detail-card p-4">
                <div class="surface-label mb-3">执行摘要</div>
                <div class="grid gap-3 md:grid-cols-2">
                  <div
                    v-for="item in detailMetaItems"
                    :key="item.label"
                    class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 px-4 py-3 text-sm"
                  >
                    <div class="text-xs text-muted-foreground">{{ item.label }}</div>
                    <div
                      class="mt-1 text-foreground"
                      :class="item.mono ? 'break-all font-mono text-[0.82rem]' : 'break-words'"
                    >
                      {{ item.value }}
                    </div>
                  </div>
                </div>
              </article>

              <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 px-4 py-4">
                <div class="surface-label mb-2 text-[0.68rem]">实时流状态</div>
                <div class="text-sm font-medium text-foreground">{{ liveStatus }}</div>
                <p class="mt-1 text-sm leading-6 text-muted-foreground">
                  实时状态会随着流连接、重放结束或异常中断自动刷新。
                </p>
              </div>
            </section>
          </div>

          <StatePanel
            v-if="store.current.errorMessage"
            title="当前运行轨迹以错误结束"
            :description="store.current.errorMessage"
            tone="danger"
          >
            <template #icon>
              <ServerOff class="size-5" />
            </template>
          </StatePanel>

          <section
            v-if="store.steps.length > 0 && store.current.durationMs > 0"
            class="detail-card overflow-hidden"
          >
            <div class="border-b border-border/70 px-5 py-4">
              <div class="space-y-1">
                <div class="surface-label">耗时分布</div>
                <h2 class="section-title text-foreground">
                  步骤耗时拆解
                </h2>
                <p class="text-sm text-muted-foreground">
                  对比每个回放步骤在整条运行轨迹中的耗时占比。
                </p>
              </div>
            </div>
            <div class="p-5">
              <StepDurationChart
                :steps="store.steps"
                :total-duration-ms="store.current.durationMs"
              />
            </div>
          </section>

          <section
            v-if="store.evaluation"
            class="detail-card overflow-hidden"
          >
            <div class="border-b border-border/70 px-5 py-4">
              <div class="space-y-1">
                <div class="surface-label">评估</div>
                <h2 class="section-title text-foreground">
                  离线质量评估
                </h2>
                <p class="text-sm text-muted-foreground">
                  查看这条运行轨迹在执行完成后的评分结果和建议。
                </p>
              </div>
            </div>

            <div class="grid gap-4 p-5 xl:grid-cols-[minmax(0,1fr)_280px]">
              <div class="space-y-4">
                <div
                  v-for="item in evaluationItems"
                  :key="item.key"
                  class="space-y-2"
                >
                  <div class="flex items-center justify-between gap-3 text-sm">
                    <span class="text-muted-foreground">{{ item.label }}</span>
                    <span class="font-medium text-foreground">{{ scoreValue(item.value) }}</span>
                  </div>
                  <div class="h-2 overflow-hidden rounded-full bg-muted/70">
                    <div
                      class="h-full rounded-full"
                      :class="scoreColor(item.value)"
                      :style="{ width: scorePercent(item.value) }"
                    />
                  </div>
                </div>

                <div v-if="store.evaluation.violations.length" class="rounded-[calc(var(--radius)+6px)] border border-destructive/20 bg-destructive/6 p-4">
                  <div class="text-sm font-medium text-destructive">违规项</div>
                  <ul class="mt-2 space-y-1 text-sm text-destructive/90">
                    <li v-for="violation in store.evaluation.violations" :key="violation">
                      {{ violation }}
                    </li>
                  </ul>
                </div>

                <div v-if="store.evaluation.suggestions.length" class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 p-4">
                  <div class="text-sm font-medium text-foreground">建议</div>
                  <ul class="mt-2 space-y-1 text-sm text-muted-foreground">
                    <li v-for="suggestion in store.evaluation.suggestions" :key="suggestion">
                      {{ suggestion }}
                    </li>
                  </ul>
                </div>
              </div>

              <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 p-5">
                <div class="surface-label mb-2 text-[0.68rem]">综合评分</div>
                <div class="text-2xl font-semibold tracking-tight text-foreground">
                  {{ scoreValue(store.evaluation.overallScore) }}
                </div>
                <div class="mt-4 space-y-2 text-sm text-muted-foreground">
                  <div class="flex items-center justify-between gap-3">
                    <span>实际步骤数</span>
                    <span class="text-foreground">{{ store.evaluation.actualSteps }}</span>
                  </div>
                  <div class="flex items-center justify-between gap-3">
                    <span>实际token数</span>
                    <span class="text-foreground">{{ store.evaluation.actualTokens }}</span>
                  </div>
                  <div class="flex items-center justify-between gap-3">
                    <span>评估时间</span>
                    <span class="text-right text-foreground">{{ formatDate(store.evaluation.evaluatedAt) }}</span>
                  </div>
                </div>
              </div>
            </div>
          </section>

          <section class="space-y-4">
            <div class="flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
              <div class="space-y-1">
                <div class="surface-label">回放</div>
                <h2 class="section-title text-foreground">
                  逐步执行过程
                </h2>
              </div>
              <div class="text-sm text-muted-foreground">
                当前运行轨迹共捕获 {{ store.steps.length }} 个步骤。
              </div>
            </div>

              <StatePanel
                v-if="store.steps.length === 0"
                title="未捕获到回放步骤"
                description="当前选中的运行轨迹暂时没有步骤数据。"
              >
                <template #icon>
                  <Activity class="size-5" />
                </template>
              </StatePanel>

              <div v-else class="space-y-4">
                <article
                  v-for="step in store.steps"
                  :key="step.id"
                  :class="['detail-card overflow-hidden border p-0', getStepConfig(step).cardClass]"
                >
                  <button
                    type="button"
                    class="flex w-full flex-col gap-4 px-5 py-4 text-left"
                    @click="toggleStep(step.id)"
                  >
                    <div class="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
                      <div class="min-w-0 space-y-3">
                        <div class="flex flex-wrap items-center gap-2">
                          <Badge variant="outline" class="font-mono text-[0.72rem]">
                            #{{ step.stepIndex }}
                          </Badge>
                          <Badge variant="outline" :class="getStepConfig(step).badgeClass">
                            <component :is="getStepConfig(step).icon" class="size-3.5" />
                            {{ getStepConfig(step).label }}
                          </Badge>
                          <span v-if="step.toolId" class="font-mono text-xs text-muted-foreground">
                            {{ step.toolId }}
                          </span>
                        </div>

                        <div class="text-base font-semibold tracking-tight text-foreground">
                          {{ step.actionType }}
                        </div>

                        <div class="flex flex-wrap items-center gap-4 text-sm text-muted-foreground">
                          <span>{{ step.phaseBefore }} → {{ step.phaseAfter }}</span>
                          <span>{{ formatDuration(step.latencyMs) }}</span>
                          <span>{{ step.tokensUsed.toLocaleString() }} token</span>
                          <span>{{ formatDate(step.createdAt) }}</span>
                        </div>

                        <div
                          v-if="step.blocked"
                          class="rounded-xl border border-amber-200/80 bg-amber-50/80 px-3 py-2 text-sm text-amber-800 dark:border-amber-500/20 dark:bg-amber-500/10 dark:text-amber-200"
                        >
                          护栏阻止了该步骤：{{ step.blockReason }}
                        </div>
                      </div>

                      <div class="text-sm text-muted-foreground">
                        {{ isExpanded(step.id) ? '收起' : '展开' }}
                      </div>
                    </div>
                  </button>

                  <div v-if="isExpanded(step.id)" class="soft-divider" />

                  <div v-if="isExpanded(step.id)" class="grid gap-4 px-5 py-4 xl:grid-cols-2">
                    <div class="space-y-2">
                      <div class="surface-label text-[0.68rem]">载荷</div>
                      <pre class="max-h-[420px] overflow-auto rounded-[calc(var(--radius)+2px)] border border-border/70 bg-background/80 p-4 text-xs leading-6 text-muted-foreground">{{ formatJson(step.actionJson) }}</pre>
                    </div>

                    <div class="space-y-4">
                      <div v-if="step.toolInputJson" class="space-y-2">
                        <div class="surface-label text-[0.68rem]">工具输入</div>
                        <pre class="max-h-[220px] overflow-auto rounded-[calc(var(--radius)+2px)] border border-border/70 bg-background/80 p-4 text-xs leading-6 text-muted-foreground">{{ formatJson(step.toolInputJson) }}</pre>
                      </div>

                      <div v-if="step.toolOutput" class="space-y-2">
                        <div class="surface-label text-[0.68rem]">工具输出</div>
                        <pre class="max-h-[260px] overflow-auto rounded-[calc(var(--radius)+2px)] border border-border/70 bg-background/80 p-4 text-xs leading-6 text-muted-foreground">{{ isToolOutputExpanded(step.id) ? step.toolOutput : truncate(step.toolOutput) }}</pre>
                        <Button
                          v-if="step.toolOutput.length > 500 && !isToolOutputExpanded(step.id)"
                          variant="outline"
                          size="sm"
                          @click.stop="expandToolOutput(step.id)"
                        >
                          查看完整输出
                        </Button>
                      </div>

                      <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 p-4">
                        <div class="surface-label mb-2 text-[0.68rem]">执行元数据</div>
                        <div class="space-y-2 text-sm text-muted-foreground">
                          <div class="flex items-center justify-between gap-3">
                            <span>成功</span>
                            <span class="text-foreground">{{ step.success ? '是' : '否' }}</span>
                          </div>
                          <div class="flex items-center justify-between gap-3">
                            <span>耗时</span>
                            <span class="text-foreground">{{ formatDuration(step.latencyMs) }}</span>
                          </div>
                          <div class="flex items-center justify-between gap-3">
                            <span>token数</span>
                            <span class="text-foreground">{{ step.tokensUsed.toLocaleString() }}</span>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                </article>
              </div>
          </section>

          <section
            v-if="store.current.finalOutput"
            class="detail-card overflow-hidden"
          >
            <div class="border-b border-border/70 px-5 py-4">
              <div class="space-y-1">
                <div class="surface-label">最终输出</div>
                <h2 class="section-title text-foreground">
                  最终响应
                </h2>
                <p class="text-sm text-muted-foreground">
                  当前运行轨迹中保存的终端响应或助手最终输出。
                </p>
              </div>
            </div>
            <div class="p-5">
              <div class="whitespace-pre-wrap text-sm leading-7 text-muted-foreground">
                {{ store.current.finalOutput }}
              </div>
            </div>
          </section>
        </template>
      </div>
    </PageContainer>
  </div>
</template>
