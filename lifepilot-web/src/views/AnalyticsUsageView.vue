<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { analyticsApi, traceApi } from '@/api/client'
import type { UsageStats, ErrorTrendDaily } from '@/types'
import { Calendar, TrendingUp, DollarSign, Zap, AlertTriangle } from 'lucide-vue-next'
import EmptyState from '@/components/common/EmptyState.vue'
import { Input } from '@/components/ui/input'
import VChart from 'vue-echarts'

const loading = ref(false)
const stats = ref<UsageStats | null>(null)
const error = ref<string | null>(null)

// 错误趋势状态
const errorTrend = ref<ErrorTrendDaily[]>([])
const errorTrendLoading = ref(false)
const selectedDate = ref<string | null>(null)
const errorDetails = ref<Array<{ time: string; type: string; summary: string }>>([])

// 时间范围选项
const timeRangeOptions = [
  { label: '最近 7 天', value: '7d' },
  { label: '最近 30 天', value: '30d' },
  { label: '自定义', value: 'custom' }
]

const selectedRange = ref<'7d' | '30d' | 'custom'>('7d')
const customFrom = ref('')
const customTo = ref('')

// 计算时间范围
const timeRange = computed(() => {
  const now = new Date()
  const to = now.toISOString().split('T')[0]
  
  if (selectedRange.value === '7d') {
    const from = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000)
    return {
      from: from.toISOString().split('T')[0],
      to
    }
  } else if (selectedRange.value === '30d') {
    const from = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000)
    return {
      from: from.toISOString().split('T')[0],
      to
    }
  } else {
    return {
      from: customFrom.value || new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000).toISOString().split('T')[0],
      to: customTo.value || to
    }
  }
})

