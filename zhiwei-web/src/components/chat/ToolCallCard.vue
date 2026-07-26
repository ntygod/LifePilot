<script setup lang="ts">
import { computed } from 'vue'
import type { ToolCallSummary, ToolRecoveryAction } from '@/types'
import { CheckCircle2, ChevronDown, LoaderCircle, Play, RefreshCw, XCircle } from 'lucide-vue-next'
import { Card } from '@/components/ui/card'
import {
  buildToolRecoveryActions,
  buildToolRecoveryContextSummary,
  buildToolRecoveryPlan,
  formatToolFailureCategory,
  isSkillTool,
  mergeToolRecoveryArtifactRefs,
  resolveToolFailureCategory,
  resolveToolRecoveryHint,
} from '@/utils/toolExecution'
import {
  Accordion,
  AccordionItem,
  AccordionTrigger,
  AccordionContent,
} from '@/components/ui/accordion'
import BrowserToolCallCard from './BrowserToolCallCard.vue'

const props = withDefaults(defineProps<{
  tool: ToolCallSummary
  showRecoveryActions?: boolean
}>(), {
  showRecoveryActions: true,
})

const emit = defineEmits<{
  (e: 'recover', action: ToolRecoveryAction): void
}>()

/** 浏览器工具走特化卡片，展示 URL / 截图 / 可交互元素列表 */
const isBrowserTool = computed(() =>
  props.tool.toolId === 'browser' || props.tool.toolId.startsWith('browser.')
)

const isRunning = computed(() => props.tool.status === 'RUNNING' || props.tool.hasMoreSteps === true)
const isFailure = computed(() => props.tool.status === 'FAILED' || props.tool.success === false)
const failureText = computed(() => [
  props.tool.outputSummary,
  props.tool.outputDetail,
].filter(Boolean).join(' '))
const recoveryActions = computed(() => (
  isFailure.value
    ? (props.tool.recoveryActions?.length ? props.tool.recoveryActions : buildToolRecoveryActions(props.tool.toolId, props.tool))
    : []
))
const recoveryHintText = computed(() => (
  isFailure.value ? (props.tool.recoveryHint ?? resolveToolRecoveryHint(props.tool.toolId, failureText.value)) : ''
))
const hasDetails = computed(() => !!(
  props.tool.inputSummary
  || props.tool.outputSummary
  || recoveryHintText.value
  || recoveryActions.value.length > 0
))
const isSkill = computed(() =>
  props.tool.executionKind === 'SKILL'
  || isSkillTool(props.tool.toolId),
)
const stateLabel = computed(() => {
  if (isRunning.value) return '进行中'
  if (isFailure.value) return '失败'
  return '已完成'
})
const detailAffordanceLabel = computed(() => (isFailure.value ? '处理' : '详情'))
const stateIcon = computed(() => {
  if (isRunning.value) return LoaderCircle
  return isFailure.value ? XCircle : CheckCircle2
})
const displayName = computed(() => props.tool.toolName || props.tool.toolId)
const inferredFailureCategory = computed(() =>
  props.tool.failureCategory ?? resolveToolFailureCategory(props.tool.toolId, failureText.value),
)
const kindLabel = computed(() => {
  if (isSkill.value) return '技能'
  switch (inferredFailureCategory.value) {
    case 'CAPABILITY':
      return '能力'
    case 'KNOWLEDGE':
      return '资料'
    case 'WORKFLOW':
      return '流程'
    case 'INTEGRATION':
      return '连接器'
    case 'AGENT':
      return '智能体'
    case 'MODEL':
      return '模型'
    case 'REPOSITORY':
      return '仓库'
    case 'MEMORY':
      return '记忆'
    default:
      return '工具'
  }
})
const subjectChip = computed(() => {
  const names = props.tool.subjectNames?.filter(Boolean) ?? []
  if (!names.length) return ''
  const label = props.tool.subjectLabel || (isSkill.value ? '技能' : '对象')
  const visibleNames = names.slice(0, 2).join('、')
  return names.length > 2 ? `${label} ${visibleNames} 等 ${names.length} 个` : `${label} ${visibleNames}`
})
const latencyLabel = computed(() => (
  props.tool.latencyMs > 0 ? `${props.tool.latencyMs}ms` : '即时'
))
const failureCategoryLabel = computed(() => formatToolFailureCategory(inferredFailureCategory.value))
const recoveryPlan = computed(() => (isFailure.value ? buildToolRecoveryPlan(props.tool) : []))
const recoveryContext = computed(() => (
  isFailure.value ? buildToolRecoveryContextSummary(props.tool) : []
))
const showRecoveryBlock = computed(() =>
  isFailure.value && !!(
    recoveryHintText.value
    || recoveryActions.value.length > 0
    || failureCategoryLabel.value
    || recoveryPlan.value.length > 0
    || recoveryContext.value.length > 0
  ),
)
const summaryText = computed(() => {
  if (props.tool.outputSummary) return props.tool.outputSummary
  if (props.tool.inputSummary) return props.tool.inputSummary
  if (isFailure.value) return recoveryHintText.value || '执行没有完成，可以继续处理。'
  if (isRunning.value) return '正在执行，完成后会更新结果。'
  return '执行完成。'
})
const actionIcon = (action: ToolRecoveryAction) => (
  action.mode === 'restart' ? RefreshCw : Play
)

