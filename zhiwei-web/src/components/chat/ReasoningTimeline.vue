<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ReasoningEvent } from '@/types'
import {
  Brain, Wrench, Lightbulb, PenLine, CheckCircle2,
  AlertCircle, ChevronDown, ChevronRight, Loader2, Clock
} from 'lucide-vue-next'

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

// 触发器主文案：流式时实时显示当前事件，完成后显示耗时 + 最后事件摘要
const triggerLabel = computed(() => {
  const list = eventList.value
  const count = list.length
  if (count === 0) return props.streaming ? '正在思考…' : '推理概要'
  // 取最新事件的简短描述
  const last = list[count - 1]
  const brief = getEventBrief(last)
  if (props.streaming) return brief
  // 完成后：耗时 + 最后事件摘要
  if (durationSeconds.value !== null) {
    const sec = durationSeconds.value
    const timeStr = sec < 1 ? '<1s' : sec < 60 ? `${Math.round(sec)}s` : `${Math.floor(sec / 60)}m${Math.round(sec % 60)}s`
    return `${count} 步 · ${timeStr} · ${brief}`
  }
  return `${count} 步 · ${brief}`
})

// 事件简短描述（用于触发器区域，控制在 40 字符内）
function getEventBrief(ev: ReasoningEvent): string {
  switch (ev.type) {
    case 'AGENT_START': return '准备上下文与预算…'
    case 'THOUGHT': {
      const text = ev.description ?? ev.title ?? ''
      if (text.length <= 40) return text || '正在思考…'
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
    case 'AGENT_START': return Brain
    case 'THOUGHT': return Lightbulb
    case 'TOOL_CALL': return Wrench
    case 'OBSERVATION': return CheckCircle2
    case 'ANSWER': return PenLine
    case 'SUSPEND': return AlertCircle
    case 'RESUME': return Brain
    case 'ANSWER_FINALIZED': return CheckCircle2
    default: return Brain
  }
}

// 事件颜色映射
function getEventColor(type: string) {
  switch (type) {
    case 'AGENT_START': return 'text-blue-500'
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
    class="mt-2 rounded-lg border border-border/60 bg-gradient-to-b from-muted/30 to-background/80 text-xs overflow-hidden transition-all duration-300"
    :class="streaming ? 'border-primary/40 shadow-[0_0_8px_-2px_hsl(var(--primary)/0.15)]' : ''"
  >
    <!-- 触发器按钮 -->
    <button
      type="button"
      class="w-full px-3 py-2.5 flex items-center justify-between gap-2 text-left
             hover:bg-muted/40 transition-all duration-200
             focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-1"
      @click="expanded = !expanded"
    >
      <div class="flex items-center gap-2 min-w-0 flex-1">
        <!-- 动态图标：跟随最新事件类型 -->
        <div class="relative shrink-0">
          <component
            :is="hasEvents ? getEventIcon(eventList[eventList.length - 1].type) : Brain"
            :size="14"
            class="transition-colors duration-300"
            :class="streaming
              ? 'text-primary'
              : hasEvents ? getEventColor(eventList[eventList.length - 1].type) : 'text-muted-foreground'"
          />
          <span
            v-if="streaming"
            class="absolute inset-0 rounded-full bg-primary/20 animate-ping"
          />
        </div>

        <span
          class="text-[11px] font-medium truncate"
          :class="streaming ? 'text-primary' : 'text-foreground/80'"
        >
          {{ triggerLabel }}
        </span>

        <!-- 工具调用统计标签 -->
        <span
          v-if="!streaming && toolCallCount > 0"
          class="hidden sm:inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded-full bg-amber-500/10 text-amber-600 dark:text-amber-400 text-[10px] shrink-0"
        >
          <Wrench :size="10" />
          {{ toolCallCount }}
        </span>
      </div>

      <div class="flex items-center gap-1.5 shrink-0">
        <!-- 流式加载指示器 -->
        <Loader2
          v-if="streaming"
          :size="12"
          class="text-primary animate-spin"
        />
        <!-- 耗时标签 -->
        <span
          v-else-if="durationSeconds !== null"
          class="inline-flex items-center gap-0.5 text-[10px] text-muted-foreground"
        >
          <Clock :size="10" />
          {{ durationSeconds < 1 ? '<1s' : durationSeconds < 60 ? `${Math.round(durationSeconds)}s` : `${Math.floor(durationSeconds / 60)}m${Math.round(durationSeconds % 60)}s` }}
        </span>
        <component
          :is="expanded ? ChevronDown : ChevronRight"
          :size="12"
          class="text-muted-foreground transition-transform duration-200"
        />
      </div>
    </button>

    <!-- 展开内容区 -->
    <Transition
      enter-active-class="transition-all duration-300 ease-out"
      enter-from-class="max-h-0 opacity-0"
      enter-to-class="max-h-[600px] opacity-100"
      leave-active-class="transition-all duration-200 ease-in"
      leave-from-class="max-h-[600px] opacity-100"
      leave-to-class="max-h-0 opacity-0"
    >
      <div v-if="expanded" class="overflow-hidden">
        <div class="border-t border-border/50 px-3 py-2.5">
          <!-- 事件时间线 -->
          <div v-if="hasEvents" class="space-y-0">
            <div
              v-for="(event, index) in eventList"
              :key="event.id"
              class="flex items-start gap-2.5 group"
            >
              <!-- 时间线轴 -->
              <div class="flex flex-col items-center shrink-0 w-5">
                <!-- 图标节点 -->
                <div
                  class="w-5 h-5 rounded-full flex items-center justify-center transition-colors duration-200
                         bg-background border border-border group-hover:border-primary/50"
                >
                  <component
                    :is="getEventIcon(event.type)"
                    :size="11"
                    :class="getEventColor(event.type)"
                  />
                </div>
                <!-- 连线（非最后一个） -->
                <div
                  v-if="index < eventList.length - 1"
                  class="w-px flex-1 min-h-[16px]"
                  :class="getLineColor(event.type)"
                />
              </div>

              <!-- 事件内容 -->
              <div class="flex-1 min-w-0 pb-2.5" :class="index === eventList.length - 1 ? 'pb-0' : ''">
                <div class="flex items-center gap-1.5 flex-wrap">
                  <span class="text-[11px] font-medium text-foreground/90 leading-5">
                    {{ event.title }}
                  </span>
                  <!-- 工具技术标识（调试用） -->
                  <span
                    v-if="event.extra?.toolId"
                    class="inline-flex items-center px-1.5 py-0.5 rounded bg-muted text-[10px] text-muted-foreground font-mono"
                  >
                    {{ event.extra.toolId }}
                  </span>
                  <!-- 相对时间 -->
                  <span
                    v-if="formatRelativeTime(event)"
                    class="text-[10px] text-muted-foreground/60"
                  >
                    {{ formatRelativeTime(event) }}
                  </span>
                </div>
                <p
                  v-if="event.description"
                  class="text-[10px] text-muted-foreground/80 leading-relaxed mt-0.5"
                >
                  {{ event.description }}
                </p>
              </div>
            </div>
          </div>

          <!-- 无事件时回退到纯文本摘要 -->
          <p v-else-if="summary" class="text-[11px] text-muted-foreground leading-relaxed">
            {{ summary }}
          </p>
        </div>
      </div>
    </Transition>
  </div>
</template>