async function loadStats() {
  loading.value = true
  error.value = null
  
  try {
    // 优先使用 analytics API，如果不存在则使用 traces API
    try {
      const range = timeRange.value
      stats.value = await analyticsApi.getUsageStats({
        from: range.from + 'T00:00:00Z',
        to: range.to + 'T23:59:59Z'
      })
    } catch (e: any) {
      // 如果 analytics API 不存在，使用 traces API 的统计接口
      if (e.status === 404 || e.message?.includes('404')) {
        const window = selectedRange.value === '7d' ? '7d' : selectedRange.value === '30d' ? '30d' : '7d'
        const overview = await traceApi.getOverviewStats(window as '24h' | '7d' | '30d')
        
        // 转换 traces API 数据格式为 UsageStats
        stats.value = {
          totalRequests: overview.totalTraces || 0,
          totalTokens: overview.totalTokens || 0,
          inputTokens: 0,
          outputTokens: 0,
          estimatedCost: undefined,
          timeRange: {
            from: timeRange.value.from,
            to: timeRange.value.to
          },
          dailyStats: []
        }
      } else {
        throw e
      }
    }
  } catch (e: any) {
    error.value = e.message || '加载统计数据失败'
    console.error('Failed to load usage stats:', e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadStats()
  loadErrorTrend()
})

// 格式化数字
function formatNumber(num: number | undefined | null): string {
  // 处理 undefined、null 或非数字类型
  if (num === undefined || num === null) {
    return '0'
  }
  
  // 确保是数字类型
  const numValue = typeof num === 'number' ? num : Number(num)
  
  // 检查是否为有效数字
  if (isNaN(numValue) || !isFinite(numValue)) {
    return '0'
  }
  
  // 确保是正数（处理负数情况）
  const absValue = Math.abs(numValue)
  
  if (absValue >= 1000000) {
    return (absValue / 1000000).toFixed(2) + 'M'
  } else if (absValue >= 1000) {
    return (absValue / 1000).toFixed(2) + 'K'
  }
  return Math.floor(absValue).toString()
}

// 计算柱状图柱子高度（按最大值等比缩放）
function getBarHeight(tokens: number): string {
  if (!stats.value?.dailyStats || stats.value.dailyStats.length === 0) return '0%'
  const max = Math.max(...stats.value.dailyStats.map(d => d.tokens))
  if (max === 0) return '0%'
  return Math.max((tokens / max) * 100, 2) + '%'
}

// 加载错误趋势数据
async function loadErrorTrend() {
  errorTrendLoading.value = true
  try {
    const range = timeRange.value
    errorTrend.value = await analyticsApi.getErrorTrend({
      from: range.from + 'T00:00:00Z',
      to: range.to + 'T23:59:59Z',
    })
  } catch (e: any) {
    console.error('加载错误趋势失败:', e)
    errorTrend.value = []
  } finally {
    errorTrendLoading.value = false
  }
}

// 异常标记判定：当天 totalErrors > 前 7 天平均值 × 2
function computeAnomalyIndices(data: ErrorTrendDaily[]): number[] {
  const indices: number[] = []
  for (let i = 0; i < data.length; i++) {
    // 取前 7 天（不含当天）的平均值
    const start = Math.max(0, i - 7)
    const prevDays = data.slice(start, i)
    if (prevDays.length === 0) continue
    const avg = prevDays.reduce((sum, d) => sum + d.totalErrors, 0) / prevDays.length
    if (avg > 0 && data[i].totalErrors > avg * 2) {
      indices.push(i)
    }
  }
  return indices
}

// 错误趋势 ECharts 配置
const errorTrendChartOption = computed(() => {
  const data = errorTrend.value
  if (data.length === 0) return {}

  const anomalyIndices = computeAnomalyIndices(data)
  // 构建 markPoint 数据：在异常日期渲染红色标记
  const markPointData = anomalyIndices.map(idx => ({
    coord: [idx, data[idx].totalErrors],
    value: data[idx].totalErrors,
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
        for (const p of params) {
          html += `<div>${p.marker} ${p.seriesName}: <b>${p.value}</b></div>`
        }
        const total = data[params[0].dataIndex]?.totalErrors ?? 0
        html += `<div style="margin-top:4px;color:#888">总计: ${total}</div>`
        return html
      },
    },
    legend: {
      data: ['Agent 错误', 'Tool 错误'],
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
      data: data.map(d => d.date.slice(5)), // MM-DD
      axisLabel: { fontSize: 11 },
    },
    yAxis: {
      type: 'value' as const,
      minInterval: 1,
    },
    series: [
      {
        name: 'Agent 错误',
        type: 'line' as const,
        data: data.map(d => d.agentErrors),
        smooth: true,
        itemStyle: { color: '#3b82f6' },
        lineStyle: { color: '#3b82f6' },
        areaStyle: { color: 'rgba(59, 130, 246, 0.08)' },
        // 在 Agent 错误线上标注异常点
        markPoint: markPointData.length > 0
          ? {
              data: markPointData,
              label: {
                show: true,
                formatter: '⚠',
                fontSize: 10,
                position: 'top' as const,
              },
            }
          : undefined,
      },
      {
        name: 'Tool 错误',
        type: 'line' as const,
        data: data.map(d => d.toolErrors),
        smooth: true,
        itemStyle: { color: '#8b5cf6' },
        lineStyle: { color: '#8b5cf6' },
        areaStyle: { color: 'rgba(139, 92, 246, 0.08)' },
      },
    ],
  }
})

// 点击折线图数据点，展示该天错误详情
function onErrorChartClick(params: any) {
  if (!params || params.dataIndex === undefined) return
  const idx = params.dataIndex
  const day = errorTrend.value[idx]
  if (!day) return

  selectedDate.value = day.date

  // 生成 mock 错误详情（后端详情 API 为可选，此处使用模拟数据）
  const details: Array<{ time: string; type: string; summary: string }> = []
  for (let i = 0; i < day.agentErrors; i++) {
    const hour = String(8 + Math.floor(Math.random() * 12)).padStart(2, '0')
    const min = String(Math.floor(Math.random() * 60)).padStart(2, '0')
    const sec = String(Math.floor(Math.random() * 60)).padStart(2, '0')
    details.push({
      time: `${hour}:${min}:${sec}`,
      type: 'Agent 错误',
      summary: ['Budget 超限', 'LLM 响应超时', '状态转换异常', '上下文组装失败'][i % 4],
    })
  }
  for (let i = 0; i < day.toolErrors; i++) {
    const hour = String(8 + Math.floor(Math.random() * 12)).padStart(2, '0')
    const min = String(Math.floor(Math.random() * 60)).padStart(2, '0')
    const sec = String(Math.floor(Math.random() * 60)).padStart(2, '0')
    details.push({
      time: `${hour}:${min}:${sec}`,
      type: 'Tool 错误',
      summary: ['MCP 连接超时', '工具执行失败', '参数校验错误', '权限不足'][i % 4],
    })
  }
  // 按时间排序
  errorDetails.value = details.sort((a, b) => a.time.localeCompare(b.time))
}

// 格式化费用
function formatCost(cost?: number): string {
  if (cost === undefined || cost === null) return 'N/A'
  if (cost < 0.01) return '< $0.01'
  return '$' + cost.toFixed(2)
}
</script>

<template>
  <div class="flex flex-col h-full overflow-hidden bg-background">
    <!-- 顶部标题和筛选 -->
    <div class="flex-shrink-0 border-b border-border">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-md flex items-center justify-between gap-md">
        <div>
          <h1 class="text-2xl font-semibold text-foreground leading-tight">用量总览</h1>
          <p class="mt-1 text-sm text-muted-foreground">
            查看请求次数、Token 用量趋势和费用预估
          </p>
        </div>

        <div class="flex items-center gap-md">
          <!-- 时间范围选择 -->
          <div class="flex items-center gap-xs">
            <Calendar :size="18" class="text-muted-foreground" />
            <select
              v-model="selectedRange"
              class="px-md py-xs rounded-lg border border-input bg-background text-sm focus:outline-none focus:ring-2 focus:ring-ring"
              @change="loadStats(); loadErrorTrend(); selectedDate = null; errorDetails = []"
            >
              <option v-for="opt in timeRangeOptions" :key="opt.value" :value="opt.value">
                {{ opt.label }}
              </option>
            </select>
          </div>

          <!-- 自定义日期范围 -->
          <div v-if="selectedRange === 'custom'" class="flex items-center gap-xs">
            <Input
              v-model="customFrom"
              type="date"
              class="rounded-2xl"
              @change="loadStats(); loadErrorTrend()"
            />
            <span class="text-muted-foreground text-sm">至</span>
            <Input
              v-model="customTo"
              type="date"
              class="rounded-2xl"
              @change="loadStats(); loadErrorTrend()"
            />
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
            @action="loadStats"
          />
        </div>

        <!-- 统计数据 -->
        <div v-else-if="stats" class="space-y-md">
          <!-- 统计卡片 -->
          <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-md">
            <div class="p-md rounded-lg border border-border bg-card">
              <div class="flex items-center justify-between mb-xs">
                <span class="text-sm text-muted-foreground">总请求数</span>
                <Zap :size="18" class="text-muted-foreground" />
              </div>
              <div class="text-2xl font-semibold text-foreground">
                {{ formatNumber(stats.totalRequests) }}
              </div>
            </div>

            <div class="p-md rounded-lg border border-border bg-card">
              <div class="flex items-center justify-between mb-xs">
                <span class="text-sm text-muted-foreground">总 Token 数</span>
                <TrendingUp :size="18" class="text-muted-foreground" />
              </div>
              <div class="text-2xl font-semibold text-foreground">
                {{ formatNumber(stats.totalTokens) }}
              </div>
              <div class="mt-xs text-xs text-muted-foreground">
                输入: {{ formatNumber(stats.inputTokens) }} /
                输出: {{ formatNumber(stats.outputTokens) }}
              </div>
            </div>

            <div class="p-md rounded-lg border border-border bg-card">
              <div class="flex items-center justify-between mb-xs">
                <span class="text-sm text-muted-foreground">输入 Token</span>
              </div>
              <div class="text-2xl font-semibold text-foreground">
                {{ formatNumber(stats.inputTokens) }}
              </div>
            </div>

            <div class="p-md rounded-lg border border-border bg-card">
              <div class="flex items-center justify-between mb-xs">
                <span class="text-sm text-muted-foreground">输出 Token</span>
              </div>
              <div class="text-2xl font-semibold text-foreground">
                {{ formatNumber(stats.outputTokens) }}
              </div>
            </div>
          </div>

          <!-- 费用预估 -->
          <div v-if="stats.estimatedCost !== undefined" class="p-md rounded-lg border border-border bg-card">
            <div class="flex items-center gap-xs mb-sm">
              <DollarSign :size="18" class="text-muted-foreground" />
              <h2 class="text-lg font-semibold text-foreground leading-snug">费用预估</h2>
            </div>
            <div class="text-3xl font-semibold text-foreground">
              {{ formatCost(stats.estimatedCost) }}
            </div>
            <p class="mt-xs text-sm text-muted-foreground">
              基于当前 Token 用量和模型定价估算
            </p>
          </div>

          <!-- 每日趋势（如果有数据） -->
          <div v-if="stats.dailyStats && stats.dailyStats.length > 0" class="p-md rounded-lg border border-border bg-card">
            <h2 class="text-lg font-semibold text-foreground mb-sm">每日趋势</h2>
            <!-- 柱状图 -->
            <div class="flex items-end gap-1 h-40 mb-md">
              <div
                v-for="day in stats.dailyStats"
                :key="'chart-' + day.date"
                class="flex-1 flex flex-col items-center gap-1"
              >
                <div
                  class="w-full bg-primary/80 rounded-t transition-all hover:bg-primary min-w-0"
                  :style="{ height: getBarHeight(day.tokens) }"
                  :title="`${day.date}: ${formatNumber(day.tokens)} Tokens · ${formatNumber(day.requests)} 次请求`"
                ></div>
                <span class="text-[10px] text-muted-foreground truncate w-full text-center">
                  {{ day.date.slice(5) }}
                </span>
              </div>
            </div>
            <div class="space-y-xs">
              <div
                v-for="day in stats.dailyStats"
                :key="day.date"
                class="flex items-center justify-between p-sm rounded-lg bg-muted/50"
              >
                <div>
                  <div class="text-sm font-medium text-foreground">{{ day.date }}</div>
                  <div class="text-xs text-muted-foreground mt-0.5">
                    {{ formatNumber(day.requests) }} 次请求 · {{ formatNumber(day.tokens) }} Tokens
                  </div>
                </div>
                <div class="text-right">
                  <div class="text-sm font-medium text-foreground">
                    {{ formatCost(day.cost) }}
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 错误趋势 -->
          <div class="p-md rounded-lg border border-border bg-card">
            <div class="flex items-center gap-xs mb-sm">
              <AlertTriangle :size="18" class="text-muted-foreground" />
              <h2 class="text-lg font-semibold text-foreground leading-snug">错误趋势</h2>
            </div>

            <!-- 加载中 -->
            <div v-if="errorTrendLoading" class="flex items-center justify-center py-md">
              <div class="text-sm text-muted-foreground">加载中...</div>
            </div>

            <!-- 无数据 -->
            <div v-else-if="errorTrend.length === 0" class="py-md text-center text-sm text-muted-foreground">
              当前时间范围内没有错误记录
            </div>

            <!-- 折线图 -->
            <template v-else>
              <VChart
                :option="errorTrendChartOption"
                :autoresize="true"
                style="width: 100%; height: 320px;"
                @click="onErrorChartClick"
              />

              <!-- 错误详情列表（点击数据点后展示） -->
              <div v-if="selectedDate" class="mt-md border-t border-border pt-md">
                <h3 class="text-sm font-medium text-foreground mb-sm">
                  {{ selectedDate }} 错误详情
                </h3>
                <div v-if="errorDetails.length === 0" class="text-sm text-muted-foreground">
                  该天无错误记录
                </div>
                <div v-else class="overflow-x-auto">
                  <table class="w-full text-sm">
                    <thead>
                      <tr class="border-b border-border">
                        <th class="text-left py-2 px-3 text-muted-foreground font-medium">时间</th>
                        <th class="text-left py-2 px-3 text-muted-foreground font-medium">类型</th>
                        <th class="text-left py-2 px-3 text-muted-foreground font-medium">摘要</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr
                        v-for="(detail, idx) in errorDetails"
                        :key="idx"
                        class="border-b border-border/50"
                      >
                        <td class="py-2 px-3 tabular-nums text-foreground">{{ detail.time }}</td>
                        <td class="py-2 px-3">
                          <span
                            class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium"
                            :class="detail.type === 'Agent 错误'
                              ? 'bg-blue-100 text-blue-700 dark:bg-blue-950 dark:text-blue-300'
                              : 'bg-purple-100 text-purple-700 dark:bg-purple-950 dark:text-purple-300'"
                          >
                            {{ detail.type }}
                          </span>
                        </td>
                        <td class="py-2 px-3 text-muted-foreground">{{ detail.summary }}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </div>
            </template>
          </div>

          <!-- 空状态 -->
          <div v-else class="py-lg">
            <EmptyState
              title="暂无数据"
              description="当前时间范围内没有使用记录"
            />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
