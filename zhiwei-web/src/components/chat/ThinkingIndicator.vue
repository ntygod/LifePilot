<script setup lang="ts">
/**
 * 思维链指示器 — 消息内联最小显示
 *
 * 流式：脉动点 + 最新状态文字（如"正在搜索网络…"）
 * 完成：可点击的"已完成思考 · Xs >"，点击打开侧边轨迹面板
 *
 * @author zsg
 * @since 2026-04-11
 */
import { computed } from 'vue'
import { ChevronRight } from 'lucide-vue-next'
import type { ReasoningEvent, ReactStepDto, ToolCallStep } from '@/types'

const props = defineProps<{
  reasoningEvents?: ReasoningEvent[]
  reactSteps?: ReactStepDto[]
  streaming?: boolean
}>()

const emit = defineEmits<{
  (e: 'show-trace'): void
}>()

const hasToolCalls = computed(() => {
  if (props.reasoningEvents?.length) {
    return props.reasoningEvents.some(e => e.type === 'TOOL_CALL')
  }
  if (props.reactSteps?.length) {
    return props.reactSteps.some(s => s.type === 'TOOL_CALL')
  }
  return false
})

const shouldShow = computed(() => {
  if (props.streaming) {
    return (props.reasoningEvents?.length ?? 0) > 0 || (props.reactSteps?.length ?? 0) > 0
  }
  return hasToolCalls.value
})

/** 流式阶段显示的最新状态文字 */
const latestStatus = computed(() => {
  if (props.reasoningEvents?.length) {
    for (let i = props.reasoningEvents.length - 1; i >= 0; i--) {
      const e = props.reasoningEvents[i]
      if (e.type === 'TOOL_CALL' && e.toolName) return `正在${e.toolName}…`
      if (e.type === 'PROGRESS' && e.description) return e.description
    }
  }
  if (props.reactSteps?.length) {
    for (let i = props.reactSteps.length - 1; i >= 0; i--) {
      const s = props.reactSteps[i]
      if (s.type === 'TOOL_CALL') {
        const tc = s as ToolCallStep
        return `正在${tc.toolName || tc.toolId}…`
      }
      if (s.type === 'PROGRESS') return (s as { content: string }).content
    }
  }
  return '正在思考…'
})

/** 耗时标签 */
const durationLabel = computed(() => {
  let sec: number | null = null
  if (props.reasoningEvents?.length && props.reasoningEvents.length >= 2) {
    const list = props.reasoningEvents
    const start = new Date(list[0].createdAt).getTime()
    const end = new Date(list[list.length - 1].createdAt).getTime()
    sec = Math.round((end - start) / 1000)
  } else if (props.reactSteps?.length) {
    const totalMs = props.reactSteps.reduce((sum, s) =>
      sum + (s.type === 'TOOL_CALL' ? (s as ToolCallStep).latencyMs : 0), 0)
    sec = Math.round(totalMs / 1000)
  }
  if (!sec || sec <= 0) return null
  return sec < 60 ? `${sec} 秒` : `${Math.floor(sec / 60)}分${sec % 60}秒`
})
</script>

<template>
  <div v-if="shouldShow" class="mt-1.5">
    <!-- 流式：脉动点 + 状态文字，可点击打开轨迹面板 -->
    <button v-if="streaming" type="button" class="thinking-status" @click="emit('show-trace')">
      <span class="thinking-dot" />
      <span>{{ latestStatus }}</span>
      <ChevronRight class="size-3 opacity-40" />
    </button>

    <!-- 完成：可点击触发器 -->
    <button v-else type="button" class="thinking-status thinking-status-done" @click="emit('show-trace')">
      <span>已完成思考</span>
      <span v-if="durationLabel" class="thinking-status-meta">· {{ durationLabel }}</span>
      <ChevronRight class="size-3 opacity-40" />
    </button>
  </div>
</template>

<style scoped>
.thinking-status {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  font-size: 12px;
  line-height: 1.7;
  color: hsl(from var(--foreground) h s l / 0.72);
  transition: color 140ms ease;
}

.thinking-status:hover {
  color: var(--foreground);
}

.thinking-status-done {
  color: hsl(from var(--muted-foreground) h s l / 0.62);
}

.thinking-status-done:hover {
  color: hsl(from var(--muted-foreground) h s l / 0.88);
}

.thinking-status-meta {
  color: hsl(from var(--muted-foreground) h s l / 0.5);
}

.thinking-dot {
  display: inline-block;
  width: 0.38rem;
  height: 0.38rem;
  border-radius: 999px;
  flex-shrink: 0;
  background: hsl(from var(--primary) h s l / 0.72);
  animation: dot-pulse 1.4s ease-in-out infinite;
}

@keyframes dot-pulse {
  0%, 100% { opacity: 0.5; transform: scale(0.85); }
  50% { opacity: 1; transform: scale(1.2); }
}
</style>
