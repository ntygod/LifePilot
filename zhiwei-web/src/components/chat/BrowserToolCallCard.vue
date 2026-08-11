<script setup lang="ts">
import { computed } from 'vue'
import type { ToolCallSummary, ToolRecoveryAction } from '@/types'
import { Card } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from '@/components/ui/accordion'
import {
  AlertCircle,
  Camera,
  ChevronDown,
  Globe,
  MousePointer2,
  Play,
  RefreshCw,
  ScanLine,
  Type,
} from 'lucide-vue-next'
import {
  buildToolRecoveryActions,
  buildToolRecoveryContextSummary,
  buildToolRecoveryPlan,
  mergeToolRecoveryArtifactRefs,
  resolveToolFailureCategory,
  resolveToolRecoveryHint,
} from '@/utils/toolExecution'

const props = withDefaults(defineProps<{
  tool: ToolCallSummary
  showRecoveryActions?: boolean
}>(), {
  showRecoveryActions: true,
})

const emit = defineEmits<{
  (e: 'recover', action: ToolRecoveryAction): void
}>()

/** 工具输出可能是 JSON 字符串或对象，统一解析为对象便于取字段 */
const parsedOutput = computed<Record<string, unknown>>(() => {
  const raw = props.tool.output as unknown
  if (!raw) return {}
  if (typeof raw === 'string') {
    const trimmed = raw.trim()
    if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return {}
    try {
      const parsed = JSON.parse(trimmed)
      return typeof parsed === 'object' && parsed !== null
        ? (parsed as Record<string, unknown>)
        : {}
    } catch {
      return {}
    }
  }
  if (typeof raw === 'object') return raw as Record<string, unknown>
  return {}
})

/** 根据 action 选择图标 */
const actionIcon = computed(() => {
  switch (props.tool.action) {
    case 'navigate':
      return Globe
    case 'screenshot':
      return Camera
    case 'snapshot':
      return ScanLine
    case 'click':
    case 'hover':
      return MousePointer2
    case 'input':
      return Type
    default:
      return Globe
  }
})

const url = computed<string>(() => (parsedOutput.value.url as string) ?? '')
const title = computed<string>(() => (parsedOutput.value.title as string) ?? '')

/** 兼容两种截图字段：裸 base64(`screenshot`) 或完整 data-uri(`screenshotDataUri`) */
const screenshotSrc = computed<string>(() => {
  const dataUri = parsedOutput.value.screenshotDataUri as string | undefined
  if (dataUri && typeof dataUri === 'string' && dataUri.length > 0) {
    return dataUri
  }
  const bare = parsedOutput.value.screenshot as string | undefined
  if (bare && typeof bare === 'string' && bare.length > 0) {
    return bare.startsWith('data:') ? bare : `data:image/png;base64,${bare}`
  }
  return ''
})

interface IndexedElement {
  index: number
  tag: string
  text?: string
  ariaLabel?: string
  name?: string
}

const elements = computed<IndexedElement[]>(() => {
  const raw = parsedOutput.value.elements
  return Array.isArray(raw) ? (raw as IndexedElement[]) : []
})

const showError = computed(() => props.tool.success === false)
const failureText = computed(() => [
  props.tool.outputSummary,
  props.tool.outputDetail,
].filter(Boolean).join(' '))
const stateLabel = computed(() => (props.tool.success === false ? '失败' : '已完成'))
const latencyLabel = computed(() => (
  props.tool.latencyMs > 0 ? `${props.tool.latencyMs}ms` : '即时'
))

/** 头部展示的操作名，action 优先，其次 toolId */
const actionLabel = computed(() => props.tool.action || props.tool.toolId)

const targetLabel = computed(() =>
  url.value
  || title.value
  || props.tool.inputSummary
  || props.tool.toolName
  || '浏览器',
)
const summaryText = computed(() => {
  if (showError.value) return props.tool.outputSummary || '浏览器操作没有完成。'
  if (screenshotSrc.value && elements.value.length > 0) {
    return `已记录页面截图，并识别 ${elements.value.length} 个可交互元素`
  }
  if (elements.value.length > 0) return `已识别 ${elements.value.length} 个可交互元素`
  if (screenshotSrc.value) return '已记录页面截图'
  return targetLabel.value
})
const hasEvidence = computed(() =>
  Boolean(screenshotSrc.value || elements.value.length > 0 || showError.value),
)
const detailAffordanceLabel = computed(() => (showError.value ? '处理' : '证据'))
const recoveryActions = computed(() => (
  showError.value
    ? (props.tool.recoveryActions?.length ? props.tool.recoveryActions : buildToolRecoveryActions(props.tool.toolId, props.tool))
    : []
))
const recoveryHintText = computed(() => (
  showError.value ? (props.tool.recoveryHint ?? resolveToolRecoveryHint(props.tool.toolId, failureText.value)) : ''
))
const recoveryPlan = computed(() => (showError.value ? buildToolRecoveryPlan(props.tool).slice(0, 3) : []))
const recoveryContext = computed(() => (
  showError.value ? buildToolRecoveryContextSummary(props.tool) : []
))
const recoveryActionIcon = (action: ToolRecoveryAction) => (
  action.mode === 'restart' ? RefreshCw : Play
)

