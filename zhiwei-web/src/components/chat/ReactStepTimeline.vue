<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ReactStepDto, ToolCallStep, ObservationStep } from '@/types'
import {
  Lightbulb, Wrench, Eye, PenLine, Pause, Play,
  ChevronDown, ChevronRight, Loader2,
  CircleDot
} from 'lucide-vue-next'
import WorkerResultCard from './WorkerResultCard.vue'

const props = defineProps<{
  /** ReAct 步骤序列 */
  steps: ReactStepDto[]
  /** 是否正在流式推理中 */
  streaming?: boolean
  /** 推理概要文本（兜底展示） */
  summary?: string
}>()

const expanded = ref(false)

// 有效步骤列表
const hasSteps = computed(() => props.steps.length > 0)

// 统计信息
const stepCount = computed(() => props.steps.length)
const toolCallCount = computed(() =>
  props.steps.filter(s => s.type === 'TOOL_CALL').length
)
const latestStep = computed(() => props.steps[props.steps.length - 1] ?? null)

// ToolCall + Observation 配对归组
interface StepGroup {
  type: 'single' | 'tool-pair'
  steps: ReactStepDto[]
}

const stepGroups = computed<StepGroup[]>(() => {
  const groups: StepGroup[] = []
  const list = props.steps
  let i = 0
  while (i < list.length) {
    const step = list[i]
    // 如果是 TOOL_CALL 且下一个是同 toolId 的 OBSERVATION，归组
    if (step.type === 'TOOL_CALL' && i + 1 < list.length && list[i + 1].type === 'OBSERVATION') {
      const obs = list[i + 1] as ObservationStep
      const tc = step as ToolCallStep
      if (obs.toolId === tc.toolId) {
        groups.push({ type: 'tool-pair', steps: [step, list[i + 1]] })
        i += 2
        continue
      }
    }
    groups.push({ type: 'single', steps: [step] })
    i++
  }
  return groups
})

// 触发器主文案：流式时实时显示当前步骤，完成后显示统计
const triggerLabel = computed(() => {
  const count = props.steps.length
  if (count === 0) return props.streaming ? '推理中…' : '推理概要'
  // 取最新步骤的简短描述
  const last = props.steps[count - 1]
  const desc = getStepBrief(last)
  if (props.streaming) return desc
  // 完成后也显示最后一步摘要，让用户不展开就能看到结论
  return `${count} 步 · ${desc}`
})

// 步骤简短描述（用于触发器区域，控制在 40 字符内）
function getStepBrief(step: ReactStepDto): string {
  switch (step.type) {
    case 'PROGRESS': {
      const text = step.content ?? ''
      if (text.length <= 40) return text || '处理中…'
      return text.substring(0, 40) + '…'
    }
    case 'THOUGHT': {
      const text = step.content ?? ''
      if (text.length <= 40) return text || '推理中…'
      return text.substring(0, 40) + '…'
    }
    case 'TOOL_CALL': return `调用 ${step.toolName ?? step.toolId}`
    case 'OBSERVATION': return `${step.success ? '✓' : '✗'} ${step.toolName ?? step.toolId} 返回`
    case 'ANSWER': return '生成回答'
    case 'SUSPEND': return '等待确认…'
    case 'RESUME': return '已恢复执行'
  }
}

// 步骤展开/折叠状态（按 index 追踪）
const expandedSteps = ref<Set<number>>(new Set())
function toggleStep(index: number) {
  if (expandedSteps.value.has(index)) {
    expandedSteps.value.delete(index)
  } else {
    expandedSteps.value.add(index)
  }
}

// 步骤图标映射
function getStepIcon(type: string) {
  switch (type) {
    case 'PROGRESS': return Loader2
    case 'THOUGHT': return Lightbulb
    case 'TOOL_CALL': return Wrench
    case 'OBSERVATION': return Eye
    case 'ANSWER': return PenLine
    case 'SUSPEND': return Pause
    case 'RESUME': return Play
    default: return CircleDot
  }
}

// 步骤颜色映射
function getStepColor(type: string) {
  switch (type) {
    case 'PROGRESS': return 'text-primary'
    case 'THOUGHT': return 'text-violet-500'
    case 'TOOL_CALL': return 'text-amber-500'
    case 'OBSERVATION': return 'text-blue-500'
    case 'ANSWER': return 'text-emerald-500'
    case 'SUSPEND': return 'text-orange-500'
    case 'RESUME': return 'text-cyan-500'
    default: return 'text-muted-foreground'
  }
}

