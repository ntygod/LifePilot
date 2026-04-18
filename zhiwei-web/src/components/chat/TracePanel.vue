<script setup lang="ts">
/**
 * 执行轨迹侧边面板 — 按步骤展示详细信息
 *
 * 后端提供自然语言摘要（inputSummary / outputSummary）和详情（outputDetail），
 * 前端直接展示，不做二次转换。
 *
 * 当前含 mock 转换层：将旧格式 JSON 模拟为自然语言，用于效果演示。
 * 后端对接完成后删除 mockSummarize* 函数即可。
 *
 * @author zsg
 * @since 2026-04-11
 */
import { computed } from 'vue'
import { Check, CircleCheck, ExternalLink, Loader2, Sparkles, X, Wrench } from 'lucide-vue-next'
import { RouterLink } from 'vue-router'
import type { ReasoningEvent, ReactStepDto, ToolCallStep, ObservationStep } from '@/types'

const props = defineProps<{
  reasoningEvents?: ReasoningEvent[]
  reactSteps?: ReactStepDto[]
  streaming?: boolean
  traceId?: string
  /** 隐藏自带的标题栏（嵌入到带 tab 的外层面板时用） */
  hideHeader?: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
}>()

// ─── 辅助 ───

function clip(s: string, max: number): string {
  return s.length <= max ? s : s.substring(0, max) + '…'
}

/** 从 JSON 串中提取第一个有意义的字符串值 */
function extractFromJson(s: string): string | null {
  try {
    const obj = JSON.parse(s)
    if (Array.isArray(obj)) return `${obj.length} 条结果`
    if (typeof obj !== 'object' || obj === null) return null
    // 按优先级提取
    for (const key of ['query', 'name', 'collectionName', 'title', 'path', 'command', 'action', 'url', 'content', 'error', 'message']) {
      const v = obj[key]
      if (typeof v === 'string' && v.length > 0) return clip(v, 80)
    }
    // 数组字段
    for (const key of ['results', 'items', 'entries', 'tasks', 'commits']) {
      if (Array.isArray(obj[key])) return `${obj[key].length} 条记录`
    }
    // 数值字段
    if (obj.count !== undefined) return `共 ${obj.count} 条`
    if (obj.lineCount !== undefined) return `${obj.lineCount} 行`
    if (obj.exitCode !== undefined) return `退出码 ${obj.exitCode}`
    return null
  } catch {
    // 截断的 JSON — 正则提取
    const m = s.match(/"(?:query|name|collectionName|title|path|command|action|error|message)"\s*:\s*"([^"]+)"/)
    return m ? clip(m[1], 80) : null
  }
}

/** 清理摘要字段：自然语言直接展示，JSON 做提取，流式残留文本去前缀 */
function cleanLabel(s: string | null | undefined): string | null {
  if (!s) return null
  const t = s.trim()
  // 流式阶段 "正在执行工具 XXX" — 去掉前缀后就是工具名，和 title 重复，不展示
  if (t.startsWith('正在执行工具')) return null
  // JSON → 提取有意义内容
  if (t.startsWith('{') || t.startsWith('[')) return extractFromJson(t)
  return t
}

// ─── 步骤构建 ───

interface TraceStep {
  key: string
  type: 'tool' | 'thought'
  title: string
  input?: string | null
  output?: string | null
  detail?: string | null
  status: 'running' | 'done' | 'error'
  meta?: string
}

