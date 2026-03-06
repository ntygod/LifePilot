<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { analyticsApi, traceApi } from '@/api/client'
import type { UsageStats } from '@/types'
import { Calendar, TrendingUp, DollarSign, Zap } from 'lucide-vue-next'
import EmptyState from '@/components/common/EmptyState.vue'
import { Input } from '@/components/ui/input'

const loading = ref(false)
const stats = ref<UsageStats | null>(null)
const error = ref<string | null>(null)

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
              @change="loadStats"
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
              @change="loadStats"
            />
            <span class="text-muted-foreground text-sm">至</span>
            <Input
              v-model="customTo"
              type="date"
              class="rounded-2xl"
              @change="loadStats"
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
