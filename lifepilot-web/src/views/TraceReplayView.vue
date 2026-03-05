<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
import { useRoute } from 'vue-router'
import { MessageCircle, Wrench, Shield, ArrowRight, BarChart3, Download } from 'lucide-vue-next'
import { useTraceStore } from '@/stores/trace'
import type { TraceItem, TraceStep } from '@/types'
import { SSE_EVENT_TYPES } from '@/constants/sseEvents'
import SearchBar from '@/components/common/SearchBar.vue'
import FilterChips from '@/components/common/FilterChips.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorState from '@/components/common/ErrorState.vue'
import StepDurationChart from '@/components/trace/StepDurationChart.vue'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'

const store = useTraceStore()
const expandedSteps = ref<Set<string>>(new Set())
const route = useRoute()

// 搜索与筛选
const searchKeyword = ref('')
const statusFilter = ref<'all' | 'success' | 'failure'>('all')
const lastSearchedKeyword = ref('')

// 时间范围筛选（列表过滤用）
const timeRangeFilter = ref<'all' | '24h' | '7d' | '30d'>('all')

const TIME_RANGE_OPTIONS = [
  { value: 'all', label: '全部' },
  { value: '24h', label: '最近 24 小时' },
  { value: '7d', label: '最近 7 天' },
  { value: '30d', label: '最近 30 天' },
]

const TIME_RANGE_MS: Record<string, number> = {
  '24h': 24 * 60 * 60 * 1000,
  '7d': 7 * 24 * 60 * 60 * 1000,
  '30d': 30 * 24 * 60 * 60 * 1000,
}

// 概览统计时间窗口
const timeWindow = ref<'24h' | '7d' | '30d'>('7d')

// 导出状态
const exporting = ref(false)

// 实时 SSE 订阅（Trace Step Stream）
const liveConnected = ref(false)
const liveError = ref<string | null>(null)
let liveSource: EventSource | null = null

// 计算当前是否处于搜索模式
const isSearching = computed(() => lastSearchedKeyword.value.trim().length > 0)

// 当前展示的列表（考虑搜索、状态筛选与时间范围过滤）
const filteredTraces = computed<TraceItem[]>(() => {
  const baseList = isSearching.value ? store.searchResults : store.list

  return baseList.filter((trace) => {
    // 状态过滤
    if (statusFilter.value === 'success' && !trace.success) return false
    if (statusFilter.value === 'failure' && trace.success) return false

    // 时间范围过滤
    if (timeRangeFilter.value !== 'all') {
      const cutoff = Date.now() - TIME_RANGE_MS[timeRangeFilter.value]
      if (new Date(trace.createdAt).getTime() < cutoff) return false
    }

    return true
  })
})

const totalPages = computed(() => Math.ceil(store.total / store.pageSize))