function recoveryActionAccessibleLabel(action: ToolRecoveryAction) {
  const context = [
    action.description,
    displayName.value,
    subjectChip.value,
    props.tool.outputSummary || props.tool.inputSummary || recoveryHintText.value,
  ].filter(Boolean).join('，')
  return context ? `${action.label}：${context}` : action.label
}

function recoveryActionTitle(action: ToolRecoveryAction) {
  return action.description ? `${action.label}：${action.description}` : action.label
}

function buildRecoveryPayload(action: ToolRecoveryAction): ToolRecoveryAction {
  const artifactRefs = mergeToolRecoveryArtifactRefs(action.artifactRefs, props.tool.artifactRefs)
  return {
    ...action,
    toolId: action.toolId ?? props.tool.toolId,
    callId: action.callId ?? props.tool.callId,
    toolName: action.toolName ?? props.tool.toolName,
    executionKind: action.executionKind ?? props.tool.executionKind,
    action: action.action ?? props.tool.action,
    category: action.category ?? props.tool.failureCategory ?? inferredFailureCategory.value,
    interrupted: action.interrupted ?? props.tool.interrupted,
    subjectLabel: action.subjectLabel ?? props.tool.subjectLabel,
    subjectNames: action.subjectNames ?? props.tool.subjectNames,
    inputSummary: action.inputSummary ?? props.tool.inputSummary,
    inputDetail: action.inputDetail ?? props.tool.inputDetail,
    outputSummary: action.outputSummary ?? props.tool.outputSummary,
    outputDetail: action.outputDetail ?? props.tool.outputDetail,
    workingDirectory: action.workingDirectory ?? props.tool.workingDirectory,
    generatedFilePath: action.generatedFilePath ?? props.tool.generatedFilePath,
    ...(artifactRefs ? { artifactRefs } : {}),
    missingCapabilities: action.missingCapabilities ?? props.tool.missingCapabilities,
    recoveryHint: action.recoveryHint ?? recoveryHintText.value,
    nextActions: action.nextActions ?? buildToolRecoveryPlan(props.tool).slice(0, 3),
  }
}
</script>