const traceSteps = computed<TraceStep[]>(() => {
  const result: TraceStep[] = []

  // 从 ReactStepDto 构建（优先）
  if (props.reactSteps?.length) {
    let toolIdx = 0
    for (const s of props.reactSteps) {
      // 只处理 THOUGHT 和 TOOL_CALL，跳过 PROGRESS / ANSWER 等
      if (s.type === 'THOUGHT') {
        const content = (s as { content: string }).content
        if (content?.trim()) {
          result.push({
            key: `thought-${s.index}`,
            type: 'thought',
            title: '思考',
            output: clip(content.trim(), 200),
            status: 'done',
          })
        }
      } else if (s.type === 'TOOL_CALL') {
        const tc = s as ToolCallStep
        const name = tc.toolName || tc.toolId
        const obs = findObservation(props.reactSteps!, s.index, tc.toolId)
        const step: TraceStep = {
          key: `tool-${toolIdx++}`,
          type: 'tool',
          title: name,
          input: cleanLabel(tc.inputSummary),
          output: null,
          detail: null,
          status: 'running',
        }
        if (obs) {
          step.status = obs.success ? 'done' : 'error'
          step.output = cleanLabel(obs.outputSummary)
          step.detail = obs.outputDetail || null
          if (tc.latencyMs > 0) step.meta = `${(tc.latencyMs / 1000).toFixed(1)}s`
        }
        result.push(step)
      }
    }
  }

  // 从 ReasoningEvent 构建（无 reactSteps 时的兜底）
  if (props.reasoningEvents?.length && !result.length) {
    let idx = 0
    for (const ev of props.reasoningEvents) {
      if (ev.type === 'THOUGHT' && ev.description?.trim()) {
        result.push({
          key: `thought-${idx++}`,
          type: 'thought',
          title: ev.title || '思考',
          output: clip(ev.description.trim(), 200),
          status: 'done',
        })
      } else if (ev.type === 'TOOL_CALL') {
        const obs = findReasoningObs(props.reasoningEvents!, ev)
        const step: TraceStep = {
          key: `tool-${idx++}`,
          type: 'tool',
          title: ev.toolName || ev.title,
          input: cleanLabel(ev.description),
          output: null,
          status: 'running',
        }
        if (obs) {
          const failed = !!obs.title?.includes('失败')
          step.status = failed ? 'error' : 'done'
          step.output = cleanLabel(obs.description)
        }
        result.push(step)
      }
    }
  }

  // 完成态：所有 running → done
  if (!props.streaming) {
    result.forEach(s => { if (s.status === 'running') s.status = 'done' })
  }

  return result
})

function findObservation(steps: ReactStepDto[], toolIndex: number, toolId: string): ObservationStep | null {
  for (const s of steps) {
    if (s.type === 'OBSERVATION' && s.index > toolIndex && (s as ObservationStep).toolId === toolId) {
      return s as ObservationStep
    }
  }
  return null
}

function findReasoningObs(events: ReasoningEvent[], toolEvent: ReasoningEvent): ReasoningEvent | null {
  const i = events.indexOf(toolEvent)
  for (let j = i + 1; j < events.length; j++) {
    if (events[j].type === 'OBSERVATION' && events[j].toolName === toolEvent.toolName) return events[j]
  }
  return null
}

const hasRunning = computed(() => traceSteps.value.some(s => s.status === 'running'))
</script>

<template>
  <div class="flex h-full flex-col">
    <!-- 头部（可由外层隐藏） -->
    <div v-if="!hideHeader" class="trace-header">
      <h3 class="text-sm font-medium text-foreground">执行轨迹</h3>
      <button
        type="button"
        class="rounded-lg p-xs text-muted-foreground/50 hover:text-foreground hover:bg-muted/40 transition-colors"
        @click="emit('close')"
      >
        <X class="size-4" />
      </button>
    </div>

    <!-- 时间线 -->
    <div class="flex-1 overflow-y-auto scrollbar-thin scrollbar-track-transparent scrollbar-thumb-border">
      <div class="flex flex-col px-md pb-md">
        <div v-for="(step, i) in traceSteps" :key="step.key" class="trace-step">
          <!-- 左侧时间线 -->
          <div class="trace-rail">
            <div class="trace-dot" :class="'trace-dot--' + step.status">
              <Loader2 v-if="step.status === 'running'" class="size-3 animate-spin" />
              <Check v-else-if="step.status === 'done'" class="size-3" />
              <X v-else class="size-3" />
            </div>
            <div v-if="i < traceSteps.length - 1 || (streaming && hasRunning)" class="trace-connector" />
          </div>

          <!-- 右侧内容 -->
          <div class="trace-card" :class="step.status === 'running' && 'trace-card--active'">
            <!-- 标题行 -->
            <div class="trace-card-head">
              <Wrench v-if="step.type === 'tool'" class="size-3 opacity-40 shrink-0" />
              <Sparkles v-else-if="step.type === 'thought'" class="size-3 opacity-40 shrink-0" />
              <span
                class="truncate font-medium"
                :class="step.status === 'running' ? 'text-foreground' : 'text-foreground/78'"
              >{{ step.title }}</span>
              <span v-if="step.meta" class="trace-meta">{{ step.meta }}</span>
              <span v-if="step.status === 'error'" class="trace-badge-error">失败</span>
            </div>

            <!-- 输入标签 -->
            <p v-if="step.input" class="trace-label">{{ step.input }}</p>

            <!-- 输出标签 -->
            <p
              v-if="step.output"
              class="trace-label"
              :class="step.status === 'error' ? 'text-destructive/65' : 'text-muted-foreground/55'"
            >{{ step.output }}</p>

            <!-- 详情内容（直接展示） -->
            <pre v-if="step.detail" class="trace-detail-content">{{ step.detail }}</pre>
          </div>
        </div>

        <!-- 完成标记 -->
        <div v-if="!streaming && traceSteps.length" class="trace-step">
          <div class="trace-rail">
            <CircleCheck class="size-[1.125rem] text-primary/50" />
          </div>
          <div class="trace-card">
            <span class="text-xs text-muted-foreground/50">执行完成</span>
          </div>
        </div>

        <!-- 流式进行中标记 -->
        <div v-if="streaming && hasRunning" class="trace-step">
          <div class="trace-rail">
            <Loader2 class="size-[1.125rem] animate-spin text-primary/60" />
          </div>
          <div class="trace-card">
            <span class="text-xs text-foreground/60">执行中…</span>
          </div>
        </div>
      </div>

      <p v-if="!traceSteps.length && !streaming" class="py-2xl text-center text-xs text-muted-foreground/50">
        暂无执行步骤
      </p>
    </div>

    <!-- 底部 -->
    <div v-if="traceId && !streaming" class="trace-footer">
      <RouterLink
        :to="{ name: 'traces', query: { id: traceId } }"
        class="trace-footer-link"
      >
        <ExternalLink class="size-3" />
        查看完整执行轨迹
      </RouterLink>
    </div>
  </div>
