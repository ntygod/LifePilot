<script setup lang="ts">
import { computed, ref } from 'vue'
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

// 事件图标映射
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

// 事件颜色映射
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

// 时间线连线颜色
function getLineColor(type: string) {
  switch (type) {
    case 'ANSWER_FINALIZED': return 'bg-emerald-500/30'
    default: return 'bg-border'
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

    <Transition
      enter-active-class="transition-all duration-300 ease-out"
      enter-from-class="max-h-0 opacity-0"
      enter-to-class="max-h-[600px] opacity-100"
      leave-active-class="transition-all duration-200 ease-in"
      leave-from-class="max-h-[600px] opacity-100"
      leave-to-class="max-h-0 opacity-0"
    >
      <div v-if="expanded" class="overflow-hidden">
        <div class="border-t border-border/55 px-3 pb-3 pt-2.5">
          <TransitionGroup
            v-if="hasEvents"
            name="trace-step"
            tag="div"
            class="space-y-2"
          >
            <div
              v-for="(event, index) in eventList"
              :key="event.id"
              class="flex items-start gap-2.5"
            >
              <div class="flex w-6 shrink-0 flex-col items-center">
                <div
                  class="reasoning-node flex h-6 w-6 items-center justify-center rounded-2xl transition-colors duration-200"
                  :class="streaming && index === eventList.length - 1 ? 'reasoning-node-active' : ''"
                >
                  <component
                    :is="getEventIcon(event.type)"
                    :size="12"
                    :class="getEventColor(event.type)"
                  />
                </div>
                <div
                  v-if="index < eventList.length - 1"
                  class="reasoning-line w-px flex-1 min-h-[18px]"
                  :class="getLineColor(event.type)"
                />
              </div>

              <div
                class="reasoning-event-card flex-1 min-w-0"
                :class="streaming && index === eventList.length - 1 ? 'reasoning-event-card-active' : ''"
              >
                <div class="flex items-start justify-between gap-2">
                  <div class="min-w-0">
                    <div class="flex items-center gap-1.5 flex-wrap">
                      <span class="text-[11px] font-medium leading-5 text-foreground/92">
                        {{ event.title }}
                      </span>
                      <span
                        v-if="event.extra?.toolId"
                        class="reasoning-meta-chip font-mono"
                      >
                        {{ event.extra.toolId }}
                      </span>
                    </div>
                  </div>
                  <span
                    v-if="formatRelativeTime(event)"
                    class="reasoning-meta-chip shrink-0"
                  >
                    {{ formatRelativeTime(event) }}
                  </span>
                </div>
                <WorkerResultCard
                  v-if="event.type === 'OBSERVATION' && event.extra?.toolId === 'spawn_workers' && event.description"
                  class="mt-2"
                  :output="event.description"
                />
                <p
                  v-else-if="event.description"
                  class="mt-1.5 text-[10px] leading-relaxed text-muted-foreground/82"
                >
                  {{ event.description }}
                </p>
              </div>
            </div>
          </TransitionGroup>

          <p v-else-if="summary" class="rounded-2xl border border-border/45 bg-background/62 px-3 py-2.5 text-[11px] leading-relaxed text-muted-foreground">
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

.reasoning-node {
  border: 1px solid hsl(from var(--border) h s l / 0.46);
  background: hsl(from var(--background) h s l / 0.82);
  box-shadow: inset 0 1px 0 hsl(from var(--card) h s l / 0.3);
}

.reasoning-node-active {
  border-color: hsl(from var(--primary) h s l / 0.24);
  box-shadow:
    inset 0 1px 0 hsl(from var(--card) h s l / 0.3),
    0 0 0 1px hsl(from var(--primary) h s l / 0.08);
}

.reasoning-line {
  opacity: 0.75;
}

.reasoning-event-card {
  border-radius: 1rem;
  border: 1px solid hsl(from var(--border) h s l / 0.42);
  background: hsl(from var(--background) h s l / 0.7);
  padding: 0.8rem 0.9rem;
  box-shadow: inset 0 1px 0 hsl(from var(--card) h s l / 0.24);
}

.reasoning-event-card-active {
  border-color: hsl(from var(--primary) h s l / 0.2);
  background: hsl(from var(--primary) h s l / 0.08);
}

.reasoning-meta-chip {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  border: 1px solid hsl(from var(--border) h s l / 0.4);
  background: hsl(from var(--card) h s l / 0.72);
  padding: 0.14rem 0.45rem;
  font-size: 10px;
  line-height: 1.1;
  color: hsl(from var(--muted-foreground) h s l / 0.84);
}

.trace-step-enter-active,
.trace-step-leave-active {
  transition:
    transform 220ms var(--ease-fluid),
    opacity 180ms var(--ease-fluid);
}

.trace-step-enter-from,
.trace-step-leave-to {
  opacity: 0;
  transform: translateY(10px);
}

.trace-step-move {
  transition: transform 220ms var(--ease-fluid);
}
</style>