// 步骤标题（Thought 类型显示内容摘要而非固定文案）
function getStepTitle(step: ReactStepDto): string {
  switch (step.type) {
    case 'PROGRESS': {
      if (step.content && step.content.length <= 30) return step.content
      return '执行进度'
    }
    case 'THOUGHT': {
      // 短内容直接作为标题，长内容用固定标题 + 内联预览
      if (step.content && step.content.length <= 30) return step.content
      return '推理思考'
    }
    case 'TOOL_CALL': return `调用工具: ${step.toolName ?? step.toolId}`
    case 'OBSERVATION': return `${step.success ? '工具返回' : '工具失败'}: ${step.toolName ?? step.toolId}`
    case 'ANSWER': return '生成回答'
    case 'SUSPEND': return 'Agent 挂起'
    case 'RESUME': return 'Agent 恢复'
  }
}

// 步骤完整内容（点击展开时显示）
function getStepContent(step: ReactStepDto): string | null {
  switch (step.type) {
    case 'PROGRESS': return step.content
    case 'THOUGHT': return step.content
    case 'TOOL_CALL': return step.inputSummary || null
    case 'OBSERVATION': return step.outputSummary || null
    case 'ANSWER': return step.content
    case 'SUSPEND': return step.reason
    case 'RESUME': return `挂起时长: ${step.suspendDurationMs}ms`
  }
}

// 步骤内联预览（始终可见的一行摘要，截断到 80 字符）
function getStepPreview(step: ReactStepDto): string | null {
  // Progress / Thought 的短内容已作为标题显示，不重复
  if ((step.type === 'THOUGHT' || step.type === 'PROGRESS') && step.content && step.content.length <= 30) return null
  const content = getStepContent(step)
  if (!content) return null
  const firstLine = content.split('\n')[0]
  if (firstLine.length > 80) return firstLine.substring(0, 80) + '…'
  return firstLine
}

// 内容是否超出预览长度（决定是否显示展开按钮）
function hasExpandableContent(step: ReactStepDto): boolean {
  // Progress / Thought 的短内容已作为标题，无需展开
  if ((step.type === 'THOUGHT' || step.type === 'PROGRESS') && step.content && step.content.length <= 30) return false
  const content = getStepContent(step)
  if (!content) return false
  return content.length > 80 || content.includes('\n')
}

// 工具配对组的标题
function getToolPairTitle(tc: ToolCallStep, obs: ObservationStep): string {
  return `${tc.toolName ?? tc.toolId} — ${obs.success ? '成功' : '失败'}`
}

// 工具配对组的内联输出预览
function getToolPairPreview(tc: ToolCallStep, obs: ObservationStep): string | null {
  // spawn_workers 专用预览
  if (tc.toolId === 'spawn_workers') {
    try {
      const data = JSON.parse(obs.outputSummary)
      if (data.workers) {
        return `${data.workers.length} 个 Worker · ${data.successCount ?? 0} 成功 · ${data.failureCount ?? 0} 失败`
      }
    } catch { /* 解析失败回退默认逻辑 */ }
  }
  const output = obs.outputSummary
  if (!output) return null
  const firstLine = output.split('\n')[0]
  if (firstLine.length > 80) return firstLine.substring(0, 80) + '…'
  return firstLine
}

function getGroupKey(group: StepGroup): string {
  const first = group.steps[0]
  return `${group.type}-${first.index}-${first.type}`
}
</script>

