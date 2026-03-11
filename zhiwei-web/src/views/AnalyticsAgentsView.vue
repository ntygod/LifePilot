<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { AlertCircle, BookOpen, Bot, Clock3, Sparkles } from 'lucide-vue-next'
import { analyticsApi } from '@/api/client'
import type { AgentStats, KnowledgeBaseStats } from '@/types'
import FilterChips from '@/components/common/FilterChips.vue'
import MetricCard from '@/components/common/MetricCard.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import PageSection from '@/components/layout/PageSection.vue'
import { Button } from '@/components/ui/button'
import { DatePicker } from '@/components/ui/date-picker'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'

const loading = ref(false)
const agentStats = ref<AgentStats[]>([])
const kbStats = ref<KnowledgeBaseStats[]>([])
const error = ref<string | null>(null)

const filterType = ref<'all' | 'agent' | 'kb'>('all')
const sortBy = ref<'callCount' | 'avgResponseTime' | 'failureRate'>('callCount')
const sortOrder = ref<'asc' | 'desc'>('desc')

const timeRangeOptions = [
  { label: '最近 7 天', value: '7d' },
  { label: '最近 30 天', value: '30d' },
  { label: '自定义', value: 'custom' },
]

const entityOptions = [
  { label: '全部', value: 'all' },
  { label: '智能体', value: 'agent' },
  { label: '知识库', value: 'kb' },
]

const selectedRange = ref<'7d' | '30d' | 'custom'>('7d')
const customFrom = ref('')
const customTo = ref('')

const timeRange = computed(() => {
  const now = new Date()
  const to = now.toISOString().split('T')[0]

  if (selectedRange.value === '7d') {
    const from = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000)
    return {
      from: from.toISOString().split('T')[0],
      to,
    }
  }

  if (selectedRange.value === '30d') {
    const from = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000)
    return {
      from: from.toISOString().split('T')[0],
      to,
    }
  }

  return {
    from: customFrom.value || new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000).toISOString().split('T')[0],
    to: customTo.value || to,
  }
})

const currentRangeLabel = computed(() => (
  selectedRange.value === 'custom'
    ? '自定义区间'
    : timeRangeOptions.find(item => item.value === selectedRange.value)?.label ?? '最近 7 天'
))
const currentEntityLabel = computed(() => (
  entityOptions.find(item => item.value === filterType.value)?.label ?? '全部'
))
const sortOptions = computed(() => [
  {
    label: filterType.value === 'kb' ? '检索次数' : '调用次数',
    value: 'callCount',
  },
  {
    label: filterType.value === 'kb' ? '平均检索耗时' : '平均响应时间',
    value: 'avgResponseTime',
  },
  {
    label: filterType.value === 'kb' ? '命中率' : '失败率',
    value: 'failureRate',
  },
])
const currentSortLabel = computed(() => (
  sortOptions.value.find(option => option.value === sortBy.value)?.label ?? '调用次数'
))
const rangeSummary = computed(() => `${timeRange.value.from} 至 ${timeRange.value.to}`)

const sortedAgentStats = computed(() => {
  const next = [...agentStats.value]
  next.sort((left, right) => {
    let leftValue: number
    let rightValue: number

    if (sortBy.value === 'callCount') {
      leftValue = left.callCount
      rightValue = right.callCount
    } else if (sortBy.value === 'avgResponseTime') {
      leftValue = left.avgResponseTime
      rightValue = right.avgResponseTime
    } else {
      leftValue = left.failureRate
      rightValue = right.failureRate
    }

    return sortOrder.value === 'asc' ? leftValue - rightValue : rightValue - leftValue
  })
  return next
})

const sortedKbStats = computed(() => {
  const next = [...kbStats.value]
  next.sort((left, right) => {
    let leftValue: number
    let rightValue: number

    if (sortBy.value === 'callCount') {
      leftValue = left.retrievalCount
      rightValue = right.retrievalCount
    } else if (sortBy.value === 'avgResponseTime') {
      leftValue = left.avgRetrievalTime || 0
      rightValue = right.avgRetrievalTime || 0
    } else {
      leftValue = left.hitRate || 0
      rightValue = right.hitRate || 0
    }

    return sortOrder.value === 'asc' ? leftValue - rightValue : rightValue - leftValue
  })
  return next
})