onMounted(async () => {
  await Promise.all([store.fetchList(), store.fetchOverviewStats(timeWindow.value)])
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
  liveError.value = null
  try {
    liveSource = new EventSource(`/api/traces/${traceId}/stream`)

    liveSource.addEventListener(SSE_EVENT_TYPES.TRACE_START, () => {
      liveConnected.value = true
    })

    liveSource.addEventListener(SSE_EVENT_TYPES.TRACE_STEP, (ev) => {
      try {
        const step = JSON.parse((ev as MessageEvent).data) as TraceStep
        const idx = store.steps.findIndex((s) => s.id === step.id)
        if (idx >= 0) {
          store.steps[idx] = step
        } else {
          store.steps.push(step)
        }
        store.steps.sort((a, b) => a.stepIndex - b.stepIndex)
      } catch (e) {
        // 忽略单条解析失败
        console.warn('trace-step 事件解析失败', e)
      }
    })

    liveSource.addEventListener(SSE_EVENT_TYPES.TRACE_END, () => {
      liveConnected.value = false
      stopLiveStream()
      // Trace 结束后刷新一次详情与步骤，确保与落盘结果一致
      if (store.current?.id) {
        void Promise.all([store.fetchDetail(store.current.id), store.fetchSteps(store.current.id)])
      }
    })

    liveSource.onerror = () => {
      liveConnected.value = false
      liveError.value = '实时连接已断开'
    }
  } catch (e: any) {
    liveError.value = e?.message ?? '无法建立实时连接'
  }
}

async function handleSearch() {
  lastSearchedKeyword.value = searchKeyword.value.trim()
  await store.search(searchKeyword.value, 20)
}

function handleClearSearch() {
  searchKeyword.value = ''
  lastSearchedKeyword.value = ''
  // 清空搜索结果，回到默认分页列表
  store.searchResults = []
}

async function handleChangeTimeWindow(window: '24h' | '7d' | '30d') {
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

function truncate(text: string, max = 500): string {
  return text.length > max ? text.slice(0, max) + '...' : text
}

function displayUserMessage(text: string | undefined | null): string {
  const raw = (text ?? '').trim()
  if (!raw) return '（内容未保存）'
  if (raw === '[[content_not_persisted]]') return '（内容未保存）'
  return text as string
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

// 评分相关工具函数
function scorePercent(score: number | undefined): string {
  if (score == null || Number.isNaN(score)) return '0%'
  const clamped = Math.max(0, Math.min(1, score))
  return `${(clamped * 100).toFixed(0)}%`
}

function scoreColor(score: number | undefined): string {
  if (score == null || Number.isNaN(score)) return 'bg-muted'
  if (score < 0.5) return 'bg-red-500'
  if (score < 0.7) return 'bg-yellow-500'
  return 'bg-green-500'
}

// 步骤类型与样式映射
type InternalStepType = 'llm' | 'tool' | 'guardrail' | 'state' | 'evaluation'

const stepTypeConfig: Record<InternalStepType, {
  label: string
  borderClass: string
  dotClass: string
  badgeClass: string
  icon: any
}> = {
  llm: {
    label: 'LLM 调用',
    borderClass: 'border-blue-300',
    dotClass: 'bg-blue-500',
    badgeClass: 'bg-blue-50 text-blue-700',
    icon: MessageCircle,
  },
  tool: {
    label: '工具调用',
    borderClass: 'border-purple-300',
    dotClass: 'bg-purple-500',
    badgeClass: 'bg-purple-50 text-purple-700',
    icon: Wrench,
  },
  guardrail: {
    label: '护栏检查',
    borderClass: 'border-amber-300',
    dotClass: 'bg-amber-500',
    badgeClass: 'bg-amber-50 text-amber-700',
    icon: Shield,
  },
  state: {
    label: '阶段切换',
    borderClass: 'border-slate-300',
    dotClass: 'bg-slate-500',
    badgeClass: 'bg-slate-50 text-slate-700',
    icon: ArrowRight,
  },
  evaluation: {
    label: '轨迹评估',
    borderClass: 'border-emerald-300',
    dotClass: 'bg-emerald-500',
    badgeClass: 'bg-emerald-50 text-emerald-700',
    icon: BarChart3,
  },
}

function resolveStepType(step: TraceStep): InternalStepType {
  // 简单启发式判断，兼容后端尚未显式返回 stepType 的情况
  if (step.toolId) return 'tool'
  if (step.blocked || step.blockReason) return 'guardrail'
  if (step.actionType?.toLowerCase().includes('evaluation')) return 'evaluation'
  if (step.phaseBefore !== step.phaseAfter) return 'state'
  return 'llm'
}

function getStepConfig(step: TraceStep) {
  return stepTypeConfig[resolveStepType(step)]
}

function parseJsonSafe<T = any>(value?: string | null): T | null {
  if (!value) return null
  try {
    return JSON.parse(value) as T
  } catch {
    return null
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
</script>

<template>
  <div class="flex flex-col h-full">
    <div class="flex-1 overflow-y-auto">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-lg">
        <!-- 错误提示 -->
        <ErrorState
          v-if="store.error && !store.loading"
          title="加载失败"
          :description="store.error"
          action-label="重试"
          :show-action="true"
          @action="store.fetchList()"
        />

        <!-- 轨迹列表 -->
        <template v-if="!store.current">
          <h2 class="text-xl font-semibold text-foreground mb-4">轨迹回放</h2>

          <!-- 搜索栏 + 状态筛选 -->
          <div class="mb-4 flex flex-wrap items-center gap-4">
            <div class="flex-1 flex items-center gap-3 min-w-[260px]">
              <SearchBar
                v-model="searchKeyword"
                placeholder="按用户问题或 Trace ID 搜索轨迹…"
                class="flex-1"
                @search="handleSearch"
              />
              <Button size="sm" @click="handleSearch">搜索</Button>
              <Button
                v-if="isSearching"
                variant="outline"
                size="sm"
                @click="handleClearSearch"
              >
                清空
              </Button>
            </div>

            <div class="flex items-center gap-4 text-xs">
              <span class="text-muted-foreground">状态：</span>
              <Button
                size="sm"
                :variant="statusFilter === 'all' ? 'secondary' : 'outline'"
                @click="statusFilter = 'all'"
              >
                全部
              </Button>
              <Button
                size="sm"
                :variant="statusFilter === 'success' ? 'secondary' : 'outline'"
                :class="statusFilter === 'success' ? 'bg-emerald-50 text-emerald-700 border-emerald-200 hover:bg-emerald-100' : ''"
                @click="statusFilter = 'success'"
              >
                仅成功
              </Button>
              <Button
                size="sm"
                :variant="statusFilter === 'failure' ? 'secondary' : 'outline'"
                :class="statusFilter === 'failure' ? 'bg-red-50 text-red-700 border-red-200 hover:bg-red-100' : ''"
                @click="statusFilter = 'failure'"
              >
                仅失败
              </Button>
            </div>
          </div>

          <!-- 时间范围筛选 -->
          <div class="mb-4 flex items-center gap-3">
            <span class="text-xs text-muted-foreground">时间范围：</span>
            <FilterChips
              v-model="timeRangeFilter"
              :options="TIME_RANGE_OPTIONS"
            />
          </div>

          <!-- 概览统计卡片 -->
          <div class="mb-6 space-y-4">
            <div class="flex items-center justify-between gap-4">
              <p class="text-sm text-muted-foreground">
                最近整体运行情况
              </p>
              <div class="flex items-center gap-2 text-xs">
                <Button
                  v-for="tw in (['24h', '7d', '30d'] as const)"
                  :key="tw"
                  size="sm"
                  :variant="timeWindow === tw ? 'secondary' : 'outline'"
                  class="rounded-full"
                  @click="handleChangeTimeWindow(tw)"
                >
                  {{ tw === '24h' ? '24 小时' : tw === '7d' ? '7 天' : '30 天' }}
                </Button>
              </div>
            </div>

            <!-- 统计卡片 Skeleton 占位符 -->
            <div
              v-if="!store.overviewStats"
              class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-4"
            >
              <Card v-for="i in 5" :key="i">
                <CardContent class="p-3">
                  <Skeleton class="h-3 w-16 mb-2" />
                  <Skeleton class="h-6 w-20" />
                </CardContent>
              </Card>
            </div>

            <!-- 统计卡片实际内容 -->
            <div
              v-else
              class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-4"
            >
              <Card>
                <CardContent class="p-3">
                  <p class="text-xs text-muted-foreground mb-1">轨迹总数</p>
                  <p class="text-xl font-semibold text-foreground leading-tight">
                    {{ store.overviewStats.totalTraces }}
                  </p>
                </CardContent>
              </Card>
              <Card>
                <CardContent class="p-3">
                  <p class="text-xs text-muted-foreground mb-1">成功率</p>
                  <p class="text-xl font-semibold text-foreground leading-tight">
                    {{ (store.overviewStats.successRate * 100).toFixed(1) }}%
                  </p>
                </CardContent>
              </Card>
              <Card>
                <CardContent class="p-3">
                  <p class="text-xs text-muted-foreground mb-1">平均步骤数</p>
                  <p class="text-xl font-semibold text-foreground leading-tight">
                    {{ store.overviewStats.avgSteps.toFixed(1) }}
                  </p>
                </CardContent>
              </Card>
              <Card>
                <CardContent class="p-3">
                  <p class="text-xs text-muted-foreground mb-1">平均耗时</p>
                  <p class="text-xl font-semibold text-foreground leading-tight">
                    {{ formatDuration(store.overviewStats.avgDurationMs) }}
                  </p>
                </CardContent>
              </Card>
              <Card>
                <CardContent class="p-3">
                  <p class="text-xs text-muted-foreground mb-1">总 Token 消耗</p>
                  <p class="text-xl font-semibold text-foreground leading-tight">
                    {{ store.overviewStats.totalTokens }}
                  </p>
                </CardContent>
              </Card>
            </div>
          </div>

          <!-- Skeleton 加载占位符 -->
          <div v-if="store.loading && !isSearching" class="space-y-4">
            <Card v-for="i in 4" :key="i">
              <CardContent class="p-4">
                <div class="flex items-center gap-4">
                  <Skeleton class="w-2 h-2 rounded-full" />
                  <Skeleton class="h-4 flex-1" />
                  <Skeleton class="h-4 w-16" />
                </div>
                <div class="flex items-center gap-4 mt-2">
                  <Skeleton class="h-3 w-12" />
                  <Skeleton class="h-3 w-20" />
                  <div class="ml-auto">
                    <Skeleton class="h-3 w-32" />
                  </div>
                </div>
              </CardContent>
            </Card>
          </div>

          <!-- 空状态 -->
          <EmptyState
            v-else-if="isSearching && lastSearchedKeyword && store.searchResults.length === 0"
            icon="🔍"
            title="未找到匹配的轨迹"
            description="尝试调整搜索关键词或筛选条件"
          />
          <EmptyState
            v-else-if="!isSearching && filteredTraces.length === 0 && store.list.length === 0"
            icon="📋"
            title="暂无轨迹记录"
            description="当 Agent 执行对话后，轨迹将自动记录在此"
          />
          <EmptyState
            v-else-if="!isSearching && filteredTraces.length === 0 && store.list.length > 0"
            icon="🔍"
            title="无匹配结果"
            description="当前筛选条件下没有轨迹，尝试调整状态或时间范围"
          />

          <!-- 轨迹列表卡片 -->
          <div v-else class="space-y-4">
            <Card
              v-for="trace in filteredTraces"
              :key="trace.id"
              class="cursor-pointer hover:-translate-y-0.5 hover:shadow-md hover:border-primary/50 transition-all duration-200"
              @click="selectTrace(trace.id)"
            >
              <CardContent class="p-4">
                <div class="flex items-center gap-4">
                  <span
                    class="w-2 h-2 rounded-full shrink-0"
                    :class="trace.success ? 'bg-green-500' : 'bg-red-500'"
                  />
                  <span class="text-sm text-foreground truncate flex-1">{{ displayUserMessage(trace.userMessage) }}</span>
                  <span class="text-xs text-muted-foreground shrink-0">{{ formatDuration(trace.durationMs) }}</span>
                </div>
                <div class="flex items-center gap-4 mt-2 text-xs text-muted-foreground">
                  <span>{{ trace.totalSteps }} 步</span>
                  <span>{{ trace.totalTokens }} tokens</span>
                  <span class="ml-auto">{{ new Date(trace.createdAt).toLocaleString() }}</span>
                </div>
              </CardContent>
            </Card>
          </div>

          <!-- 分页 -->
          <div
            v-if="totalPages > 1 && !isSearching"
            class="flex items-center justify-center gap-4 mt-6"
          >
            <Button
              variant="outline"
              size="sm"
              :disabled="store.page <= 0"
              @click="store.fetchList(store.page - 1)"
            >
              上一页
            </Button>
            <span class="text-sm text-muted-foreground">{{ store.page + 1 }} / {{ totalPages }}</span>
            <Button
              variant="outline"
              size="sm"
              :disabled="store.page >= totalPages - 1"
              @click="store.fetchList(store.page + 1)"
            >
              下一页
            </Button>
          </div>
        </template>

        <!-- 轨迹详情 -->
        <template v-else>
          <div class="flex items-center gap-3 mb-4">
            <Button variant="ghost" size="sm" @click="backToList">← 返回</Button>
            <h2 class="text-xl font-semibold text-foreground truncate">
              {{ displayUserMessage(store.current.userMessage) }}
            </h2>
            <Badge v-if="liveConnected" variant="outline">实时中</Badge>
            <Badge
              v-else-if="liveError"
              variant="destructive"
              :title="liveError"
            >
              实时断开
            </Badge>
            <Button
              variant="outline"
              size="sm"
              class="ml-auto"
              :disabled="exporting"
              @click="handleExportCurrent"
            >
              <Download class="w-4 h-4" />
              <span>{{ exporting ? '导出中…' : '导出 JSON' }}</span>
            </Button>
          </div>

          <!-- 汇总信息 -->
          <Card class="mb-6">
            <CardContent class="p-4 flex items-center gap-6 text-sm">
              <div class="flex items-center gap-2">
                <span
                  class="w-2 h-2 rounded-full"
                  :class="store.current.success ? 'bg-green-500' : 'bg-red-500'"
                />
                <span>{{ store.current.success ? '成功' : '失败' }}</span>
              </div>
              <span>{{ store.current.totalSteps }} 步</span>
              <span>{{ store.current.totalTokens }} tokens</span>
              <span>{{ formatDuration(store.current.durationMs) }}</span>
              <span v-if="store.current.modelId" class="text-muted-foreground">{{ store.current.modelId }}</span>
            </CardContent>
          </Card>

          <!-- 步骤耗时分布 -->
          <Card
            v-if="store.steps.length > 0 && store.current.durationMs > 0"
            class="mb-6"
          >
            <CardHeader class="pb-2">
              <h3 class="text-sm font-medium text-foreground">步骤耗时分布</h3>
            </CardHeader>
            <CardContent>
              <StepDurationChart
                :steps="store.steps"
                :total-duration-ms="store.current.durationMs"
              />
            </CardContent>
          </Card>

          <div v-if="store.current.errorMessage" class="mb-4 p-3 rounded-md bg-destructive/10 text-destructive text-sm">
            {{ store.current.errorMessage }}
          </div>

          <!-- 评估分数区域 -->
          <Card v-if="store.evaluation" class="mb-6">
            <CardHeader class="pb-2">
              <div class="flex items-center justify-between">
                <div>
                  <h3 class="text-sm font-medium text-foreground leading-tight">
                    评估分数
                  </h3>
                  <p class="text-xs text-muted-foreground">
                    基于多维度对本次轨迹质量进行离线评估
                  </p>
                </div>
                <div class="text-right">
                  <p class="text-xs text-muted-foreground">
                    综合评分
                  </p>
                  <p
                    class="text-xl font-semibold leading-tight"
                    :class="store.evaluation.overallScore < 0.5
                      ? 'text-red-500'
                      : store.evaluation.overallScore < 0.7
                        ? 'text-yellow-500'
                        : 'text-green-500'"
                  >
                    {{ store.evaluation.overallScore.toFixed(2) }}
                  </p>
                </div>
              </div>
            </CardHeader>
            <CardContent>
              <div class="space-y-3">
                <div
                  v-for="item in [
                    { key: 'toolSelectionScore', label: '工具选择合理性', value: store.evaluation.toolSelectionScore },
                    { key: 'parameterValidityScore', label: '参数合法性与幂等性', value: store.evaluation.parameterValidityScore },
                    { key: 'stepEfficiencyScore', label: '步骤效率', value: store.evaluation.stepEfficiencyScore },
                    { key: 'policyComplianceScore', label: '策略与护栏合规', value: store.evaluation.policyComplianceScore },
                    { key: 'tokenEfficiencyScore', label: 'Token 使用效率', value: store.evaluation.tokenEfficiencyScore },
                  ]"
                  :key="item.key"
                  class="space-y-1"
                >
                  <div class="flex items-center justify-between text-xs">
                    <span class="text-muted-foreground">{{ item.label }}</span>
                    <span class="font-medium text-foreground">
                      {{ item.value.toFixed(2) }}
                    </span>
                  </div>
                  <div class="h-1.5 rounded-full bg-muted overflow-hidden">
                    <div
                      class="h-full rounded-full"
                      :class="scoreColor(item.value)"
                      :style="{ width: scorePercent(item.value) }"
                    />
                  </div>
                </div>

                <div class="mt-3 grid grid-cols-2 gap-3 text-xs text-muted-foreground">
                  <div>
                    <p>实际步骤数：{{ store.evaluation.actualSteps }}</p>
                    <p>实际 Token：{{ store.evaluation.actualTokens }}</p>
                  </div>
                  <div class="text-right">
                    <p>
                      评估时间：{{ new Date(store.evaluation.evaluatedAt).toLocaleString() }}
                    </p>
                  </div>
                </div>

                <div
                  v-if="store.evaluation.violations.length"
                  class="mt-3"
                >
                  <p class="text-xs font-medium text-destructive mb-1">
                    违规项
                  </p>
                  <ul class="list-disc list-inside text-xs text-destructive">
                    <li v-for="v in store.evaluation.violations" :key="v">
                      {{ v }}
                    </li>
                  </ul>
                </div>

                <div
                  v-if="store.evaluation.suggestions.length"
                  class="mt-3"
                >
                  <p class="text-xs font-medium text-foreground mb-1">
                    优化建议
                  </p>
                  <ul class="list-disc list-inside text-xs text-muted-foreground">
                    <li v-for="s in store.evaluation.suggestions" :key="s">
                      {{ s }}
                    </li>
                  </ul>
                </div>
              </div>
            </CardContent>
          </Card>

          <!-- 时间线 -->
          <div class="space-y-0">
            <div
              v-for="step in store.steps"
              :key="step.id"
              class="relative pl-6 pb-4 border-l-2 last:border-l-0"
              :class="getStepConfig(step).borderClass"
            >
              <!-- 节点圆点 -->
              <div
                class="absolute -left-[5px] top-0 w-2 h-2 rounded-full"
                :class="getStepConfig(step).dotClass"
              />

              <div
                class="cursor-pointer"
                @click="toggleStep(step.id)"
              >
                <div class="flex items-center gap-3 text-sm">
                  <span class="font-mono text-muted-foreground">#{{ step.stepIndex }}</span>
                  <span class="text-foreground">{{ step.actionType }}</span>
                  <Badge variant="outline" :class="getStepConfig(step).badgeClass">
                    <component
                      :is="getStepConfig(step).icon"
                      class="w-3 h-3"
                    />
                    <span>{{ getStepConfig(step).label }}</span>
                  </Badge>
                  <span v-if="step.toolId" class="font-mono text-xs text-muted-foreground">{{ step.toolId }}</span>
                  <span class="text-xs text-muted-foreground ml-auto">{{ step.phaseBefore }} → {{ step.phaseAfter }}</span>
                  <span class="text-xs text-muted-foreground">{{ formatDuration(step.latencyMs) }}</span>
                </div>
                <div v-if="step.blocked" class="text-xs text-yellow-600 mt-1">
                  护栏拦截：{{ step.blockReason }}
                </div>
              </div>

              <!-- 展开详情 -->
              <div v-if="expandedSteps.has(step.id)" class="mt-2 space-y-2 text-xs">
                <!-- LLM 调用详情 -->
                <template v-if="resolveStepType(step) === 'llm'">
                  <div class="space-y-1">
                    <span class="text-muted-foreground">模型调用详情：</span>
                    <pre class="p-2 rounded-md bg-muted whitespace-pre-wrap break-words">{{ parseJsonSafe(step.actionJson) }}</pre>
                  </div>
                </template>

                <!-- 工具调用详情 -->
                <template v-else-if="resolveStepType(step) === 'tool'">
                  <div v-if="step.toolInputJson" class="space-y-1">
                    <span class="text-muted-foreground">输入：</span>
                    <pre class="p-2 rounded-md bg-muted whitespace-pre-wrap break-words">{{ step.toolInputJson }}</pre>
                  </div>
                  <div v-if="step.toolOutput" class="space-y-1">
                    <span class="text-muted-foreground">输出：</span>
                    <pre class="p-2 rounded-md bg-muted whitespace-pre-wrap break-words">{{ expandedSteps.has(step.id + '-full') ? step.toolOutput : truncate(step.toolOutput) }}</pre>
                    <Button
                      v-if="step.toolOutput.length > 500 && !expandedSteps.has(step.id + '-full')"
                      variant="link"
                      size="sm"
                      class="h-auto p-0"
                      @click.stop="expandedSteps.add(step.id + '-full')"
                    >
                      展开全部
                    </Button>
                  </div>
                  <div class="flex items-center gap-2 text-xs text-muted-foreground">
                    <span>执行耗时：{{ formatDuration(step.latencyMs) }}</span>
                    <span>Tokens：{{ step.tokensUsed }}</span>
                  </div>
                </template>

                <!-- 护栏步骤详情 -->
                <template v-else-if="resolveStepType(step) === 'guardrail'">
                  <div v-if="step.actionJson" class="space-y-1">
                    <span class="text-muted-foreground">护栏检查详情：</span>
                    <pre class="p-2 rounded-md bg-muted whitespace-pre-wrap break-words">{{ step.actionJson }}</pre>
                  </div>
                </template>

                <!-- 状态切换步骤详情 -->
                <template v-else-if="resolveStepType(step) === 'state'">
                  <div class="space-y-1">
                    <span class="text-muted-foreground">阶段切换</span>
                    <p class="text-xs text-foreground">
                      {{ step.phaseBefore }} → {{ step.phaseAfter }}
                    </p>
                  </div>
                  <div v-if="step.actionJson" class="space-y-1">
                    <span class="text-muted-foreground">动作摘要：</span>
                    <pre class="p-2 rounded-md bg-muted whitespace-pre-wrap break-words">{{ step.actionJson }}</pre>
                  </div>
                </template>

                <!-- 评估步骤详情 -->
                <template v-else>
                  <div class="space-y-1">
                    <span class="text-muted-foreground">评估详情：</span>
                    <pre class="p-2 rounded-md bg-muted whitespace-pre-wrap break-words">{{ step.actionJson }}</pre>
                  </div>
                </template>
              </div>
            </div>
          </div>

          <!-- 最终输出 -->
          <Card v-if="store.current.finalOutput" class="mt-6">
            <CardHeader class="pb-2">
              <h3 class="text-sm font-medium text-foreground">最终输出</h3>
            </CardHeader>
            <CardContent>
              <p class="text-sm text-muted-foreground whitespace-pre-wrap">{{ store.current.finalOutput }}</p>
            </CardContent>
          </Card>
        </template>
      </div>
    </div>
  </div>
</template>
