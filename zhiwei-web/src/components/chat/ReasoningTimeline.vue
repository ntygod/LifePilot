<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import type { ReasoningEvent } from '@/types'
import {
  Play, Wrench, Lightbulb, PenLine, CheckCircle2,
  AlertCircle, ChevronDown, ChevronRight, Loader2, Clock,
  RotateCcw, CircleDot
} from 'lucide-vue-next'
import WorkerResultCard from './WorkerResultCard.vue'

const props = defineProps<{
  /** 推理概要文本（后端返回的单行摘要） */
  summary?: string
  /** 推理事件时间线 */
  events?: ReasoningEvent[]
  /** 是否正在流式推理中 */
  streaming?: boolean
}>()

const expanded = ref(false)

/* ---- 流式期间自动展开，结束后延迟折叠 ---- */
let collapseTimer: ReturnType<typeof setTimeout> | null = null

watch(() => props.streaming, (streaming) => {
  if (collapseTimer !== null) {
    clearTimeout(collapseTimer)
    collapseTimer = null
  }
  if (streaming) {
    expanded.value = true
  } else {
    collapseTimer = setTimeout(() => {
      collapseTimer = null
      expanded.value = false
    }, 1200)
  }
})

onBeforeUnmount(() => {
  if (collapseTimer !== null) {
    clearTimeout(collapseTimer)
  }
})

// 有效事件列表
const eventList = computed(() => props.events ?? [])
const hasEvents = computed(() => eventList.value.length > 0)
const latestEvent = computed(() => eventList.value[eventList.value.length - 1] ?? null)

// 计算推理耗时（从第一个事件到最后一个事件的时间差）
const durationSeconds = computed(() => {
  if (eventList.value.length < 2) return null
  const first = eventList.value[0]
  const last = eventList.value[eventList.value.length - 1]
  if (!first.createdAt || !last.createdAt) return null
  const start = new Date(first.createdAt).getTime()
  const end = new Date(last.createdAt).getTime()
  const diff = (end - start) / 1000
  return diff > 0 ? diff : null
})

// 统计信息
const stepCount = computed(() => eventList.value.length)
const toolCallCount = computed(() =>
  eventList.value.filter(e => e.type === 'TOOL_CALL').length
)

const durationLabel = computed(() => {
  if (durationSeconds.value === null) return null
  const sec = durationSeconds.value
  return sec < 1 ? '<1s' : sec < 60 ? `${Math.round(sec)}s` : `${Math.floor(sec / 60)}m${Math.round(sec % 60)}s`
})

// 触发器主文案：流式时实时显示当前事件，完成后显示耗时 + 最后事件摘要
const triggerLabel = computed(() => {
  const list = eventList.value
  const count = list.length
  if (count === 0) return props.streaming ? '推理中…' : '推理概要'
  const last = list[count - 1]
  const brief = getEventBrief(last)
  if (props.streaming) return brief
  if (durationLabel.value) {
    return `${count} 步 · ${durationLabel.value} · ${brief}`
  }
  return `${count} 步 · ${brief}`
})

// 事件简短描述（用于触发器区域，控制在 40 字符内）
function getEventBrief(ev: ReasoningEvent): string {
  switch (ev.type) {
    case 'AGENT_START': return '准备上下文与预算…'
    case 'PROGRESS': {
      const text = ev.description ?? ev.title ?? ''
      if (text.length <= 40) return text || '处理中…'
      return text.substring(0, 40) + '…'
    }
    case 'THOUGHT': {
      const text = ev.description ?? ev.title ?? ''
      if (text.length <= 40) return text || '推理中…'
      return text.substring(0, 40) + '…'
    }
    case 'TOOL_CALL': return ev.toolName ? `调用 ${ev.toolName}` : '调用工具…'
    case 'OBSERVATION': return ev.toolName ? `${ev.toolName} 返回` : '工具返回'
    case 'ANSWER': return '生成回答'
    case 'SUSPEND': return '等待确认…'
    case 'RESUME': return '已恢复执行'
    case 'ANSWER_FINALIZED': return '回答已完成'
    default: return ev.title ?? '处理中…'
  }
}

// 事件图标映射（触发栏使用）
function getEventIcon(type: string) {
  switch (type) {
    case 'AGENT_START': return Play
    case 'PROGRESS': return Loader2
    case 'THOUGHT': return Lightbulb
    case 'TOOL_CALL': return Wrench
    case 'OBSERVATION': return CheckCircle2
    case 'ANSWER': return PenLine
    case 'SUSPEND': return AlertCircle
    case 'RESUME': return RotateCcw
    case 'ANSWER_FINALIZED': return CheckCircle2
    default: return CircleDot
  }
}

