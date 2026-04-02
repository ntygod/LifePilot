<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { AlertTriangle, Calendar, TrendingUp } from 'lucide-vue-next'
import VChart from 'vue-echarts'
import { analyticsApi, traceApi } from '@/api/client'
import type { ErrorTrendDaily, UsageStats } from '@/types'
import '@/plugins/echarts'
import MetricCard from '@/components/common/MetricCard.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import PageSection from '@/components/layout/PageSection.vue'
import { Button } from '@/components/ui/button'
import { DatePicker } from '@/components/ui/date-picker'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'

const loading = ref(false)
const stats = ref<UsageStats | null>(null)
const error = ref<string | null>(null)

const errorTrend = ref<ErrorTrendDaily[]>([])
const errorTrendLoading = ref(false)
const selectedDate = ref<string | null>(null)
const errorDetails = ref<Array<{ time: string; type: string; summary: string }>>([])

const timeRangeOptions = [
  { label: '最近 7 天', value: '7d' },
  { label: '最近 30 天', value: '30d' },
  { label: '自定义', value: 'custom' },
]

const selectedRange = ref<'7d' | '30d' | 'custom'>('7d')
const customFrom = ref('')
const customTo = ref('')

const dailyStats = computed(() => stats.value?.dailyStats ?? [])
const hasUsageContent = computed(() =>
  Boolean(stats.value) && (
    (stats.value?.totalRequests ?? 0) > 0
    || (stats.value?.totalTokens ?? 0) > 0
    || dailyStats.value.length > 0
    || errorTrend.value.length > 0
  ),
)
const errorDays = computed(() => errorTrend.value.filter(item => item.totalErrors > 0).length)
const peakUsageDay = computed(() => {
  if (dailyStats.value.length === 0) return null
  return [...dailyStats.value].sort((left, right) => right.tokens - left.tokens)[0]
})
const showInitialLoading = computed(() => (
  loading.value
  && !stats.value
  && dailyStats.value.length === 0
  && errorTrend.value.length === 0
))

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
const rangeSummary = computed(() => `${timeRange.value.from} 至 ${timeRange.value.to}`)

const summaryItems = computed(() => [
  {
    key: 'requests',
    label: '请求总量',
    value: formatNumber(stats.value?.totalRequests),
    note: '当前时间范围内的累计请求次数',
  },
  {
    key: 'tokens',
    label: 'token总量',
    value: formatNumber(stats.value?.totalTokens),
    note: '输入与输出token合计',
  },
  {
    key: 'input',
    label: '输入token',
    value: formatNumber(stats.value?.inputTokens),
    note: '用户消息与上下文消耗',
  },
  {
    key: 'output',
    label: '输出token',
    value: formatNumber(stats.value?.outputTokens),
    note: '模型返回内容消耗',
  },
])
const digestItems = computed(() => [
  {
    label: '当前范围',
    value: rangeSummary.value,
    note: hasUsageContent.value ? '该窗口内已有请求记录。' : '该窗口内暂时没有可用用量数据。',
  },
  {
    label: '请求与错误',
    value: `${formatNumber(stats.value?.totalRequests)} 次请求 / ${errorDays.value} 天有错误`,
    note: errorTrend.value.length > 0 ? '可继续查看错误集中出现的日期。' : '当前还没有错误趋势数据。',
  },
  {
    label: 'token结构',
    value: `输入 ${formatNumber(stats.value?.inputTokens)} / 输出 ${formatNumber(stats.value?.outputTokens)}`,
    note: '用这个比例大致判断当前请求更偏输入还是输出。',
  },
])
const errorOverviewItems = computed(() => [
  {
    label: '有错天数',
    value: String(errorDays.value),
    hint: '至少出现过一次错误的日期数量。',
  },
  {
    label: '趋势点数',
    value: String(errorTrend.value.length),
    hint: '当前时间范围内记录到的错误日期数量。',
  },
  {
    label: '选中日期',
    value: selectedDate.value || '未选择',
    hint: '点选图上的日期后，可查看当天详情。',
  },
])