<template>
  <BrowserToolCallCard
    v-if="isBrowserTool"
    :tool="tool"
    :show-recovery-actions="showRecoveryActions"
    @recover="emit('recover', $event)"
  />
  <Card
    v-else
    :class="[
      'tool-call-card gap-0 py-0 text-xs shadow-none',
      isFailure
        ? 'tool-call-card-failure'
        : (isRunning ? 'tool-call-card-running' : 'tool-call-card-success'),
    ]"
  >
    <Accordion
      v-if="hasDetails"
      type="single"
      collapsible
      class="w-full"
    >
      <AccordionItem value="details" class="border-b-0">
        <AccordionTrigger class="tool-call-trigger px-3 py-2.5 text-left hover:no-underline">
          <div class="flex min-w-0 items-center gap-3">
            <span
              class="tool-call-icon inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-2xl"
              :class="isFailure
                ? 'tool-call-icon-failure'
                : (isRunning ? 'tool-call-icon-running' : 'tool-call-icon-success')"
            >
              <component
                :is="stateIcon"
                :size="14"
                class="shrink-0"
                :class="{ 'animate-spin': isRunning }"
              />
            </span>
            <div class="min-w-0 space-y-1">
              <div class="flex min-w-0 items-center gap-2.5">
                <span class="truncate font-medium text-foreground/92">{{ displayName }}</span>
                <span class="tool-call-chip shrink-0">{{ kindLabel }}</span>
                <span v-if="tool.action" class="tool-call-chip truncate">{{ tool.action }}</span>
                <span v-if="subjectChip" class="tool-call-chip truncate">{{ subjectChip }}</span>
                <span
                  class="tool-call-chip shrink-0"
                  :class="isFailure
                    ? 'tool-call-chip-failure'
                    : (isRunning ? 'tool-call-chip-running' : 'tool-call-chip-success')"
                >
                  {{ stateLabel }}
                </span>
              </div>
              <p class="truncate text-[11px] text-muted-foreground/82">{{ summaryText }}</p>
            </div>
          </div>
          <template #icon>
            <span class="tool-call-trigger-meta ml-auto shrink-0">
              <span class="tool-call-detail-affordance">{{ detailAffordanceLabel }}</span>
              <span class="tool-call-latency">{{ latencyLabel }}</span>
              <ChevronDown class="tool-call-chevron size-3.5" />
            </span>
          </template>
        </AccordionTrigger>
        <AccordionContent class="px-3 pb-3">
          <div class="space-y-2 border-t border-border/50 pt-2.5">
            <div v-if="tool.inputSummary" class="tool-call-detail-block">
              <div class="tool-call-detail-label">输入</div>
              <p class="text-[11px] leading-5 text-foreground/84">{{ tool.inputSummary }}</p>
            </div>
            <div v-if="tool.outputSummary" class="tool-call-detail-block">
              <div class="tool-call-detail-label">输出</div>
              <p class="text-[11px] leading-5 text-foreground/84">{{ tool.outputSummary }}</p>
            </div>
            <div v-if="showRecoveryBlock" class="tool-call-detail-block tool-call-detail-block--recovery">
              <div class="tool-call-detail-label">处理建议</div>
              <p v-if="recoveryHintText" class="text-[11px] leading-5 text-foreground/84">{{ recoveryHintText }}</p>
              <p v-if="failureCategoryLabel" class="mt-1 text-[11px] leading-5 text-muted-foreground">
                卡点：{{ failureCategoryLabel }}
              </p>
              <div
                v-if="recoveryPlan.length"
                class="tool-call-recovery-plan"
                aria-label="续接计划"
              >
                <span
                  v-for="item in recoveryPlan"
                  :key="item"
                  class="tool-call-recovery-plan__item"
                >
                  {{ item }}
                </span>
              </div>
              <div
                v-if="recoveryContext.length"
                class="tool-call-recovery-context"
                aria-label="恢复上下文"
              >
                <span class="tool-call-recovery-context__label">继续时保留</span>
                <span
                  v-for="item in recoveryContext"
                  :key="item"
                  class="tool-call-recovery-context__item"
                >
                  {{ item }}
                </span>
              </div>
            </div>
          </div>
        </AccordionContent>
      </AccordionItem>
    </Accordion>

    <!-- 无详情时仅展示头部信息 -->
    <div
      v-else
      class="tool-call-trigger flex items-center gap-3 px-3 py-2.5"
    >
      <span
        class="tool-call-icon inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-2xl"
        :class="isFailure
          ? 'tool-call-icon-failure'
          : (isRunning ? 'tool-call-icon-running' : 'tool-call-icon-success')"
      >
        <component
          :is="stateIcon"
          :size="14"
          class="shrink-0"
          :class="{ 'animate-spin': isRunning }"
        />
      </span>
      <div class="min-w-0 space-y-1">
        <div class="flex min-w-0 items-center gap-2.5">
          <span class="truncate font-medium text-foreground/92">{{ displayName }}</span>
          <span class="tool-call-chip shrink-0">{{ kindLabel }}</span>
          <span v-if="tool.action" class="tool-call-chip truncate">{{ tool.action }}</span>
          <span v-if="subjectChip" class="tool-call-chip truncate">{{ subjectChip }}</span>
          <span
            class="tool-call-chip shrink-0"
            :class="isFailure
              ? 'tool-call-chip-failure'
              : (isRunning ? 'tool-call-chip-running' : 'tool-call-chip-success')"
          >
            {{ stateLabel }}
          </span>
        </div>
        <p class="text-[11px] text-muted-foreground/78">{{ summaryText }}</p>
      </div>
      <span class="tool-call-latency ml-auto shrink-0 text-[10px]">{{ latencyLabel }}</span>
    </div>

    <div v-if="showRecoveryActions && recoveryActions.length" class="tool-call-recovery-actions">
      <button
        v-for="action in recoveryActions"
        :key="action.id"
        type="button"
        class="tool-call-recovery-action"
        :class="{ 'tool-call-recovery-action--primary': action.mode !== 'restart' }"
        :title="recoveryActionTitle(action)"
        :aria-label="recoveryActionAccessibleLabel(action)"
        @click.stop="emit('recover', buildRecoveryPayload(action))"
      >
        <component :is="actionIcon(action)" class="size-3" />
        <span>{{ action.label }}</span>
      </button>
    </div>
  </Card>