<template>
  <div
    v-if="summary || hasSteps"
    class="react-panel mt-2 overflow-hidden rounded-xl border text-xs transition-all duration-300"
    :class="streaming ? 'react-panel-streaming' : 'react-panel-idle'"
  >
    <button
      type="button"
      class="react-trigger flex w-full items-center justify-between gap-2 px-3 py-2 text-left focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-1"
      @click="expanded = !expanded"
    >
      <div class="flex min-w-0 flex-1 items-center gap-2">
        <div
          class="react-icon-shell relative shrink-0"
          :class="streaming ? 'react-icon-shell-streaming' : ''"
        >
          <component
            :is="latestStep ? getStepIcon(latestStep.type) : CircleDot"
            :size="12"
            class="transition-colors duration-300"
            :class="streaming
              ? 'text-primary'
              : latestStep ? getStepColor(latestStep.type) : 'text-muted-foreground'"
          />
        </div>
        <div class="flex min-w-0 flex-1 items-center gap-2">
          <span class="shrink-0 text-[10px] font-semibold tracking-[0.08em] text-muted-foreground/82">执行轨迹</span>
          <span v-if="toolCallCount > 0" class="react-chip">
            <Wrench :size="10" />
            {{ toolCallCount }}
          </span>
          <span class="text-muted-foreground/30">·</span>
          <p
            class="min-w-0 truncate text-[11px] font-medium"
            :class="streaming ? 'text-primary' : 'text-foreground/84'"
          >
            {{ triggerLabel }}
          </p>
        </div>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <span v-if="streaming" class="react-chip react-chip-streaming">
          <Loader2 :size="10" class="animate-spin" />
          进行中
        </span>
        <span class="react-chip">{{ stepCount }} 步</span>
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
            v-if="hasSteps"
            name="react-step"
            tag="div"
            class="space-y-2"
          >
            <div
              v-for="(group, gi) in stepGroups"
              :key="getGroupKey(group)"
              class="flex items-start gap-2.5"
            >
              <div class="flex w-6 shrink-0 flex-col items-center">
                <div
                  class="react-node flex h-6 w-6 items-center justify-center rounded-2xl"
                  :class="streaming && gi === stepGroups.length - 1 ? 'react-node-active' : ''"
                >
                  <Wrench v-if="group.type === 'tool-pair'" :size="12" class="text-amber-500" />
                  <component
                    v-else
                    :is="getStepIcon(group.steps[0].type)"
                    :size="12"
                    :class="getStepColor(group.steps[0].type)"
                  />
                </div>
                <div v-if="gi < stepGroups.length - 1" class="react-line w-px flex-1 min-h-[18px] bg-border" />
              </div>

              <div
                class="react-card flex-1 min-w-0"
                :class="streaming && gi === stepGroups.length - 1 ? 'react-card-active' : ''"
              >
                <template v-if="group.type === 'tool-pair'">
                  <div class="flex items-start justify-between gap-2">
                    <div class="min-w-0">
                      <div class="flex items-center gap-1.5 flex-wrap">
                        <button
                          type="button"
                          class="text-left text-[11px] font-medium leading-5 text-foreground/92 transition-colors hover:text-primary"
                          @click="toggleStep((group.steps[0] as ToolCallStep).index)"
                        >
                          {{ getToolPairTitle(group.steps[0] as ToolCallStep, group.steps[1] as ObservationStep) }}
                        </button>
                        <span
                          class="react-meta-chip"
                          :class="(group.steps[1] as ObservationStep).success ? 'react-meta-chip-success' : 'react-meta-chip-failure'"
                        >
                          {{ (group.steps[1] as ObservationStep).success ? '成功' : '失败' }}
                        </span>
                      </div>
                    </div>
                    <span
                      v-if="(group.steps[0] as ToolCallStep).latencyMs > 0"
                      class="react-meta-chip shrink-0"
                    >
                      {{ (group.steps[0] as ToolCallStep).latencyMs }}ms
                    </span>
                  </div>
                  <p
                    v-if="getToolPairPreview(group.steps[0] as ToolCallStep, group.steps[1] as ObservationStep)"
                    class="mt-1.5 text-[10px] leading-relaxed text-muted-foreground/82"
                  >
                    {{ getToolPairPreview(group.steps[0] as ToolCallStep, group.steps[1] as ObservationStep) }}
                  </p>
                  <div
                    v-if="expandedSteps.has((group.steps[0] as ToolCallStep).index)"
                    class="mt-2 space-y-2"
                  >
                    <WorkerResultCard
                      v-if="(group.steps[0] as ToolCallStep).toolId === 'spawn_workers'"
                      :output="(group.steps[1] as ObservationStep).outputSummary"
                    />
                    <template v-else>
                      <div v-if="(group.steps[0] as ToolCallStep).inputSummary" class="react-detail-block">
                        <div class="react-detail-label">输入</div>
                        <p class="text-[10px] leading-relaxed text-foreground/84">
                          {{ (group.steps[0] as ToolCallStep).inputSummary }}
                        </p>
                      </div>
                      <div v-if="(group.steps[1] as ObservationStep).outputSummary" class="react-detail-block">
                        <div class="react-detail-label">输出</div>
                        <p class="text-[10px] leading-relaxed text-foreground/84">
                          {{ (group.steps[1] as ObservationStep).outputSummary }}
                        </p>
                      </div>
                    </template>
                  </div>
                </template>

                <template v-else>
                  <div class="flex items-start justify-between gap-2">
                    <div class="min-w-0">
                      <button
                        v-if="hasExpandableContent(group.steps[0])"
                        type="button"
                        class="text-left text-[11px] font-medium leading-5 text-foreground/92 transition-colors hover:text-primary"
                        @click="toggleStep(group.steps[0].index)"
                      >
                        {{ getStepTitle(group.steps[0]) }}
                      </button>
                      <span v-else class="text-[11px] font-medium leading-5 text-foreground/92">
                        {{ getStepTitle(group.steps[0]) }}
                      </span>
                    </div>
                    <span
                      v-if="group.steps[0].type === 'OBSERVATION'"
                      class="react-meta-chip shrink-0"
                      :class="(group.steps[0] as ObservationStep).success ? 'react-meta-chip-success' : 'react-meta-chip-failure'"
                    >
                      {{ (group.steps[0] as ObservationStep).success ? '成功' : '失败' }}
                    </span>
                  </div>
                  <p
                    v-if="getStepPreview(group.steps[0]) && !expandedSteps.has(group.steps[0].index)"
                    class="mt-1.5 text-[10px] leading-relaxed text-muted-foreground/82"
                  >
                    {{ getStepPreview(group.steps[0]) }}
                  </p>
                  <div
                    v-if="expandedSteps.has(group.steps[0].index) && getStepContent(group.steps[0])"
                    class="mt-2 react-detail-block"
                  >
                    <p class="text-[10px] whitespace-pre-wrap leading-relaxed text-foreground/84">
                      {{ getStepContent(group.steps[0]) }}
                    </p>
                  </div>
                </template>
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
.react-panel {
  position: relative;
  background: hsl(from var(--card) h s l / 0.92);
  box-shadow:
    0 14px 24px -30px hsl(var(--shadow-color) / 0.12),
    inset 0 1px 0 hsl(from var(--card) h s l / 0.44);
}

