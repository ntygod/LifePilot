<!--
  工具调用统计页面。

  展示 Tool 调用排行榜（按调用次数降序）和调用趋势折线图（成功/失败双线）。
  支持 7 天 / 30 天 / 90 天时间范围切换。

  @author zsg
  @since 2026-03-18
-->
<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { analyticsApi } from '@/api/client'
import type { ToolCallStats, ToolDailyTrend } from '@/types'
import VChart from 'vue-echarts'
import { Wrench } from 'lucide-vue-next'
import EmptyState from '@/components/common/EmptyState.vue'

// 状态
const selectedRange = ref<'7d' | '30d' | '90d'>('7d')
const loading = ref(false)
const error = ref<string | null>(null)
const toolStats = ref<ToolCallStats[]>([])
const dailyTrend = ref<ToolDailyTrend[]>([])

// 时间范围选项
const rangeOptions = [
  { label: '最近 7 天', value: '7d' as const },
  { label: '最近 30 天', value: '30d' as const },
  { label: '最近 90 天', value: '90d' as const },
]

// 根据选中范围计算 from/to ISO 日期字符串
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

// 加载数据
async function loadData() {
  loading.value = true
  error.value = null
  try {
    const range = computeTimeRange(selectedRange.value)
    const resp = await analyticsApi.getToolAnalytics({
      from: range.from + 'T00:00:00Z',
      to: range.to + 'T23:59:59Z',
    })
    toolStats.value = [...resp.toolStats].sort((a, b) => b.callCount - a.callCount)
    dailyTrend.value = resp.dailyTrend
  } catch (e: any) {
    error.value = e.message || '加载工具统计数据失败'
    console.error('Failed to load tool analytics:', e)
  } finally {
    loading.value = false
  }
}

// 切换时间范围时重新加载
watch(selectedRange, () => loadData())

onMounted(() => loadData())

// 是否有数据
const hasData = computed(() => toolStats.value.length > 0 || dailyTrend.value.length > 0)

// 计算成功率（保留一位小数）
function successRate(stat: ToolCallStats): string {
  if (stat.callCount === 0) return '0.0'
  return ((stat.successCount / stat.callCount) * 100).toFixed(1)
}

// 折线图 ECharts 配置
const trendChartOption = computed(() => ({
  tooltip: {
    trigger: 'axis' as const,
  },
  legend: {
    data: ['成功次数', '失败次数'],
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
    data: dailyTrend.value.map(d => d.date.slice(5)), // 只展示 MM-DD
    axisLabel: { fontSize: 11 },
  },
  yAxis: {
    type: 'value' as const,
    minInterval: 1,
  },
  series: [
    {
      name: '成功次数',
      type: 'line' as const,
      data: dailyTrend.value.map(d => d.successCount),
      smooth: true,
      itemStyle: { color: '#10b981' },
      lineStyle: { color: '#10b981' },
      areaStyle: { color: 'rgba(16, 185, 129, 0.08)' },
    },
    {
      name: '失败次数',
      type: 'line' as const,
      data: dailyTrend.value.map(d => d.failureCount),
      smooth: true,
      itemStyle: { color: '#ef4444' },
      lineStyle: { color: '#ef4444' },
      areaStyle: { color: 'rgba(239, 68, 68, 0.08)' },
    },
  ],
}))
</script>

<template>
  <div class="flex flex-col h-full overflow-hidden bg-background">
    <!-- 顶部标题和时间范围选择器 -->
    <div class="flex-shrink-0 border-b border-border">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-md flex items-center justify-between gap-md">
        <div>
          <h1 class="text-2xl font-semibold text-foreground leading-tight">工具调用统计</h1>
          <p class="mt-1 text-sm text-muted-foreground">
            查看各 Tool 的调用频次、成功率和平均耗时
          </p>
        </div>

        <!-- 时间范围按钮组 -->
        <div class="flex items-center gap-xs">
          <Wrench :size="18" class="text-muted-foreground" />
          <div class="flex rounded-lg border border-input overflow-hidden">
            <button
              v-for="opt in rangeOptions"
              :key="opt.value"
              class="px-3 py-1.5 text-sm transition-colors"
              :class="selectedRange === opt.value
                ? 'bg-primary text-primary-foreground'
                : 'bg-background text-foreground hover:bg-muted'"
              @click="selectedRange = opt.value"
            >
              {{ opt.label }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 内容区域 -->
    <div class="flex-1 overflow-y-auto">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-lg">
        <!-- 加载状态 -->
        <div v-if="loading" class="flex items-center justify-center py-lg">
          <div class="text-sm text-muted-foreground">加载中...</div>
        </div>

        <!-- 错误状态 -->
        <div v-else-if="error" class="py-lg">
          <EmptyState
            title="加载失败"
            :description="error"
            action-label="重试"
            :show-action="true"
            @action="loadData"
          />
        </div>

        <!-- 空数据 -->
        <div v-else-if="!hasData" class="py-lg">
          <EmptyState
            icon="🔧"
            title="暂无工具调用数据"
            description="当前时间范围内没有工具调用记录"
          />
        </div>

        <!-- 统计数据 -->
        <div v-else class="space-y-md">
          <!-- Tool 调用排行榜 -->
          <div class="p-md rounded-lg border border-border bg-card">
            <h2 class="text-lg font-semibold text-foreground mb-sm">调用排行榜</h2>
            <div class="overflow-x-auto">
              <table class="w-full text-sm">
                <thead>
                  <tr class="border-b border-border">
                    <th class="text-left py-2 px-3 text-muted-foreground font-medium">工具名称</th>
                    <th class="text-right py-2 px-3 text-muted-foreground font-medium">调用次数</th>
                    <th class="text-right py-2 px-3 text-muted-foreground font-medium">成功率</th>
                    <th class="text-right py-2 px-3 text-muted-foreground font-medium">平均耗时</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="stat in toolStats"
                    :key="stat.toolId"
                    class="border-b border-border/50 hover:bg-muted/50 transition-colors"
                  >
                    <td class="py-2.5 px-3 font-medium text-foreground">{{ stat.toolName }}</td>
                    <td class="py-2.5 px-3 text-right tabular-nums text-foreground">
                      {{ stat.callCount.toLocaleString() }}
                    </td>
                    <td class="py-2.5 px-3 text-right tabular-nums">
                      <span
                        :class="Number(successRate(stat)) >= 90
                          ? 'text-green-600 dark:text-green-400'
                          : Number(successRate(stat)) >= 70
                            ? 'text-yellow-600 dark:text-yellow-400'
                            : 'text-destructive'"
                      >
                        {{ successRate(stat) }}%
                      </span>
                    </td>
                    <td class="py-2.5 px-3 text-right tabular-nums text-muted-foreground">
                      {{ stat.avgLatencyMs }}ms
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <!-- Tool 调用趋势折线图 -->
          <div v-if="dailyTrend.length > 0" class="p-md rounded-lg border border-border bg-card">
            <h2 class="text-lg font-semibold text-foreground mb-sm">调用趋势</h2>
            <VChart
              :option="trendChartOption"
              :autoresize="true"
              style="width: 100%; height: 320px;"
            />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
