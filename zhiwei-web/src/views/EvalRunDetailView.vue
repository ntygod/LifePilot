<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, AlertTriangle, CheckCircle2, XCircle } from 'lucide-vue-next'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import MetricCard from '@/components/common/MetricCard.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { useEvalStore } from '@/stores/eval'

const route = useRoute()
const router = useRouter()
const store = useEvalStore()

const evalRunId = computed(() => route.params.evalRunId as string)

onMounted(async () => {
  const id = evalRunId.value
  await Promise.all([store.fetchReport(id), store.fetchRunResults(id)])
})

/** 返回列表 */
function goBack() {
  router.push({ name: 'eval' })
}

/** 格式化时间 */
function formatTime(iso: string) {
  return new Date(iso).toLocaleString('zh-CN')
}

/** 评分百分比 */
function pct(score: number) {
  return (score * 100).toFixed(1) + '%'
}

/** 通过率 */
const passRate = computed(() => {
  const r = store.currentReport
  if (!r || r.totalScenarios === 0) return '—'
  return ((r.passCount / r.totalScenarios) * 100).toFixed(1) + '%'
})

/** 判断低分 */
function isLowScore(score: number) {
  return score < 0.6
}
</script>

<template>
  <PageContainer>
    <PageHeader
      :title="`运行详情`"
      :description="store.currentReport ? `运行 ID: ${evalRunId}` : '加载中…'"
    >
      <template #actions>
        <Button variant="outline" @click="goBack">
          <ArrowLeft class="mr-1.5 size-4" />
          返回列表
        </Button>
      </template>
    </PageHeader>

    <!-- 加载骨架 -->
    <div v-if="store.loading && !store.currentReport" class="space-y-4">
      <div class="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <Skeleton v-for="i in 4" :key="i" class="h-24 rounded-lg" />
      </div>
      <Skeleton class="h-64 rounded-lg" />
    </div>

    <template v-else-if="store.currentReport">
      <!-- 汇总卡片 -->
      <div class="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <MetricCard label="通过率" :value="passRate">
          <template #icon>
            <CheckCircle2 class="size-5" />
          </template>
        </MetricCard>
        <MetricCard label="平均分" :value="pct(store.currentReport.averageOverallScore)">
          <template #icon>
            <span class="text-sm font-semibold">Avg</span>
          </template>
        </MetricCard>
        <MetricCard
          label="退化状态"
          :value="store.currentReport.degraded ? '已退化' : '正常'"
        >
          <template #icon>
            <AlertTriangle v-if="store.currentReport.degraded" class="size-5 text-destructive" />
            <CheckCircle2 v-else class="size-5 text-emerald-600" />
          </template>
        </MetricCard>
        <MetricCard label="场景总数" :value="store.currentReport.totalScenarios">
          <template #icon>
            <span class="text-sm font-semibold">#</span>
          </template>
        </MetricCard>
      </div>

      <!-- 退化场景提示 -->
      <div
        v-if="store.currentReport.newRegressions.length > 0"
        class="rounded-lg border border-destructive/30 bg-destructive/5 p-4"
      >
        <div class="flex items-center gap-2 text-sm font-medium text-destructive">
          <AlertTriangle class="size-4" />
          新增退化场景
        </div>
        <ul class="mt-2 space-y-1 text-sm text-muted-foreground">
          <li v-for="s in store.currentReport.newRegressions" :key="s">• {{ s }}</li>
        </ul>
      </div>

      <!-- 场景级结果表格 -->
      <div class="section-panel overflow-hidden">
        <table class="w-full text-sm">
          <thead>
            <tr class="border-b border-border/60 text-left text-muted-foreground">
              <th class="px-4 py-3 font-medium">场景 ID</th>
              <th class="px-4 py-3 font-medium text-center">综合评分</th>
              <th class="px-4 py-3 font-medium text-center">LLM Judge</th>
              <th class="px-4 py-3 font-medium text-center">违规数</th>
              <th class="px-4 py-3 font-medium text-center">状态</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="result in store.currentRunResults"
              :key="result.evalId"
              class="border-b border-border/40"
              :class="{ 'bg-destructive/5': isLowScore(result.overallScore) }"
            >
              <td class="px-4 py-3 font-mono text-xs">{{ result.scenarioId }}</td>
              <td class="px-4 py-3 text-center font-medium" :class="isLowScore(result.overallScore) ? 'text-destructive' : 'text-foreground'">
                {{ pct(result.overallScore) }}
              </td>
              <td class="px-4 py-3 text-center">
                {{ result.llmJudgeScore != null ? pct(result.llmJudgeScore) : '—' }}
              </td>
              <td class="px-4 py-3 text-center">
                <Badge v-if="result.violations.length > 0" variant="destructive">
                  {{ result.violations.length }}
                </Badge>
                <span v-else class="text-muted-foreground">0</span>
              </td>
              <td class="px-4 py-3 text-center">
                <XCircle v-if="isLowScore(result.overallScore)" class="mx-auto size-4 text-destructive" />
                <CheckCircle2 v-else class="mx-auto size-4 text-emerald-600" />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </PageContainer>
</template>