</template>

<style scoped>
.tool-call-card {
  position: relative;
  overflow: hidden;
  border-radius: 0.5rem;
  border: 1px solid hsl(from var(--border) h s l / 0.48);
  background: hsl(from var(--card) h s l / 0.88);
  box-shadow: inset 0 1px 0 hsl(from var(--card) h s l / 0.34);
  transition:
    border-color 180ms var(--ease-fluid),
    background-color 180ms var(--ease-fluid);
}

.tool-call-card:hover {
  border-color: hsl(from var(--border) h s l / 0.62);
  background: hsl(from var(--card) h s l / 0.95);
}

.tool-call-card-success {
  border-color: hsl(from var(--border) h s l / 0.48);
}

.tool-call-card-running {
  border-color: hsl(from var(--primary) h s l / 0.2);
  background: hsl(from var(--primary) h s l / 0.04);
}

.tool-call-card-failure {
  border-color: hsl(from var(--destructive) h s l / 0.22);
  background: hsl(from var(--destructive) h s l / 0.05);
}

.tool-call-trigger {
  transition: background-color 180ms var(--ease-fluid);
}

.tool-call-trigger:hover {
  background: hsl(from var(--accent) h s l / 0.3);
}

.tool-call-trigger-meta {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
}

.tool-call-detail-affordance {
  color: hsl(from var(--muted-foreground) h s l / 0.78);
  font-size: 10px;
  line-height: 1;
}

.tool-call-chevron {
  color: hsl(from var(--muted-foreground) h s l / 0.78);
  transition: transform 160ms var(--ease-fluid);
}

.tool-call-trigger[data-state='open'] .tool-call-chevron {
  transform: rotate(180deg);
}

.tool-call-icon {
  border: 1px solid hsl(from var(--border) h s l / 0.44);
  background: hsl(from var(--background) h s l / 0.8);
  box-shadow: inset 0 1px 0 hsl(from var(--card) h s l / 0.28);
}

.tool-call-icon-success {
  color: hsl(160 66% 38%);
}

.tool-call-icon-running {
  color: hsl(from var(--primary) h s l / 0.88);
}

.tool-call-icon-failure {
  color: hsl(from var(--destructive) h s l / 0.92);
}

.tool-call-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  border: 1px solid hsl(from var(--border) h s l / 0.44);
  background: hsl(from var(--background) h s l / 0.72);
  padding: 0.18rem 0.5rem;
  font-size: 10px;
  line-height: 1.1;
  color: hsl(from var(--muted-foreground) h s l / 0.92);
}

.tool-call-chip-success {
  border-color: hsl(160 56% 78% / 0.9);
  background: hsl(160 56% 92% / 0.86);
  color: hsl(160 58% 30%);
}

