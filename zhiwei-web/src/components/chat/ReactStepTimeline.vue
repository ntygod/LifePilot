<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ReactStepDto, ToolCallStep, ObservationStep } from '@/types'
import {
  Brain, Wrench, Eye, PenLine, Pause, Play,
  ChevronDown, ChevronRight, Loader2, Clock,
  CheckCircle2, XCircle
} from 'lucide-vue-next'

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
const toolCallCount = computed(() =>
  props.steps.filter(s => s.type === 'TOOL_CALL').length
)

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
  if (count === 0) return props.streaming ? '正在思考…' : '推理概要'
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
    case 'THOUGHT': {
      const text = step.content ?? ''
      if (text.length <= 40) return text || '正在思考…'
      return text.substring(0, 40) + '…'
    }
    case 'TOOL_CALL': return `调用 ${step.toolId}`
    case 'OBSERVATION': return `${step.success ? '✓' : '✗'} ${step.toolId} 返回`
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
    case 'THOUGHT': return Brain
    case 'TOOL_CALL': return Wrench
    case 'OBSERVATION': return Eye
    case 'ANSWER': return PenLine
    case 'SUSPEND': return Pause
    case 'RESUME': return Play
    default: return Brain
  }
}

// 步骤颜色映射
function getStepColor(type: string) {
  switch (type) {
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
    case 'THOUGHT': {
      // 短内容直接作为标题，长内容用固定标题 + 内联预览
      if (step.content && step.content.length <= 30) return step.content
      return '推理思考'
    }
    case 'TOOL_CALL': return `调用工具: ${step.toolId}`
    case 'OBSERVATION': return `${step.success ? '工具返回' : '工具失败'}: ${step.toolId}`
    case 'ANSWER': return '生成回答'
    case 'SUSPEND': return 'Agent 挂起'
    case 'RESUME': return 'Agent 恢复'
  }
}

