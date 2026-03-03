<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { analyticsApi, agentApi } from '@/api/client'
import type { AgentStats, KnowledgeBaseStats } from '@/types'
import { Bot, BookOpen, TrendingUp, AlertCircle, Clock } from 'lucide-vue-next'
import EmptyState from '@/components/common/EmptyState.vue'

const loading = ref(false)
const agentStats = ref<AgentStats[]>([])
const kbStats = ref<KnowledgeBaseStats[]>([])
const error = ref<string | null>(null)

// 筛选和排序
const filterType = ref<'all' | 'agent' | 'kb'>('all')
const sortBy = ref<'callCount' | 'avgResponseTime' | 'failureRate'>('callCount')
const sortOrder = ref<'asc' | 'desc'>('desc')

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

// 排序后的 Agent 统计
const sortedAgentStats = computed(() => {
  const sorted = [...agentStats.value]
  sorted.sort((a, b) => {
    let aVal: number, bVal: number
    
    if (sortBy.value === 'callCount') {
      aVal = a.callCount
      bVal = b.callCount
    } else if (sortBy.value === 'avgResponseTime') {
      aVal = a.avgResponseTime
      bVal = b.avgResponseTime
    } else {
      aVal = a.failureRate
      bVal = b.failureRate
    }
    
    return sortOrder.value === 'asc' ? aVal - bVal : bVal - aVal
  })
  return sorted
})

// 排序后的知识库统计
const sortedKbStats = computed(() => {
  const sorted = [...kbStats.value]
  sorted.sort((a, b) => {
    const aVal = sortBy.value === 'callCount' ? a.retrievalCount : (a.hitRate || 0)
    const bVal = sortBy.value === 'callCount' ? b.retrievalCount : (b.hitRate || 0)
    return sortOrder.value === 'asc' ? aVal - bVal : bVal - aVal
  })
  return sorted
})

