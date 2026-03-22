<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  CheckCircle2, XCircle, ChevronDown, ChevronRight,
  Users, Clock, Zap
} from 'lucide-vue-next'

interface WorkerOutput {
  workerIndex: number
  task: string
  success: boolean
  output: string
  tokensUsed: number
  durationMs: number
}

const props = defineProps<{
  /** OBSERVATION 的 outputSummary（JSON 字符串） */
  output: string
}>()

const parsed = computed<{ workers: WorkerOutput[], totalTokensUsed: number, wallClockMs: number, successCount: number, failureCount: number } | null>(() => {
  try {
    const data = JSON.parse(props.output)
    if (data.workers && Array.isArray(data.workers)) return data
    return null
  } catch {
    return null
  }
})

const expandedWorkers = ref<Set<number>>(new Set())

function toggleWorker(index: number) {
  if (expandedWorkers.value.has(index)) {
    expandedWorkers.value.delete(index)
  } else {
    expandedWorkers.value.add(index)
  }
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  const sec = ms / 1000
  if (sec < 60) return `${sec.toFixed(1)}s`
  return `${Math.floor(sec / 60)}m${Math.round(sec % 60)}s`
}
</script>

<template>
  <div v-if="parsed" class="mt-1 space-y-1.5">
    <!-- 汇总栏 -->
    <div class="flex items-center gap-3 text-[10px] text-muted-foreground">
      <span class="inline-flex items-center gap-1">
        <Users :size="10" />
        {{ parsed.workers.length }} 个 Worker
      </span>
      <span v-if="parsed.successCount > 0" class="inline-flex items-center gap-0.5 text-emerald-600 dark:text-emerald-400">
        <CheckCircle2 :size="10" />
        {{ parsed.successCount }}
      </span>
      <span v-if="parsed.failureCount > 0" class="inline-flex items-center gap-0.5 text-destructive">
        <XCircle :size="10" />
        {{ parsed.failureCount }}
      </span>
      <span class="inline-flex items-center gap-0.5">
        <Clock :size="10" />
        {{ formatDuration(parsed.wallClockMs) }}
      </span>
      <span class="inline-flex items-center gap-0.5">
        <Zap :size="10" />
        {{ parsed.totalTokensUsed }} tokens
      </span>
    </div>

    <!-- Worker 列表 -->
    <div
      v-for="worker in parsed.workers"
      :key="worker.workerIndex"
      class="rounded-md border text-[10px] overflow-hidden transition-colors"
      :class="worker.success ? 'border-border bg-muted/20' : 'border-destructive/40 bg-destructive/5'"
    >
      <!-- Worker 头部 -->
      <button
        type="button"
        class="w-full flex items-center gap-2 px-2 py-1.5 text-left hover:bg-muted/40 transition-colors"
        @click="toggleWorker(worker.workerIndex)"
      >
        <component
          :is="worker.success ? CheckCircle2 : XCircle"
          :size="12"
          class="shrink-0"
          :class="worker.success ? 'text-emerald-500' : 'text-destructive'"
        />
        <span class="font-medium text-foreground/90 truncate flex-1">
          {{ worker.task }}
        </span>
        <span class="shrink-0 text-muted-foreground/60">
          {{ formatDuration(worker.durationMs) }}
        </span>
        <component
          :is="expandedWorkers.has(worker.workerIndex) ? ChevronDown : ChevronRight"
          :size="10"
          class="shrink-0 text-muted-foreground"
        />
      </button>

      <!-- Worker 输出（展开） -->
      <div
        v-if="expandedWorkers.has(worker.workerIndex)"
        class="border-t border-border/50 px-2 py-1.5"
      >
        <p class="text-muted-foreground/80 leading-relaxed whitespace-pre-wrap break-words">{{ worker.output }}</p>
      </div>
    </div>
  </div>

  <!-- 解析失败回退到纯文本 -->
  <p v-else class="text-[10px] text-muted-foreground/80 leading-relaxed mt-0.5 whitespace-pre-wrap">
    {{ output }}
  </p>
</template>