// 步骤完整内容（点击展开时显示）
function getStepContent(step: ReactStepDto): string | null {
  switch (step.type) {
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
  // Thought 短内容已作为标题显示，不重复
  if (step.type === 'THOUGHT' && step.content && step.content.length <= 30) return null
  const content = getStepContent(step)
  if (!content) return null
  const firstLine = content.split('\n')[0]
  if (firstLine.length > 80) return firstLine.substring(0, 80) + '…'
  return firstLine
}

// 内容是否超出预览长度（决定是否显示展开按钮）
function hasExpandableContent(step: ReactStepDto): boolean {
  // Thought 短内容已作为标题，无需展开
  if (step.type === 'THOUGHT' && step.content && step.content.length <= 30) return false
  const content = getStepContent(step)
  if (!content) return false
  return content.length > 80 || content.includes('\n')
}

// 工具配对组的标题
function getToolPairTitle(tc: ToolCallStep, obs: ObservationStep): string {
  return `${tc.toolId} — ${obs.success ? '成功' : '失败'}`
}

// 工具配对组的内联输出预览
function getToolPairPreview(obs: ObservationStep): string | null {
  const output = obs.outputSummary
  if (!output) return null
  const firstLine = output.split('\n')[0]
  if (firstLine.length > 80) return firstLine.substring(0, 80) + '…'
  return firstLine
}
</script>

<template>
  <div
    v-if="summary || hasSteps"
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
        <div class="relative shrink-0">
          <component
            :is="hasSteps ? getStepIcon(steps[steps.length - 1].type) : Brain"
            :size="14"
            class="transition-colors duration-300"
            :class="streaming
              ? 'text-primary'
              : hasSteps ? getStepColor(steps[steps.length - 1].type) : 'text-muted-foreground'"
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
        <Loader2 v-if="streaming" :size="12" class="text-primary animate-spin" />
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
          <!-- 步骤时间线 -->
          <div v-if="hasSteps" class="space-y-0">
            <template v-for="(group, gi) in stepGroups" :key="gi">
              <!-- 工具配对组 -->
              <div v-if="group.type === 'tool-pair'" class="flex items-start gap-2.5 group/step">
                <div class="flex flex-col items-center shrink-0 w-5">
                  <div class="w-5 h-5 rounded-full flex items-center justify-center bg-background border border-border group-hover/step:border-primary/50">
                    <Wrench :size="11" class="text-amber-500" />
                  </div>
                  <div v-if="gi < stepGroups.length - 1" class="w-px flex-1 min-h-[16px] bg-border" />
                </div>
                <div class="flex-1 min-w-0 pb-2.5" :class="gi === stepGroups.length - 1 ? 'pb-0' : ''">
                  <div class="flex items-center gap-1.5 flex-wrap">
                    <button
                      type="button"
                      class="flex items-center gap-1.5 text-[11px] font-medium text-foreground/90 leading-5 hover:text-primary transition-colors"
                      @click="toggleStep((group.steps[0] as ToolCallStep).index)"
                    >
                      <component
                        :is="(group.steps[1] as ObservationStep).success ? CheckCircle2 : XCircle"
                        :size="10"
                        :class="(group.steps[1] as ObservationStep).success ? 'text-emerald-500' : 'text-destructive'"
                      />
                      {{ getToolPairTitle(group.steps[0] as ToolCallStep, group.steps[1] as ObservationStep) }}
                    </button>
                    <span
                      v-if="(group.steps[0] as ToolCallStep).latencyMs > 0"
                      class="text-[10px] text-muted-foreground/60"
                    >
                      {{ (group.steps[0] as ToolCallStep).latencyMs }}ms
                    </span>
                  </div>
                  <!-- 内联输出预览（始终可见） -->
                  <p
                    v-if="getToolPairPreview(group.steps[1] as ObservationStep)"
                    class="text-[10px] text-muted-foreground/80 leading-relaxed mt-0.5 truncate"
                  >
                    {{ getToolPairPreview(group.steps[1] as ObservationStep) }}
                  </p>
                  <!-- 展开：完整输入 + 输出 -->
                  <div
                    v-if="expandedSteps.has((group.steps[0] as ToolCallStep).index)"
                    class="mt-1 space-y-1 text-[10px] text-muted-foreground/80 leading-relaxed"
                  >
                    <p v-if="(group.steps[0] as ToolCallStep).inputSummary">
                      <span class="text-muted-foreground font-medium">输入：</span>
                      {{ (group.steps[0] as ToolCallStep).inputSummary }}
                    </p>
                    <p v-if="(group.steps[1] as ObservationStep).outputSummary">
                      <span class="text-muted-foreground font-medium">输出：</span>
                      {{ (group.steps[1] as ObservationStep).outputSummary }}
                    </p>
                  </div>
                </div>
              </div>

              <!-- 单步骤 -->
              <div v-else class="flex items-start gap-2.5 group/step">
                <div class="flex flex-col items-center shrink-0 w-5">
                  <div
                    class="w-5 h-5 rounded-full flex items-center justify-center bg-background border border-border group-hover/step:border-primary/50"
                    :class="streaming && gi === stepGroups.length - 1 ? 'animate-pulse' : ''"
                  >
                    <component
                      :is="getStepIcon(group.steps[0].type)"
                      :size="11"
                      :class="getStepColor(group.steps[0].type)"
                    />
                  </div>
                  <div v-if="gi < stepGroups.length - 1" class="w-px flex-1 min-h-[16px] bg-border" />
                </div>
                <div class="flex-1 min-w-0 pb-2.5" :class="gi === stepGroups.length - 1 ? 'pb-0' : ''">
                  <div class="flex items-center gap-1.5 flex-wrap">
                    <button
                      v-if="hasExpandableContent(group.steps[0])"
                      type="button"
                      class="text-[11px] font-medium text-foreground/90 leading-5 hover:text-primary transition-colors"
                      @click="toggleStep(group.steps[0].index)"
                    >
                      {{ getStepTitle(group.steps[0]) }}
                    </button>
                    <span v-else class="text-[11px] font-medium text-foreground/90 leading-5">
                      {{ getStepTitle(group.steps[0]) }}
                    </span>
                  </div>
                  <!-- 内联预览（始终可见） -->
                  <p
                    v-if="getStepPreview(group.steps[0]) && !expandedSteps.has(group.steps[0].index)"
                    class="text-[10px] text-muted-foreground/80 leading-relaxed mt-0.5 truncate"
                  >
                    {{ getStepPreview(group.steps[0]) }}
                  </p>
                  <!-- 展开后显示完整内容 -->
                  <p
                    v-if="expandedSteps.has(group.steps[0].index) && getStepContent(group.steps[0])"
                    class="text-[10px] text-muted-foreground/80 leading-relaxed mt-0.5 whitespace-pre-wrap"
                  >
                    {{ getStepContent(group.steps[0]) }}
                  </p>
                </div>
              </div>
            </template>
          </div>

          <!-- 无步骤时回退到纯文本摘要 -->
          <p v-else-if="summary" class="text-[11px] text-muted-foreground leading-relaxed">
            {{ summary }}
          </p>
        </div>
      </div>
    </Transition>
  </div>
</template>
