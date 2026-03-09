<script setup lang="ts">
import { ref } from 'vue'
import type { TraceStep } from '@/types'
import {
  resolveStepType,
  calcBarWidth,
  STEP_COLORS,
  STEP_TYPE_LABELS,
  type StepType,
} from '@/utils/traceUtils'

defineProps<{
  steps: TraceStep[]
  totalDurationMs: number
}>()

// tooltip 状态
const hoveredStepId = ref<string | null>(null)

function formatMs(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

function getColor(step: TraceStep): string {
  return STEP_COLORS[resolveStepType(step)]
}

function getLabel(step: TraceStep): string {
  return STEP_TYPE_LABELS[resolveStepType(step)]
}
</script>

<template>
  <div class="space-y-2">
    <!-- 图例 -->
    <div class="flex items-center gap-4 text-xs text-muted-foreground mb-1">
      <span
        v-for="(color, type) in STEP_COLORS"
        :key="type"
        class="inline-flex items-center gap-1"
      >
        <span class="w-2.5 h-2.5 rounded-sm" :class="color" />
        {{ STEP_TYPE_LABELS[type as StepType] }}
      </span>
    </div>

    <!-- 条形图 -->
    <div class="space-y-1.5">
      <div
        v-for="step in steps"
        :key="step.id"
        class="relative flex items-center gap-2 group"
        @mouseenter="hoveredStepId = step.id"
        @mouseleave="hoveredStepId = null"
      >
        <!-- 步骤序号 -->
        <span class="w-8 text-right text-xs font-mono text-muted-foreground shrink-0">
          #{{ step.stepIndex }}
        </span>

        <!-- 条形 -->
        <div class="flex-1 h-5 bg-muted/40 rounded overflow-hidden">
          <div
            class="h-full rounded transition-all duration-200"
            :class="getColor(step)"
            :style="{ width: calcBarWidth(step.latencyMs, totalDurationMs) }"
          />
        </div>

        <!-- 耗时标签 -->
        <span class="w-14 text-right text-xs text-muted-foreground shrink-0">
          {{ formatMs(step.latencyMs) }}
        </span>

        <!-- Tooltip -->
        <div
          v-if="hoveredStepId === step.id"
          class="absolute left-10 -top-8 z-10 px-3 py-1.5 rounded-md bg-popover text-popover-foreground
                 border border-border shadow-md text-xs whitespace-nowrap pointer-events-none"
        >
          <span class="font-medium">{{ step.actionType }}</span>
          <span class="mx-1.5 text-muted-foreground">·</span>
          <span>{{ getLabel(step) }}</span>
          <span class="mx-1.5 text-muted-foreground">·</span>
          <span>{{ formatMs(step.latencyMs) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>