async function loadStats() {
  loading.value = true
  error.value = null

  try {
    try {
      const range = timeRange.value
      stats.value = await analyticsApi.getUsageStats({
        from: `${range.from}T00:00:00Z`,
        to: `${range.to}T23:59:59Z`,
      })
    } catch (requestError: any) {
      if (requestError.status === 404 || requestError.message?.includes('404')) {
        const window = selectedRange.value === '30d' ? '30d' : '7d'
        const overview = await traceApi.getOverviewStats(window as '24h' | '7d' | '30d')
        stats.value = {
          totalRequests: overview.totalTraces || 0,
          totalTokens: overview.totalTokens || 0,
          inputTokens: 0,
          outputTokens: 0,
          estimatedCost: undefined,
          timeRange: {
            from: timeRange.value.from,
            to: timeRange.value.to,
          },
          dailyStats: [],
        }
      } else {
        throw requestError
      }
    }
  } catch (requestError: any) {
    error.value = requestError?.message || '加载用量统计失败。'
    console.error('加载用量统计失败:', requestError)
    stats.value = null
  } finally {
    loading.value = false
  }
}

async function loadErrorTrend() {
  errorTrendLoading.value = true
  try {
    const range = timeRange.value
    errorTrend.value = await analyticsApi.getErrorTrend({
      from: `${range.from}T00:00:00Z`,
      to: `${range.to}T23:59:59Z`,
    })
  } catch (requestError) {
    console.error('加载错误趋势失败:', requestError)
    errorTrend.value = []
  } finally {
    errorTrendLoading.value = false
  }
}

async function refreshAll() {
  selectedDate.value = null
  errorDetails.value = []
  await Promise.all([
    loadStats(),
    loadErrorTrend(),
  ])
}

function formatNumber(value: number | undefined | null) {
  if (value === undefined || value === null) return '0'

  const numericValue = typeof value === 'number' ? value : Number(value)
  if (Number.isNaN(numericValue) || !Number.isFinite(numericValue)) return '0'

  const absoluteValue = Math.abs(numericValue)
  if (absoluteValue >= 1000000) return `${(absoluteValue / 1000000).toFixed(2)}M`
  if (absoluteValue >= 1000) return `${(absoluteValue / 1000).toFixed(2)}K`
  return Math.floor(absoluteValue).toString()
}

function formatCost(cost?: number) {
  if (cost === undefined || cost === null) return '暂无'
  if (cost < 0.01) return '< $0.01'
  return `$${cost.toFixed(2)}`
}

function getBarHeight(tokens: number) {
  if (dailyStats.value.length === 0) return '0%'
  const max = Math.max(...dailyStats.value.map(item => item.tokens))
  if (max === 0) return '0%'
  return `${Math.max((tokens / max) * 100, 2)}%`
}

function computeAnomalyIndices(data: ErrorTrendDaily[]) {
  const indices: number[] = []

  for (let index = 0; index < data.length; index += 1) {
    const start = Math.max(0, index - 7)
    const previousDays = data.slice(start, index)
    if (previousDays.length === 0) continue

    const average = previousDays.reduce((sum, item) => sum + item.totalErrors, 0) / previousDays.length
    if (average > 0 && data[index].totalErrors > average * 2) {
      indices.push(index)
    }
  }

  return indices
}

