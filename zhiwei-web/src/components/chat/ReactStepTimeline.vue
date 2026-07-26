<script setup lang="ts">
/**
 * 执行轨迹 — 三态 UI（v2 基于同类产品调研重设计）
 *
 * 与 ReasoningTimeline 视觉一致，数据源为 ReactStepDto
 *
 * @author zsg
 * @since 2026-04-11
 */
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { Check, ChevronDown, ChevronRight, ExternalLink, X } from 'lucide-vue-next'
import type { ReactStepDto, ToolCallStep, ObservationStep } from '@/types'
import { isInternalCapabilityId } from '@/utils/liveCapabilities'
import { normalizeTurnStatusText } from '@/utils/turnPhase'

/** 将原始输出（可能是 JSON）转为人类可读的详情文本 */
function humanizeDetail(raw: string | undefined, isError: boolean): string | null {
  if (!raw) return null
  const trimmed = raw.trim()
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try {
      const obj = JSON.parse(trimmed)
      if (obj.error) return obj.error
      if (obj.message) return obj.message
      return null
    } catch {
      const errorMatch = trimmed.match(/"error"\s*:\s*"([^"]+)"/)
      if (errorMatch) return errorMatch[1]
      return null
    }
  }
  const line = trimmed.split('\n')[0]
  return line.length <= 80 ? line : line.substring(0, 80) + '…'
}

const props = defineProps<{
  steps: ReactStepDto[]
  streaming?: boolean
  summary?: string
  traceId?: string
}>()

// ─── 数据 ───

const visibleSteps = computed(() => props.steps.filter(isVisibleStep))

const hasToolCalls = computed(() => visibleSteps.value.some(s => s.type === 'TOOL_CALL'))

/** 耗时 */
const totalMs = computed(() =>
  visibleSteps.value.reduce((sum, s) => sum + (s.type === 'TOOL_CALL' ? (s as ToolCallStep).latencyMs : 0), 0),
)
const durationSeconds = computed(() => {
  const s = Math.round(totalMs.value / 1000)
  return s > 0 ? s : null
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

  for (const step of visibleSteps.value) {
    if (step.type === 'TOOL_CALL') {
      const tc = step as ToolCallStep
      const name = tc.toolName || tc.toolId
      let s = map.get(name)
      if (!s) {
        s = { name, count: 0, status: 'running', details: [] }
        map.set(name, s)
        order.push(name)
      }
      s.count++
    } else if (step.type === 'OBSERVATION') {
      const obs = step as ObservationStep
      const name = obs.toolName || obs.toolId
      const s = map.get(name)
      if (s) {
        s.status = obs.success ? 'running' : 'error'
        s.details = []
        const detail = humanizeDetail(obs.outputSummary, !obs.success)
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

const latestProgress = computed(() => {
  for (let i = visibleSteps.value.length - 1; i >= 0; i--) {
    const step = visibleSteps.value[i]
    if (step.type === 'PROGRESS') {
      const normalized = normalizeTurnStatusText((step as { content: string }).content)
      if (normalized) return normalized
    }
  }
  return null
})

// ─── 摘要 ───

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
  if (props.streaming) return visibleSteps.value.length > 0
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

function isVisibleTool(toolId?: string | null, toolName?: string | null) {
  const id = toolId?.trim()
  if (id) return !isInternalCapabilityId(id)
  const name = toolName?.trim()
  return !!name && !isInternalCapabilityId(name)
}

function isVisibleStep(step: ReactStepDto) {
  if (step.type === 'TOOL_CALL') {
    const tc = step as ToolCallStep
    return isVisibleTool(tc.toolId, tc.toolName)
  }
  if (step.type === 'OBSERVATION') {
    const obs = step as ObservationStep
    return isVisibleTool(obs.toolId, obs.toolName)
  }
  if (step.type === 'PROGRESS') {
    return !!normalizeTurnStatusText((step as { content: string }).content)
  }
  return true
}
</script>

<template>
  <div v-if="shouldShow" class="mt-1.5">
    <!-- ━━━ 流式 ━━━ -->
    <template v-if="streaming">
      <p v-if="!hasToolCalls" class="thinking-live">
        <span class="thinking-dot" />
        <span>{{ latestProgress || '正在思考…' }}</span>
      </p>
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

    <!-- ━━━ 完成 ━━━ -->
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
          <button v-if="s.details.length" type="button" class="thinking-group-header" @click="toggleGroup(s.name)">
            <component :is="expandedGroups.has(s.name) ? ChevronDown : ChevronRight" class="size-3 opacity-40" />
            <span>{{ s.count === 1 ? s.name : `${s.name}（${s.count}次）` }}</span>
            <span v-if="s.status === 'error'" class="text-[10px] text-destructive/70">失败</span>
          </button>
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