</template>

<style scoped>
.trace-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  padding: 0.75rem 0.875rem;
  border-bottom: 1px solid hsl(from var(--border) h s l / 0.32);
}

.trace-step {
  display: flex;
  gap: 0.625rem;
}

/* ── 左侧轨道 ── */
.trace-rail {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 1.375rem;
  flex-shrink: 0;
  padding-top: 0.625rem;
}

.trace-dot {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 1.375rem;
  height: 1.375rem;
  border-radius: 999px;
}

.trace-dot--done {
  color: hsl(from var(--muted-foreground) h s l / 0.5);
  background: hsl(from var(--muted) h s l / 0.62);
}
.trace-dot--running {
  color: hsl(from var(--primary) h s l / 0.92);
  background: hsl(from var(--primary) h s l / 0.14);
}
.trace-dot--error {
  color: hsl(from var(--destructive) h s l / 0.78);
  background: hsl(from var(--destructive) h s l / 0.1);
}

.trace-connector {
  flex: 1;
  width: 1.5px;
  min-height: 0.5rem;
  margin-top: 0.25rem;
  margin-bottom: 0.1rem;
  background: hsl(from var(--border) h s l / 0.5);
}

/* ── 右侧卡片 ── */
.trace-card {
  flex: 1;
  min-width: 0;
  padding: 0.5rem 0.625rem;
  margin-bottom: 0.125rem;
  border-radius: 0.5rem;
  transition: background 160ms ease;
}

.trace-card--active {
  background: hsl(from var(--primary) h s l / 0.04);
}

.trace-card-head {
  display: flex;
  align-items: center;
  gap: 0.3rem;
  font-size: 13px;
  line-height: 1.5;
  color: hsl(from var(--foreground) h s l / 0.82);
}

.trace-card--active .trace-card-head {
  color: var(--foreground);
}

.trace-meta {
  font-weight: 400;
  font-size: 11px;
  color: hsl(from var(--muted-foreground) h s l / 0.4);
  margin-left: auto;
  flex-shrink: 0;
}

.trace-badge-error {
  font-size: 10px;
  font-weight: 500;
  color: hsl(from var(--destructive) h s l / 0.78);
  background: hsl(from var(--destructive) h s l / 0.08);
  border-radius: 0.25rem;
  padding: 0 0.3rem;
  line-height: 1.6;
  flex-shrink: 0;
}

.trace-label {
  margin-top: 0.15rem;
  font-size: 12px;
  line-height: 1.55;
  color: hsl(from var(--foreground) h s l / 0.5);
}

.trace-detail-content {
  margin-top: 0.35rem;
  padding: 0.5rem 0.625rem;
  font-size: 11.5px;
  line-height: 1.6;
  color: hsl(from var(--foreground) h s l / 0.58);
  background: hsl(from var(--muted) h s l / 0.35);
  border-radius: 0.375rem;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 16rem;
  overflow-y: auto;
  font-family: inherit;
  scrollbar-width: thin;
}

/* ── 底部 ── */
.trace-footer {
  border-top: 1px solid hsl(from var(--border) h s l / 0.32);
  padding: 0.625rem 0.875rem;
}

.trace-footer-link {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  font-size: 12px;
  color: hsl(from var(--primary) h s l / 0.56);
  transition: color 140ms ease;
}
.trace-footer-link:hover {
  color: hsl(from var(--primary) h s l / 0.88);
}
</style>