function recoveryActionAccessibleLabel(action: ToolRecoveryAction) {
  const context = [
    action.description,
    props.tool.toolName || props.tool.toolId,
    url.value || title.value || props.tool.inputSummary,
    props.tool.outputSummary,
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
    category: action.category ?? props.tool.failureCategory ?? resolveToolFailureCategory(props.tool.toolId, failureText.value),
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
    nextActions: action.nextActions ?? recoveryPlan.value,
  }
}
</script>

<template>
  <Card
    :class="[
      'browser-tool-card gap-0 py-0 text-xs shadow-none',
      showError ? 'browser-tool-card-failure' : 'browser-tool-card-success',
    ]"
  >
    <Accordion
      v-if="hasEvidence"
      type="single"
      collapsible
      class="w-full"
    >
      <AccordionItem value="evidence" class="border-b-0">
        <AccordionTrigger class="browser-tool-trigger px-3 py-2.5 text-left hover:no-underline">
          <div class="flex min-w-0 items-center gap-3">
            <span
              class="browser-tool-icon inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-2xl"
              :class="showError ? 'browser-tool-icon-failure' : 'browser-tool-icon-success'"
            >
              <component :is="actionIcon" :size="14" class="shrink-0" />
            </span>
            <div class="min-w-0 space-y-1">
              <div class="flex min-w-0 items-center gap-2.5">
                <span class="shrink-0 font-medium text-foreground/92">{{ actionLabel }}</span>
                <span class="browser-tool-chip shrink-0">浏览器</span>
                <span
                  class="browser-tool-chip shrink-0"
                  :class="showError ? 'browser-tool-chip-failure' : 'browser-tool-chip-success'"
                >
                  {{ stateLabel }}
                </span>
              </div>
              <p class="truncate text-[11px] text-muted-foreground/82" :title="url || targetLabel">
                {{ summaryText }}
              </p>
            </div>
          </div>
          <template #icon>
            <span class="browser-tool-trigger-meta ml-auto shrink-0">
              <span class="browser-tool-detail-affordance">{{ detailAffordanceLabel }}</span>
              <span class="browser-tool-latency">{{ latencyLabel }}</span>
              <ChevronDown class="browser-tool-chevron size-3.5" />
            </span>
          </template>
        </AccordionTrigger>
        <AccordionContent class="px-3 pb-3">
          <div class="space-y-2 border-t border-border/50 pt-2.5">
            <div v-if="showError" class="browser-tool-evidence browser-tool-evidence--error">
              <div class="browser-tool-evidence-label">处理建议</div>
              <div class="flex items-start gap-sm text-destructive">
                <AlertCircle :size="14" class="mt-xs shrink-0" />
                <span class="text-xs">{{ tool.outputSummary || '执行失败' }}</span>
              </div>
              <p v-if="recoveryHintText" class="mt-2 text-[11px] leading-5 text-foreground/84">
                {{ recoveryHintText }}
              </p>
              <div
                v-if="recoveryPlan.length"
                class="browser-tool-recovery-plan"
                aria-label="续接计划"
              >
                <span
                  v-for="item in recoveryPlan"
                  :key="item"
                  class="browser-tool-recovery-plan__item"
                >
                  {{ item }}
                </span>
              </div>
              <div
                v-if="recoveryContext.length"
                class="browser-tool-recovery-context"
                aria-label="恢复上下文"
              >
                <span class="browser-tool-recovery-context__label">继续时保留</span>
                <span
                  v-for="item in recoveryContext"
                  :key="item"
                  class="browser-tool-recovery-context__item"
                >
                  {{ item }}
                </span>
              </div>
            </div>

            <!-- 截图缩略图：点击放大 -->
            <div v-if="screenshotSrc" class="browser-tool-evidence">
              <div class="browser-tool-evidence-label">页面截图</div>
              <Dialog>
                <DialogTrigger as-child>
                  <img
                    :src="screenshotSrc"
                    :alt="title || url || '浏览器截图'"
                    class="browser-tool-screenshot"
                    loading="lazy"
                  />
                </DialogTrigger>
                <DialogContent class="max-w-2xl">
                  <DialogTitle>{{ title || url || '浏览器截图' }}</DialogTitle>
                  <img
                    :src="screenshotSrc"
                    :alt="title || url || '浏览器截图'"
                    class="w-full rounded-md"
                  />
                </DialogContent>
              </Dialog>
            </div>

            <!-- snapshot 特有：可交互元素列表 -->
            <div v-if="elements.length > 0" class="browser-tool-evidence">
              <div class="browser-tool-evidence-label">
                已识别 {{ elements.length }} 个可交互元素
              </div>
              <ul class="space-y-xs">
                <li
                  v-for="el in elements.slice(0, 20)"
                  :key="el.index"
                  class="browser-tool-element"
                >
                  <span class="browser-tool-element__index">#{{ el.index }}</span>
                  <span class="browser-tool-element__tag">{{ el.tag }}</span>
                  <span class="truncate">{{ el.text || el.ariaLabel || el.name || '' }}</span>
                </li>
                <li v-if="elements.length > 20" class="pl-sm text-muted-foreground">
                  … 仅展示前 20 个
                </li>
              </ul>
            </div>
          </div>
        </AccordionContent>
      </AccordionItem>
    </Accordion>

    <!-- 无证据时仅展示轻量摘要 -->
    <div
      v-else
      class="browser-tool-trigger flex items-center gap-3 px-3 py-2.5"
    >
      <span
        class="browser-tool-icon inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-2xl"
        :class="showError ? 'browser-tool-icon-failure' : 'browser-tool-icon-success'"
      >
        <component :is="actionIcon" :size="14" class="shrink-0" />
      </span>
      <div class="min-w-0 space-y-1">
        <div class="flex min-w-0 items-center gap-2.5">
          <span class="shrink-0 font-medium text-foreground/92">{{ actionLabel }}</span>
          <span class="browser-tool-chip shrink-0">浏览器</span>
          <span
            class="browser-tool-chip shrink-0"
            :class="showError ? 'browser-tool-chip-failure' : 'browser-tool-chip-success'"
          >
            {{ stateLabel }}
          </span>
        </div>
        <p class="truncate text-[11px] text-muted-foreground/82" :title="url || targetLabel">
          {{ summaryText }}
        </p>
      </div>
      <span class="browser-tool-latency ml-auto shrink-0">{{ latencyLabel }}</span>
    </div>

    <div v-if="showRecoveryActions && recoveryActions.length" class="browser-tool-card-recovery-actions">
      <button
        v-for="action in recoveryActions"
        :key="action.id"
        type="button"
        class="browser-tool-card-recovery-action"
        :class="{ 'browser-tool-card-recovery-action--primary': action.mode !== 'restart' }"
        :title="recoveryActionTitle(action)"
        :aria-label="recoveryActionAccessibleLabel(action)"
        @click.stop="emit('recover', buildRecoveryPayload(action))"
      >
        <component :is="recoveryActionIcon(action)" class="size-3" />
        <span>{{ action.label }}</span>
      </button>
    </div>
  </Card>
