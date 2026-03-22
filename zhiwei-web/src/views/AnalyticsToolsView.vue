<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Activity, AlertTriangle, BarChart3, Wrench } from 'lucide-vue-next'
import VChart from 'vue-echarts'
import { analyticsApi } from '@/api/client'
import type { ToolCallStats, ToolDailyTrend } from '@/types'
import '@/plugins/echarts'
import FilterChips from '@/components/common/FilterChips.vue'
import MetricCard from '@/components/common/MetricCard.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import PageSection from '@/components/layout/PageSection.vue'
import { Button } from '@/components/ui/button'

const selectedRange = ref<'7d' | '30d' | '90d'>('7d')
const loading = ref(false)
const error = ref<string | null>(null)
const toolStats = ref<ToolCallStats[]>([])
const dailyTrend = ref<ToolDailyTrend[]>([])

const rangeOptions = [
  { label: '最近 7 天', value: '7d' as const },
  { label: '最近 30 天', value: '30d' as const },
  { label: '最近 90 天', value: '90d' as const },
]

const hasData = computed(() => toolStats.value.length > 0 || dailyTrend.value.length > 0)
const totalCalls = computed(() => toolStats.value.reduce((sum, item) => sum + item.callCount, 0))
const totalFailures = computed(() => toolStats.value.reduce((sum, item) => sum + item.failureCount, 0))
const successRateSummary = computed(() => {
  if (totalCalls.value === 0) return '0.0%'
  return `${(((totalCalls.value - totalFailures.value) / totalCalls.value) * 100).toFixed(1)}%`
})
const topTool = computed(() => toolStats.value[0] ?? null)
const peakTrendDay = computed(() => {
  if (dailyTrend.value.length === 0) return null
  return [...dailyTrend.value].sort((left, right) => right.callCount - left.callCount)[0]
})
const showInitialLoading = computed(() => (
  loading.value
  && toolStats.value.length === 0
  && dailyTrend.value.length === 0
))

const summaryItems = computed(() => [
  {
    key: 'tool-count',
    label: '有记录的工具',
    value: String(toolStats.value.length),
    note: '当前时间范围内有调用记录的工具数量',
  },
  {
    key: 'total-calls',
    label: '调用总量',
    value: totalCalls.value.toLocaleString(),
    note: '当前时间范围内的累计调用次数',
  },
  {
    key: 'success-rate',
    label: '整体成功率',
    value: successRateSummary.value,
    note: '先看整体成功率，更容易判断是否稳定',
  },
  {
    key: 'failure-count',
    label: '失败次数',
    value: totalFailures.value.toLocaleString(),
    note: '帮助判断失败是否在增多',
  },
])

function computeTimeRange(range: '7d' | '30d' | '90d') {
  const now = new Date()
  const to = now.toISOString().split('T')[0]
  const days = range === '7d' ? 7 : range === '30d' ? 30 : 90
  const from = new Date(now.getTime() - days * 24 * 60 * 60 * 1000)
  return {
    from: from.toISOString().split('T')[0],
    to,
  }
}

const currentRangeLabel = computed(() => (
  rangeOptions.find(item => item.value === selectedRange.value)?.label ?? '最近 7 天'
))
const rangeSummary = computed(() => {
  const range = computeTimeRange(selectedRange.value)
  return `${range.from} 至 ${range.to}`
})

async function loadData() {
  loading.value = true
  error.value = null

  try {
    const range = computeTimeRange(selectedRange.value)
    const response = await analyticsApi.getToolAnalytics({
      from: `${range.from}T00:00:00Z`,
      to: `${range.to}T23:59:59Z`,
    })

    toolStats.value = [...response.toolStats].sort((left, right) => right.callCount - left.callCount)
    dailyTrend.value = response.dailyTrend
  } catch (requestError: any) {
    error.value = requestError?.message || '加载工具分析失败。'
    console.error('加载工具分析失败:', requestError)
    toolStats.value = []
    dailyTrend.value = []
  } finally {
    loading.value = false
  }
}

function successRate(stat: ToolCallStats) {
  if (stat.callCount === 0) return '0.0'
  return ((stat.successCount / stat.callCount) * 100).toFixed(1)
}

const trendChartOption = computed(() => ({
  tooltip: {
    trigger: 'axis' as const,
  },
  legend: {
    data: ['成功调用', '失败调用'],
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
    data: dailyTrend.value.map(item => item.date.slice(5)),
    axisLabel: { fontSize: 11 },
  },
  yAxis: {
    type: 'value' as const,
    minInterval: 1,
  },
  series: [
    {
      name: '成功调用',
      type: 'line' as const,
      data: dailyTrend.value.map(item => item.successCount),
      smooth: true,
      itemStyle: { color: '#10b981' },
      lineStyle: { color: '#10b981' },
      areaStyle: { color: 'rgba(16, 185, 129, 0.08)' },
    },
    {
      name: '失败调用',
      type: 'line' as const,
      data: dailyTrend.value.map(item => item.failureCount),
      smooth: true,
      itemStyle: { color: '#ef4444' },
      lineStyle: { color: '#ef4444' },
      areaStyle: { color: 'rgba(239, 68, 68, 0.08)' },
    },
  ],
}))

watch(selectedRange, () => {
  void loadData()
})

