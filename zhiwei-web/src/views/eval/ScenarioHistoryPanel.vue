<script setup lang="ts">
import { computed, watch } from 'vue'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetDescription,
} from '@/components/ui/sheet'
import ScoreTrendChart from './ScoreTrendChart.vue'
import { useEvalStore } from '@/stores/eval'

const props = defineProps<{
  scenarioId: string
  scenarioName: string
  open: boolean
}>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
}>()

const store = useEvalStore()

// ── 打开时加载场景历史 ──
watch(() => props.open, (isOpen) => {
  if (isOpen && props.scenarioId) {
    store.fetchScenarioHistory(props.scenarioId)
  }
})

// ── 趋势图数据映射 ──
const trendData = computed(() => {
  const history = store.scenarioHistory[props.scenarioId] ?? []
  return history.map(r => ({
    date: new Date(r.evaluatedAt).toLocaleDateString('zh-CN'),
    score: r.overallScore,
    runId: r.evalRunId,
  }))
})

// ── 历史结果列表 ──
const historyItems = computed(() => store.scenarioHistory[props.scenarioId] ?? [])

/** 格式化日期 */
function formatDate(iso: string) {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  })
}

/** 格式化分数为百分比 */
function formatScore(score: number | null | undefined): string {
  if (score == null) return '-'
  return `${(score * 100).toFixed(1)}%`
}

/** 截断运行 ID */
function truncateId(id: string): string {
  return id.length > 8 ? id.slice(0, 8) + '…' : id
}
</script>

<template>
  <Sheet :open="open" @update:open="emit('update:open', $event)">
    <SheetContent class="overflow-y-auto p-6" style="width: 100%; max-width: 36rem;">
      <SheetHeader>
        <SheetTitle>{{ scenarioName }} — 历史趋势</SheetTitle>
        <SheetDescription>场景 {{ scenarioId }} 的历史评估结果与评分趋势</SheetDescription>
      </SheetHeader>

      <!-- 加载中 -->
      <div v-if="store.loading" class="mt-6 space-y-4">
        <Skeleton class="h-[200px] w-full" />
        <Skeleton class="h-8 w-full" />
        <Skeleton class="h-8 w-full" />
        <Skeleton class="h-8 w-full" />
      </div>

      <!-- 空数据 -->
      <div
        v-else-if="historyItems.length === 0"
        class="mt-6 px-4 py-12 text-center"
      >
        <p class="text-sm text-muted-foreground">该场景暂无历史评估数据</p>
      </div>

      <!-- 有数据 -->
      <div v-else class="mt-6 space-y-6">
        <!-- 评分趋势图 -->
        <div>
          <h4 class="mb-2 text-xs font-medium text-muted-foreground">评分趋势</h4>
          <ScoreTrendChart :data="trendData" />
        </div>

        <!-- 历史结果表格 -->
        <div class="overflow-x-auto">
          <h4 class="mb-2 text-xs font-medium text-muted-foreground">历史结果</h4>
          <table class="w-full text-sm">
            <thead>
              <tr class="border-b border-border/60">
                <th class="px-3 py-2 text-left font-medium text-muted-foreground">运行 ID</th>
                <th class="px-3 py-2 text-right font-medium text-muted-foreground">综合评分</th>
                <th class="px-3 py-2 text-right font-medium text-muted-foreground">LLM 评分</th>
                <th class="px-3 py-2 text-right font-medium text-muted-foreground">违规数</th>
                <th class="px-3 py-2 text-left font-medium text-muted-foreground">评估时间</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="item in historyItems"
                :key="item.evalId"
                class="border-b border-border/40 transition-colors hover:bg-muted/50"
                :class="{ 'bg-yellow-50 dark:bg-yellow-950/20': item.overallScore < 0.6 }"
              >
                <td class="px-3 py-2 font-mono text-xs" :title="item.evalRunId">
                  {{ truncateId(item.evalRunId) }}
                </td>
                <td class="px-3 py-2 text-right tabular-nums">
                  {{ formatScore(item.overallScore) }}
                </td>
                <td class="px-3 py-2 text-right tabular-nums">
                  {{ formatScore(item.llmJudgeScore) }}
                </td>
                <td class="px-3 py-2 text-right">
                  <Badge
                    v-if="item.violations.length > 0"
                    variant="destructive"
                    class="text-[11px]"
                  >
                    {{ item.violations.length }}
                  </Badge>
                  <span v-else class="text-muted-foreground">0</span>
                </td>
                <td class="px-3 py-2 text-muted-foreground">
                  {{ formatDate(item.evaluatedAt) }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </SheetContent>
  </Sheet>
</template>
