<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import { MessageCircle, Wrench, Shield, ArrowRight, BarChart3, Download } from 'lucide-vue-next'
import { useTraceStore } from '@/stores/trace'
import type { TraceItem, TraceStep } from '@/types'

const store = useTraceStore()
const expandedSteps = ref<Set<string>>(new Set())
const route = useRoute()

// 搜索与筛选
const searchKeyword = ref('')
const statusFilter = ref<'all' | 'success' | 'failure'>('all')
const lastSearchedKeyword = ref('')

// 概览统计时间窗口
const timeWindow = ref<'24h' | '7d' | '30d'>('7d')

// 导出状态
const exporting = ref(false)

// 计算当前是否处于搜索模式
const isSearching = computed(() => lastSearchedKeyword.value.trim().length > 0)

// 当前展示的列表（考虑搜索与筛选）
const filteredTraces = computed<TraceItem[]>(() => {
  const baseList = isSearching.value ? store.searchResults : store.list

  return baseList.filter((trace) => {
    if (statusFilter.value === 'success' && !trace.success) return false
    if (statusFilter.value === 'failure' && trace.success) return false
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
}

function backToList() {
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
  <div class="flex flex-col h-full px-6 py-6 overflow-y-auto">
    <!-- 错误提示 -->
    <div v-if="store.error" class="mb-4 p-4 rounded-md bg-destructive/10 text-destructive text-sm">
      {{ store.error }}
    </div>

    <!-- 轨迹列表 -->
    <template v-if="!store.current">
      <h2 class="text-xl font-semibold text-foreground mb-4">轨迹回放</h2>

      <!-- 搜索栏 + 状态筛选 -->
      <div class="mb-4 flex flex-wrap items-center gap-4">
        <div class="flex-1 flex items-center gap-4 min-w-[260px]">
          <input
            v-model="searchKeyword"
            type="search"
            placeholder="按用户问题或 Trace ID 搜索轨迹…"
            class="flex-1 h-9 rounded-2xl border border-input bg-background px-4 text-sm
                   placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent transition-all duration-200"
            @keyup.enter="handleSearch"
          >
          <button
            type="button"
            class="px-4 py-2 rounded-lg bg-primary text-primary-foreground text-sm font-medium
                   hover:bg-primary/90 hover:shadow-md focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 active:scale-[0.98] transition-all duration-200"
            @click="handleSearch"
          >
            搜索
          </button>
          <button
            v-if="isSearching"
            type="button"
            class="px-3 py-2 rounded-lg border border-border text-xs text-muted-foreground
                   hover:bg-accent hover:text-accent-foreground transition-colors"
            @click="handleClearSearch"
          >
            清空
          </button>
        </div>

        <div class="flex items-center gap-4 text-xs">
          <span class="text-muted-foreground">状态：</span>
          <button
            type="button"
            class="px-4 py-2 rounded-lg border text-xs font-medium transition-all duration-200 focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
            :class="statusFilter === 'all'
              ? 'bg-accent text-accent-foreground border-accent shadow-sm'
              : 'border-border text-muted-foreground hover:bg-accent/60 hover:text-accent-foreground'"
            @click="statusFilter = 'all'"
          >
            全部
          </button>
          <button
            type="button"
            class="px-4 py-2 rounded-lg border text-xs font-medium transition-all duration-200 focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
            :class="statusFilter === 'success'
              ? 'bg-emerald-50 text-emerald-700 border-emerald-200 shadow-sm'
              : 'border-border text-muted-foreground hover:bg-accent/60 hover:text-accent-foreground'"
            @click="statusFilter = 'success'"
          >
            仅成功
          </button>
          <button
            type="button"
            class="px-4 py-2 rounded-lg border text-xs font-medium transition-all duration-200 focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
            :class="statusFilter === 'failure'
              ? 'bg-red-50 text-red-700 border-red-200 shadow-sm'
              : 'border-border text-muted-foreground hover:bg-accent/60 hover:text-accent-foreground'"
            @click="statusFilter = 'failure'"
          >
            仅失败
          </button>
        </div>
      </div>

      <!-- 概览统计卡片 -->
      <div class="mb-6 space-y-4">
        <div class="flex items-center justify-between gap-4">
          <p class="text-sm text-muted-foreground">
            最近整体运行情况
          </p>
          <div class="flex items-center gap-4 text-xs">
            <button
              type="button"
              class="px-4 py-2 rounded-full border transition-all duration-200 focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
              :class="timeWindow === '24h'
                ? 'bg-accent text-accent-foreground border-accent shadow-sm'
                : 'border-border text-muted-foreground hover:bg-accent/60 hover:text-accent-foreground'"
              @click="handleChangeTimeWindow('24h')"
            >
              24 小时
            </button>
            <button
              type="button"
              class="px-4 py-2 rounded-full border transition-all duration-200 focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
              :class="timeWindow === '7d'
                ? 'bg-accent text-accent-foreground border-accent shadow-sm'
                : 'border-border text-muted-foreground hover:bg-accent/60 hover:text-accent-foreground'"
              @click="handleChangeTimeWindow('7d')"
            >
              7 天
            </button>
            <button
              type="button"
              class="px-4 py-2 rounded-full border transition-all duration-200 focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
              :class="timeWindow === '30d'
                ? 'bg-accent text-accent-foreground border-accent shadow-sm'
                : 'border-border text-muted-foreground hover:bg-accent/60 hover:text-accent-foreground'"
              @click="handleChangeTimeWindow('30d')"
            >
              30 天
            </button>
          </div>
        </div>

        <div
          v-if="store.overviewStats"
          class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-4"
        >
          <div class="rounded-lg bg-card border border-border p-3 shadow-sm">
            <p class="text-xs text-muted-foreground mb-1">轨迹总数</p>
            <p class="text-xl font-semibold text-foreground leading-tight">
              {{ store.overviewStats.totalTraces }}
            </p>
          </div>
          <div class="rounded-lg bg-card border border-border p-3 shadow-sm">
            <p class="text-xs text-muted-foreground mb-1">成功率</p>
            <p class="text-xl font-semibold text-foreground leading-tight">
              {{ (store.overviewStats.successRate * 100).toFixed(1) }}%
            </p>
          </div>
          <div class="rounded-lg bg-card border border-border p-3 shadow-sm">
            <p class="text-xs text-muted-foreground mb-1">平均步骤数</p>
            <p class="text-xl font-semibold text-foreground leading-tight">
              {{ store.overviewStats.avgSteps.toFixed(1) }}
            </p>
          </div>
          <div class="rounded-lg bg-card border border-border p-3 shadow-sm">
            <p class="text-xs text-muted-foreground mb-1">平均耗时</p>
            <p class="text-xl font-semibold text-foreground leading-tight">
              {{ formatDuration(store.overviewStats.avgDurationMs) }}
            </p>
          </div>
          <div class="rounded-lg bg-card border border-border p-3 shadow-sm">
            <p class="text-xs text-muted-foreground mb-1">总 Token 消耗</p>
            <p class="text-xl font-semibold text-foreground leading-tight">
              {{ store.overviewStats.totalTokens }}
            </p>
          </div>
        </div>
        <div
          v-else
          class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-4 text-xs text-muted-foreground"
        >
          <div
            v-for="i in 5"
            :key="i"
              class="rounded-lg bg-muted/60 border border-dashed border-border p-4"
          >
            加载统计中…
          </div>
        </div>
      </div>

      <!-- 列表 / 空状态 -->
      <div v-if="store.loading && !isSearching" class="text-sm text-muted-foreground">
        加载中...
      </div>
      <div
        v-else-if="isSearching && lastSearchedKeyword && store.searchResults.length === 0"
        class="text-sm text-muted-foreground"
      >
        未找到匹配的轨迹
      </div>
      <div
        v-else-if="!isSearching && store.list.length === 0"
        class="text-sm text-muted-foreground"
      >
        暂无轨迹记录
      </div>
      <div v-else class="space-y-4">
        <div
          v-for="trace in filteredTraces"
          :key="trace.id"
          class="border border-border rounded-lg p-4 cursor-pointer hover:border-primary/50 transition-colors"
          @click="selectTrace(trace.id)"
        >
          <div class="flex items-center gap-4">
            <span
              class="w-2 h-2 rounded-full shrink-0"
              :class="trace.success ? 'bg-green-500' : 'bg-red-500'"
            />
            <span class="text-sm text-foreground truncate flex-1">{{ trace.userMessage }}</span>
            <span class="text-xs text-muted-foreground shrink-0">{{ formatDuration(trace.durationMs) }}</span>
          </div>
          <div class="flex items-center gap-4 mt-2 text-xs text-muted-foreground">
            <span>{{ trace.totalSteps }} 步</span>
            <span>{{ trace.totalTokens }} tokens</span>
            <span class="ml-auto">{{ new Date(trace.createdAt).toLocaleString() }}</span>
          </div>
        </div>
      </div>

      <!-- 分页 -->
      <div
        v-if="totalPages > 1 && !isSearching"
        class="flex items-center justify-center gap-4 mt-6"
      >
        <button
          class="text-sm px-3 py-1 rounded-md border border-input hover:bg-accent transition-colors disabled:opacity-50"
          :disabled="store.page <= 0"
          @click="store.fetchList(store.page - 1)"
        >上一页</button>
        <span class="text-sm text-muted-foreground">{{ store.page + 1 }} / {{ totalPages }}</span>
        <button
          class="text-sm px-3 py-1 rounded-md border border-input hover:bg-accent transition-colors disabled:opacity-50"
          :disabled="store.page >= totalPages - 1"
          @click="store.fetchList(store.page + 1)"
        >下一页</button>
      </div>
    </template>

    <!-- 轨迹详情 -->
    <template v-else>
      <div class="flex items-center gap-3 mb-4">
        <button
          class="text-sm text-muted-foreground hover:text-foreground transition-colors"
          @click="backToList"
        >← 返回</button>
        <h2 class="text-xl font-semibold text-foreground truncate">
          {{ store.current.userMessage }}
        </h2>
        <button
          type="button"
          class="ml-auto inline-flex items-center gap-2 px-3 py-1.5 rounded-lg border border-border text-xs font-medium
                 hover:bg-accent hover:text-accent-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 active:scale-[0.98] transition-all duration-200"
          :disabled="exporting"
          @click="handleExportCurrent"
        >
          <Download class="w-4 h-4" />
          <span>{{ exporting ? '导出中…' : '导出 JSON' }}</span>
        </button>
      </div>

      <!-- 汇总信息 -->
      <div class="flex items-center gap-6 mb-6 p-4 rounded-lg bg-muted text-sm">
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
      </div>

      <div v-if="store.current.errorMessage" class="mb-4 p-3 rounded-md bg-destructive/10 text-destructive text-sm">
        {{ store.current.errorMessage }}
      </div>

      <!-- 评估分数区域 -->
      <div
        v-if="store.evaluation"
        class="mb-6 p-4 rounded-lg border border-border bg-card shadow-sm"
      >
        <div class="flex items-center justify-between mb-3">
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
      </div>

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
              <span
                class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px]"
                :class="getStepConfig(step).badgeClass"
              >
                <component
                  :is="getStepConfig(step).icon"
                  class="w-3 h-3"
                />
                <span>{{ getStepConfig(step).label }}</span>
              </span>
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
                <pre class="p-2 rounded-md bg-muted whitespace-pre-wrap break-words">
{{ parseJsonSafe(step.actionJson) }}</pre>
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
                <button
                  v-if="step.toolOutput.length > 500 && !expandedSteps.has(step.id + '-full')"
                  class="text-primary hover:underline"
                  @click.stop="expandedSteps.add(step.id + '-full')"
                >展开全部</button>
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
      <div v-if="store.current.finalOutput" class="mt-6 p-4 rounded-lg border border-border">
        <h3 class="text-sm font-medium text-foreground mb-2">最终输出</h3>
        <p class="text-sm text-muted-foreground whitespace-pre-wrap">{{ store.current.finalOutput }}</p>
      </div>
    </template>
  </div>
</template>
