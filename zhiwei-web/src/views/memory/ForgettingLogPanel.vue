<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RotateCcw } from 'lucide-vue-next'
import { memoryApi } from '@/api/client'
import type { ForgettingLog, ForgettingLogListParams } from '@/types'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { DatePicker } from '@/components/ui/date-picker'
import Pagination from '@/components/common/Pagination.vue'

// ── 筛选状态 ──
const filterTimeFrom = ref('')
const filterTimeTo = ref('')
const filterStrategy = ref<string>('')

// ── 列表状态 ──
const PAGE_SIZE = 20
const currentPage = ref(0)
const items = ref<ForgettingLog[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref<string | null>(null)

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

// ── 遗忘策略选项 ──
const FORGETTING_STRATEGIES = [
  { value: 'TIME_DECAY', label: '时间衰减' },
  { value: 'LOW_IMPORTANCE', label: '低重要性' },
  { value: 'DUPLICATE', label: '重复' },
  { value: 'CAPACITY', label: '容量限制' },
  { value: 'MANUAL', label: '手动' },
] as const

// ── 数据加载 ──
async function loadLogs() {
  loading.value = true
  error.value = null
  try {
    const params: ForgettingLogListParams = {
      page: currentPage.value,
      size: PAGE_SIZE,
    }
    if (filterTimeFrom.value) params.timeFrom = filterTimeFrom.value
    if (filterTimeTo.value) params.timeTo = filterTimeTo.value
    if (filterStrategy.value) params.strategy = filterStrategy.value

    const result = await memoryApi.listForgettingLogs(params)
    items.value = result.items
    total.value = result.total
  } catch (e: any) {
    console.error('加载遗忘日志失败:', e)
    error.value = e?.message || '加载遗忘日志失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

function handlePageChange(page: number) {
  currentPage.value = page
  loadLogs()
}

// 筛选条件变化时重新加载
watch([filterTimeFrom, filterTimeTo, filterStrategy], () => {
  currentPage.value = 0
  loadLogs()
})

onMounted(() => loadLogs())

// ── 辅助函数 ──
function formatDate(iso: string) {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  })
}
</script>

<template>
  <div class="space-y-4">
    <!-- 筛选栏 -->
    <div class="detail-card p-4">
      <div class="flex flex-wrap items-end gap-3">
        <!-- 开始时间 -->
        <div>
          <label class="text-xs text-muted-foreground mb-1 block">开始时间</label>
          <DatePicker v-model="filterTimeFrom" placeholder="开始日期" class="w-36" />
        </div>

        <!-- 结束时间 -->
        <div>
          <label class="text-xs text-muted-foreground mb-1 block">结束时间</label>
          <DatePicker v-model="filterTimeTo" placeholder="结束日期" class="w-36" />
        </div>

        <!-- 遗忘策略下拉框 -->
        <div class="w-40">
          <label class="text-xs text-muted-foreground mb-1 block">遗忘策略</label>
          <Select v-model="filterStrategy">
            <SelectTrigger>
              <SelectValue placeholder="全部策略" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="">全部策略</SelectItem>
              <SelectItem v-for="s in FORGETTING_STRATEGIES" :key="s.value" :value="s.value">
                {{ s.label }}
              </SelectItem>
            </SelectContent>
          </Select>
        </div>
      </div>
    </div>

    <!-- 加载中骨架屏 -->
    <div v-if="loading" class="detail-card p-4 space-y-3">
      <Skeleton v-for="i in 6" :key="i" class="h-10 w-full" />
    </div>

    <!-- 错误状态 -->
    <div v-else-if="error" class="detail-card px-6 py-8 text-center">
      <p class="text-sm text-muted-foreground">{{ error }}</p>
      <Button variant="outline" class="mt-4" @click="loadLogs">
        <RotateCcw class="mr-1.5 size-4" />
        重试
      </Button>
    </div>

    <!-- 空状态 -->
    <div v-else-if="items.length === 0" class="detail-card px-6 py-12 text-center">
      <p class="text-sm text-muted-foreground">暂无遗忘日志</p>
      <p class="mt-1 text-xs text-muted-foreground">遗忘日志会在系统自动清理记忆时生成。</p>
    </div>

    <!-- 遗忘日志表格 -->
    <div v-else class="detail-card overflow-x-auto">
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-border/60">
            <th class="px-4 py-3 text-left font-medium text-muted-foreground">实体名称</th>
            <th class="px-4 py-3 text-left font-medium text-muted-foreground">遗忘策略</th>
            <th class="px-4 py-3 text-left font-medium text-muted-foreground">执行动作</th>
            <th class="px-4 py-3 text-right font-medium text-muted-foreground">遗忘优先级</th>
            <th class="px-4 py-3 text-left font-medium text-muted-foreground">原因</th>
            <th class="px-4 py-3 text-left font-medium text-muted-foreground">创建时间</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="log in items"
            :key="log.id"
            class="border-b border-border/40 transition-colors hover:bg-muted/50"
          >
            <td class="px-4 py-3 font-medium text-foreground">{{ log.entityName }}</td>
            <td class="px-4 py-3">
              <span class="rounded-md bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">
                {{ log.strategy }}
              </span>
            </td>
            <td class="px-4 py-3 text-muted-foreground">{{ log.actionTaken }}</td>
            <td class="px-4 py-3 text-right tabular-nums">{{ log.forgettingPriority.toFixed(2) }}</td>
            <td class="px-4 py-3 text-muted-foreground max-w-xs truncate" :title="log.reason">
              {{ log.reason }}
            </td>
            <td class="px-4 py-3 text-muted-foreground">{{ formatDate(log.createdAt) }}</td>
          </tr>
        </tbody>
      </table>

      <!-- 分页控件 -->
      <div class="flex items-center justify-between border-t border-border/60 px-4 py-3">
        <span class="text-xs text-muted-foreground">共 {{ total }} 条</span>
        <Pagination
          v-if="pageCount > 1"
          :page="currentPage"
          :page-count="pageCount"
          size="sm"
          @change="handlePageChange"
        />
      </div>
    </div>
  </div>
</template>