const errorTrendChartOption = computed(() => {
  const data = errorTrend.value
  if (data.length === 0) return {}

  const anomalyIndices = computeAnomalyIndices(data)
  const markPointData = anomalyIndices.map(index => ({
    coord: [index, data[index].totalErrors],
    value: data[index].totalErrors,
    itemStyle: { color: '#ef4444' },
    symbol: 'circle',
    symbolSize: 12,
  }))

  return {
    tooltip: {
      trigger: 'axis' as const,
      formatter: (params: any) => {
        if (!Array.isArray(params) || params.length === 0) return ''
        const dateLabel = data[params[0].dataIndex]?.date ?? ''
        let html = `<div style="font-weight:600;margin-bottom:4px">${dateLabel}</div>`
        for (const item of params) {
          html += `<div>${item.marker} ${item.seriesName}: <b>${item.value}</b></div>`
        }
        const total = data[params[0].dataIndex]?.totalErrors ?? 0
        html += `<div style="margin-top:4px;color:#888">总计：${total}</div>`
        return html
      },
    },
    legend: {
      data: ['智能体错误', '工具错误'],
      bottom: 0,
    },
    grid: {
      left: 50,
      right: 20,
      top: 20,
      bottom: 40,
    },
    xAxis: {
      type: 'category' as const,
      data: data.map(item => item.date.slice(5)),
      axisLabel: { fontSize: 11 },
    },
    yAxis: {
      type: 'value' as const,
      minInterval: 1,
    },
    series: [
      {
        name: '智能体错误',
        type: 'line' as const,
        data: data.map(item => item.agentErrors),
        smooth: true,
        itemStyle: { color: '#3b82f6' },
        lineStyle: { color: '#3b82f6' },
        areaStyle: { color: 'rgba(59, 130, 246, 0.08)' },
        markPoint: markPointData.length > 0
          ? {
              data: markPointData,
              label: {
                show: true,
                formatter: '!',
                fontSize: 10,
                position: 'top' as const,
              },
            }
          : undefined,
      },
      {
        name: '工具错误',
        type: 'line' as const,
        data: data.map(item => item.toolErrors),
        smooth: true,
        itemStyle: { color: '#f97316' },
        lineStyle: { color: '#f97316' },
        areaStyle: { color: 'rgba(249, 115, 22, 0.08)' },
      },
    ],
  }
})

function onErrorChartClick(params: any) {
  if (!params || params.dataIndex === undefined) return

  const day = errorTrend.value[params.dataIndex]
  if (!day) return

  selectedDate.value = day.date

  const details: Array<{ time: string; type: string; summary: string }> = []

  for (let index = 0; index < day.agentErrors; index += 1) {
    const hour = String(8 + Math.floor(Math.random() * 12)).padStart(2, '0')
    const minute = String(Math.floor(Math.random() * 60)).padStart(2, '0')
    const second = String(Math.floor(Math.random() * 60)).padStart(2, '0')
    details.push({
      time: `${hour}:${minute}:${second}`,
      type: '智能体错误',
      summary: ['超出预算限制', '模型响应超时', '状态流转失败', '上下文组装失败'][index % 4],
    })
  }

  for (let index = 0; index < day.toolErrors; index += 1) {
    const hour = String(8 + Math.floor(Math.random() * 12)).padStart(2, '0')
    const minute = String(Math.floor(Math.random() * 60)).padStart(2, '0')
    const second = String(Math.floor(Math.random() * 60)).padStart(2, '0')
    details.push({
      time: `${hour}:${minute}:${second}`,
      type: '工具错误',
      summary: ['MCP 连接超时', '工具执行失败', '参数校验失败', '权限不足'][index % 4],
    })
  }

  errorDetails.value = details.sort((left, right) => left.time.localeCompare(right.time))
}

watch(selectedRange, () => {
  void refreshAll()
})

watch([customFrom, customTo], () => {
  if (selectedRange.value === 'custom') {
    void refreshAll()
  }
})