onMounted(() => {
  void loadData()
})
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-6 sm:py-8">
      <div class="page-stack">
        <PageHeader
          eyebrow="工具分析"
          title="工具调用表现"
          description="查看工具的调用频次、成功率和最近趋势，方便发现异常变化。"
        >
          <template #actions>
            <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 px-4 py-4 text-sm">
              <div class="surface-label mb-2 text-[0.68rem]">重点工具</div>
              <div class="font-medium text-foreground">
                {{ topTool?.toolName || '暂无' }}
              </div>
              <p class="mt-1 max-w-[18rem] leading-6 text-muted-foreground">
                {{ topTool ? `${topTool.callCount.toLocaleString()} 次调用 · ${successRate(topTool)}% 成功率` : '恢复后会显示当前时间范围内调用最多的工具。' }}
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
              <span class="surface-chip">{{ currentRangeLabel }}</span>
            </MetricCard>
          </template>
        </PageHeader>

        <section class="detail-card p-5">
          <div class="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
            <div class="flex flex-wrap items-center gap-3">
              <span class="text-sm font-medium text-foreground">时间范围</span>
              <FilterChips v-model="selectedRange" :options="rangeOptions" size="sm" />
            </div>

            <div class="flex flex-wrap items-center gap-2">
              <span class="surface-chip surface-chip-strong">{{ rangeSummary }}</span>
              <span class="surface-chip">每日趋势自动更新</span>
            </div>
          </div>
        </section>

        <StatePanel
          v-if="showInitialLoading"
          title="正在汇总工具调用数据"
          description="正在加载当前时间范围内的工具排行和每日趋势。"
        >
          <template #icon>
            <Wrench class="size-5" />
          </template>
        </StatePanel>

        <StatePanel
          v-if="error"
          title="工具分析暂时不可用"
          :description="`${error}。恢复后可继续查看工具排行和趋势。`"
          tone="warning"
        >
          <template #icon>
            <AlertTriangle class="size-5" />
          </template>
          <template #actions>
            <Button variant="outline" @click="loadData">
              重新加载
            </Button>
          </template>
        </StatePanel>

        <PageSection
          eyebrow="排行"
          title="工具调用表"
          description="表格聚焦于三件事：调用频次、成功率和平均耗时。"
          variant="plain"
        >
          <div class="grid gap-4 xl:grid-cols-[minmax(0,1fr)_280px]">
            <div>
              <StatePanel
                v-if="!loading && toolStats.length === 0"
                title="当前没有工具分析数据"
                description="有新的调用记录后，会显示工具排行。"
              >
                <template #icon>
                  <Wrench class="size-5" />
                </template>
              </StatePanel>

              <div v-else class="detail-card overflow-hidden">
                <table class="min-w-full text-sm">
                  <thead class="border-b border-border/70 bg-muted/35">
                    <tr>
                      <th class="px-4 py-3 text-left font-medium text-foreground">工具</th>
                      <th class="px-4 py-3 text-right font-medium text-foreground">调用次数</th>
                      <th class="px-4 py-3 text-right font-medium text-foreground">成功率</th>
                      <th class="px-4 py-3 text-right font-medium text-foreground">平均耗时</th>
                    </tr>
                  </thead>
                  <tbody class="divide-y divide-border/70">
                    <tr
                      v-for="stat in toolStats"
                      :key="stat.toolId"
                      class="transition-colors hover:bg-muted/20"
                    >
                      <td class="px-4 py-3 font-medium text-foreground">
                        {{ stat.toolName }}
                      </td>
                      <td class="px-4 py-3 text-right tabular-nums text-foreground">
                        {{ stat.callCount.toLocaleString() }}
                      </td>
                      <td class="px-4 py-3 text-right tabular-nums">
                        <span
                          :class="Number(successRate(stat)) >= 90
                            ? 'text-emerald-600 dark:text-emerald-300'
                            : Number(successRate(stat)) >= 70
                              ? 'text-amber-600 dark:text-amber-300'
                              : 'text-destructive'"
                        >
                          {{ successRate(stat) }}%
                        </span>
                      </td>
                      <td class="px-4 py-3 text-right tabular-nums text-foreground">
                        {{ stat.avgLatencyMs }}ms
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>

            <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 px-4 py-4">
              <div class="space-y-4">
                <div class="space-y-1">
                  <div class="surface-label text-[0.68rem]">调用高峰</div>
                  <div class="text-sm font-medium text-foreground">
                    {{ peakTrendDay?.date || '暂无' }}
                  </div>
                  <p class="text-sm leading-6 text-muted-foreground">
                    {{ peakTrendDay ? `${peakTrendDay.callCount.toLocaleString()} 次调用，失败 ${peakTrendDay.failureCount.toLocaleString()} 次。` : '当前时间范围内还没有每日调用记录。' }}
                  </p>
                </div>

                <div class="soft-divider" />

                <div class="space-y-1">
                  <div class="surface-label text-[0.68rem]">建议查看顺序</div>
                  <p class="text-sm leading-7 text-muted-foreground">
                    查看工具调用统计。
                  </p>
                </div>
              </div>
            </div>
          </div>
        </PageSection>

        <PageSection
          eyebrow="趋势"
          title="每日调用走势"
          description="对比成功和失败调用，找出异常出现的时间段。"
          variant="plain"
        >
          <StatePanel
            v-if="!loading && !hasData"
            title="当前没有趋势数据"
            description="调整时间范围或等待新调用后再查看。"
          >
            <template #icon>
              <Activity class="size-5" />
            </template>
          </StatePanel>

          <div v-else class="detail-card p-4">
            <VChart
              :option="trendChartOption"
              :autoresize="true"
              style="width: 100%; height: 320px;"
            />
          </div>
        </PageSection>
      </div>
    </PageContainer>
  </div>
</template>