// 事件颜色映射（触发栏使用）
function getEventColor(type: string) {
  switch (type) {
    case 'AGENT_START': return 'text-blue-500'
    case 'PROGRESS': return 'text-primary'
    case 'THOUGHT': return 'text-violet-500'
    case 'TOOL_CALL': return 'text-amber-500'
    case 'OBSERVATION': return 'text-blue-500'
    case 'ANSWER': return 'text-primary'
    case 'SUSPEND': return 'text-orange-500'
    case 'RESUME': return 'text-cyan-500'
    case 'ANSWER_FINALIZED': return 'text-emerald-500'
    default: return 'text-muted-foreground'
  }
}

// 紧凑时间线圆点颜色
function getDotColor(type: string) {
  switch (type) {
    case 'AGENT_START': return 'bg-blue-400/80'
    case 'PROGRESS': return 'bg-primary/70'
    case 'THOUGHT': return 'bg-violet-500'
    case 'TOOL_CALL': return 'bg-amber-500'
    case 'OBSERVATION': return 'bg-blue-500'
    case 'ANSWER': return 'bg-emerald-500'
    case 'ANSWER_FINALIZED': return 'bg-emerald-500'
    case 'SUSPEND': return 'bg-orange-500'
    case 'RESUME': return 'bg-cyan-500'
    default: return 'bg-muted-foreground/50'
  }
}

// 格式化事件时间（相对于第一个事件的偏移）
function formatRelativeTime(event: ReasoningEvent): string | null {
  if (eventList.value.length === 0 || !event.createdAt) return null
  const first = eventList.value[0]
  if (!first.createdAt) return null
  const start = new Date(first.createdAt).getTime()
  const current = new Date(event.createdAt).getTime()
  const diffMs = current - start
  if (diffMs < 1000) return `+${diffMs}ms`
  return `+${(diffMs / 1000).toFixed(1)}s`
}
</script>

<template>
  <div
    v-if="summary || hasEvents"
    class="reasoning-panel mt-3 overflow-hidden rounded-[1.15rem] border text-xs transition-all duration-300"
    :class="streaming ? 'reasoning-panel-streaming' : 'reasoning-panel-idle'"
  >
    <!-- 触发栏 -->
    <button
      type="button"
      class="reasoning-trigger flex w-full items-center justify-between gap-3 px-3 py-3 text-left focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-1"
      @click="expanded = !expanded"
    >
      <div class="flex min-w-0 flex-1 items-center gap-3">
        <div
          class="reasoning-icon-shell relative shrink-0"
          :class="streaming ? 'reasoning-icon-shell-streaming' : ''"
        >
          <component
            :is="latestEvent ? getEventIcon(latestEvent.type) : CircleDot"
            :size="14"
            class="transition-colors duration-300"
            :class="streaming
              ? 'text-primary'
              : latestEvent ? getEventColor(latestEvent.type) : 'text-muted-foreground'"
          />
        </div>
        <div class="min-w-0 space-y-1">
          <div class="flex items-center gap-2">
            <span class="text-[10px] font-semibold tracking-[0.08em] text-muted-foreground/82">推理轨迹</span>
            <span v-if="toolCallCount > 0" class="reasoning-chip">
              <Wrench :size="10" />
              {{ toolCallCount }}
            </span>
          </div>
          <p
            class="truncate text-[11px] font-medium"
            :class="streaming ? 'text-primary' : 'text-foreground/84'"
          >
            {{ triggerLabel }}
          </p>
        </div>
      </div>

      <div class="flex shrink-0 items-center gap-2">
        <span v-if="streaming" class="reasoning-chip reasoning-chip-streaming">
          <Loader2 :size="10" class="animate-spin" />
          进行中
        </span>
        <span v-else-if="durationLabel" class="reasoning-chip">
          <Clock :size="10" />
          {{ durationLabel }}
        </span>
        <span class="reasoning-chip">{{ stepCount }} 步</span>
        <component
          :is="expanded ? ChevronDown : ChevronRight"
          :size="12"
          class="text-muted-foreground transition-transform duration-200"
        />
      </div>
    </button>

    <!-- 紧凑时间线 -->
    <Transition
      enter-active-class="transition-all duration-200 ease-out"
      enter-from-class="max-h-0 opacity-0"
      enter-to-class="max-h-96 opacity-100"
      leave-active-class="transition-all duration-150 ease-in"
      leave-from-class="max-h-96 opacity-100"
      leave-to-class="max-h-0 opacity-0"
    >
      <div v-if="expanded" class="overflow-hidden">
        <div class="reasoning-detail border-t border-border/40 scrollbar-thin scrollbar-track-transparent scrollbar-thumb-border/40">
          <TransitionGroup
            v-if="hasEvents"
            name="trace-step"
            tag="div"
          >
            <div
              v-for="(event, index) in eventList"
              :key="event.id"
            >
              <div class="reasoning-step">
                <span
                  class="reasoning-dot"
                  :class="[
                    getDotColor(event.type),
                    streaming && index === eventList.length - 1 && 'reasoning-dot-active'
                  ]"
                />
                <span class="reasoning-step-title">{{ event.title }}</span>
                <span
                  v-if="event.description && event.type !== 'TOOL_CALL'"
                  class="reasoning-step-desc"
                >
                  {{ event.description }}
                </span>
                <span
                  v-if="formatRelativeTime(event)"
                  class="reasoning-step-time"
                >
                  {{ formatRelativeTime(event) }}
                </span>
              </div>
              <WorkerResultCard
                v-if="event.type === 'OBSERVATION' && event.extra?.toolId === 'spawn_workers' && event.description"
                class="ml-4 mt-0.5 mb-1"
                :output="event.description"
              />
            </div>
          </TransitionGroup>

          <p
            v-else-if="summary"
            class="py-1 text-[10px] leading-relaxed text-muted-foreground/70"
          >
            {{ summary }}
          </p>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.reasoning-panel {
  position: relative;
  background: hsl(from var(--card) h s l / 0.92);
  box-shadow:
    0 14px 24px -30px hsl(var(--shadow-color) / 0.12),
    inset 0 1px 0 hsl(from var(--card) h s l / 0.44);
}

