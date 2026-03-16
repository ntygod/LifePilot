<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Play, FlaskConical, AlertTriangle } from 'lucide-vue-next'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { useEvalStore } from '@/stores/eval'

const router = useRouter()
const store = useEvalStore()
const running = ref(false)

onMounted(() => {
  store.fetchScenarios()
})

/** 触发全量评估 */
async function handleRunAll() {
  running.value = true
  const report = await store.triggerRun({})
  running.value = false
  if (report) {
    router.push({ name: 'evalRunDetail', params: { evalRunId: report.evalRunId } })
  }
}

/** 跳转运行详情 */
function goToDetail(evalRunId: string) {
  router.push({ name: 'evalRunDetail', params: { evalRunId } })
}

/** 格式化时间 */
function formatTime(iso: string) {
  return new Date(iso).toLocaleString('zh-CN')
}

/** 截断 ID 显示 */
function shortId(id: string) {
  return id.length > 12 ? id.slice(0, 12) + '…' : id
}
</script>

<template>
  <PageContainer>
    <PageHeader
      title="评估管理"
      description="运行 Benchmark 场景评估，追踪 Agent 质量变化"
    >
      <template #actions>
        <Button :disabled="running" @click="handleRunAll">
          <Play class="mr-1.5 size-4" />
          {{ running ? '评估中…' : '运行评估' }}
        </Button>
      </template>
    </PageHeader>

    <!-- 错误提示 -->
    <div v-if="store.error" class="rounded-lg border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive">
      {{ store.error }}
    </div>

    <!-- 加载骨架 -->
    <div v-if="store.loading && store.runs.length === 0" class="space-y-3">
      <Skeleton v-for="i in 4" :key="i" class="h-14 w-full rounded-lg" />
    </div>

    <!-- 空状态 -->
    <StatePanel
      v-else-if="store.runs.length === 0 && !store.loading"
      title="暂无评估记录"
      description="点击「运行评估」开始首次 Benchmark 评估"
    >
      <template #icon>
        <FlaskConical class="size-10 text-muted-foreground/60" />
      </template>
    </StatePanel>

    <!-- 运行列表 -->
    <div v-else class="section-panel overflow-hidden">
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-border/60 text-left text-muted-foreground">
            <th class="px-4 py-3 font-medium">运行 ID</th>
            <th class="px-4 py-3 font-medium">评估时间</th>
            <th class="px-4 py-3 font-medium text-center">场景数</th>
            <th class="px-4 py-3 font-medium text-center">通过 / 失败</th>
            <th class="px-4 py-3 font-medium text-center">平均分</th>
            <th class="px-4 py-3 font-medium text-center">状态</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="run in store.runs"
            :key="run.evalRunId"
            class="cursor-pointer border-b border-border/40 transition-colors hover:bg-muted/40"
            @click="goToDetail(run.evalRunId)"
          >
            <td class="px-4 py-3 font-mono text-xs">{{ shortId(run.evalRunId) }}</td>
            <td class="px-4 py-3">{{ formatTime(run.evaluatedAt) }}</td>
            <td class="px-4 py-3 text-center">{{ run.totalScenarios }}</td>
            <td class="px-4 py-3 text-center">
              <span class="text-emerald-600">{{ run.passCount }}</span>
              <span class="mx-1 text-muted-foreground">/</span>
              <span class="text-destructive">{{ run.failCount }}</span>
            </td>
            <td class="px-4 py-3 text-center font-medium">
              {{ (run.averageOverallScore * 100).toFixed(1) }}%
            </td>
            <td class="px-4 py-3 text-center">
              <Badge v-if="run.degraded" variant="destructive" class="gap-1">
                <AlertTriangle class="size-3" />
                退化
              </Badge>
              <Badge v-else variant="secondary">正常</Badge>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </PageContainer>
</template>
