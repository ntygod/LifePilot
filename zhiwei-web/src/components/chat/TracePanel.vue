<script setup lang="ts">
/**
 * 任务步骤侧边面板 — 按用户可理解的任务步骤展示详细信息
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
import { Check, CircleCheck, ExternalLink, Loader2, Play, RefreshCw, Sparkles, X, Wrench } from 'lucide-vue-next'
import { RouterLink } from 'vue-router'
import type { ReasoningEvent, ReactStepDto, ToolCallStep, ObservationStep, ToolCallSummary, TurnRecoveryContext } from '@/types'
import { isInternalCapabilityId } from '@/utils/liveCapabilities'
import {
  buildToolRecoveryPlan,
  formatToolFailureCategory,
  resolveRestartActionDescription,
  resolveResumeActionDescription,
  resolveToolAction,
  resolveToolExecutionKind,
  resolveToolFailureCategory,
} from '@/utils/toolExecution'

const props = defineProps<{
  reasoningEvents?: ReasoningEvent[]
  reactSteps?: ReactStepDto[]
  toolSummaries?: ToolCallSummary[]
  turnRecoveryContext?: TurnRecoveryContext | null
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

function cleanDetail(s: string | null | undefined): string | null {
  if (!s) return null
  const t = s.trim()
  if (!t) return null
  if (t.startsWith('{') || t.startsWith('[')) return extractFromJson(t)
  return clip(t, 360)
}

// ─── 步骤构建 ───

interface TraceStep {
  key: string
  type: 'tool' | 'thought' | 'recovery'
  title: string
  subject?: string | null
  input?: string | null
  output?: string | null
  detail?: string | null
  status: 'running' | 'done' | 'error'
  meta?: string
  nextActions?: string[]
  recoveryAction?: 'resume' | 'restart'
  recoveryDescription?: string
}

const taskStepCount = computed(() => traceSteps.value.filter(step => step.type !== 'thought').length)
const failedStepCount = computed(() => traceSteps.value.filter(step => step.status === 'error').length)
const panelSummary = computed(() => {
  if (!traceSteps.value.length) {
    return props.streaming ? '知微正在准备任务步骤。' : '这轮没有可展示的执行步骤。'
  }
  if (props.streaming && hasRunning.value) {
    return taskStepCount.value > 0
      ? `正在推进 ${taskStepCount.value} 个任务步骤。`
      : '知微正在推进当前任务。'
  }
  if (failedStepCount.value > 0) {
    return `${failedStepCount.value} 个步骤需要处理，其余步骤可作为恢复参考。`
  }
  return taskStepCount.value > 0
    ? `已完成 ${taskStepCount.value} 个任务步骤。`
    : '已完成本轮思考整理。'
})

function naturalToolTitle(toolId?: string, toolName?: string, subjectNames?: string[]) {
  const key = `${toolId ?? ''} ${toolName ?? ''}`.toLowerCase()
  if (key.includes('skill')) return '准备相关技能'
  if (
    key.includes('knowledge.')
    || key.includes('kb.')
    || key.includes('rag.')
    || key.includes('document.')
    || key.includes('pdf.')
    || key.includes('vector.')
    || key.includes('资料')
    || key.includes('知识库')
  ) {
    return '整理资料'
  }
  if (key.includes('web.') || key.includes('search') || key.includes('fetch') || key.includes('搜索')) {
    return '查找资料'
  }
  if (key.includes('browser') || key.includes('浏览器')) return '查看页面'
  if (key.includes('file.') || key.includes('文件') || key.includes('write') || key.includes('edit')) {
    return '整理文件'
  }
  if (key.includes('shell') || key.includes('code') || key.includes('命令') || key.includes('测试')) {
    return '执行验证'
  }
  if (key.includes('memory') || key.includes('记忆')) return '处理记忆'
  if (key.includes('workflow.') || key.includes('工作流') || key.includes('流程')) return '推进流程'
  if (key.includes('mcp.') || key.includes('connector.') || key.includes('integration.') || key.includes('连接器')) {
    return '连接工具服务'
  }
  if (key.includes('agent.') || key.includes('智能体')) {
    return '协作处理'
  }
  if (
    key.includes('llm.')
    || key.includes('model.')
    || key.includes('embedding.')
    || key.includes('vision.')
    || key.includes('image.')
    || key.includes('audio.')
    || key.includes('模型')
  ) {
    return '整理回答'
  }
  if (key.includes('git.') || key.includes('仓库')) return '处理仓库'
  if (subjectNames?.length) return '处理相关对象'
  return '推进任务步骤'
}

function subjectText(label?: string, names?: string[]) {
  const visibleNames = names?.filter(Boolean) ?? []
  if (!visibleNames.length) return null
  return `${label || '对象'} ${visibleNames.slice(0, 3).join('、')}`
}

function isVisibleToolStep(toolId?: string | null, toolName?: string | null) {
  const id = toolId?.trim()
  if (id) {
    return !isInternalCapabilityId(id)
  }
  const name = toolName?.trim()
  return !!name && !isInternalCapabilityId(name)
}

function statusFromToolSummary(tool: ToolCallSummary): TraceStep['status'] {
  if (tool.status === 'FAILED' || tool.success === false) {
    return 'error'
  }
  if (tool.status === 'RUNNING' || tool.hasMoreSteps) {
    return 'running'
  }
  return 'done'
}

function buildToolSummaryStep(tool: ToolCallSummary, index: number): TraceStep | null {
  if (!isVisibleToolStep(tool.toolId, tool.toolName)) {
    return null
  }
  const status = statusFromToolSummary(tool)
  const step: TraceStep = {
    key: `summary-tool-${index}-${tool.callId ?? tool.toolId}`,
    type: 'tool',
    title: naturalToolTitle(tool.toolId, tool.toolName, tool.subjectNames),
    subject: subjectText(tool.subjectLabel, tool.subjectNames),
    input: cleanLabel(tool.inputSummary),
    output: cleanLabel(tool.outputSummary),
    detail: cleanDetail(tool.outputDetail),
    status,
  }
  if (tool.latencyMs > 0) {
    step.meta = `${(tool.latencyMs / 1000).toFixed(1)}s`
  }
  if (status === 'error') {
    step.nextActions = buildToolRecoveryPlan({
      toolId: tool.toolId,
      executionKind: tool.executionKind,
      failureCategory: tool.failureCategory,
      subjectNames: tool.subjectNames,
    }).slice(0, 2)
  }
  return step
}

function buildRecoveryStep(context?: TurnRecoveryContext | null): TraceStep | null {
  if (!context) {
    return null
  }
  const checkpoint = context.checkpoint
  const action = recoveryActionKind(context)
  const step: TraceStep = {
    key: `recovery-${context.sourceTraceId ?? context.assistantEntryId ?? context.capturedAt ?? action}`,
    type: 'recovery',
    title: action === 'restart' ? '重新开始并修正' : '从断点继续',
    subject: recoverySubject(checkpoint),
    input: cleanLabel(context.resumeInput ?? checkpoint?.inputSummary),
    output: cleanLabel(checkpoint?.outputSummary),
    detail: cleanDetail(context.detail ?? checkpoint?.outputDetail),
    status: 'done',
    nextActions: context.nextActions?.filter(Boolean).slice(0, 2),
    recoveryAction: action,
    recoveryDescription: recoveryDescription(context, action),
  }
  return step
}

function recoveryActionKind(context: TurnRecoveryContext): 'resume' | 'restart' {
  const mode = context.checkpoint?.recoveryActionMode?.toLowerCase()
  if (mode === 'restart') return 'restart'
  if (mode === 'resume') return 'resume'
  return context.action === 'RESTART' ? 'restart' : 'resume'
}

function recoverySubject(checkpoint?: TurnRecoveryContext['checkpoint']) {
  if (!checkpoint) {
    return null
  }
  const subjectNames = checkpoint.subjectNames?.filter(Boolean) ?? []
  if (checkpoint.executionKind === 'SKILL' || checkpoint.failureCategory === 'SKILL') {
    return subjectNames.length
      ? `技能 ${subjectNames.slice(0, 2).join('、')}`
      : (checkpoint.action ?? checkpoint.toolName ?? '技能步骤')
  }
  if (checkpoint.action && checkpoint.toolName && checkpoint.action !== checkpoint.toolName) {
    return `${checkpoint.action} · ${checkpoint.toolName}`
  }
  return checkpoint.toolName
    ?? checkpoint.action
    ?? (checkpoint.toolId ? resolveToolAction(checkpoint.toolId) : undefined)
    ?? formatToolFailureCategory(checkpoint.failureCategory)
    ?? null
}

function recoveryDescription(context: TurnRecoveryContext, action: 'resume' | 'restart') {
  const explicit = cleanLabel(context.checkpoint?.recoveryActionDescription)
  if (explicit) {
    return explicit
  }
  const strategy = cleanLabel(context.resumeStrategy)
  if (strategy) {
    return strategy
  }
  const category = context.checkpoint?.failureCategory ?? 'UNKNOWN'
  return action === 'restart'
    ? resolveRestartActionDescription(category)
    : resolveResumeActionDescription(category)
}

const traceSteps = computed<TraceStep[]>(() => {
  const result: TraceStep[] = []
  const recoveryStep = buildRecoveryStep(props.turnRecoveryContext)
  if (recoveryStep) {
    result.push(recoveryStep)
  }

  // 从 ReactStepDto 构建（优先）
  if (props.reactSteps?.length) {
    let toolIdx = 0
    const matchedObservationIndexes = new Set<number>()
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
        if (!isVisibleToolStep(tc.toolId, tc.toolName)) {
          continue
        }
        const obs = findObservation(
          props.reactSteps!,
          s.index,
          tc.toolId,
          tc.callId,
          matchedObservationIndexes,
        )
        const step: TraceStep = {
          key: `tool-${toolIdx++}`,
          type: 'tool',
          title: naturalToolTitle(tc.toolId, tc.toolName, tc.subjectNames),
          subject: subjectText(tc.subjectLabel, tc.subjectNames),
          input: cleanLabel(tc.inputSummary),
          output: null,
          detail: null,
          status: 'running',
        }
        if (obs) {
          step.status = obs.success ? 'done' : 'error'
          step.output = cleanLabel(obs.outputSummary)
          step.detail = cleanDetail(obs.outputDetail)
          if (!obs.success) {
            const failureText = [obs.outputSummary, obs.outputDetail].filter(Boolean).join(' ')
            step.nextActions = buildToolRecoveryPlan({
              toolId: tc.toolId,
              executionKind: resolveToolExecutionKind(tc.toolId),
              failureCategory: resolveToolFailureCategory(tc.toolId, failureText),
              outputSummary: obs.outputSummary,
              outputDetail: obs.outputDetail,
              subjectNames: tc.subjectNames,
            }).slice(0, 2)
          }
          if (tc.latencyMs > 0) step.meta = `${(tc.latencyMs / 1000).toFixed(1)}s`
        }
        result.push(step)
      }
    }
  }

  // 历史消息可能只有 toolsSummary，仍然要能查看任务步骤。
  if (props.toolSummaries?.length && !hasNonRecoverySteps(result)) {
    props.toolSummaries.forEach((tool, index) => {
      const step = buildToolSummaryStep(tool, index)
      if (step) {
        result.push(step)
      }
    })
  }

  // 从 ReasoningEvent 构建（无 reactSteps 时的兜底）
  if (props.reasoningEvents?.length && !hasNonRecoverySteps(result)) {
    let idx = 0
    const matchedObservationEvents = new Set<ReasoningEvent>()
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
        const toolId = readReasoningToolId(ev)
        if (!isVisibleToolStep(toolId, ev.toolName || ev.title)) {
          continue
        }
        const obs = findReasoningObs(props.reasoningEvents!, ev, matchedObservationEvents)
        const step: TraceStep = {
          key: `tool-${idx++}`,
          type: 'tool',
          title: naturalToolTitle(toolId, ev.toolName || ev.title),
          input: cleanLabel(ev.description),
          output: null,
          status: 'running',
        }
        if (obs) {
          const failed = !!obs.title?.includes('失败')
          step.status = failed ? 'error' : 'done'
          step.output = cleanLabel(obs.description)
          if (failed) {
            step.nextActions = buildToolRecoveryPlan({
              toolId,
              executionKind: toolId ? resolveToolExecutionKind(toolId) : undefined,
              failureCategory: toolId ? resolveToolFailureCategory(toolId, obs.description) : undefined,
              outputSummary: obs.description,
            }).slice(0, 2)
          }
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

function hasNonRecoverySteps(steps: TraceStep[]) {
  return steps.some(step => step.type !== 'recovery')
}

function findObservation(
  steps: ReactStepDto[],
  toolIndex: number,
  toolId: string,
  callId?: string,
  matchedObservationIndexes = new Set<number>(),
): ObservationStep | null {
  const normalizedCallId = callId?.trim()
  if (normalizedCallId) {
    const byCallId = steps.find((step): step is ObservationStep =>
      step.type === 'OBSERVATION'
      && step.index > toolIndex
      && step.callId === normalizedCallId
      && !matchedObservationIndexes.has(step.index),
    )
    if (byCallId) {
      matchedObservationIndexes.add(byCallId.index)
      return byCallId
    }
    return null
  }

  for (const s of steps) {
    if (
      s.type === 'OBSERVATION'
      && s.index > toolIndex
      && !matchedObservationIndexes.has(s.index)
      && (s as ObservationStep).toolId === toolId
    ) {
      matchedObservationIndexes.add(s.index)
      return s as ObservationStep
    }
  }
  return null
}

function findReasoningObs(
  events: ReasoningEvent[],
  toolEvent: ReasoningEvent,
  matchedObservationEvents = new Set<ReasoningEvent>(),
): ReasoningEvent | null {
  const startIndex = events.indexOf(toolEvent)
  const callId = readReasoningCallId(toolEvent)
  if (callId) {
    for (let index = startIndex + 1; index < events.length; index += 1) {
      const event = events[index]
      if (
        event.type === 'OBSERVATION'
        && !matchedObservationEvents.has(event)
        && readReasoningCallId(event) === callId
      ) {
        matchedObservationEvents.add(event)
        return event
      }
    }
    return null
  }

  for (let j = startIndex + 1; j < events.length; j++) {
    const event = events[j]
    if (
      event.type === 'OBSERVATION'
      && !matchedObservationEvents.has(event)
      && event.toolName === toolEvent.toolName
    ) {
      matchedObservationEvents.add(event)
      return event
    }
  }
  return null
}

function readReasoningToolId(event: ReasoningEvent) {
  const value = event.extra?.toolId ?? event.extra?.tool_id ?? event.extra?.name
  return typeof value === 'string' ? value : undefined
}

function readReasoningCallId(event: ReasoningEvent) {
  const value = event.extra?.callId ?? event.extra?.call_id
  return typeof value === 'string' && value.trim() ? value.trim() : undefined
}

const hasRunning = computed(() => traceSteps.value.some(s => s.status === 'running'))
</script>

<template>
  <div class="flex h-full flex-col">
    <!-- 头部（可由外层隐藏） -->
    <div v-if="!hideHeader" class="trace-header">
      <h3 class="text-sm font-medium text-foreground">任务步骤</h3>
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
      <div v-if="traceSteps.length || streaming" class="trace-summary" aria-live="polite">
        {{ panelSummary }}
      </div>
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
              <component
                :is="step.recoveryAction === 'restart' ? RefreshCw : Play"
                v-else-if="step.type === 'recovery'"
                class="size-3 opacity-50 shrink-0"
              />
              <Sparkles v-else-if="step.type === 'thought'" class="size-3 opacity-40 shrink-0" />
              <span
                class="truncate font-medium"
                :class="step.status === 'running' ? 'text-foreground' : 'text-foreground/78'"
              >{{ step.title }}</span>
              <span v-if="step.subject" class="trace-subject">{{ step.subject }}</span>
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

            <div
              v-if="step.recoveryDescription"
              class="trace-recovery-description"
              aria-label="恢复方式"
            >
              <span class="trace-recovery-description__label">恢复方式</span>
              <span class="trace-recovery-description__text">{{ step.recoveryDescription }}</span>
            </div>

            <!-- 详情内容（直接展示） -->
            <pre v-if="step.detail" class="trace-detail-content">{{ step.detail }}</pre>

            <div v-if="step.nextActions?.length" class="trace-next-actions" aria-label="处理建议">
              <span class="trace-next-actions__label">建议</span>
              <span
                v-for="action in step.nextActions"
                :key="action"
                class="trace-next-actions__item"
              >
                {{ action }}
              </span>
            </div>
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
        暂无任务步骤
      </p>
    </div>

    <!-- 底部 -->
    <div v-if="traceId && !streaming" class="trace-footer">
      <RouterLink
        :to="{ name: 'traces', query: { id: traceId } }"
        class="trace-footer-link"
      >
        <ExternalLink class="size-3" />
        查看完整任务记录
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

.trace-summary {
  margin: 0.5rem 0.875rem 0.25rem;
  padding: 0.5rem 0.625rem;
  border-radius: 0.5rem;
  background: hsl(from var(--muted) h s l / 0.24);
  color: hsl(from var(--muted-foreground) h s l / 0.9);
  font-size: 12px;
  line-height: 1.5;
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

.trace-subject {
  min-width: 0;
  max-width: 10rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  border-radius: 999px;
  background: hsl(from var(--muted) h s l / 0.38);
  padding: 0 0.4rem;
  color: hsl(from var(--muted-foreground) h s l / 0.82);
  font-size: 10px;
  line-height: 1.6;
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

.trace-recovery-description {
  display: grid;
  gap: 0.2rem;
  margin-top: 0.4rem;
  border-radius: 0.45rem;
  border: 1px solid hsl(38 92% 50% / 0.22);
  background: hsl(38 92% 50% / 0.075);
  padding: 0.42rem 0.55rem;
}

.trace-recovery-description__label {
  color: hsl(34 72% 34%);
  font-size: 11px;
  font-weight: 600;
}

.trace-recovery-description__text {
  color: hsl(from var(--foreground) h s l / 0.74);
  font-size: 11.5px;
  line-height: 1.55;
}

.trace-next-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.35rem;
  margin-top: 0.45rem;
}

.trace-next-actions__label {
  color: hsl(from var(--muted-foreground) h s l / 0.7);
  font-size: 11px;
  font-weight: 600;
}

.trace-next-actions__item {
  min-width: 0;
  max-width: 100%;
  border-radius: 999px;
  border: 1px solid hsl(from var(--border) h s l / 0.46);
  background: hsl(from var(--background) h s l / 0.58);
  padding: 0.18rem 0.5rem;
  color: hsl(from var(--foreground) h s l / 0.62);
  font-size: 11px;
  line-height: 1.45;
  overflow-wrap: anywhere;
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