</template>

<style scoped>
.browser-tool-card {
  overflow: hidden;
  border-radius: 0.5rem;
  border: 1px solid hsl(from var(--border) h s l / 0.48);
  background: hsl(from var(--card) h s l / 0.88);
  box-shadow: inset 0 1px 0 hsl(from var(--card) h s l / 0.34);
  transition:
    border-color 180ms var(--ease-fluid),
    background-color 180ms var(--ease-fluid);
}

.browser-tool-card:hover {
  border-color: hsl(from var(--border) h s l / 0.62);
  background: hsl(from var(--card) h s l / 0.95);
}

.browser-tool-card-failure {
  border-color: hsl(from var(--destructive) h s l / 0.22);
  background: hsl(from var(--destructive) h s l / 0.05);
}

.browser-tool-trigger {
  transition: background-color 180ms var(--ease-fluid);
}

.browser-tool-trigger:hover {
  background: hsl(from var(--accent) h s l / 0.3);
}

.browser-tool-icon {
  border: 1px solid hsl(from var(--border) h s l / 0.44);
  background: hsl(from var(--background) h s l / 0.8);
  box-shadow: inset 0 1px 0 hsl(from var(--card) h s l / 0.28);
}

.browser-tool-icon-success {
  color: hsl(160 66% 38%);
}

.browser-tool-icon-failure {
  color: hsl(from var(--destructive) h s l / 0.92);
}