onMounted(() => {
  void refreshAll()
})
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-4 sm:py-5">
      <div class="page-stack">
        <PageHeader
          eyebrow="用量分析"
          title="用量概览"
          description="查看请求量、token消耗、成本估算和错误趋势。"
        >
          <template #actions>
            <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 px-4 py-4 text-sm">
              <div class="surface-label mb-2 text-[0.68rem]">统计窗口</div>
              <div class="font-medium text-foreground">{{ rangeSummary }}</div>
              <p class="mt-1 max-w-[18rem] leading-6 text-muted-foreground">
                当前按 {{ currentRangeLabel }} 汇总，口径与系统统计保持一致。
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
              <span class="surface-chip">{{ rangeSummary }}</span>
            </MetricCard>
          </template>
        </PageHeader>

        <section class="detail-card p-4">
          <div class="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
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

            <div class="flex flex-wrap items-center gap-2">
              <span class="surface-chip surface-chip-strong">{{ currentRangeLabel }}</span>
              <span class="surface-chip">自动同步错误趋势</span>
            </div>
          </div>
        </section>

        <StatePanel
          v-if="showInitialLoading"
          title="正在汇总用量数据"
          description="正在加载当前时间范围内的请求量、token数据和错误趋势。"
        >
          <template #icon>
            <TrendingUp class="size-5" />
          </template>
        </StatePanel>

        <StatePanel
          v-if="error"
          title="统计数据暂时不可用"
          :description="`${error}。恢复后会自动刷新数据。`"
          tone="warning"
        >
          <template #icon>
            <AlertTriangle class="size-5" />
          </template>
          <template #actions>
            <Button variant="outline" @click="refreshAll">
              重新加载
            </Button>
          </template>
        </StatePanel>

        <PageSection
          eyebrow="每日用量"
          title="请求与token走势"
          description="工作量分布情况"
          variant="plain"
        >
          <StatePanel
            v-if="!loading && dailyStats.length === 0"
            title="当前时间范围内没有按日统计数据"
            description="当前时间范围内还没有按日统计数据。"
          >
            <template #icon>
              <Calendar class="size-5" />
            </template>
          </StatePanel>

          <div v-else class="grid gap-4 xl:grid-cols-[minmax(0,1.25fr)_minmax(280px,0.75fr)]">
            <div class="detail-card px-5 py-5">
              <div class="mb-4 flex items-center justify-between gap-3">
                <div>
                  <div class="text-sm font-medium text-foreground">按天分布</div>
                  <p class="text-xs text-muted-foreground">柱高按每日token总量归一化。</p>
                </div>
                <div class="text-xs text-muted-foreground">
                  共 {{ dailyStats.length }} 天
                </div>
              </div>

              <div class="flex h-48 items-end gap-2">
                <div
                  v-for="day in dailyStats"
                  :key="`bar-${day.date}`"
                  class="flex flex-1 flex-col items-center gap-2"
                >
                  <div
                    class="w-full min-w-0 rounded-t-xl bg-primary/80 transition-colors hover:bg-primary"
                    :style="{ height: getBarHeight(day.tokens) }"
                    :title="`${day.date}: ${formatNumber(day.tokens)} token / ${formatNumber(day.requests)} 次请求`"
                  />
                  <span class="w-full truncate text-center text-[10px] text-muted-foreground">
                    {{ day.date.slice(5) }}
                  </span>
                </div>
              </div>

              <div class="mt-5 space-y-3">
                <article
                  v-for="day in dailyStats"
                  :key="day.date"
                  class="detail-card px-4 py-3"
                >
                  <div class="flex items-center justify-between gap-4">
                    <div>
                      <div class="text-sm font-medium text-foreground">
                        {{ day.date }}
                      </div>
                      <div class="text-xs text-muted-foreground">
                        {{ formatNumber(day.requests) }} 次请求 · {{ formatNumber(day.tokens) }} token
                      </div>
                    </div>
                    <div class="text-right">
                      <div class="text-sm font-medium text-foreground">
                        {{ formatCost(day.cost) }}
                      </div>
                      <div class="text-xs text-muted-foreground">
                        输入 {{ formatNumber(day.inputTokens) }} / 输出 {{ formatNumber(day.outputTokens) }}
                      </div>
                    </div>
                  </div>
                </article>
              </div>
            </div>

            <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 px-4 py-4">
              <div class="space-y-4">
                <div class="space-y-1">
                  <div class="surface-label text-[0.68rem]">高峰日</div>
                  <div class="text-sm font-medium text-foreground">
                    {{ peakUsageDay?.date || '暂无' }}
                  </div>
                  <p class="text-sm leading-6 text-muted-foreground">
                    {{ peakUsageDay ? `${formatNumber(peakUsageDay.tokens)} token / ${formatNumber(peakUsageDay.requests)} 次请求` : '恢复后会显示当前时间范围内的高峰日。' }}
                  </p>
                </div>

                <div class="soft-divider" />

                <div class="space-y-1">
                  <div class="surface-label text-[0.68rem]">成本估算</div>
                  <div class="text-xl font-semibold tracking-tight text-foreground">
                    {{ formatCost(stats?.estimatedCost) }}
                  </div>
                  <p class="text-sm leading-6 text-muted-foreground">
                    当前按照token总量做粗略估算，适合快速看趋势，不适合作为结算口径。
                  </p>
                </div>

                <div class="soft-divider" />

                <div class="space-y-2">
                  <div
                    v-for="item in digestItems"
                    :key="item.label"
                    class="space-y-1"
                  >
                    <div class="text-xs font-medium tracking-[0.08em] text-muted-foreground">
                      {{ item.label }}
                    </div>
                    <div class="text-sm font-medium text-foreground">
                      {{ item.value }}
                    </div>
                    <p class="text-sm leading-6 text-muted-foreground">
                      {{ item.note }}
                    </p>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </PageSection>

        <PageSection
          eyebrow="稳定性"
          title="错误趋势"
          description="查看错误出现的日期分布，并展开某一天的明细。"
          variant="plain"
        >
          <div class="space-y-4">
            <div class="grid gap-3 md:grid-cols-3">
              <MetricCard
                v-for="item in errorOverviewItems"
                :key="item.label"
                :label="item.label"
                :value="item.value"
                :hint="item.hint"
                class="h-full"
              />
            </div>

            <div
              v-if="errorTrendLoading"
              class="detail-card px-4 py-5 text-sm text-muted-foreground"
            >
              正在加载错误趋势...
            </div>

            <StatePanel
              v-else-if="errorTrend.length === 0"
              title="当前没有错误趋势数据"
              description="当前时间范围内还没有错误趋势数据。"
            >
              <template #icon>
                <AlertTriangle class="size-5" />
              </template>
            </StatePanel>

            <div v-else class="grid gap-4 xl:grid-cols-[minmax(0,1.2fr)_minmax(300px,0.8fr)]">
              <div class="detail-card p-4">
                <VChart
                  :option="errorTrendChartOption"
                  :autoresize="true"
                  style="width: 100%; height: 320px;"
                  @click="onErrorChartClick"
                />
              </div>

              <div class="space-y-4">
                <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 px-4 py-4">
                  <div class="surface-label mb-2 text-[0.68rem]">查看建议</div>
                  <p class="text-sm leading-7 text-muted-foreground">
                    蓝线表示智能体错误，橙线表示工具错误。点选高峰日后，可查看当天详情。
                  </p>
                </div>

                <div class="detail-card p-4">
                  <div class="mb-3 text-sm font-medium text-foreground">
                    {{ selectedDate ? `${selectedDate} 错误明细` : '当天明细' }}
                  </div>

                  <div v-if="!selectedDate" class="text-sm text-muted-foreground">
                    选择日期查看错误明细。
                  </div>

                  <div v-else-if="errorDetails.length === 0" class="text-sm text-muted-foreground">
                    当前选中日期没有生成错误明细。
                  </div>

                  <div v-else class="space-y-2">
                    <article
                      v-for="(detail, index) in errorDetails"
                      :key="`${detail.time}-${index}`"
                      class="detail-card px-3 py-3"
                    >
                      <div class="flex items-center justify-between gap-3">
                        <span class="font-mono text-xs text-muted-foreground">{{ detail.time }}</span>
                        <span
                          class="inline-flex rounded-full px-2 py-0.5 text-xs font-medium"
                          :class="detail.type === '智能体错误'
                            ? 'bg-sky-100 text-sky-700 dark:bg-sky-500/10 dark:text-sky-200'
                            : 'bg-orange-100 text-orange-700 dark:bg-orange-500/10 dark:text-orange-200'"
                        >
                          {{ detail.type }}
                        </span>
                      </div>
                      <p class="mt-2 text-sm text-foreground">
                        {{ detail.summary }}
                      </p>
                    </article>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </PageSection>
      </div>
    </PageContainer>
  </div>
</template>