.tool-call-chip-running {
  border-color: hsl(from var(--primary) h s l / 0.18);
  background: hsl(from var(--primary) h s l / 0.08);
  color: hsl(from var(--primary) h s l / 0.82);
}

.tool-call-chip-failure {
  border-color: hsl(from var(--destructive) h s l / 0.2);
  background: hsl(from var(--destructive) h s l / 0.08);
  color: hsl(from var(--destructive) h s l / 0.86);
}

.tool-call-latency {
  border-radius: 999px;
  border: 1px solid hsl(from var(--border) h s l / 0.42);
  background: hsl(from var(--background) h s l / 0.74);
  padding: 0.22rem 0.52rem;
  color: hsl(from var(--muted-foreground) h s l / 0.88);
}

.tool-call-detail-block {
  border-radius: 0.5rem;
  border: 1px solid hsl(from var(--border) h s l / 0.42);
  background: hsl(from var(--background) h s l / 0.62);
  padding: 0.75rem 0.8rem;
}

.tool-call-detail-block--recovery {
  border-color: hsl(38 92% 50% / 0.24);
  background: hsl(38 92% 50% / 0.08);
}

.tool-call-detail-label {
  margin-bottom: 0.32rem;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: hsl(from var(--muted-foreground) h s l / 0.84);
}

.tool-call-recovery-plan {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem;
  margin-top: 0.5rem;
}

.tool-call-recovery-plan__item {
  display: inline-flex;
  min-height: 1.4rem;
  align-items: center;
  border-radius: 999px;
  border: 1px solid hsl(38 92% 50% / 0.18);
  background: hsl(from var(--background) h s l / 0.62);
  padding: 0.18rem 0.5rem;
  color: hsl(from var(--muted-foreground) h s l / 0.95);
  font-size: 11px;
  line-height: 1.2;
}

.tool-call-recovery-context {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem;
  align-items: center;
  margin-top: 0.55rem;
}

.tool-call-recovery-context__label,
.tool-call-recovery-context__item {
  display: inline-flex;
  min-height: 1.4rem;
  align-items: center;
  border-radius: 999px;
  font-size: 11px;
  line-height: 1.2;
}

.tool-call-recovery-context__label {
  padding: 0.18rem 0.2rem 0.18rem 0;
  color: hsl(from var(--muted-foreground) h s l / 0.78);
}

.tool-call-recovery-context__item {
  border: 1px solid hsl(from var(--primary) h s l / 0.18);
  background: hsl(from var(--primary) h s l / 0.06);
  padding: 0.18rem 0.5rem;
  color: hsl(from var(--foreground) h s l / 0.84);
}

.tool-call-recovery-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.45rem;
  border-top: 1px solid hsl(from var(--border) h s l / 0.44);
  padding: 0.58rem 0.75rem 0.7rem;
}

.tool-call-recovery-action {
  display: inline-flex;
  align-items: center;
  gap: 0.32rem;
  min-height: 1.65rem;
  border-radius: 999px;
  border: 1px solid hsl(from var(--border) h s l / 0.5);
  background: hsl(from var(--background) h s l / 0.78);
  padding: 0.28rem 0.58rem;
  font-size: 11px;
  line-height: 1.1;
  color: hsl(from var(--foreground) h s l / 0.84);
  transition:
    background-color 160ms var(--ease-fluid),
    border-color 160ms var(--ease-fluid),
    color 160ms var(--ease-fluid);
}

.tool-call-recovery-action:hover {
  border-color: hsl(from var(--primary) h s l / 0.32);
  background: hsl(from var(--primary) h s l / 0.08);
  color: hsl(from var(--primary) h s l / 0.92);
}

.tool-call-recovery-action:focus-visible {
  outline: 2px solid hsl(from var(--ring) h s l / 0.65);
  outline-offset: 2px;
}

.tool-call-recovery-action--primary {
  border-color: hsl(from var(--primary) h s l / 0.24);
  background: hsl(from var(--primary) h s l / 0.1);
  color: hsl(from var(--primary) h s l / 0.9);
}
</style>