.react-panel-idle {
  border-color: hsl(from var(--border) h s l / 0.5);
}

.react-panel-streaming {
  border-color: hsl(from var(--primary) h s l / 0.24);
}

.react-trigger {
  transition: background-color 180ms var(--ease-fluid);
}

.react-trigger:hover {
  background: hsl(from var(--accent) h s l / 0.32);
}

.react-icon-shell {
  display: inline-flex;
  height: 1.5rem;
  width: 1.5rem;
  align-items: center;
  justify-content: center;
  border-radius: 0.625rem;
  border: 1px solid hsl(from var(--border) h s l / 0.46);
  background: hsl(from var(--background) h s l / 0.8);
  box-shadow: inset 0 1px 0 hsl(from var(--card) h s l / 0.32);
}

.react-icon-shell-streaming {
  box-shadow:
    inset 0 1px 0 hsl(from var(--card) h s l / 0.32),
    0 0 0 1px hsl(from var(--primary) h s l / 0.08);
}

.react-chip {
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

.react-chip-streaming {
  border-color: hsl(from var(--primary) h s l / 0.18);
  background: hsl(from var(--primary) h s l / 0.1);
  color: hsl(from var(--primary) h s l / 0.92);
}

.react-node {
  border: 1px solid hsl(from var(--border) h s l / 0.46);
  background: hsl(from var(--background) h s l / 0.82);
  box-shadow: inset 0 1px 0 hsl(from var(--card) h s l / 0.3);
}

.react-node-active {
  border-color: hsl(from var(--primary) h s l / 0.24);
  box-shadow:
    inset 0 1px 0 hsl(from var(--card) h s l / 0.3),
    0 0 0 1px hsl(from var(--primary) h s l / 0.08);
}

.react-line {
  opacity: 0.75;
}

.react-card {
  border-radius: 1rem;
  border: 1px solid hsl(from var(--border) h s l / 0.42);
  background: hsl(from var(--background) h s l / 0.7);
  padding: 0.8rem 0.9rem;
  box-shadow: inset 0 1px 0 hsl(from var(--card) h s l / 0.24);
}

.react-card-active {
  border-color: hsl(from var(--primary) h s l / 0.2);
  background: hsl(from var(--primary) h s l / 0.08);
}

.react-meta-chip {
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

.react-meta-chip-success {
  border-color: hsl(160 56% 78% / 0.9);
  background: hsl(160 56% 92% / 0.86);
  color: hsl(160 58% 30%);
}

.react-meta-chip-failure {
  border-color: hsl(from var(--destructive) h s l / 0.2);
  background: hsl(from var(--destructive) h s l / 0.08);
  color: hsl(from var(--destructive) h s l / 0.86);
}

.react-detail-block {
  border-radius: 0.9rem;
  border: 1px solid hsl(from var(--border) h s l / 0.42);
  background: hsl(from var(--background) h s l / 0.62);
  padding: 0.7rem 0.8rem;
}

.react-detail-label {
  margin-bottom: 0.32rem;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: hsl(from var(--muted-foreground) h s l / 0.84);
}

.react-step-enter-active,
.react-step-leave-active {
  transition:
    transform 220ms var(--ease-fluid),
    opacity 180ms var(--ease-fluid);
}

.react-step-enter-from,
.react-step-leave-to {
  opacity: 0;
  transform: translateY(10px);
}

.react-step-move {
  transition: transform 220ms var(--ease-fluid);
}
</style>
