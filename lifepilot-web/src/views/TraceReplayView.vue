<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useTraceStore } from '@/stores/trace'

const store = useTraceStore()
const expandedSteps = ref<Set<string>>(new Set())

onMounted(() => store.fetchList())

const totalPages = computed(() => Math.ceil(store.total / store.pageSize))

async function selectTrace(id: string) {
  await store.fetchDetail(id)
  await store.fetchSteps(id)
  expandedSteps.value.clear()
}

function backToList() {
  store.current = null
  store.steps = []
}

function toggleStep(stepId: string) {
  if (expandedSteps.value.has(stepId)) {
    expandedSteps.value.delete(stepId)
  } else {
    expandedSteps.value.add(stepId)
  }
}

function truncate(text: string, max = 500): string {
  return text.length > max ? text.slice(0, max) + '...' : text
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}
</script>

<template>
  <div class="flex flex-col h-full p-6 overflow-y-auto">
    <!-- 错误提示 -->
    <div v-if="store.error" class="mb-4 p-3 rounded-md bg-destructive/10 text-destructive text-sm">
      {{ store.error }}
    </div>

    <!-- 轨迹列表 -->
    <template v-if="!store.current">
      <h2 class="text-xl font-semibold text-foreground mb-6">轨迹回放</h2>

      <div v-if="store.loading" class="text-sm text-muted-foreground">加载中...</div>
      <div v-else-if="store.list.length === 0" class="text-sm text-muted-foreground">暂无轨迹记录</div>
      <div v-else class="space-y-2">
        <div
          v-for="trace in store.list"
          :key="trace.id"
          class="border border-border rounded-md p-4 cursor-pointer hover:border-primary/50 transition-colors"
          @click="selectTrace(trace.id)"
        >
          <div class="flex items-center gap-3">
            <span
              class="w-2 h-2 rounded-full shrink-0"
              :class="trace.success ? 'bg-green-500' : 'bg-red-500'"
            />
            <span class="text-sm text-foreground truncate flex-1">{{ trace.userMessage }}</span>
            <span class="text-xs text-muted-foreground shrink-0">{{ formatDuration(trace.durationMs) }}</span>
          </div>
          <div class="flex items-center gap-4 mt-2 text-xs text-muted-foreground">
            <span>{{ trace.totalSteps }} 步</span>
            <span>{{ trace.totalTokens }} tokens</span>
            <span class="ml-auto">{{ new Date(trace.createdAt).toLocaleString() }}</span>
          </div>
        </div>
      </div>

      <!-- 分页 -->
      <div v-if="totalPages > 1" class="flex items-center justify-center gap-2 mt-6">
        <button
          class="text-sm px-3 py-1 rounded-md border border-input hover:bg-accent transition-colors disabled:opacity-50"
          :disabled="store.page <= 0"
          @click="store.fetchList(store.page - 1)"
        >上一页</button>
        <span class="text-sm text-muted-foreground">{{ store.page + 1 }} / {{ totalPages }}</span>
        <button
          class="text-sm px-3 py-1 rounded-md border border-input hover:bg-accent transition-colors disabled:opacity-50"
          :disabled="store.page >= totalPages - 1"
          @click="store.fetchList(store.page + 1)"
        >下一页</button>
      </div>
    </template>

    <!-- 轨迹详情 -->
    <template v-else>
      <div class="flex items-center gap-3 mb-4">
        <button
          class="text-sm text-muted-foreground hover:text-foreground transition-colors"
          @click="backToList"
        >← 返回</button>
        <h2 class="text-xl font-semibold text-foreground truncate">{{ store.current.userMessage }}</h2>
      </div>

      <!-- 汇总信息 -->
      <div class="flex items-center gap-6 mb-6 p-4 rounded-lg bg-muted text-sm">
        <div class="flex items-center gap-2">
          <span
            class="w-2 h-2 rounded-full"
            :class="store.current.success ? 'bg-green-500' : 'bg-red-500'"
          />
          <span>{{ store.current.success ? '成功' : '失败' }}</span>
        </div>
        <span>{{ store.current.totalSteps }} 步</span>
        <span>{{ store.current.totalTokens }} tokens</span>
        <span>{{ formatDuration(store.current.durationMs) }}</span>
        <span v-if="store.current.modelId" class="text-muted-foreground">{{ store.current.modelId }}</span>
      </div>

      <div v-if="store.current.errorMessage" class="mb-4 p-3 rounded-md bg-destructive/10 text-destructive text-sm">
        {{ store.current.errorMessage }}
      </div>

      <!-- 时间线 -->
      <div class="space-y-0">
        <div
          v-for="step in store.steps"
          :key="step.id"
          class="relative pl-6 pb-4 border-l-2 last:border-l-0"
          :class="step.success ? 'border-green-300' : 'border-red-300'"
        >
          <!-- 节点圆点 -->
          <div
            class="absolute -left-[5px] top-0 w-2 h-2 rounded-full"
            :class="step.blocked ? 'bg-yellow-500' : step.success ? 'bg-green-500' : 'bg-red-500'"
          />

          <div
            class="cursor-pointer"
            @click="toggleStep(step.id)"
          >
            <div class="flex items-center gap-3 text-sm">
              <span class="font-mono text-muted-foreground">#{{ step.stepIndex }}</span>
              <span class="text-foreground">{{ step.actionType }}</span>
              <span v-if="step.toolId" class="font-mono text-xs text-muted-foreground">{{ step.toolId }}</span>
              <span class="text-xs text-muted-foreground ml-auto">{{ step.phaseBefore }} → {{ step.phaseAfter }}</span>
              <span class="text-xs text-muted-foreground">{{ formatDuration(step.latencyMs) }}</span>
            </div>
            <div v-if="step.blocked" class="text-xs text-yellow-600 mt-1">
              护栏拦截：{{ step.blockReason }}
            </div>
          </div>

          <!-- 展开详情 -->
          <div v-if="expandedSteps.has(step.id)" class="mt-2 space-y-2 text-xs">
            <div v-if="step.toolInputJson" class="space-y-1">
              <span class="text-muted-foreground">输入：</span>
              <pre class="p-2 rounded-md bg-muted whitespace-pre-wrap break-words">{{ step.toolInputJson }}</pre>
            </div>
            <div v-if="step.toolOutput" class="space-y-1">
              <span class="text-muted-foreground">输出：</span>
              <pre class="p-2 rounded-md bg-muted whitespace-pre-wrap break-words">{{ expandedSteps.has(step.id + '-full') ? step.toolOutput : truncate(step.toolOutput) }}</pre>
              <button
                v-if="step.toolOutput.length > 500 && !expandedSteps.has(step.id + '-full')"
                class="text-primary hover:underline"
                @click.stop="expandedSteps.add(step.id + '-full')"
              >展开全部</button>
            </div>
            <div v-if="step.actionJson" class="space-y-1">
              <span class="text-muted-foreground">动作：</span>
              <pre class="p-2 rounded-md bg-muted whitespace-pre-wrap break-words">{{ step.actionJson }}</pre>
            </div>
            <div class="text-muted-foreground">{{ step.tokensUsed }} tokens</div>
          </div>
        </div>
      </div>

      <!-- 最终输出 -->
      <div v-if="store.current.finalOutput" class="mt-6 p-4 rounded-lg border border-border">
        <h3 class="text-sm font-medium text-foreground mb-2">最终输出</h3>
        <p class="text-sm text-muted-foreground whitespace-pre-wrap">{{ store.current.finalOutput }}</p>
      </div>
    </template>
  </div>
</template>