.reasoning-panel-idle {
  border-color: hsl(from var(--border) h s l / 0.5);
}

.reasoning-panel-streaming {
  border-color: hsl(from var(--primary) h s l / 0.24);
}

.reasoning-trigger {
  transition: background-color 180ms var(--ease-fluid);
}

.reasoning-trigger:hover {
  background: hsl(from var(--accent) h s l / 0.32);
}

.reasoning-icon-shell {
  display: inline-flex;
  height: 2rem;
  width: 2rem;
  align-items: center;
  justify-content: center;
  border-radius: 1rem;
  border: 1px solid hsl(from var(--border) h s l / 0.46);
  background: hsl(from var(--background) h s l / 0.8);
  box-shadow: inset 0 1px 0 hsl(from var(--card) h s l / 0.32);
}

.reasoning-icon-shell-streaming {
  box-shadow:
    inset 0 1px 0 hsl(from var(--card) h s l / 0.32),
    0 0 0 1px hsl(from var(--primary) h s l / 0.08);
}

.reasoning-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  border-radius: 999px;
  border: 1px solid hsl(from var(--border) h s l / 0.42);
  background: hsl(from var(--background) h s l / 0.72);
  padding: 0.22rem 0.5rem;
  font-size: 10px;
  line-height: 1.1;
  color: hsl(from var(--muted-foreground) h s l / 0.88);
}

.reasoning-chip-streaming {
  border-color: hsl(from var(--primary) h s l / 0.18);
  background: hsl(from var(--primary) h s l / 0.1);
  color: hsl(from var(--primary) h s l / 0.92);
}

/* ---- 紧凑时间线 ---- */

.reasoning-detail {
  padding: 0.35rem 0.7rem 0.3rem 0.75rem;
  max-height: 180px;
  overflow-y: auto;
}

.reasoning-step {
  display: flex;
  align-items: baseline;
  gap: 0.4rem;
  padding: 0.15rem 0;
  font-size: 11px;
  line-height: 1.5;
}

.reasoning-dot {
  margin-top: 0.4em;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  flex-shrink: 0;
}

.reasoning-dot-active {
  animation: dot-pulse 1.4s ease infinite;
}

.reasoning-step-title {
  font-weight: 500;
  color: hsl(from var(--foreground) h s l / 0.82);
  white-space: nowrap;
  flex-shrink: 0;
}

.reasoning-step-desc {
  flex: 1;
  min-width: 0;
  color: hsl(from var(--muted-foreground) h s l / 0.58);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reasoning-step-time {
  flex-shrink: 0;
  margin-left: auto;
  font-size: 10px;
  font-variant-numeric: tabular-nums;
  color: hsl(from var(--muted-foreground) h s l / 0.4);
}

@keyframes dot-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

/* ---- 步骤进入动画 ---- */

.trace-step-enter-active,
.trace-step-leave-active {
  transition:
    transform 200ms var(--ease-fluid),
    opacity 160ms var(--ease-fluid);
}

.trace-step-enter-from,
.trace-step-leave-to {
  opacity: 0;
  transform: translateY(6px);
}

.trace-step-move {
  transition: transform 200ms var(--ease-fluid);
}
</style>