const totalAgentCalls = computed(() => agentStats.value.reduce((sum, item) => sum + item.callCount, 0))
const totalKbRetrievals = computed(() => kbStats.value.reduce((sum, item) => sum + item.retrievalCount, 0))
const averageFailureRate = computed(() => {
  if (agentStats.value.length === 0) return '0.00%'
  const average = agentStats.value.reduce((sum, item) => sum + item.failureRate, 0) / agentStats.value.length
  return `${(average * 100).toFixed(2)}%`
})
const topAgent = computed(() => sortedAgentStats.value[0] ?? null)
const topKnowledgeBase = computed(() => sortedKbStats.value[0] ?? null)
const showInitialLoading = computed(() => (
  loading.value
  && agentStats.value.length === 0
  && kbStats.value.length === 0
))

const summaryItems = computed(() => [
  {
    key: 'agent-count',
    label: '有记录的智能体',
    value: String(agentStats.value.length),
    note: '当前时间范围内有调用记录的智能体数量',
  },
  {
    key: 'agent-calls',
    label: '智能体调用总量',
    value: formatNumber(totalAgentCalls.value),
    note: '便于判断是否需要继续往单个智能体拆分',
  },
  {
    key: 'kb-retrievals',
    label: '知识库检索总量',
    value: formatNumber(totalKbRetrievals.value),
    note: '看看知识库是否持续被用到',
  },
  {
    key: 'failure-rate',
    label: '平均失败率',
    value: averageFailureRate.value,
    note: '先看整体情况，再决定是否深入查看',
  },
])
const focusItems = computed(() => [
  {
    key: 'agent',
    label: '调用最多的智能体',
    value: topAgent.value?.agentName || '暂无',
    note: topAgent.value
      ? `${formatNumber(topAgent.value.callCount)} 次调用 · ${formatTime(topAgent.value.avgResponseTime)} 平均响应`
      : '当前时间范围内没有智能体调用记录。',
  },
  {
    key: 'kb',
    label: '检索最多的知识库',
    value: topKnowledgeBase.value?.kbName || '暂无',
    note: topKnowledgeBase.value
      ? `${formatNumber(topKnowledgeBase.value.retrievalCount)} 次检索 · ${topKnowledgeBase.value.hitRate !== undefined ? formatPercent(topKnowledgeBase.value.hitRate) : '暂无'} 命中率`
      : '当前时间范围内没有知识库检索记录。',
  },
])

async function loadStats() {
  loading.value = true
  error.value = null

  try {
    const range = timeRange.value
    const params = {
      from: `${range.from}T00:00:00Z`,
      to: `${range.to}T23:59:59Z`,
    }

    try {
      agentStats.value = await analyticsApi.getAgentStats(params)
    } catch (requestError: any) {
      if (requestError.status !== 404 && !requestError.message?.includes('404')) {
        console.error('加载智能体统计失败:', requestError)
      }
      agentStats.value = []
    }

    try {
      kbStats.value = await analyticsApi.getKnowledgeBaseStats(params)
    } catch (requestError: any) {
      if (requestError.status !== 404 && !requestError.message?.includes('404')) {
        console.error('加载知识库统计失败:', requestError)
      }
      kbStats.value = []
    }
  } catch (requestError: any) {
    error.value = requestError?.message || '加载分析数据失败。'
    console.error('加载分析数据失败:', requestError)
  } finally {
    loading.value = false
  }
}

function formatNumber(value: number) {
  if (value >= 1000000) return `${(value / 1000000).toFixed(2)}M`
  if (value >= 1000) return `${(value / 1000).toFixed(2)}K`
  return value.toString()
}

