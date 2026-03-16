<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { AlertTriangle } from 'lucide-vue-next'
import { Badge } from '@/components/ui/badge'
import StatePanel from '@/components/common/StatePanel.vue'
import { useEvalStore } from '@/stores/eval'

const store = useEvalStore()
const router = useRouter()

/** 按 evaluatedAt 降序排列的运行列表 */
const sortedRuns = computed(() =>
  [...store.runs].sort(
    (a, b) => new Date(b.evaluatedAt).getTime() - new Date(a.evaluatedAt).getTime(),
  ),
)

/** 截断运行 ID 显示前 8 位 */
function truncateId(id: string): string {
  return id.length > 8 ? id.slice(0, 8) + '…' : id
}

/** 格式化评估时间 */
function formatTime(iso: string): string {
  const d = new Date(iso)
  return d.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/** 格式化维度平均分 */
function formatDimensions(dims: Record<string, number>): string {
  const entries = Object.entries(dims)
  if (entries.length === 0) return '—'
  return entries.map(([k, v]) => `${k}: ${(v * 100).toFixed(0)}%`).join(', ')
}

/** 导航到运行详情页 */
function goToDetail(evalRunId: string) {
  router.push({ name: 'evalRunDetail', params: { evalRunId } })
}
</script>

<template>
  <!-- 空状态 -->
  <StatePanel
    v-if="sortedRuns.length === 0"
    title="暂无评估记录"
    description="点击「运行评估」按钮开始第一次评估"
  />

  <!-- 运行历史表格 -->
  <div v-else class="section-panel overflow-x-auto">
    <table class="w-full text-sm">
      <thead>
        <tr class="border-b text-left text-xs font-medium text-muted-foreground">
          <th class="px-4 py-3">运行 ID</th>
          <th class="px-4 py-3">评估时间</th>
          <th class="px-4 py-3 text-center">场景数</th>
          <th class="px-4 py-3 text-center">通过/失败</th>
          <th class="px-4 py-3 text-center">平均分</th>
          <th class="px-4 py-3 text-center">退化状态</th>
          <th class="px-4 py-3">维度平均分</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="run in sortedRuns"
          :key="run.evalRunId"
          class="cursor-pointer border-b transition-colors hover:bg-muted/40"
          @click="goToDetail(run.evalRunId)"
        >
          <td class="px-4 py-3 font-mono text-xs">
            {{ truncateId(run.evalRunId) }}
          </td>
          <td class="px-4 py-3 text-muted-foreground">
            {{ formatTime(run.evaluatedAt) }}
          </td>
          <td class="px-4 py-3 text-center">
            {{ run.totalScenarios }}
          </td>
          <td class="px-4 py-3 text-center">
            <span class="text-green-600 dark:text-green-400">{{ run.passCount }}</span>
            <span class="mx-1 text-muted-foreground">/</span>
            <span class="text-red-600 dark:text-red-400">{{ run.failCount }}</span>
          </td>
          <td class="px-4 py-3 text-center font-medium">
            {{ (run.averageOverallScore * 100).toFixed(1) }}%
          </td>
          <td class="px-4 py-3 text-center">
            <Badge
              v-if="run.degraded"
              variant="destructive"
              class="gap-1"
            >
              <AlertTriangle class="size-3" />
              已退化
            </Badge>
            <span v-else class="text-muted-foreground">正常</span>
          </td>
          <td class="max-w-[260px] truncate px-4 py-3 text-xs text-muted-foreground">
            {{ formatDimensions(run.dimensionAverages) }}
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
