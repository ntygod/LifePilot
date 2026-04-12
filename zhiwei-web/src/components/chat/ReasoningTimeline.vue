<script setup lang="ts">
/**
 * 推理轨迹 — 三态 UI（v2 基于同类产品调研重设计）
 *
 * 设计参考：ChatGPT "Thought for Xs" 折叠 + Claude 结构化展开
 * 核心原则：不打断阅读，不抢注意力，按需披露
 *
 * - 流式：一行动态文字 + 脉动点，无面板
 * - 完成（折叠）：灰色小字 "思考了 Xs · 工具计数"，右箭头
 * - 完成（展开）：薄左边线 + 分组 bullet 列表
 * - 纯对话无工具：流式 "正在思考…"，完成后隐藏
 *
 * @author zsg
 * @since 2026-04-11
 */
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { Check, ChevronDown, ChevronRight, ExternalLink, X } from 'lucide-vue-next'
import type { ReasoningEvent } from '@/types'

const props = defineProps<{
  summary?: string
  events?: ReasoningEvent[]
  streaming?: boolean
  traceId?: string
}>()

/** 将原始输出（可能是 JSON）转为人类可读的详情文本 */
function humanizeDetail(raw: string | undefined, isError: boolean): string | null {
  if (!raw) return null
  const trimmed = raw.trim()
  // JSON → 提取有意义的信息
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try {
      const obj = JSON.parse(trimmed)
      if (obj.error) return obj.error
      if (obj.message) return obj.message
      // 成功的 JSON 不展示原始数据
      return null
    } catch {
      // 截断的 JSON，尝试提取 error 字段
      const errorMatch = trimmed.match(/"error"\s*:\s*"([^"]+)"/)
      if (errorMatch) return errorMatch[1]
      return null
    }
  }
  // 普通文本：截断
  const line = trimmed.split('\n')[0]
  return line.length <= 80 ? line : line.substring(0, 80) + '…'
}

// ─── 数据 ───

const eventList = computed(() => props.events ?? [])

const hasToolCalls = computed(() =>
  eventList.value.some(e => e.type === 'TOOL_CALL'),
)

/** 耗时（秒） */
const durationSeconds = computed(() => {
  const list = eventList.value
  if (list.length < 2) return null
  const start = new Date(list[0].createdAt).getTime()
  const end = new Date(list[list.length - 1].createdAt).getTime()
  const sec = Math.round((end - start) / 1000)
  return sec > 0 ? sec : null
})

// ─── 阶段合并 ───

interface Stage {
  name: string
  count: number
  status: 'running' | 'done' | 'error'
  details: string[]
}

const stages = computed<Stage[]>(() => {
  const map = new Map<string, Stage>()
  const order: string[] = []

  for (const ev of eventList.value) {
    if (ev.type === 'TOOL_CALL' && ev.toolName) {
      let s = map.get(ev.toolName)
      if (!s) {
        s = { name: ev.toolName, count: 0, status: 'running', details: [] }
        map.set(ev.toolName, s)
        order.push(ev.toolName)
      }
      s.count++
    } else if (ev.type === 'OBSERVATION' && ev.toolName) {
      const s = map.get(ev.toolName)
      if (s) {
        const failed = !!ev.title?.includes('失败')
        // 每次覆盖：只保留最后一次调用的状态和详情
        // 中间的重试失败对用户没有意义
        s.status = failed ? 'error' : 'running'
        s.details = []
        const detail = humanizeDetail(ev.description, failed)
        if (detail) s.details = [detail]
      }
    }
  }

  const result = order.map(k => map.get(k)!)
  if (!props.streaming) {
    result.forEach(s => { if (s.status === 'running') s.status = 'done' })
  } else if (result.length > 1) {
    for (let i = 0; i < result.length - 1; i++) {
      if (result[i].status === 'running') result[i].status = 'done'
    }
  }
  return result
})

/** 最新 PROGRESS 描述 */
const latestProgress = computed(() => {
  for (let i = eventList.value.length - 1; i >= 0; i--) {
    const e = eventList.value[i]
    if (e.type === 'PROGRESS' && e.description) return e.description
  }
  return null
})

// ─── 摘要文本 ───

const durationLabel = computed(() => {
  if (!durationSeconds.value) return null
  const s = durationSeconds.value
  return s < 60 ? `思考了 ${s} 秒` : `思考了 ${Math.floor(s / 60)}分${s % 60}秒`
})

const toolSummaryParts = computed(() =>
  stages.value.map(s => s.count === 1 ? s.name : `${s.name} ${s.count} 次`),
)

// ─── 显示控制 ───

const shouldShow = computed(() => {
  if (props.streaming) return eventList.value.length > 0
  return hasToolCalls.value
})

const expanded = ref(false)
const expandedGroups = ref<Set<string>>(new Set())

watch(() => props.streaming, (v) => { if (!v) expanded.value = false })

function toggleGroup(name: string) {
  expandedGroups.value.has(name) ? expandedGroups.value.delete(name) : expandedGroups.value.add(name)
}

function stageRunningLabel(s: Stage) {
  return s.count <= 1 ? `${s.name}…` : `${s.name}（已执行 ${s.count} 次）…`
}
</script>