function formatTime(ms: number) {
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(2)}s`
}

function formatPercent(rate: number) {
  return `${(rate * 100).toFixed(2)}%`
}

watch(selectedRange, () => {
  void loadStats()
})

watch([customFrom, customTo], () => {
  if (selectedRange.value === 'custom') {
    void loadStats()
  }
})

onMounted(() => {
  void loadStats()
})
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-6 sm:py-8">
      <div class="page-stack">
        <PageHeader
          eyebrow="分析"
          title="智能体与知识库使用情况"
          description="查看智能体和知识库在当前时间范围内的调用情况、响应速度和失败率。"
        >
          <template #actions>
            <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 px-4 py-4 text-sm">
              <div class="surface-label mb-2 text-[0.68rem]">当前视角</div>
              <div class="font-medium text-foreground">{{ currentEntityLabel }}</div>
              <p class="mt-1 max-w-[18rem] leading-6 text-muted-foreground">
                {{ rangeSummary }} · {{ currentSortLabel }} {{ sortOrder === 'asc' ? '升序' : '降序' }}
              </p>
            </div>
          </template>
          <template #meta>
            <MetricCard
              v-for="item in summaryItems"
              :key="item.key"
              :label="item.label"
              :value="item.value"
              :hint="item.note"
              class="h-full"
            >
              <div class="flex flex-wrap gap-2">
                <span class="surface-chip">{{ currentRangeLabel }}</span>
                <span class="surface-chip">{{ currentEntityLabel }}</span>
              </div>
            </MetricCard>
          </template>
        </PageHeader>

        <section class="detail-card p-5">
          <div class="grid gap-4 xl:grid-cols-[minmax(0,1fr)_auto_auto] xl:items-center">
            <div class="flex flex-wrap items-center gap-3">
              <span class="text-sm font-medium text-foreground">时间范围</span>
              <Select v-model="selectedRange">
                <SelectTrigger class="w-[160px] bg-background">
                  <SelectValue placeholder="选择时间范围" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem v-for="option in timeRangeOptions" :key="option.value" :value="option.value">
                    {{ option.label }}
                  </SelectItem>
                </SelectContent>
              </Select>

              <div v-if="selectedRange === 'custom'" class="flex flex-wrap items-center gap-2">
                <DatePicker v-model="customFrom" placeholder="开始日期" class="w-[160px]" />
                <DatePicker v-model="customTo" placeholder="结束日期" class="w-[160px]" />
              </div>
            </div>

            <div class="flex flex-wrap items-center gap-3">
              <span class="text-sm font-medium text-foreground">查看对象</span>
              <FilterChips v-model="filterType" :options="entityOptions" size="sm" />
            </div>

            <div class="flex flex-wrap items-center gap-3 xl:justify-end">
              <Select v-model="sortBy">
                <SelectTrigger class="w-[180px] bg-background">
                  <SelectValue placeholder="选择排序字段" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem
                    v-for="option in sortOptions"
                    :key="option.value"
                    :value="option.value"
                  >
                    {{ option.label }}
                  </SelectItem>
                </SelectContent>
              </Select>

              <Button variant="outline" @click="sortOrder = sortOrder === 'asc' ? 'desc' : 'asc'">
                {{ sortOrder === 'asc' ? '升序' : '降序' }}
              </Button>
            </div>
          </div>
        </section>

        <StatePanel
          v-if="showInitialLoading"
          title="正在汇总对象使用情况"
          description="正在拉取智能体调用和知识库检索数据。"
        >
          <template #icon>
            <Sparkles class="size-5" />
          </template>
        </StatePanel>

        <StatePanel
          v-if="error"
          title="分析数据暂时不可用"
          :description="`${error}。恢复后可继续查看当前时间范围的数据。`"
          tone="warning"
        >
          <template #icon>
            <AlertCircle class="size-5" />
          </template>
          <template #actions>
            <Button variant="outline" @click="loadStats">
              重新加载
            </Button>
          </template>
        </StatePanel>

        <PageSection
          eyebrow="重点对象"
          title="优先关注对象"
          description="把最可能影响体验的对象先拎出来，再回头看完整表格。"
          variant="plain"
        >
          <div class="grid gap-4 xl:grid-cols-[minmax(0,1fr)_280px]">
            <div class="space-y-3">
              <article
                v-for="item in focusItems"
                :key="item.key"
                class="list-card p-4"
              >
                <div class="surface-label mb-2 text-[0.68rem]">{{ item.label }}</div>
                <div class="text-sm font-medium text-foreground">
                  {{ item.value }}
                </div>
                <p class="mt-1 text-sm leading-6 text-muted-foreground">
                  {{ item.note }}
                </p>
              </article>
            </div>

            <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 px-4 py-4">
              <div class="space-y-4 text-sm leading-7 text-muted-foreground">
                <div>
                  <div class="surface-label mb-2 text-[0.68rem]">查看提示</div>
                  <p>先确认谁最常用，再看谁更慢、谁更不稳定。</p>
                </div>
                <div class="soft-divider" />
                <p>如果当前没有明显问题，可以切换到知识库视角，检查检索命中率和平均耗时。</p>
                <div class="flex flex-wrap gap-2">
                  <span class="surface-chip surface-chip-strong">{{ currentEntityLabel }}</span>
                  <span class="surface-chip">{{ currentSortLabel }}</span>
                  <span class="surface-chip">{{ sortOrder === 'asc' ? '升序' : '降序' }}</span>
                </div>
              </div>
            </div>
          </div>
        </PageSection>

        <PageSection
          v-if="filterType === 'all' || filterType === 'agent'"
          eyebrow="智能体"
          title="智能体使用表"
          description="表格直接回答三个问题：谁用得最多、谁最慢、谁最不稳定。"
          variant="plain"
        >
          <template #actions>
            <span class="surface-chip">{{ currentSortLabel }} {{ sortOrder === 'asc' ? '升序' : '降序' }}</span>
          </template>

          <StatePanel
            v-if="!loading && sortedAgentStats.length === 0"
            title="当前没有智能体分析结果"
            description="有新的调用记录后，可查看这里的使用情况。"
          >
            <template #icon>
              <Bot class="size-5" />
            </template>
          </StatePanel>

          <div v-else class="detail-card overflow-hidden">
            <table class="min-w-full text-sm">
              <thead class="border-b border-border/70 bg-muted/35">
                <tr>
                  <th class="px-4 py-3 text-left font-medium text-foreground">智能体</th>
                  <th class="px-4 py-3 text-right font-medium text-foreground">调用次数</th>
                  <th class="px-4 py-3 text-right font-medium text-foreground">平均响应时间</th>
                  <th class="px-4 py-3 text-right font-medium text-foreground">失败率</th>
                  <th class="px-4 py-3 text-right font-medium text-foreground">总词元</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-border/70">
                <tr
                  v-for="stat in sortedAgentStats"
                  :key="stat.agentId"
                  class="transition-colors hover:bg-muted/20"
                >
                  <td class="px-4 py-3 font-medium text-foreground">
                    {{ stat.agentName }}
                  </td>
                  <td class="px-4 py-3 text-right tabular-nums text-foreground">
                    {{ formatNumber(stat.callCount) }}
                  </td>
                  <td class="px-4 py-3 text-right tabular-nums text-foreground">
                    <span class="inline-flex items-center gap-1">
                      <Clock3 class="size-3.5 text-muted-foreground" />
                      {{ formatTime(stat.avgResponseTime) }}
                    </span>
                  </td>
                  <td class="px-4 py-3 text-right tabular-nums">
                    <span
                      :class="stat.failureRate > 0.1 ? 'text-destructive' : 'text-foreground'"
                      class="inline-flex items-center gap-1"
                    >
                      <AlertCircle v-if="stat.failureRate > 0.1" class="size-3.5" />
                      {{ formatPercent(stat.failureRate) }}
                    </span>
                  </td>
                  <td class="px-4 py-3 text-right tabular-nums text-foreground">
                    {{ formatNumber(stat.totalTokens) }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </PageSection>

        <PageSection
          v-if="filterType === 'all' || filterType === 'kb'"
          eyebrow="知识库"
          title="知识库检索表"
          description="把检索量、命中率和平均耗时放在一张表里，方便判断知识库是否真的在发挥作用。"
          variant="plain"
        >
          <template #actions>
            <span class="surface-chip">{{ currentSortLabel }} {{ sortOrder === 'asc' ? '升序' : '降序' }}</span>
          </template>

          <StatePanel
            v-if="!loading && sortedKbStats.length === 0"
            title="当前没有知识库分析结果"
            description="当前时间范围内还没有知识库检索记录。"
          >
            <template #icon>
              <BookOpen class="size-5" />
            </template>
          </StatePanel>

          <div v-else class="detail-card overflow-hidden">
            <table class="min-w-full text-sm">
              <thead class="border-b border-border/70 bg-muted/35">
                <tr>
                  <th class="px-4 py-3 text-left font-medium text-foreground">知识库</th>
                  <th class="px-4 py-3 text-right font-medium text-foreground">检索次数</th>
                  <th class="px-4 py-3 text-right font-medium text-foreground">命中率</th>
                  <th class="px-4 py-3 text-right font-medium text-foreground">平均检索耗时</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-border/70">
                <tr
                  v-for="stat in sortedKbStats"
                  :key="stat.kbId"
                  class="transition-colors hover:bg-muted/20"
                >
                  <td class="px-4 py-3 font-medium text-foreground">
                    {{ stat.kbName }}
                  </td>
                  <td class="px-4 py-3 text-right tabular-nums text-foreground">
                    {{ formatNumber(stat.retrievalCount) }}
                  </td>
                  <td class="px-4 py-3 text-right tabular-nums text-foreground">
                    {{ stat.hitRate !== undefined ? formatPercent(stat.hitRate) : '暂无' }}
                  </td>
                  <td class="px-4 py-3 text-right tabular-nums text-foreground">
                    {{ stat.avgRetrievalTime ? formatTime(stat.avgRetrievalTime) : '暂无' }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </PageSection>
      </div>
    </PageContainer>
  </div>
</template>