async function loadStats() {
  loading.value = true
  error.value = null
  
  try {
    const range = timeRange.value
    const timeRangeParam = {
      from: range.from + 'T00:00:00Z',
      to: range.to + 'T23:59:59Z'
    }
    
    // 加载 Agent 统计
    try {
      agentStats.value = await analyticsApi.getAgentStats(timeRangeParam)
    } catch (e: any) {
      if (e.status !== 404 && !e.message?.includes('404')) {
        console.error('Failed to load agent stats:', e)
      }
      // 如果 API 不存在，使用空数组
      agentStats.value = []
    }
    
    // 加载知识库统计
    try {
      kbStats.value = await analyticsApi.getKnowledgeBaseStats(timeRangeParam)
    } catch (e: any) {
      if (e.status !== 404 && !e.message?.includes('404')) {
        console.error('Failed to load KB stats:', e)
      }
      // 如果 API 不存在，使用空数组
      kbStats.value = []
    }
  } catch (e: any) {
    error.value = e.message || '加载统计数据失败'
    console.error('Failed to load analytics:', e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadStats()
})

// 格式化数字
function formatNumber(num: number): string {
  if (num >= 1000000) {
    return (num / 1000000).toFixed(2) + 'M'
  } else if (num >= 1000) {
    return (num / 1000).toFixed(2) + 'K'
  }
  return num.toString()
}

// 格式化时间（毫秒转秒）
function formatTime(ms: number): string {
  if (ms < 1000) return ms + 'ms'
  return (ms / 1000).toFixed(2) + 's'
}

// 格式化百分比
function formatPercent(rate: number): string {
  return (rate * 100).toFixed(2) + '%'
}
</script>

<template>
  <div class="flex flex-col h-full overflow-hidden bg-background">
    <!-- 顶部标题和时间筛选 -->
    <div class="flex-shrink-0 border-b border-border">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-md flex items-center justify-between gap-md">
        <div>
          <h1 class="text-2xl font-semibold text-foreground leading-tight">Agent / 知识库分析</h1>
          <p class="mt-1 text-sm text-muted-foreground">
            查看 Agent 调用统计和知识库检索统计
          </p>
        </div>

        <div class="flex items-center gap-md">
          <!-- 时间范围选择 -->
          <div class="flex items-center gap-xs">
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
            <input
              v-model="customFrom"
              type="date"
              class="px-md py-xs rounded-2xl border border-input bg-background text-sm focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent"
              @change="loadStats"
            />
            <span class="text-muted-foreground text-sm">至</span>
            <input
              v-model="customTo"
              type="date"
              class="px-md py-xs rounded-2xl border border-input bg-background text-sm focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent"
              @change="loadStats"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- 内容区域 -->
    <div class="flex-1 overflow-y-auto">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-lg">
        <!-- 筛选和排序 -->
        <div class="flex items-center gap-md mb-md">
          <div class="flex items-center gap-xs">
            <button
              v-for="type in [
                { value: 'all', label: '全部' },
                { value: 'agent', label: 'Agent' },
                { value: 'kb', label: '知识库' }
              ]"
              :key="type.value"
              class="px-md py-xs rounded-lg text-sm transition-all duration-200 active:scale-[0.98]"
              :class="filterType === type.value
                ? 'bg-primary text-primary-foreground'
                : 'bg-muted text-muted-foreground hover:bg-muted/80'"
              @click="filterType = type.value as any"
            >
              {{ type.label }}
            </button>
          </div>

          <div class="flex items-center gap-xs ml-auto">
            <span class="text-sm text-muted-foreground">排序:</span>
            <select
              v-model="sortBy"
              class="px-md py-xs rounded-lg border border-input bg-background text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            >
              <option value="callCount">调用次数</option>
              <option value="avgResponseTime">平均响应时间</option>
              <option value="failureRate">失败率</option>
            </select>
            <button
              class="px-md py-xs rounded-lg border border-input bg-background text-sm hover:bg-accent transition-all duration-200 active:scale-[0.98]"
              @click="sortOrder = sortOrder === 'asc' ? 'desc' : 'asc'"
            >
              {{ sortOrder === 'asc' ? '↑' : '↓' }}
            </button>
          </div>
        </div>

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

        <!-- Agent / 知识库统计 -->
        <div v-else class="space-y-lg">
          <!-- Agent 统计表格 -->
          <div v-if="filterType === 'all' || filterType === 'agent'" class="space-y-sm">
            <div class="flex items-center gap-xs mb-sm">
              <Bot :size="20" class="text-muted-foreground" />
              <h2 class="text-xl font-semibold text-foreground leading-snug">Agent 使用情况</h2>
            </div>

            <div v-if="sortedAgentStats.length === 0" class="py-lg">
              <EmptyState
                title="暂无 Agent 数据"
                description="当前时间范围内没有 Agent 调用记录"
              />
            </div>

            <div v-else class="rounded-lg border border-border overflow-hidden bg-card">
              <table class="w-full">
                <thead class="bg-muted/50">
                  <tr>
                    <th class="px-md py-sm text-left text-sm font-medium text-foreground">Agent 名称</th>
                    <th class="px-md py-sm text-right text-sm font-medium text-foreground">调用次数</th>
                    <th class="px-md py-sm text-right text-sm font-medium text-foreground">平均响应时间</th>
                    <th class="px-md py-sm text-right text-sm font-medium text-foreground">失败率</th>
                    <th class="px-md py-sm text-right text-sm font-medium text-foreground">总 Token</th>
                  </tr>
                </thead>
                <tbody class="divide-y divide-border">
                  <tr
                    v-for="stat in sortedAgentStats"
                    :key="stat.agentId"
                    class="hover:bg-muted/30 transition-colors"
                  >
                    <td class="px-md py-sm text-sm text-foreground">{{ stat.agentName }}</td>
                    <td class="px-md py-sm text-sm text-right text-foreground">
                      {{ formatNumber(stat.callCount) }}
                    </td>
                    <td class="px-md py-sm text-sm text-right text-foreground">
                      <div class="flex items-center justify-end gap-xs">
                        <Clock :size="14" class="text-muted-foreground" />
                        {{ formatTime(stat.avgResponseTime) }}
                      </div>
                    </td>
                    <td class="px-md py-sm text-sm text-right">
                      <div
                        class="flex items-center justify-end gap-xs"
                        :class="stat.failureRate > 0.1 ? 'text-destructive' : 'text-foreground'"
                      >
                        <AlertCircle v-if="stat.failureRate > 0.1" :size="14" />
                        {{ formatPercent(stat.failureRate) }}
                      </div>
                    </td>
                    <td class="px-md py-sm text-sm text-right text-foreground">
                      {{ formatNumber(stat.totalTokens) }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <!-- 知识库统计表格 -->
          <div v-if="filterType === 'all' || filterType === 'kb'" class="space-y-sm">
            <div class="flex items-center gap-xs mb-sm">
              <BookOpen :size="20" class="text-muted-foreground" />
              <h2 class="text-xl font-semibold text-foreground leading-snug">知识库检索情况</h2>
            </div>

            <div v-if="sortedKbStats.length === 0" class="py-lg">
              <EmptyState
                title="暂无知识库数据"
                description="当前时间范围内没有知识库检索记录"
              />
            </div>

            <div v-else class="rounded-lg border border-border overflow-hidden bg-card">
              <table class="w-full">
                <thead class="bg-muted/50">
                  <tr>
                    <th class="px-md py-sm text-left text-sm font-medium text-foreground">知识库名称</th>
                    <th class="px-md py-sm text-right text-sm font-medium text-foreground">检索次数</th>
                    <th class="px-md py-sm text-right text-sm font-medium text-foreground">命中率</th>
                    <th class="px-md py-sm text-right text-sm font-medium text-foreground">平均检索时间</th>
                  </tr>
                </thead>
                <tbody class="divide-y divide-border">
                  <tr
                    v-for="stat in sortedKbStats"
                    :key="stat.kbId"
                    class="hover:bg-muted/30 transition-colors"
                  >
                    <td class="px-md py-sm text-sm text-foreground">{{ stat.kbName }}</td>
                    <td class="px-md py-sm text-sm text-right text-foreground">
                      {{ formatNumber(stat.retrievalCount) }}
                    </td>
                    <td class="px-md py-sm text-sm text-right text-foreground">
                      <div class="flex items-center justify-end gap-xs">
                        <TrendingUp :size="14" class="text-muted-foreground" />
                        {{ stat.hitRate !== undefined ? formatPercent(stat.hitRate) : 'N/A' }}
                      </div>
                    </td>
                    <td class="px-md py-sm text-sm text-right text-foreground">
                      {{ stat.avgRetrievalTime ? formatTime(stat.avgRetrievalTime) : 'N/A' }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <!-- 完全空状态 -->
          <div
            v-if="filterType === 'all' && sortedAgentStats.length === 0 && sortedKbStats.length === 0"
            class="py-lg"
          >
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