<template>
  <div v-if="shouldShow" class="mt-1.5">
    <!-- ━━━ 流式 ━━━ -->
    <template v-if="streaming">
      <!-- 无工具 -->
      <p v-if="!hasToolCalls" class="thinking-live">
        <span class="thinking-dot" />
        <span>{{ latestProgress || '正在思考…' }}</span>
      </p>
      <!-- 有工具 -->
      <div v-else class="flex flex-col">
        <p
          v-for="s in stages" :key="s.name"
          class="thinking-live"
          :class="s.status === 'running' ? 'text-foreground/78' : 'text-muted-foreground/50'"
        >
          <Check v-if="s.status === 'done'" class="size-3 shrink-0" />
          <X v-else-if="s.status === 'error'" class="size-3 shrink-0 text-destructive/60" />
          <span v-else class="thinking-dot" />
          <span>{{ s.status === 'running' ? stageRunningLabel(s) : (s.count === 1 ? s.name : `${s.name}（${s.count}次）`) }}</span>
        </p>
        <p v-if="latestProgress && !stages.some(s => s.status === 'running')" class="thinking-live text-foreground/78">
          <span class="thinking-dot" />
          <span>{{ latestProgress }}</span>
        </p>
      </div>
    </template>

    <!-- ━━━ 完成：折叠 / 展开 ━━━ -->
    <template v-else>
      <button type="button" class="thinking-trigger" @click="expanded = !expanded">
        <span v-if="durationLabel">{{ durationLabel }}</span>
        <template v-for="(part, i) in toolSummaryParts" :key="part">
          <span class="text-border/60">·</span>
          <span>{{ part }}</span>
        </template>
        <component :is="expanded ? ChevronDown : ChevronRight" class="size-3 opacity-50" />
      </button>

      <div v-if="expanded" class="thinking-detail">
        <div v-for="s in stages" :key="s.name">
          <!-- 有详情：可展开 -->
          <button v-if="s.details.length" type="button" class="thinking-group-header" @click="toggleGroup(s.name)">
            <component :is="expandedGroups.has(s.name) ? ChevronDown : ChevronRight" class="size-3 opacity-40" />
            <span>{{ s.count === 1 ? s.name : `${s.name}（${s.count}次）` }}</span>
            <span v-if="s.status === 'error'" class="text-[10px] text-destructive/70">失败</span>
          </button>
          <!-- 无详情：纯文本，不可展开 -->
          <p v-else class="thinking-group-label">
            {{ s.count === 1 ? s.name : `${s.name}（${s.count}次）` }}
          </p>
          <div v-if="expandedGroups.has(s.name) && s.details.length" class="thinking-group-body">
            <p v-for="(d, i) in s.details" :key="i">{{ d }}</p>
          </div>
        </div>
        <RouterLink
          v-if="traceId"
          :to="{ name: 'traces', query: { id: traceId } }"
          class="thinking-trace-link"
        >
          <ExternalLink class="size-3" />
          查看完整执行轨迹
        </RouterLink>
      </div>
    </template>
  </div>
</template>

<style scoped>
/* ── 流式行 ── */
.thinking-live {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  font-size: 12px;
  line-height: 1.7;
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

/* ── 折叠触发器 ── */
.thinking-trigger {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  font-size: 12px;
  line-height: 1.5;
  color: hsl(from var(--muted-foreground) h s l / 0.62);
  transition: color 140ms ease;
}

.thinking-trigger:hover {
  color: hsl(from var(--muted-foreground) h s l / 0.88);
}

/* ── 展开区域 ── */
.thinking-detail {
  margin-top: 0.35rem;
  padding-left: 0.15rem;
  border-left: 1.5px solid hsl(from var(--border) h s l / 0.35);
}

.thinking-group-header {
  display: flex;
  align-items: center;
  gap: 0.3rem;
  padding: 0.15rem 0 0.15rem 0.55rem;
  font-size: 12px;
  line-height: 1.5;
  color: hsl(from var(--foreground) h s l / 0.72);
  transition: color 120ms ease;
}

.thinking-group-header:hover {
  color: var(--foreground);
}

.thinking-group-label {
  padding: 0.15rem 0 0.15rem 0.55rem;
  font-size: 12px;
  line-height: 1.5;
  color: hsl(from var(--foreground) h s l / 0.58);
}

.thinking-group-body {
  padding: 0 0 0.2rem 1.6rem;
  font-size: 11px;
  line-height: 1.6;
  color: hsl(from var(--muted-foreground) h s l / 0.6);
}

.thinking-group-body p {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.thinking-trace-link {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  margin-top: 0.35rem;
  padding-left: 0.55rem;
  font-size: 11px;
  color: hsl(from var(--primary) h s l / 0.6);
  transition: color 120ms ease;
}

.thinking-trace-link:hover {
  color: hsl(from var(--primary) h s l / 0.92);
}

@keyframes dot-pulse {
  0%, 100% { opacity: 0.5; transform: scale(0.85); }
  50% { opacity: 1; transform: scale(1.2); }
}
</style>