.browser-tool-chip {
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

.browser-tool-chip-success {
  border-color: hsl(160 56% 78% / 0.9);
  background: hsl(160 56% 92% / 0.86);
  color: hsl(160 58% 30%);
}

.browser-tool-chip-failure {
  border-color: hsl(from var(--destructive) h s l / 0.2);
  background: hsl(from var(--destructive) h s l / 0.08);
  color: hsl(from var(--destructive) h s l / 0.86);
}

.browser-tool-trigger-meta {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
}

.browser-tool-detail-affordance {
  color: hsl(from var(--muted-foreground) h s l / 0.78);
  font-size: 10px;
  line-height: 1;
}

.browser-tool-chevron {
  color: hsl(from var(--muted-foreground) h s l / 0.78);
  transition: transform 160ms var(--ease-fluid);
}

.browser-tool-trigger[data-state='open'] .browser-tool-chevron {
  transform: rotate(180deg);
}

.browser-tool-latency {
  border-radius: 999px;
  border: 1px solid hsl(from var(--border) h s l / 0.42);
  background: hsl(from var(--background) h s l / 0.74);
  padding: 0.22rem 0.52rem;
  color: hsl(from var(--muted-foreground) h s l / 0.88);
  font-size: 10px;
  line-height: 1;
}

.browser-tool-evidence {
  border-radius: 0.5rem;
  border: 1px solid hsl(from var(--border) h s l / 0.42);
  background: hsl(from var(--background) h s l / 0.62);
  padding: 0.75rem 0.8rem;
}

.browser-tool-evidence--error {
  border-color: hsl(from var(--destructive) h s l / 0.2);
  background: hsl(from var(--destructive) h s l / 0.06);
}

.browser-tool-evidence-label {
  margin-bottom: 0.45rem;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: hsl(from var(--muted-foreground) h s l / 0.84);
}

.browser-tool-recovery-plan,
.browser-tool-recovery-context {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem;
  align-items: center;
  margin-top: 0.55rem;
}

.browser-tool-recovery-plan__item,
.browser-tool-recovery-context__label,
.browser-tool-recovery-context__item {
  display: inline-flex;
  min-height: 1.4rem;
  align-items: center;
  border-radius: 999px;
  font-size: 11px;
  line-height: 1.2;
}

.browser-tool-recovery-plan__item {
  border: 1px solid hsl(from var(--destructive) h s l / 0.16);
  background: hsl(from var(--background) h s l / 0.62);
  padding: 0.18rem 0.5rem;
  color: hsl(from var(--muted-foreground) h s l / 0.95);
}

.browser-tool-recovery-context__label {
  padding: 0.18rem 0.2rem 0.18rem 0;
  color: hsl(from var(--muted-foreground) h s l / 0.78);
}

.browser-tool-recovery-context__item {
  border: 1px solid hsl(from var(--primary) h s l / 0.18);
  background: hsl(from var(--primary) h s l / 0.06);
  padding: 0.18rem 0.5rem;
  color: hsl(from var(--foreground) h s l / 0.84);
}

.browser-tool-screenshot {
  width: 100%;
  cursor: zoom-in;
  border-radius: 0.5rem;
  border: 1px solid hsl(from var(--border) h s l / 0.5);
}

.browser-tool-element {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 0.5rem;
  color: hsl(from var(--foreground) h s l / 0.86);
}

.browser-tool-element__index {
  flex-shrink: 0;
  border-radius: 0.25rem;
  background: hsl(from var(--muted) h s l / 0.78);
  padding: 0.08rem 0.35rem;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;
  font-size: 10px;
}

.browser-tool-element__tag {
  flex-shrink: 0;
  color: hsl(from var(--muted-foreground) h s l / 0.86);
}

.browser-tool-card-recovery-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
  border-top: 1px solid hsl(from var(--border) h s l / 0.44);
  padding: 0.58rem 0.75rem 0.7rem;
}

.browser-tool-card-recovery-action {
  display: inline-flex;
  min-height: 1.75rem;
  cursor: pointer;
  align-items: center;
  gap: 0.35rem;
  border-radius: 999px;
  border: 1px solid hsl(from var(--border) h s l / 0.52);
  background: hsl(from var(--background) h s l / 0.72);
  padding: 0.3rem 0.62rem;
  color: hsl(from var(--foreground) h s l / 0.82);
  font-size: 0.6875rem;
  font-weight: 500;
  transition:
    border-color 180ms var(--ease-fluid),
    background-color 180ms var(--ease-fluid),
    color 180ms var(--ease-fluid);
}

.browser-tool-card-recovery-action:hover {
  border-color: hsl(from var(--primary) h s l / 0.34);
  background: hsl(from var(--primary) h s l / 0.08);
  color: hsl(from var(--primary) h s l / 0.95);
}

.browser-tool-card-recovery-action:focus-visible {
  outline: 2px solid hsl(from var(--primary) h s l / 0.48);
  outline-offset: 2px;
}

.browser-tool-card-recovery-action--primary {
  border-color: hsl(from var(--primary) h s l / 0.28);
  background: hsl(from var(--primary) h s l / 0.1);
  color: hsl(from var(--primary) h s l / 0.95);
}
</style>
