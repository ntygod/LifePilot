<script setup lang="ts">
import { computed, nextTick, ref, watch, type Component } from 'vue'
import { RouterLink } from 'vue-router'
import {
  AlertTriangle,
  ArrowUpRight,
  BookOpenCheck,
  Brain,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Copy,
  EyeOff,
  FileCode2,
  FileText,
  FileType,
  Globe2,
  ListChecks,
  LoaderCircle,
  Mic,
  PauseCircle,
  Pencil,
  Play,
  Presentation,
  RefreshCw,
  Sheet,
  Sparkles,
  Terminal,
  Wrench,
} from 'lucide-vue-next'
import type {
  A2uiComponent,
  KnowledgeSettlement,
  Message,
  MissingCapability,
  PermissionApprovalLog,
  PermissionApprovalRequest,
  ReasoningEvent,
  ReactStepDto,
  SourceSummary,
  TaskRecoveryCheckpoint,
  ToolCallSummary,
  ToolFailureCategory,
  ToolRecoveryAction,
} from '@/types'
import A2uiRenderer from '@/components/a2ui/A2uiRenderer.vue'
import { buildPermissionApprovalLog } from '@/utils/permissionApproval'
import { copyToClipboard } from '@/utils/clipboard'
import {
  buildMemoryChangeOverview,
  buildMemorySourceExplanation,
  isDeleteMemoryChange,
  memorySourceCollectionNoun,
} from '@/utils/memorySource'
import {
  canUseManualRestart,
  canUseManualResume,
  hasRecoverableTaskState,
  resolveManualResumeStatusLabel,
} from '@/utils/taskRecovery'
import { isInternalCapabilityId } from '@/utils/liveCapabilities'
import { normalizeTurnStatusText } from '@/utils/turnPhase'
import {
  buildToolRecoveryActions,
  buildToolRecoveryContextSummary,
  buildToolRecoveryPlan,
  formatToolFailureCategory,
  isSkillTool,
  resolveToolAction,
  resolveToolExecutionKind,
  resolveToolFailureCategory,
  resolveToolRecoveryHint,
  resolveResumeActionLabel,
} from '@/utils/toolExecution'
import KbSourceTag from './KbSourceTag.vue'
import MemorySourceTag from './MemorySourceTag.vue'
import MessageActions from './MessageActions.vue'
import MessageError from './MessageError.vue'
import MessageFeedback from './MessageFeedback.vue'
import ThinkingIndicator from './ThinkingIndicator.vue'
import StreamingText from './StreamingText.vue'
import PermissionApprovalBubble from './PermissionApprovalBubble.vue'
import ArtifactCard from './ArtifactCard.vue'
import ImageLightbox from './ImageLightbox.vue'

const props = defineProps<{
  message: Message
  projectId?: string | null
  artifactKnowledgeBaseId?: string | null
  artifactKnowledgeBaseName?: string | null
  persistArtifactKnowledgeSettlement?: (message: Message, payload: ArtifactKnowledgeSavedPayload) => Promise<void> | void
  savingToKnowledge?: boolean
  savedKnowledgeBaseId?: string | null
  savedKnowledgeBaseName?: string | null
  saveKnowledgeError?: string | null
  streaming?: boolean
  streamingContent?: string
  streamingReasoningEvents?: ReasoningEvent[]
  streamingReactSteps?: ReactStepDto[]
  isLastAssistant?: boolean
  streamingA2uiComponents?: A2uiComponent[]
  streamingPermissionApprovals?: Record<string, PermissionApprovalRequest>
  streamingPermissionApprovalResolutions?: Record<string, 'approved' | 'rejected' | 'expired'>
  recoveryReturnSessionId?: string | null
}>()

const emit = defineEmits<{
  (e: 'retry', message: Message): void
  (e: 'like', message: Message): void
  (e: 'dislike', message: Message, feedback?: string): void
  (e: 'fork', message: Message): void
  (e: 'regenerate', message: Message): void
  (e: 'resume', message: Message, action?: ToolRecoveryAction): void
  (e: 'restart', message: Message, action?: ToolRecoveryAction): void
  (e: 'copy', content: string, success: boolean): void
  (e: 'remember', message: Message): void
  (e: 'save-knowledge', message: Message): void
  (e: 'save-artifact-knowledge', message: Message, payload: ArtifactKnowledgeSavedPayload): void
  (e: 'follow-up', prompt: string): void
  (e: 'edit', message: Message, newContent: string): void
  (e: 'show-trace', messageId: string): void
  (e: 'inspect-memory', source: SourceSummary): void
  (e: 'permission-approval-resolve', requestId: string, resolution: 'approved' | 'rejected' | 'expired', subjectType?: string): void
}>()

const showImagePreview = ref(false)
const userCopied = ref(false)
const recoveryCopied = ref(false)
const imageOutputPanelRef = ref<HTMLElement | null>(null)
const interactiveOutputPanelRef = ref<HTMLElement | null>(null)
const fileOutputPanelRef = ref<HTMLElement | null>(null)
const memoryChangePanelRef = ref<HTMLElement | null>(null)
const isEditing = ref(false)
const editContent = ref('')
const editTextareaRef = ref<HTMLTextAreaElement | null>(null)
const memorySettleTitle = '放入输入框，发送后后台整理为记忆'
const canRememberMessage = computed(() => props.message.content.trim().length > 0)
const saveKnowledgeErrorMessage = computed(() => props.saveKnowledgeError?.trim() || '')

interface ArtifactKnowledgeSavedPayload {
  artifactId: string
  fileName: string
  knowledgeBaseId: string
  knowledgeBaseName: string
}

const artifactKnowledgeSavedTargets = ref<Record<string, ArtifactKnowledgeSavedPayload>>({})

watch(
  () => [
    props.message.id,
    props.artifactKnowledgeBaseId ?? '',
    (props.message.artifactRefs ?? []).map(ref => ref.artifactId).join('|'),
  ].join('::'),
  () => {
    artifactKnowledgeSavedTargets.value = {}
  },
)

function startEditing() {
  editContent.value = props.message.content
  isEditing.value = true
  nextTick(() => {
    const textarea = editTextareaRef.value
    if (textarea) {
      autoResizeTextarea(textarea)
      textarea.focus()
      textarea.setSelectionRange(textarea.value.length, textarea.value.length)
    }
  })
}

function cancelEditing() {
  isEditing.value = false
  editContent.value = ''
}

function submitEdit() {
  const trimmed = editContent.value.trim()
  if (!trimmed) return
  isEditing.value = false
  emit('edit', props.message, trimmed)
  editContent.value = ''
}

function autoResizeTextarea(el: HTMLTextAreaElement) {
  el.style.height = 'auto'
  el.style.height = `${el.scrollHeight}px`
}

function onEditInput(event: Event) {
  autoResizeTextarea(event.target as HTMLTextAreaElement)
}

function onEditKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    submitEdit()
  }
  if (event.key === 'Escape') {
    cancelEditing()
  }
}

async function handleUserCopy() {
  if (await copyToClipboard(props.message.content)) {
    userCopied.value = true
    window.setTimeout(() => { userCopied.value = false }, 2000)
  }
}
const previewImageUrl = ref<string | null>(null)

const timeLabel = computed(() => {
  const date = new Date(props.message.timestamp)
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
})

const imageAttachments = computed(() =>
  props.message.attachments?.filter(attachment => attachment.isImage) ?? [],
)

const fileAttachments = computed(() =>
  props.message.attachments?.filter(attachment => !attachment.isImage && !attachment.type?.startsWith('audio/')) ?? [],
)

/**
 * 判断附件是否是 AI 可解析的文档类型。
 *
 * Phase 1B 实际可解析集合：pdf / docx / xlsx / pptx / md / txt / csv / log / tsv。
 *
 * 与后端保持同步：`PlainTextParser.EXTENSIONS` + `MarkdownParser.EXTENSIONS` +
 * `WordParser / PdfParser / ExcelParser / PowerpointParser` 的 supportedExtensions()，
 * 以及 `BrowserIngressService.DOCUMENT_MIME_PREFIXES`。新增文档 parser 时需同步更新。
 */
function isParseableDocument(att: { type?: string; filename: string }): boolean {
  const t = att.type?.toLowerCase() ?? ''
  if (t === 'application/pdf') return true
  if (t.includes('wordprocessingml')) return true  // docx
  if (t.includes('spreadsheetml')) return true     // xlsx
  if (t.includes('presentationml')) return true    // pptx
  if (t === 'text/markdown' || t === 'text/plain' || t === 'text/csv'
      || t === 'text/tab-separated-values') return true
  // 兜底按扩展名识别
  const ext = att.filename.split('.').pop()?.toLowerCase()
  return ['pdf', 'docx', 'xlsx', 'pptx', 'md', 'txt', 'csv', 'log', 'tsv'].includes(ext ?? '')
}

/** 按文件扩展名返回 lucide 图标组件 */
function documentIcon(att: { type?: string; filename: string }) {
  const ext = att.filename.split('.').pop()?.toLowerCase() ?? ''
  if (ext === 'pdf') return FileType
  if (ext === 'xlsx' || ext === 'csv') return Sheet
  if (ext === 'pptx') return Presentation
  if (ext === 'md') return FileCode2
  return FileText
}

const audioAttachments = computed(() =>
  props.message.attachments?.filter(attachment => attachment.type?.startsWith('audio/')) ?? [],
)

const kbSources = computed(() =>
  props.message.sources?.filter(source => source.type === 'knowledgeBase') ?? [],
)

const memorySources = computed(() =>
  props.message.sources?.filter(source => source.type === 'memory') ?? [],
)

const memoryChanges = computed(() =>
  props.message.memoryChanges?.filter(source => source.type === 'memory') ?? [],
)

function inspectMemory(source: SourceSummary) {
  emit('inspect-memory', source)
}

const showAllMemorySources = ref(false)
const showAllMemoryChanges = ref(false)
const showMemoryChangeExplanation = ref(false)

const visibleMemorySources = computed(() =>
  showAllMemorySources.value ? memorySources.value : memorySources.value.slice(0, 3),
)

const hiddenMemorySourceCount = computed(() =>
  Math.max(0, memorySources.value.length - visibleMemorySources.value.length),
)

const visibleMemoryChanges = computed(() =>
  showAllMemoryChanges.value ? memoryChanges.value : memoryChanges.value.slice(0, 3),
)

const hiddenMemoryChangeCount = computed(() =>
  Math.max(0, memoryChanges.value.length - visibleMemoryChanges.value.length),
)

function expandMemorySources() {
  showAllMemorySources.value = true
}

function expandMemoryChanges() {
  showAllMemoryChanges.value = true
}

const memoryChangeOverview = computed(() => buildMemoryChangeOverview(memoryChanges.value))
const memoryChangeNoun = computed(() => memorySourceCollectionNoun(memoryChanges.value))

const firstMemoryChange = computed(() => memoryChanges.value[0] ?? null)

const deleteMemoryChangeCount = computed(() =>
  memoryChanges.value.filter(isDeleteMemoryChange).length,
)

const memoryChangeSettledTitle = computed(() => {
  if (deleteMemoryChangeCount.value === 0) {
    if (memoryChangeNoun.value === '技能') return '知微掌握了技能'
    if (memoryChangeNoun.value === '上下文') return '知微更新了上下文'
    return '知微记住了'
  }
  return deleteMemoryChangeCount.value === memoryChanges.value.length
    ? (memoryChangeNoun.value === '记忆' ? '知微忘记了' : `知微忘记了${memoryChangeNoun.value}`)
    : `知微更新了${memoryChangeNoun.value}`
})

function inspectFirstMemoryChange() {
  if (!firstMemoryChange.value) return
  inspectMemory(firstMemoryChange.value)
}

function normalizedProjectId() {
  return typeof props.projectId === 'string' ? props.projectId.trim() : ''
}

const memoryManagementRoute = computed(() => {
  const projectId = normalizedProjectId()
  const entityId = firstMemoryChange.value?.id?.trim()
  return {
    name: 'memories',
    query: {
      tab: 'entities',
      ...(entityId ? { entityId } : {}),
      ...(projectId ? { projectId } : {}),
    },
  }
})

/** 图片类型 artifact —— 内嵌消息流 */
const imageArtifactRefs = computed(() =>
  props.message.artifactRefs?.filter(ref => ref.kind === 'IMAGE') ?? [],
)

/** 非图片 artifact —— 尾部附件卡片 */
const fileArtifactRefs = computed(() =>
  props.message.artifactRefs?.filter(ref => ref.kind !== 'IMAGE') ?? [],
)

const visibleA2uiComponents = computed(() => {
  if (props.streaming) {
    return props.streamingA2uiComponents ?? props.message.a2uiComponents ?? []
  }

  return props.message.a2uiComponents ?? []
})

const toolSummaries = computed<ToolCallSummary[]>(() => {
  if (props.message.toolsSummary?.length) {
    return props.message.toolsSummary.filter(isVisibleExecutionSummary)
  }
  const steps = props.streaming && props.streamingReactSteps?.length
    ? props.streamingReactSteps
    : (props.message.reactSteps ?? [])
  return buildToolSummariesFromSteps(steps, !props.streaming)
})

function buildToolSummariesFromSteps(steps: ReactStepDto[], finalizeIncomplete = false): ToolCallSummary[] {
  const summaries: ToolCallSummary[] = []
  for (const step of steps) {
    if (step.type === 'TOOL_CALL') {
      if (!isVisibleExecutionStep(step.toolId, step.toolName)) {
        continue
      }
      summaries.push({
        toolId: step.toolId,
        callId: step.callId,
        toolName: step.toolName,
        executionKind: resolveToolExecutionKind(step.toolId),
        status: 'RUNNING',
        action: resolveToolAction(step.toolId),
        subjectLabel: step.subjectLabel,
        subjectNames: step.subjectNames,
        success: true,
        latencyMs: step.latencyMs,
        inputSummary: step.inputSummary,
        inputDetail: step.inputDetail,
        hasMoreSteps: true,
      })
      continue
    }
    if (step.type === 'OBSERVATION') {
      if (!isVisibleExecutionStep(step.toolId, step.toolName)) {
        continue
      }
      const pending = findPendingToolSummary(summaries, step)
      const fallback: ToolCallSummary = {
        toolId: step.toolId,
        callId: step.callId,
        toolName: step.toolName,
        executionKind: resolveToolExecutionKind(step.toolId),
        status: 'RUNNING',
        action: resolveToolAction(step.toolId),
        success: step.success,
        latencyMs: 0,
      }
      const target: ToolCallSummary = pending ?? fallback
      target.callId = target.callId ?? step.callId
      target.success = step.success
      target.status = step.success ? 'SUCCEEDED' : 'FAILED'
      target.executionKind = target.executionKind ?? resolveToolExecutionKind(step.toolId)
      target.action = target.action ?? resolveToolAction(step.toolId)
      target.subjectLabel = step.subjectLabel ?? target.subjectLabel
      target.subjectNames = step.subjectNames ?? target.subjectNames
      target.outputSummary = step.outputSummary
      target.outputDetail = step.outputDetail
      target.workingDirectory = step.workingDirectory
      target.generatedFilePath = step.generatedFilePath
      target.output = step.output
      if (step.success === false) {
        const failureText = [step.outputSummary, step.outputDetail].filter(Boolean).join(' ')
        target.failureCategory = resolveToolFailureCategory(step.toolId, failureText)
        target.recoveryActions = buildToolRecoveryActions(step.toolId, target)
        target.recoveryHint = resolveToolRecoveryHint(step.toolId, failureText)
      }
      target.hasMoreSteps = false
      if (!pending) {
        summaries.push(target)
      }
    }
  }
  if (finalizeIncomplete) {
    summaries
      .filter(tool => tool.hasMoreSteps === true)
      .forEach(markInterruptedToolSummary)
  }
  return summaries
}

function isVisibleExecutionStep(toolId?: string | null, toolName?: string | null) {
  const id = toolId?.trim()
  if (id) {
    return !isInternalCapabilityId(id)
  }
  const name = toolName?.trim()
  return !!name && !isInternalCapabilityId(name)
}

function isVisibleExecutionSummary(tool: ToolCallSummary) {
  return isVisibleExecutionStep(tool.toolId, tool.toolName)
}

function findPendingToolSummary(summaries: ToolCallSummary[], step: Extract<ReactStepDto, { type: 'OBSERVATION' }>) {
  if (step.callId) {
    const matched = summaries.find(item =>
      item.callId === step.callId && item.hasMoreSteps,
    )
    if (matched) return matched
  }
  return summaries.find(item =>
    item.toolId === step.toolId && item.hasMoreSteps,
  )
}

function markInterruptedToolSummary(tool: ToolCallSummary) {
  tool.success = false
  tool.status = 'FAILED'
  tool.hasMoreSteps = false
  tool.interrupted = true
  tool.failureCategory = tool.failureCategory ?? resolveToolFailureCategory(tool.toolId)
  tool.recoveryActions = tool.recoveryActions?.length
    ? tool.recoveryActions
    : buildToolRecoveryActions(tool.toolId, tool)
  tool.recoveryHint = tool.recoveryHint ?? resolveInterruptedToolRecoveryHint(tool)
  tool.outputSummary = tool.outputSummary ?? '这一步已经开始，但没有返回执行结果。'
}

function resolveInterruptedToolRecoveryHint(tool: ToolCallSummary) {
  if (isSkillExecution(tool)) {
    const subjectName = tool.subjectNames?.find(Boolean)
    if (tool.toolId === 'skill.load') {
      return subjectName
        ? `技能 ${subjectName} 已经开始加载但没有返回结果，可以检查技能名称或依赖后继续。`
        : '技能加载已经开始但没有返回结果，可以检查技能名称或依赖后继续。'
    }
    return subjectName
      ? `技能 ${subjectName} 已经开始执行但没有返回结果，可以检查输入、依赖或技能步骤后继续。`
      : '技能执行已经开始但没有返回结果，可以检查输入、依赖或技能步骤后继续。'
  }
  return resolveToolRecoveryHint(tool.toolId)
}

function formatFailureCategory(category?: ToolFailureCategory) {
  return formatToolFailureCategory(category)
}

function buildToolRecoveryPayload(
  tool: ToolCallSummary,
  action: ToolRecoveryAction,
): ToolRecoveryAction {
  const artifactRefs = mergeRecoveryArtifactRefs(action.artifactRefs, tool.artifactRefs)
  const failureText = [tool.outputSummary, tool.outputDetail].filter(Boolean).join(' ')
  return {
    ...action,
    toolId: action.toolId ?? tool.toolId,
    callId: action.callId ?? tool.callId,
    toolName: action.toolName ?? tool.toolName,
    executionKind: action.executionKind ?? tool.executionKind,
    action: action.action ?? tool.action,
    interrupted: action.interrupted ?? tool.interrupted,
    category: action.category ?? tool.failureCategory ?? resolveToolFailureCategory(tool.toolId, failureText),
    subjectLabel: action.subjectLabel ?? tool.subjectLabel,
    subjectNames: action.subjectNames ?? tool.subjectNames,
    inputSummary: action.inputSummary ?? tool.inputSummary,
    inputDetail: action.inputDetail ?? tool.inputDetail,
    outputSummary: action.outputSummary ?? tool.outputSummary,
    outputDetail: action.outputDetail ?? tool.outputDetail,
    workingDirectory: action.workingDirectory ?? tool.workingDirectory,
    generatedFilePath: action.generatedFilePath ?? tool.generatedFilePath,
    ...(artifactRefs ? { artifactRefs } : {}),
    missingCapabilities: action.missingCapabilities ?? tool.missingCapabilities,
    recoveryHint: action.recoveryHint ?? tool.recoveryHint ?? resolveToolRecoveryHint(tool.toolId, failureText),
    nextActions: action.nextActions ?? buildToolRecoveryPlan(tool).slice(0, 3),
  }
}

function mergeRecoveryArtifactRefs(
  ...groups: Array<ToolRecoveryAction['artifactRefs'] | undefined | null>
): ToolRecoveryAction['artifactRefs'] | undefined {
  const seen = new Set<string>()
  const refs: NonNullable<ToolRecoveryAction['artifactRefs']> = []
  for (const group of groups) {
    for (const ref of group ?? []) {
      const artifactId = ref.artifactId?.trim()
      if (!artifactId || seen.has(artifactId)) continue
      seen.add(artifactId)
      refs.push({
        ...ref,
        artifactId,
        fileName: ref.fileName?.trim() || artifactId,
        mimeType: ref.mimeType?.trim() || 'application/octet-stream',
        kind: ref.kind === 'IMAGE' ? 'IMAGE' : 'FILE',
        size: typeof ref.size === 'number' && Number.isFinite(ref.size) ? ref.size : 0,
        downloadUrl: ref.downloadUrl?.trim() || '',
      })
      if (refs.length >= 8) return refs
    }
  }
  return refs.length ? refs : undefined
}

function handleRecoveryPromptResume() {
  emit('resume', props.message, recoveryPrimaryAction.value)
}

function handleRecoveryPromptRestart() {
  emit('restart', props.message, recoveryRestartAction.value)
}

interface ContextUsageItem {
  id: string
  label: string
  detail?: string
  icon: Component
  tone?: 'default' | 'success' | 'warning' | 'checking'
  inspectMemorySource?: SourceSummary
  knowledgeRoute?: {
    name: string
    params: Record<string, string>
  }
  knowledgeTarget?: boolean
  outputTarget?: boolean
  memoryChangeTarget?: boolean
  showTrace?: boolean
}

function buildTurnRecoveryUsageItem(): ContextUsageItem {
  const context = turnRecoveryContext.value
  const action = turnRecoveryActionKind(context)
  const subject = context?.checkpoint ? buildRecoverySnapshotSubject(context.checkpoint) : undefined
  const nextAction = context?.nextActions?.find(Boolean)
  const detail = [
    subject,
    nextAction ? `按计划：${nextAction}` : undefined,
  ].filter(Boolean).join(' · ') || undefined
  if (action === 'RESTART') {
    return {
      id: 'turn-recovery',
      label: '重新开始并修正',
      detail,
      icon: RefreshCw,
      showTrace: true,
    }
  }
  return {
    id: 'turn-recovery',
    label: '从断点继续',
    detail,
    icon: Play,
    showTrace: true,
  }
}

function turnRecoveryActionKind(context?: Message['turnRecoveryContext'] | null) {
  const mode = context?.checkpoint?.recoveryActionMode?.toLowerCase()
  if (mode === 'restart') return 'RESTART'
  if (mode === 'resume') return 'RESUME'
  return context?.action === 'RESTART' ? 'RESTART' : 'RESUME'
}

function isSkillExecution(tool: { executionKind?: string | null, toolId?: string | null }) {
  return tool.executionKind === 'SKILL'
    || isSkillTool(tool.toolId ?? undefined)
}

function isFailedExecution(tool: ToolCallSummary) {
  return tool.status === 'FAILED' || tool.success === false
}

const skillExecutionCount = computed(() =>
  toolSummaries.value.filter(isSkillExecution).length,
)

const normalToolExecutionCount = computed(() =>
  Math.max(0, toolSummaries.value.length - skillExecutionCount.value),
)

const failedExecutionCount = computed(() =>
  toolSummaries.value.filter(isFailedExecution).length,
)

const successfulExecutions = computed(() =>
  toolSummaries.value.filter(tool => !isFailedExecution(tool)),
)

function executionPartsText() {
  const stepCount = normalToolExecutionCount.value + skillExecutionCount.value
  return stepCount > 0 ? `${stepCount} 个步骤` : ''
}

function successfulToolKeyMatches(predicate: (key: string) => boolean) {
  return successfulExecutions.value.some(tool => {
    const key = buildExecutionKey(tool)
    return predicate(key)
  })
}

function successfulToolCategoryMatches(category: ToolFailureCategory) {
  return successfulExecutions.value.some(tool => executionCategory(tool) === category)
}

function executionCategory(tool: ToolCallSummary): ToolFailureCategory {
  return tool.failureCategory ?? resolveToolFailureCategory(
    tool.toolId,
    [tool.outputSummary, tool.outputDetail].filter(Boolean).join(' '),
  )
}

function buildExecutionKey(tool: ToolCallSummary) {
  return [
    tool.toolId,
    tool.toolName,
    tool.action,
    tool.inputSummary,
    tool.outputSummary,
  ]
    .filter(Boolean)
    .join(' ')
    .toLowerCase()
}

function matchesResearchExecution(key: string) {
  return key.includes('web.')
    || key.includes('search')
    || key.includes('fetch')
    || key.includes('browser')
    || key.includes('搜索')
    || key.includes('网页')
}

function matchesKnowledgeExecution(key: string) {
  return key.includes('knowledge')
    || key.includes('kb.')
    || key.includes('rag.')
    || key.includes('document.')
    || key.includes('pdf.')
    || key.includes('vector.')
    || key.includes('知识库')
}

function matchesValidationExecution(key: string) {
  return key.includes('shell')
    || key.includes('code')
    || key.includes('命令')
    || key.includes('测试')
    || key.includes('验证')
}

function matchesFileExecution(key: string) {
  return key.includes('file.')
    || key.includes('write')
    || key.includes('edit')
    || key.includes('文件')
}

function matchesMemoryExecution(key: string) {
  return key.includes('memory')
    || key.includes('记忆')
}

function matchesScheduleExecution(key: string) {
  return key.includes('schedule')
    || key.includes('cron')
    || key.includes('calendar')
    || key.includes('reminder')
    || key.includes('提醒')
    || key.includes('日程')
}

function matchesWorkflowExecution(key: string) {
  return key.includes('workflow.')
    || key.includes('工作流')
    || key.includes('流程')
}

function matchesIntegrationExecution(key: string) {
  return key.includes('mcp.')
    || key.includes('connector.')
    || key.includes('integration.')
    || key.includes('连接器')
}

function matchesAgentExecution(key: string) {
  return key.includes('agent.')
    || key.includes('智能体')
}

function matchesModelExecution(key: string) {
  return key.includes('llm.')
    || key.includes('model.')
    || key.includes('embedding.')
    || key.includes('vision.')
    || key.includes('image.')
    || key.includes('audio.')
    || key.includes('模型')
}

function matchesRepositoryExecution(key: string) {
  return key.includes('git.')
    || key.includes('仓库')
}

interface CompletedExecutionSignals {
  hasSkill: boolean
  hasKnowledge: boolean
  hasResearch: boolean
  hasValidation: boolean
  hasFileWork: boolean
  hasMemoryWork: boolean
  hasSchedule: boolean
  hasWorkflow: boolean
  hasIntegration: boolean
  hasAgent: boolean
  hasModel: boolean
  hasRepository: boolean
  actionLabels: string[]
}

function completedExecutionSignals(): CompletedExecutionSignals {
  const hasSkill = successfulExecutions.value.some(isSkillExecution)
  const hasKnowledge = successfulToolCategoryMatches('KNOWLEDGE') || successfulToolKeyMatches(matchesKnowledgeExecution)
  const hasResearch = successfulToolKeyMatches(matchesResearchExecution)
  const hasValidation = successfulToolKeyMatches(matchesValidationExecution)
  const hasFileWork = successfulToolKeyMatches(matchesFileExecution)
  const hasMemoryWork = successfulToolKeyMatches(matchesMemoryExecution)
  const hasSchedule = successfulToolKeyMatches(matchesScheduleExecution)
  const hasWorkflow = successfulToolCategoryMatches('WORKFLOW') || successfulToolKeyMatches(matchesWorkflowExecution)
  const hasIntegration = successfulToolCategoryMatches('INTEGRATION') || successfulToolKeyMatches(matchesIntegrationExecution)
  const hasAgent = successfulToolCategoryMatches('AGENT') || successfulToolKeyMatches(matchesAgentExecution)
  const hasModel = successfulToolCategoryMatches('MODEL') || successfulToolKeyMatches(matchesModelExecution)
  const hasRepository = successfulToolCategoryMatches('REPOSITORY') || successfulToolKeyMatches(matchesRepositoryExecution)
  const actionLabels: string[] = []

  if (hasKnowledge) {
    actionLabels.push('整理资料')
  }
  else if (hasResearch) {
    actionLabels.push('查资料')
  }
  if (hasValidation) actionLabels.push('执行验证')
  if (hasFileWork) actionLabels.push('处理文件')
  if (hasMemoryWork) actionLabels.push('处理记忆')
  if (hasSchedule) actionLabels.push('安排跟进')
  if (hasWorkflow) actionLabels.push('推进流程')
  if (hasIntegration) actionLabels.push('调用连接器')
  if (hasAgent) actionLabels.push('协作处理')
  if (hasModel) actionLabels.push('分析输入')
  if (hasRepository) actionLabels.push('处理仓库')

  return {
    hasSkill,
    hasKnowledge,
    hasResearch,
    hasValidation,
    hasFileWork,
    hasMemoryWork,
    hasSchedule,
    hasWorkflow,
    hasIntegration,
    hasAgent,
    hasModel,
    hasRepository,
    actionLabels,
  }
}

function completedExecutionActionText(actionLabels: string[]) {
  const visibleLabels = actionLabels.slice(0, 2)
  if (visibleLabels.length === 0) return ''
  return `${visibleLabels.join('、')}${actionLabels.length > visibleLabels.length ? '等' : ''}`
}

function completedExecutionOutcomeText() {
  if (!successfulExecutions.value.length) {
    return ''
  }
  const signals = completedExecutionSignals()
  const {
    hasSkill,
    hasKnowledge,
    hasResearch,
    hasValidation,
    hasFileWork,
    hasMemoryWork,
    hasSchedule,
    hasWorkflow,
    hasIntegration,
    hasAgent,
    hasModel,
    hasRepository,
    actionLabels,
  } = signals
  const actionText = completedExecutionActionText(actionLabels)

  if (actionLabels.length > 1 && actionText) {
    return hasSkill ? `已按相关技能${actionText}并整理结果。` : `已${actionText}并整理结果。`
  }

  if (hasKnowledge) {
    return hasSkill ? '已按相关技能整理资料并生成回答。' : '已整理资料并生成回答。'
  }
  if (hasResearch) {
    return hasSkill ? '已按相关技能查资料并整理回答。' : '已查资料并整理回答。'
  }
  if (hasValidation) {
    return '已执行验证并整理结果。'
  }
  if (hasFileWork) {
    return '已处理文件并整理结果。'
  }
  if (hasMemoryWork) {
    return '已处理记忆并整理回答。'
  }
  if (hasSchedule) {
    return '已安排跟进并整理结果。'
  }
  if (hasWorkflow) {
    return '已推进流程并整理结果。'
  }
  if (hasIntegration) {
    return '已调用连接器并整理结果。'
  }
  if (hasAgent) {
    return '已协作处理并整理结果。'
  }
  if (hasModel) {
    return '已完成分析并整理结果。'
  }
  if (hasRepository) {
    return '已处理仓库并整理结果。'
  }
  if (hasSkill) {
    return '已按相关技能处理任务。'
  }
  return '已处理本轮任务。'
}

function completedExecutionIcon(): Component {
  if (skillExecutionCount.value > 0) return Sparkles
  if (successfulToolCategoryMatches('KNOWLEDGE') || successfulToolKeyMatches(matchesKnowledgeExecution)) return BookOpenCheck
  if (successfulToolCategoryMatches('REPOSITORY') || successfulToolKeyMatches(matchesRepositoryExecution)) return Terminal
  if (successfulToolCategoryMatches('MODEL') || successfulToolKeyMatches(matchesModelExecution)) return Sparkles
  if (successfulToolCategoryMatches('AGENT') || successfulToolKeyMatches(matchesAgentExecution)) return Sparkles
  if (successfulToolCategoryMatches('WORKFLOW') || successfulToolKeyMatches(matchesWorkflowExecution)) return Wrench
  if (successfulToolCategoryMatches('INTEGRATION') || successfulToolKeyMatches(matchesIntegrationExecution)) return Wrench
  return CheckCircle2
}

function outcomePartsText() {
  const parts: string[] = []
  if (memoryChanges.value.length > 0) {
    parts.push(`沉淀 ${memoryChanges.value.length} 条${memoryChangeNoun.value}`)
  }
  return parts.join('，')
}

function degradedExecutionDetail(parts: string) {
  if (failedExecutionCount.value > 0) {
    return `${failedExecutionCount.value} 个步骤需要处理，可以从失败处继续。`
  }
  if (parts) {
    return '任务尚未完整完成，可以继续或重新开始。'
  }
  return '这轮任务没有完整完成，可以继续或重新开始。'
}

function primaryFailedExecutionSnapshot(): TaskRecoveryCheckpoint | ToolCallSummary | null {
  if (taskRecovery.value?.checkpoint) {
    return taskRecovery.value.checkpoint
  }
  return toolSummaries.value.find(isFailedExecution)
    ?? recoverableTurnRecoveryCheckpoint.value
    ?? null
}

function snapshotFailureCategory(snapshot: TaskRecoveryCheckpoint | ToolCallSummary | null): ToolFailureCategory {
  if (!snapshot) return 'UNKNOWN'
  const failureText = [
    snapshot.outputSummary,
    snapshot.outputDetail,
  ].filter(Boolean).join(' ')
  return snapshot.failureCategory
    ?? (snapshot.toolId ? resolveToolFailureCategory(snapshot.toolId, failureText) : 'UNKNOWN')
}

function compactCapabilityText(value: string, maxLength = 28) {
  const text = value.trim()
  if (text.length <= maxLength) return text
  return `${text.slice(0, Math.max(1, maxLength - 1))}…`
}

function missingCapabilityItems(snapshot: TaskRecoveryCheckpoint | ToolCallSummary | null): MissingCapability[] {
  return snapshot?.missingCapabilities
    ?.filter(item => !!item?.id?.trim())
    ?? []
}

function missingCapabilityIds(snapshot: TaskRecoveryCheckpoint | ToolCallSummary | null) {
  return Array.from(new Set(
    missingCapabilityItems(snapshot)
      .map(item => item.id.trim())
      .filter(Boolean),
  ))
}

function capabilityWarningSkillName(snapshot: TaskRecoveryCheckpoint | ToolCallSummary | null) {
  return snapshot?.subjectNames?.find(Boolean)
    ?? missingCapabilityItems(snapshot).find(item => item.skillName?.trim())?.skillName?.trim()
    ?? ''
}

function formatMissingCapabilityList(
  snapshot: TaskRecoveryCheckpoint | ToolCallSummary | null,
  maxItems = 2,
) {
  const items = missingCapabilityItems(snapshot)
  if (!items.length) return ''
  const labels = items.slice(0, maxItems)
    .map(item => compactCapabilityText(item.id, 26))
  return `${labels.join('、')}${items.length > labels.length ? ` 等 ${items.length} 个` : ''}`
}

function capabilityWarningExecution() {
  return successfulExecutions.value.find(tool =>
    missingCapabilityItems(tool).length > 0
    && (tool.status === 'SUCCEEDED' || tool.success === true),
  ) ?? null
}

function capabilityWarningTitle(snapshot: TaskRecoveryCheckpoint | ToolCallSummary | null) {
  const missing = formatMissingCapabilityList(snapshot)
  return missing ? `能力待补齐：${missing}` : '能力待补齐'
}

function capabilityWarningDetail(snapshot: TaskRecoveryCheckpoint | ToolCallSummary | null) {
  const missing = formatMissingCapabilityList(snapshot, 3)
  const skillName = snapshot?.subjectNames?.find(Boolean)
    ?? missingCapabilityItems(snapshot)[0]?.skillName
  const scope = skillName ? `技能 ${skillName} 已加载` : '技能已加载'
  if (missing) {
    return `${scope}，但 ${missing} 当前不可用；依赖它的步骤需要在能力中心修复缺失能力或调整 Skill suggestedTools。`
  }
  return `${scope}，但部分建议能力当前不可用。`
}

const capabilityWarningSummary = computed(() => capabilityWarningExecution())

const showCapabilityWarningRepairLink = computed(() =>
  !!capabilityWarningSummary.value
  && props.message.role === 'assistant'
  && !props.streaming
  && !showRecoveryPrompt.value,
)

const capabilityWarningRepairText = computed(() => {
  const warning = capabilityWarningSummary.value
  if (!warning) return ''
  const missing = formatMissingCapabilityList(warning, 3)
  const skillName = capabilityWarningSkillName(warning)
  const subject = skillName ? `技能 ${skillName}` : '这个技能'
  return missing
    ? `修复 ${missing} 后，${subject} 后续步骤就能调用完整工具。`
    : `补齐建议能力后，${subject} 后续步骤就能调用完整工具。`
})

const capabilityWarningRepairMissing = computed(() =>
  missingCapabilityIds(capabilityWarningSummary.value).join(','),
)

const capabilityWarningRepairSkill = computed(() =>
  capabilityWarningSkillName(capabilityWarningSummary.value),
)

const capabilityWarningRepairRoute = computed(() => {
  const warning = capabilityWarningSummary.value
  if (!warning) {
    return { name: 'capabilities' }
  }
  const query: Record<string, string> = {
    from: 'capability-warning',
  }
  if (capabilityWarningRepairMissing.value) {
    query.missing = capabilityWarningRepairMissing.value
  }
  if (capabilityWarningRepairSkill.value) {
    query.skill = capabilityWarningRepairSkill.value
  }
  return {
    name: 'capabilities',
    query,
  }
})

function failedExecutionTitle(snapshot: TaskRecoveryCheckpoint | ToolCallSummary | null) {
  if (!snapshot) {
    return '任务没有完整完成'
  }
  const category = snapshotFailureCategory(snapshot)
  if (category === 'CAPABILITY') {
    const missing = formatMissingCapabilityList(snapshot)
    return missing ? `能力需要修复：${missing}` : '能力需要修复'
  }
  if (isSkillExecution(snapshot)) {
    const skillName = snapshot.subjectNames?.find(Boolean)
    return skillName ? `技能 ${skillName} 卡住了` : '技能步骤卡住了'
  }
  switch (category) {
    case 'COMMAND':
      return '验证没有完成'
    case 'FILE':
      return '文件处理没有完成'
    case 'BROWSER':
      return '页面操作没有完成'
    case 'NETWORK':
      return '资料访问没有完成'
    case 'MEMORY':
      return '记忆处理没有完成'
    case 'KNOWLEDGE':
      return '资料处理没有完成'
    case 'WORKFLOW':
      return '流程没有完成'
    case 'INTEGRATION':
      return '连接器没有完成'
    case 'AGENT':
      return '协作没有完成'
    case 'MODEL':
      return '模型处理没有完成'
    case 'REPOSITORY':
      return '仓库处理没有完成'
    default:
      return '任务没有完整完成'
  }
}

function failedExecutionDetail(snapshot: TaskRecoveryCheckpoint | ToolCallSummary | null, parts: string) {
  if (taskRecovery.value?.detail) {
    return taskRecovery.value.detail
  }
  if (snapshot && snapshot === recoverableTurnRecoveryCheckpoint.value && turnRecoveryContext.value?.detail) {
    return turnRecoveryContext.value.detail
  }
  if (snapshotFailureCategory(snapshot) === 'CAPABILITY') {
    const missing = formatMissingCapabilityList(snapshot, 3)
    if (missing) {
      return `缺失 ${missing}，在能力中心修复缺失能力或修正 Skill suggestedTools 后可以从断点继续。`
    }
  }
  if (snapshot && 'recoveryHint' in snapshot && snapshot.recoveryHint) {
    return snapshot.recoveryHint
  }
  if (snapshot?.interrupted) {
    return '这一步已经开始但没有返回结果，可以从断点继续。'
  }
  switch (snapshotFailureCategory(snapshot)) {
    case 'CAPABILITY':
      return '依赖的工具或技能当前不可用，修复后可以从断点继续。'
    case 'COMMAND':
      return '命令或代码没有完成，可以修正错误后继续执行。'
    case 'FILE':
      return '文件操作没有完成，检查路径或权限后可以继续。'
    case 'BROWSER':
      return '浏览器操作没有完成，检查页面状态后可以继续。'
    case 'NETWORK':
      return '外部访问没有完成，可以重试或更换资料来源。'
    case 'MEMORY':
      return '记忆操作没有完成，调整条件后可以继续。'
    case 'KNOWLEDGE':
      return '资料处理没有完成，检查来源、索引或解析结果后可以继续。'
    case 'WORKFLOW':
      return '工作流没有完成，可以从失败节点继续推进。'
    case 'INTEGRATION':
      return '连接器调用没有完成，恢复连接或授权后可以继续。'
    case 'AGENT':
      return '智能体协作没有完成，可以合并已有结果后继续本地处理。'
    case 'MODEL':
      return '模型处理没有完成，调整配置、输入或重试策略后可以继续。'
    case 'REPOSITORY':
      return '仓库操作没有完成，处理分支、权限或冲突后可以继续。'
    default:
      return degradedExecutionDetail(parts)
  }
}

function hasStoredTraceData() {
  return !!props.message.reactSteps?.length
    || !!props.message.reasoningEvents?.length
    || toolSummaries.value.length > 0
}

type TaskExecutionTone = 'running' | 'success' | 'warning' | 'waiting'

interface TaskExecutionSummary {
  tone: TaskExecutionTone
  icon: Component
  title: string
  detail: string
}

function isRunningExecution(tool: ToolCallSummary) {
  return tool.status === 'RUNNING' || tool.hasMoreSteps === true
}

function latestRunningExecution() {
  for (let index = toolSummaries.value.length - 1; index >= 0; index -= 1) {
    const tool = toolSummaries.value[index]
    if (tool && isRunningExecution(tool)) {
      return tool
    }
  }
  return toolSummaries.value[toolSummaries.value.length - 1] ?? null
}

function runningExecutionSummary(): TaskExecutionSummary {
  const current = latestRunningExecution()
  if (!current) {
    return {
      tone: 'running',
      icon: LoaderCircle,
      title: '正在推进任务',
      detail: '知微正在推进当前任务。',
    }
  }

  const key = buildExecutionKey(current)
  const category = executionCategory(current)
  if (category === 'KNOWLEDGE' || matchesKnowledgeExecution(key)) {
    return {
      tone: 'running',
      icon: BookOpenCheck,
      title: '正在整理资料',
      detail: '正在检索、读取或解析本地资料，完成后会整理回答。',
    }
  }
  if (matchesResearchExecution(key)) {
    return {
      tone: 'running',
      icon: Globe2,
      title: '正在查资料',
      detail: '正在检索和读取资料，完成后会整理回答。',
    }
  }
  if (matchesValidationExecution(key)) {
    return {
      tone: 'running',
      icon: Terminal,
      title: '正在执行验证',
      detail: '正在运行命令或测试，完成后会整理结果。',
    }
  }
  if (matchesFileExecution(key)) {
    return {
      tone: 'running',
      icon: FileText,
      title: '正在处理文件',
      detail: '正在读取或修改文件，完成后会给出结果。',
    }
  }
  if (matchesMemoryExecution(key)) {
    return {
      tone: 'running',
      icon: Brain,
      title: '正在处理记忆',
      detail: '正在检索或整理长期记忆。',
    }
  }
  if (matchesScheduleExecution(key)) {
    return {
      tone: 'running',
      icon: Wrench,
      title: '正在安排跟进',
      detail: '正在创建或更新后续任务。',
    }
  }
  if (category === 'WORKFLOW' || matchesWorkflowExecution(key)) {
    return {
      tone: 'running',
      icon: Wrench,
      title: '正在推进流程',
      detail: '正在执行工作流步骤，完成后会合并结果。',
    }
  }
  if (category === 'INTEGRATION' || matchesIntegrationExecution(key)) {
    return {
      tone: 'running',
      icon: Wrench,
      title: '正在连接工具服务',
      detail: '正在调用本地或外部连接器，完成后会整理结果。',
    }
  }
  if (category === 'AGENT' || matchesAgentExecution(key)) {
    return {
      tone: 'running',
      icon: Sparkles,
      title: '正在协作处理',
      detail: '正在等待或合并智能体协作结果。',
    }
  }
  if (category === 'MODEL' || matchesModelExecution(key)) {
    return {
      tone: 'running',
      icon: Sparkles,
      title: '正在整理回答',
      detail: '正在分析输入并组织结果。',
    }
  }
  if (category === 'REPOSITORY' || matchesRepositoryExecution(key)) {
    return {
      tone: 'running',
      icon: Terminal,
      title: '正在处理仓库',
      detail: '正在读取或操作仓库状态，完成后会整理结果。',
    }
  }
  if (isSkillExecution(current)) {
    return {
      tone: 'running',
      icon: Sparkles,
      title: '正在准备相关技能',
      detail: '知微正在按任务需要加载或执行技能。',
    }
  }
  return {
    tone: 'running',
    icon: Wrench,
    title: '正在推进任务',
    detail: '知微正在执行必要步骤，完成后会整理结果。',
  }
}

const taskRecovery = computed(() => props.message.taskRecovery)
const turnRecoveryContext = computed(() => props.message.turnRecoveryContext)
const recoverableTurnRecoveryContext = computed(() =>
  hasRecoverableTaskState(props.message)
    ? turnRecoveryContext.value
    : null,
)
const recoverableTurnRecoveryCheckpoint = computed(() =>
  recoverableTurnRecoveryContext.value?.checkpoint,
)
const isMemoryChangeChecking = computed(() => props.message.memoryChangeStatus === 'checking')
const isMemoryChangeCheckedEmpty = computed(() => props.message.memoryChangeStatus === 'checked-empty')
const isMemoryChangeFailed = computed(() => props.message.memoryChangeStatus === 'failed')
const isMemoryChangeDisabled = computed(() => props.message.memoryChangeStatus === 'disabled')
const isMemoryChangeSettled = computed(() =>
  memoryChanges.value.length > 0
  && !isMemoryChangeChecking.value
  && !isMemoryChangeFailed.value
  && !isMemoryChangeDisabled.value,
)
const showMemoryChangePanel = computed(() =>
  isMemoryChangeChecking.value
  || isMemoryChangeCheckedEmpty.value
  || isMemoryChangeFailed.value
  || isMemoryChangeDisabled.value
  || !!memoryChangeOverview.value,
)

function memoryChangeReasonText(reason?: string) {
  if (!reason) return null
  const knownReasons: Record<string, string> = {
    memory_system_disabled: '当前记忆功能未启用。',
    memory_repository_unavailable: '当前记忆存储暂不可用。',
    memory_learning_disabled: '当前自动记忆学习已关闭。',
    memory_status_timeout: '后台整理用时过长，前端已停止等待。',
    memory_status_refresh_failed: '前端暂时没有拿到后台整理结果。',
    user_memory_write_denied: '已按你的要求跳过长期记忆写入。',
    user_memory_read_denied: '已按你的要求不参考长期记忆。',
    conversation_smoke_test: '这是测试消息，不写入长期记忆。',
    project_context_dependency_missing: '项目上下文依赖未就绪，知微没有写入记忆。',
    project_context_resolution_failed: '当前项目上下文暂时不可用，知微没有写入记忆。',
    personal_context_resolution_failed: '个人记忆上下文暂时不可用，知微没有写入记忆。',
    chat_session_lookup_failed: '会话上下文暂时无法确认，知微没有写入记忆。',
    llm_timeout: '模型整理记忆超时。',
  }
  return knownReasons[reason] ?? `后台返回原因：${reason}。`
}

const memoryChangeReasonDescription = computed(() => memoryChangeReasonText(props.message.memoryChangeReason))
const memoryChangePanelTitle = computed(() =>
  isMemoryChangeChecking.value
    ? '正在整理记忆'
    : (isMemoryChangeCheckedEmpty.value
        ? '记忆已检查'
        : (isMemoryChangeFailed.value
            ? '记忆整理未完成'
            : (isMemoryChangeDisabled.value ? '未写入记忆' : memoryChangeSettledTitle.value))),
)
const memoryChangePanelCount = computed(() =>
  isMemoryChangeChecking.value
    ? '后台处理中'
    : (isMemoryChangeCheckedEmpty.value
        ? '无需新增'
        : (isMemoryChangeFailed.value
            ? '可手动记住'
            : (isMemoryChangeDisabled.value ? '已跳过' : memoryChangeOverview.value?.action))),
)
const memoryChangePanelDetail = computed(() =>
  isMemoryChangeChecking.value
    ? '回答已完成，知微正在后台整理可长期复用的信息；你可以继续对话。'
    : (isMemoryChangeCheckedEmpty.value
        ? '这轮没有发现需要写入长期记忆的新信息。'
        : (isMemoryChangeFailed.value
            ? `这轮回答已经完成，但后台自动整理记忆没有成功。${memoryChangeReasonDescription.value ?? ''}`
            : (isMemoryChangeDisabled.value
                ? `这轮没有写入长期记忆。${memoryChangeReasonDescription.value ?? '当前设置或记忆链路不允许自动沉淀。'}`
                : memoryChangeOverview.value?.detail))),
)
const memoryChangePanelImpact = computed(() =>
  isMemoryChangeChecking.value
    ? '整理完成后会在这里显示，可查看、调整或忘记。'
    : (isMemoryChangeCheckedEmpty.value
        ? '不会改动现有记忆；你也可以继续明确告诉知微要记住什么。'
        : (isMemoryChangeFailed.value
            ? '不会影响本轮回答；需要保留的信息可以在后续对话里再次明确告诉知微。'
            : (isMemoryChangeDisabled.value
                ? '不会改动现有记忆；需要保留的信息可以在后续对话里明确要求知微记住。'
                : memoryChangeOverview.value?.impact))),
)
const memoryChangePanelImpactLabel = computed(() =>
  isMemoryChangeChecking.value
    ? '完成后'
    : (isMemoryChangeCheckedEmpty.value || isMemoryChangeFailed.value || isMemoryChangeDisabled.value ? '结果' : '后续影响'),
)
const memoryChangeExplanationName = computed(() => `${memoryChangeNoun.value}沉淀说明`)
const memoryChangePanelAriaLabel = computed(() => `本轮${memoryChangeNoun.value}整理结果`)
const memoryChangeInspectActionLabel = computed(() => `查看和调整本轮${memoryChangeNoun.value}`)
const showMemoryChangeExplanationToggle = computed(() =>
  !isMemoryChangeChecking.value
  && (!!memoryChangePanelDetail.value || !!memoryChangePanelImpact.value),
)
const showMemoryChangeExplanationBody = computed(() =>
  isMemoryChangeChecking.value || showMemoryChangeExplanation.value,
)
const showMemoryManagementLink = computed(() =>
  !isMemoryChangeChecking.value
  && (isMemoryChangeSettled.value || !firstMemoryChange.value)
  && (
    isMemoryChangeSettled.value
    || isMemoryChangeCheckedEmpty.value
    || isMemoryChangeFailed.value
    || isMemoryChangeDisabled.value
  ),
)
const showManualMemorySettleAction = computed(() =>
  !isMemoryChangeChecking.value
  && !firstMemoryChange.value
  && canRememberMessage.value
  && (isMemoryChangeCheckedEmpty.value || isMemoryChangeFailed.value),
)

const taskExecutionSummary = computed<TaskExecutionSummary | null>(() => {
  if (props.message.role !== 'assistant') {
    return null
  }

  if (toolSummaries.value.length === 0 && !taskRecovery.value) {
    return null
  }

  const parts = executionPartsText()

  if (props.streaming) {
    return runningExecutionSummary()
  }

  if (taskRecovery.value?.status === 'SUSPENDED') {
    return {
      tone: 'waiting',
      icon: PauseCircle,
      title: taskRecovery.value.title,
      detail: parts
        ? '已处理部分任务，当前等待继续。'
        : taskRecovery.value.detail,
    }
  }

  if (
    failedExecutionCount.value > 0
    || taskRecovery.value?.status === 'DEGRADED'
    || props.message.turnStatus === 'DEGRADED'
    || props.message.completionMode === 'DEGRADED'
  ) {
    const snapshot = primaryFailedExecutionSnapshot()
    return {
      tone: 'warning',
      icon: AlertTriangle,
      title: failedExecutionTitle(snapshot),
      detail: failedExecutionDetail(snapshot, parts),
    }
  }

  const capabilityWarning = capabilityWarningSummary.value
  if (capabilityWarning) {
    return {
      tone: 'warning',
      icon: AlertTriangle,
      title: capabilityWarningTitle(capabilityWarning),
      detail: capabilityWarningDetail(capabilityWarning),
    }
  }

  return null
})

const completedTurnResultItem = computed<ContextUsageItem | null>(() => {
  if (props.message.role !== 'assistant' || props.streaming) {
    return null
  }
  if (
    failedExecutionCount.value > 0
    || taskRecovery.value
    || props.message.turnStatus === 'DEGRADED'
    || props.message.completionMode === 'DEGRADED'
    || props.message.turnStatus === 'SUSPENDED'
    || props.message.completionMode === 'SUSPENDED'
  ) {
    return null
  }
  if (toolSummaries.value.length === 0) {
    return null
  }

  const detail = outcomePartsText()
  const label = completedExecutionOutcomeText().replace(/[。！？.!?]+$/, '')
  return {
    id: 'turn-result',
    label: label || '已完成本轮任务',
    detail: detail || undefined,
    icon: completedExecutionIcon(),
    tone: 'success',
    showTrace: hasStoredTraceData(),
  }
})

function summarizeSourceNames(sources: SourceSummary[]) {
  const names = sources
    .map(source => source.name?.trim())
    .filter(Boolean)
    .slice(0, 2)
  if (sources.length > 2) {
    names.push(`等 ${sources.length} 项`)
  }
  return names.length ? names.join('、') : undefined
}

function memoryReferenceNoun(sources: SourceSummary[]) {
  return memorySourceCollectionNoun(sources)
}

function memoryReferenceLabel(sources: SourceSummary[]) {
  return `参考了 ${sources.length} 条${memoryReferenceNoun(sources)}`
}

function buildReferenceUsageItems(): ContextUsageItem[] {
  const items: ContextUsageItem[] = []
  const firstKb = kbSources.value[0]
  const firstMemory = memorySources.value[0]

  if (firstKb) {
    items.push({
      id: 'references-knowledge',
      label: `参考了 ${kbSources.value.length} 个资料库`,
      detail: summarizeSourceNames(kbSources.value),
      icon: BookOpenCheck,
      knowledgeRoute: { name: 'knowledgeBaseDetail', params: { id: firstKb.id } },
    })
  }

  if (firstMemory) {
    items.push({
      id: 'references-memory',
      label: memoryReferenceLabel(memorySources.value),
      detail: summarizeSourceNames(memorySources.value),
      icon: Brain,
      inspectMemorySource: firstMemory,
    })
  }

  return items
}

function buildMemorySettledUsageItem(): ContextUsageItem {
  const count = memoryChanges.value.length
  const label = deleteMemoryChangeCount.value === 0
    ? `已沉淀 ${count} 条${memoryChangeNoun.value}`
    : (deleteMemoryChangeCount.value === count
        ? `已忘记 ${count} 条${memoryChangeNoun.value}`
        : `已调整 ${count} 条${memoryChangeNoun.value}`)
  return {
    id: 'memory-settled',
    label,
    detail: memoryChangeOverview.value?.action,
    icon: Brain,
    tone: 'success',
    memoryChangeTarget: true,
  }
}

function latestPersistedKnowledgeSettlement() {
  const settlements = props.message.knowledgeSettlements ?? []
  for (let index = settlements.length - 1; index >= 0; index -= 1) {
    const settlement = settlements[index]
    if (settlement.knowledgeBaseName?.trim() || settlement.knowledgeBaseId?.trim()) {
      return settlement
    }
  }
  return null
}

function latestPersistedArtifactSettlement() {
  const settlements = props.message.knowledgeSettlements ?? []
  for (let index = settlements.length - 1; index >= 0; index -= 1) {
    const settlement = settlements[index]
    if (settlement.artifactId?.trim() && (settlement.knowledgeBaseName?.trim() || settlement.knowledgeBaseId?.trim())) {
      return settlement
    }
  }
  return null
}

function persistedArtifactSettlements() {
  return (props.message.knowledgeSettlements ?? [])
    .filter(settlement => !!settlement.artifactId?.trim())
}

function savedKnowledgeBaseNameForArtifact(artifactId: string) {
  const savedTarget = artifactKnowledgeSavedTargets.value[artifactId]?.knowledgeBaseName?.trim()
  if (savedTarget) return savedTarget

  const persisted = findPersistedArtifactSettlement(artifactId)
  return persisted?.knowledgeBaseName?.trim() || persisted?.knowledgeBaseId?.trim() || null
}

function findPersistedArtifactSettlement(artifactId: string): KnowledgeSettlement | null {
  const normalizedId = artifactId.trim()
  if (!normalizedId) return null
  const settlements = props.message.knowledgeSettlements ?? []
  for (let index = settlements.length - 1; index >= 0; index -= 1) {
    const settlement = settlements[index]
    if (settlement.artifactId?.trim() === normalizedId) {
      return settlement
    }
  }
  return null
}

function buildKnowledgeSavedUsageItem(): ContextUsageItem | null {
  const savedName = props.savedKnowledgeBaseName?.trim()
  const savedArtifacts = Object.values(artifactKnowledgeSavedTargets.value)
  const artifactTarget = savedArtifacts[0]
  const persistedArtifactTarget = latestPersistedArtifactSettlement()
  const persistedTarget = latestPersistedKnowledgeSettlement()
  const knowledgeBaseName = savedName
    || artifactTarget?.knowledgeBaseName?.trim()
    || persistedArtifactTarget?.knowledgeBaseName?.trim()
    || persistedTarget?.knowledgeBaseName?.trim()
    || persistedArtifactTarget?.knowledgeBaseId?.trim()
    || persistedTarget?.knowledgeBaseId?.trim()
  if (props.message.role !== 'assistant' || !knowledgeBaseName) {
    return null
  }
  const artifactSettlementCount = savedArtifacts.length || persistedArtifactSettlements().length
  const targetId = props.savedKnowledgeBaseId?.trim()
    || artifactTarget?.knowledgeBaseId?.trim()
    || persistedArtifactTarget?.knowledgeBaseId?.trim()
    || persistedTarget?.knowledgeBaseId?.trim()
    || (savedName ? props.artifactKnowledgeBaseId?.trim() : '')
  const artifactDetail = artifactSettlementCount > 1
    ? `${knowledgeBaseName} · ${artifactSettlementCount} 个文件`
    : knowledgeBaseName
  return {
    id: 'knowledge-saved',
    label: '已存入资料库',
    detail: artifactDetail,
    icon: BookOpenCheck,
    tone: 'success',
    knowledgeTarget: Boolean(targetId),
    knowledgeRoute: targetId
      ? { name: 'knowledgeBaseDetail', params: { id: targetId } }
      : undefined,
  }
}

function buildKnowledgeSaveFailedUsageItem(): ContextUsageItem | null {
  if (
    props.message.role !== 'assistant'
    || !saveKnowledgeErrorMessage.value
    || props.savedKnowledgeBaseName?.trim()
  ) {
    return null
  }
  return {
    id: 'knowledge-save-failed',
    label: '资料未存入',
    detail: saveKnowledgeErrorMessage.value,
    icon: AlertTriangle,
    tone: 'warning',
  }
}

function buildOutputUsageItem(): ContextUsageItem | null {
  const artifactCount = imageArtifactRefs.value.length + fileArtifactRefs.value.length
  const hasInteractiveOutput = visibleA2uiComponents.value.length > 0
  if (artifactCount === 0 && !hasInteractiveOutput) {
    return null
  }
  if (artifactCount > 0 && hasInteractiveOutput) {
    return {
      id: 'turn-output',
      label: '生成产出',
      detail: `${artifactCount} 个文件 · 交互结果`,
      icon: FileText,
      tone: 'success',
      outputTarget: true,
    }
  }
  if (artifactCount > 0) {
    const firstArtifact = imageArtifactRefs.value[0] ?? fileArtifactRefs.value[0]
    return {
      id: 'turn-output',
      label: `生成 ${artifactCount} 个文件`,
      detail: firstArtifact?.fileName,
      icon: FileText,
      tone: 'success',
      outputTarget: true,
    }
  }
  return {
    id: 'turn-output',
    label: '生成交互结果',
    detail: '可在回答中查看',
    icon: Sparkles,
    tone: 'success',
    outputTarget: true,
  }
}

function buildUserMemoryContextUsageItem(): ContextUsageItem | null {
  if (props.message.role !== 'user') {
    return null
  }
  const mode = props.message.singleTurnOverride?.memoryContextMode
  if (mode === 'focused') {
    return {
      id: 'user-memory-context-focused',
      label: '本轮用记忆',
      detail: '会参考偏好和事实',
      icon: Brain,
      tone: 'success',
    }
  }
  if (mode === 'off') {
    return {
      id: 'user-memory-context-off',
      label: '本轮不用记忆',
      detail: '只按当前消息处理',
      icon: EyeOff,
      tone: 'warning',
    }
  }
  return null
}

function buildUserKnowledgeContextUsageItem(): ContextUsageItem | null {
  if (props.message.role !== 'user') {
    return null
  }
  const knowledgeBaseCount = props.message.singleTurnOverride?.knowledgeBaseIds
    ?.filter(id => typeof id === 'string' && id.trim().length > 0)
    .length ?? 0
  if (knowledgeBaseCount === 0) {
    return null
  }
  return {
    id: 'user-knowledge-context',
    label: '本轮用资料',
    detail: `${knowledgeBaseCount} 个资料库`,
    icon: BookOpenCheck,
    tone: 'success',
  }
}

function buildExecutionConstraintUsageItem(): ContextUsageItem | null {
  if (props.message.role !== 'assistant') {
    return null
  }
  const disabledTools = props.message.executionConstraints?.disabledTools
    ?.filter(tool => typeof tool.id === 'string' && tool.id.trim().length > 0) ?? []
  if (disabledTools.length === 0) {
    return null
  }
  const disabledIds = disabledTools.map(tool => tool.id.trim())
  const disabledLabels = disabledTools
    .map(tool => tool.label?.trim() || tool.id.trim())
    .filter(Boolean)
  const disabledText = summarizeDisabledConstraintLabels(disabledLabels)
  const noWeb = disabledIds.some(id =>
    id === 'web'
    || id === 'web.search'
    || id === 'web.fetch'
    || id === 'browser'
    || id.startsWith('browser.'),
  )
  if (noWeb) {
    return {
      id: 'execution-constraints-no-web',
      label: '本轮未联网',
      detail: disabledText ? `${disabledText}已关闭` : undefined,
      icon: EyeOff,
      tone: 'warning',
    }
  }
  return {
    id: 'execution-constraints-disabled-tools',
    label: '按要求限制工具',
    detail: disabledText ? `${disabledText}已关闭` : undefined,
    icon: EyeOff,
    tone: 'warning',
  }
}

function summarizeDisabledConstraintLabels(labels: string[]) {
  const normalized = [...new Set(labels.map(label => label.trim()).filter(Boolean))]
  if (normalized.length === 0) return ''
  const visible = normalized.slice(0, 2)
  return `${visible.join('、')}${normalized.length > visible.length ? '等' : ''}`
}

const contextUsageItems = computed<ContextUsageItem[]>(() => {
  const items: ContextUsageItem[] = []

  const userKnowledgeContextUsage = buildUserKnowledgeContextUsageItem()
  if (userKnowledgeContextUsage) {
    items.push(userKnowledgeContextUsage)
  }
  const userMemoryContextUsage = buildUserMemoryContextUsageItem()
  if (userMemoryContextUsage) {
    items.push(userMemoryContextUsage)
  }
  if (turnRecoveryContext.value && props.message.role === 'assistant') {
    items.push(buildTurnRecoveryUsageItem())
  }
  const executionConstraintUsage = buildExecutionConstraintUsageItem()
  if (executionConstraintUsage) {
    items.push(executionConstraintUsage)
  }
  items.push(...buildReferenceUsageItems())
  const resultUsage = completedTurnResultItem.value
  if (resultUsage) {
    items.push(resultUsage)
  }
  const outputUsage = buildOutputUsageItem()
  if (outputUsage) {
    items.push(outputUsage)
  }
  const knowledgeSavedUsage = buildKnowledgeSavedUsageItem()
  if (knowledgeSavedUsage) {
    items.push(knowledgeSavedUsage)
  }
  const knowledgeSaveFailedUsage = buildKnowledgeSaveFailedUsageItem()
  if (knowledgeSaveFailedUsage) {
    items.push(knowledgeSaveFailedUsage)
  }
  if (isMemoryChangeSettled.value) {
    items.push(buildMemorySettledUsageItem())
  }
  return items
})

function contextUsageActionTitle(item: ContextUsageItem) {
  if (item.memoryChangeTarget) {
    return `${item.label}，点击查看本轮记忆`
  }
  if (item.inspectMemorySource) {
    return `${item.label}，点击查看和调整`
  }
  if (item.knowledgeTarget) {
    return `${item.label}，点击打开资料库`
  }
  if (item.knowledgeRoute) {
    return `${item.label}，点击查看资料来源`
  }
  if (item.showTrace) {
    return `${item.label}，点击查看任务步骤`
  }
  if (item.outputTarget) {
    return `${item.label}，点击查看产物`
  }
  return item.label
}

function handleContextUsageItemClick(item: ContextUsageItem) {
  if (item.memoryChangeTarget) {
    focusMemoryChangePanel()
    return
  }
  if (item.inspectMemorySource) {
    inspectMemory(item.inspectMemorySource)
    return
  }
  if (item.showTrace) {
    emit('show-trace', props.message.id)
    return
  }
  if (item.outputTarget) {
    focusOutputPanel()
  }
}

function handleArtifactSavedToKnowledge(payload: ArtifactKnowledgeSavedPayload) {
  if (!payload.artifactId || !payload.knowledgeBaseId || !payload.knowledgeBaseName) {
    return
  }
  artifactKnowledgeSavedTargets.value = {
    ...artifactKnowledgeSavedTargets.value,
    [payload.artifactId]: payload,
  }
  emit('save-artifact-knowledge', props.message, payload)
}

function persistArtifactKnowledgeSettlement(payload: ArtifactKnowledgeSavedPayload) {
  return props.persistArtifactKnowledgeSettlement?.(props.message, payload)
}

function focusMemoryChangePanel() {
  if (hiddenMemoryChangeCount.value > 0) {
    showAllMemoryChanges.value = true
  }
  nextTick(() => {
    const target = memoryChangePanelRef.value
    if (!target) return
    const reducedMotion = typeof window !== 'undefined'
      && typeof window.matchMedia === 'function'
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches
    target.scrollIntoView({
      behavior: reducedMotion ? 'auto' : 'smooth',
      block: 'nearest',
    })
    target.focus({ preventScroll: true })
  })
}

function focusOutputPanel() {
  const target = imageOutputPanelRef.value
    ?? interactiveOutputPanelRef.value
    ?? fileOutputPanelRef.value
  if (!target) return
  const reducedMotion = typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches
  target.scrollIntoView({
    behavior: reducedMotion ? 'auto' : 'smooth',
    block: 'nearest',
  })
  target.focus({ preventScroll: true })
}

const failedToolSummaries = computed(() =>
  toolSummaries.value.filter(isFailedExecution),
)

const isResumableAssistantTurn = computed(() =>
  hasRecoverableTaskState(props.message)
  || failedToolSummaries.value.length > 0,
)

const showRecoveryPrompt = computed(() =>
  props.message.role === 'assistant'
  && !props.streaming
  && (props.isLastAssistant ?? false)
  && isResumableAssistantTurn.value
  && (
    failedToolSummaries.value.length > 0
    || !!taskRecovery.value
    || !!recoverableTurnRecoveryCheckpoint.value
  ),
)

const recoveryPromptTitle = computed(() => {
  const failed = recoverySnapshot.value
  if (failed) {
    return failedExecutionTitle(failed)
  }
  if (taskRecovery.value?.title) {
    return taskRecovery.value.title
  }
  if (recoverableTurnRecoveryContext.value?.title) {
    return recoverableTurnRecoveryContext.value.title
  }
  if (taskRecovery.value && !failed) {
    return '可以继续这一轮'
  }
  return '这一步卡住了'
})

const recoveryNextActions = computed(() => {
  const planned = taskRecovery.value?.nextActions?.filter(Boolean).slice(0, 3)
  if (planned?.length) return planned
  const failed = failedToolSummaries.value[0]
  if (failed) return buildToolRecoveryPlan(failed).slice(0, 3)
  return recoverableTurnRecoveryContext.value?.nextActions?.filter(Boolean).slice(0, 3) ?? []
})

const recoveryPromptDetail = computed(() => {
  const hasSnapshot = recoverySnapshotLines.value.length > 0
  const fallbackDetail = hasSnapshot
    ? '可以从失败处继续，或重新开始这一轮。'
    : '可以按计划继续处理，或重新开始这一轮。'
  const detail = taskRecovery.value?.detail
    ?? failedToolSummaries.value[0]?.recoveryHint
    ?? recoverableTurnRecoveryContext.value?.detail
    ?? recoverableTurnRecoveryContext.value?.resumeStrategy
    ?? fallbackDetail
  if (!hasSnapshot) {
    if (recoveryNextActions.value.length) {
      return `${detail} 继续时会按下面计划接着处理。`
    }
    return detail
  }
  return `${detail} 继续时会带上当前进度和下面的计划接着处理。`
})

const recoveryCanResume = computed(() => canUseManualResume(taskRecovery.value))

const recoveryCanRestart = computed(() => canUseManualRestart(taskRecovery.value))

const recoveryContinuityText = computed(() => {
  const snapshot = recoverySnapshot.value
  if (!snapshot) {
    if (taskRecovery.value && !recoveryCanResume.value) {
      return waitingRecoveryContinuityText()
    }
    return recoveryNextActions.value.length ? '会复用本轮已有结果继续处理。' : ''
  }

  if (!recoveryCanResume.value) {
    return waitingRecoveryContinuityText()
  }

  if (snapshot.interrupted) {
    return '已保留已完成步骤，继续时会从未返回结果的这一步接上。'
  }

  if (isSkillExecution(snapshot)) {
    return '已保留当前对话、技能主体和失败输出，继续时会从这个技能步骤接上。'
  }

  if (snapshot.outputSummary || snapshot.outputDetail) {
    return '已保留已完成步骤和失败输出，继续时会从这里接上。'
  }

  return '已保留当前进度，继续时会从这里接上。'
})

function waitingRecoveryContinuityText() {
  switch (taskRecovery.value?.resumeMode) {
    case 'user_reply':
      return '已保留当前进度，等你补充后知微会接着处理。'
    case 'browser':
      return '已保留当前进度，等浏览器步骤完成后再接着处理。'
    case 'external':
      return '已保留当前进度，等外部任务返回后再接着处理。'
    case 'scheduled':
      return '已保留当前进度，到设定时间后知微会接着处理。'
    default:
      return '已保留当前进度，条件满足后会接着处理。'
  }
}

function buildTaskRecoveryAction(mode: 'resume' | 'restart'): ToolRecoveryAction | undefined {
  const recovery = taskRecovery.value
  const checkpoint = recovery?.checkpoint
  if (!recovery) return undefined
  const artifactRefs = mergeRecoveryArtifactRefs(checkpoint?.artifactRefs, props.message.artifactRefs)
  return {
    id: `task-recovery-${mode}`,
    label: mode === 'restart'
      ? '重新开始'
      : (recovery.actionLabel ?? '继续'),
    description: mode === 'restart'
      ? '保留恢复摘要和失败线索，重新开始这一轮。'
      : '保留当前进度，按恢复计划从卡住的位置继续。',
    mode,
    category: checkpoint?.failureCategory,
    toolId: checkpoint?.toolId,
    callId: checkpoint?.callId,
    toolName: checkpoint?.toolName,
    executionKind: checkpoint?.executionKind,
    action: checkpoint?.action,
    interrupted: checkpoint?.interrupted,
    subjectLabel: checkpoint?.subjectLabel,
    subjectNames: checkpoint?.subjectNames,
    inputSummary: checkpoint?.inputSummary,
    inputDetail: checkpoint?.inputDetail,
    outputSummary: checkpoint?.outputSummary,
    outputDetail: checkpoint?.outputDetail,
    workingDirectory: checkpoint?.workingDirectory,
    generatedFilePath: checkpoint?.generatedFilePath,
    ...(artifactRefs ? { artifactRefs } : {}),
    missingCapabilities: checkpoint?.missingCapabilities,
    recoveryHint: recovery.detail,
    nextActions: recovery.nextActions?.filter(Boolean).slice(0, 3),
  }
}

function buildTurnRecoveryContextAction(mode: 'resume' | 'restart'): ToolRecoveryAction | undefined {
  const context = recoverableTurnRecoveryContext.value
  const checkpoint = context?.checkpoint
  if (!context || !checkpoint) return undefined
  const artifactRefs = mergeRecoveryArtifactRefs(checkpoint.artifactRefs, props.message.artifactRefs)
  const category = checkpoint.failureCategory ?? snapshotFailureCategory(checkpoint)
  return {
    id: `turn-recovery-${mode}`,
    label: mode === 'restart'
      ? '重新开始'
      : (checkpoint.recoveryActionLabel
          ?? (category === 'UNKNOWN' ? '继续' : resolveResumeActionLabel(category))),
    description: mode === 'restart'
      ? '保留恢复上下文和失败线索，重新开始这一轮。'
      : checkpoint.recoveryActionDescription
        ?? context.resumeStrategy
        ?? '保留恢复上下文，从上次断点继续。',
    mode,
    category,
    toolId: checkpoint.toolId,
    callId: checkpoint.callId,
    toolName: checkpoint.toolName,
    executionKind: checkpoint.executionKind,
    action: checkpoint.action,
    interrupted: checkpoint.interrupted,
    subjectLabel: checkpoint.subjectLabel,
    subjectNames: checkpoint.subjectNames,
    inputSummary: checkpoint.inputSummary,
    inputDetail: checkpoint.inputDetail,
    outputSummary: checkpoint.outputSummary,
    outputDetail: checkpoint.outputDetail,
    workingDirectory: checkpoint.workingDirectory,
    generatedFilePath: checkpoint.generatedFilePath,
    ...(artifactRefs ? { artifactRefs } : {}),
    missingCapabilities: checkpoint.missingCapabilities,
    recoveryHint: context.detail ?? context.resumeStrategy,
    nextActions: context.nextActions?.filter(Boolean).slice(0, 3),
  }
}

const recoveryPrimaryAction = computed<ToolRecoveryAction | undefined>(() => {
  if (taskRecovery.value) return buildTaskRecoveryAction('resume')
  const failed = failedToolSummaries.value[0]
  const action = failed?.recoveryActions?.find(item => item.mode !== 'restart')
    ?? failed?.recoveryActions?.[0]
    ?? (failed ? buildToolRecoveryActions(failed.toolId, failed).find(item => item.mode !== 'restart') : undefined)
  if (!failed || !action) return buildTurnRecoveryContextAction('resume')
  return buildToolRecoveryPayload(failed, action)
})

const recoveryRestartAction = computed<ToolRecoveryAction | undefined>(() => {
  if (taskRecovery.value) return buildTaskRecoveryAction('restart')
  const failed = failedToolSummaries.value[0]
  const action = failed?.recoveryActions?.find(item => item.mode === 'restart')
    ?? (failed ? buildToolRecoveryActions(failed.toolId, failed).find(item => item.mode === 'restart') : undefined)
  if (!failed || !action) return buildTurnRecoveryContextAction('restart')
  return buildToolRecoveryPayload(failed, action)
})

const recoveryActionLabel = computed(() =>
  taskRecovery.value?.actionLabel ?? recoveryPrimaryAction.value?.label ?? '继续',
)

const recoveryPrimaryActionDescription = computed(() =>
  recoveryPrimaryAction.value?.description,
)

const recoveryRestartActionDescription = computed(() =>
  recoveryRestartAction.value?.description,
)

const recoveryActionStrategyText = computed(() => {
  const primary = recoveryPrimaryActionDescription.value
  const restart = recoveryRestartActionDescription.value
  if (primary && restart && recoveryCanResume.value && recoveryCanRestart.value) {
    return `继续：${primary} 重启：${restart}`
  }
  if (primary && recoveryCanResume.value) {
    return primary
  }
  if (restart && recoveryCanRestart.value) {
    return restart
  }
  return ''
})

const recoveryNextStepText = computed(() => {
  const snapshot = recoverySnapshot.value
  const actionLabel = recoveryActionLabel.value || '继续'
  if (snapshotFailureCategory(snapshot) === 'CAPABILITY') {
    const missing = formatMissingCapabilityList(snapshot, 3)
    return missing
      ? `先在能力中心修复 ${missing}，回来点“${actionLabel}”。`
      : `先在能力中心修复缺失能力，回来点“${actionLabel}”。`
  }
  if (!recoveryCanResume.value) {
    const status = recoveryStatusLabel.value
    return status ? `${status}，条件满足后知微会接着处理。` : ''
  }
  if (actionLabel && actionLabel !== '继续') {
    return `修正卡点后点“${actionLabel}”，知微会带着当前进度接上。`
  }
  if (recoveryNextActions.value.length > 0) {
    return '确认卡点和续接计划后点“继续”，知微会带着当前进度接上。'
  }
  return ''
})

const recoveryResumeAriaLabel = computed(() =>
  [
    recoveryActionLabel.value,
    recoveryPromptTitle.value,
    recoveryPrimaryActionDescription.value,
  ].filter(Boolean).join('：'),
)

const recoveryRestartAriaLabel = computed(() =>
  [
    '重新开始',
    recoveryPromptTitle.value,
    recoveryRestartActionDescription.value,
  ].filter(Boolean).join('：'),
)

const recoveryStatusLabel = computed(() => resolveManualResumeStatusLabel(taskRecovery.value))

const recoveryCheckpoint = computed<TaskRecoveryCheckpoint | undefined>(() =>
  taskRecovery.value?.checkpoint,
)

const recoverySnapshot = computed<TaskRecoveryCheckpoint | ToolCallSummary | null>(() =>
  recoveryCheckpoint.value
    ?? failedToolSummaries.value[0]
    ?? recoverableTurnRecoveryCheckpoint.value
    ?? null,
)

const showCapabilityRepairLink = computed(() =>
  snapshotFailureCategory(recoverySnapshot.value) === 'CAPABILITY'
  || recoveryPrimaryAction.value?.category === 'CAPABILITY',
)

const recoveryCapabilityRepairMissing = computed(() =>
  missingCapabilityIds(recoverySnapshot.value).join(','),
)

const recoveryCapabilityRepairSkill = computed(() =>
  capabilityWarningSkillName(recoverySnapshot.value),
)

const recoveryCapabilityRepairRoute = computed(() => {
  const returnSessionId = props.recoveryReturnSessionId?.trim()
  const query: Record<string, string> = {
    from: returnSessionId ? 'task-recovery' : 'capability-warning',
  }
  if (returnSessionId) {
    query.returnSessionId = returnSessionId
  }
  if (returnSessionId && props.message.turnId) {
    query.returnTurnId = props.message.turnId
  }
  if (returnSessionId && props.message.id) {
    query.returnEntryId = props.message.id
  }
  if (recoveryCapabilityRepairMissing.value) {
    query.missing = recoveryCapabilityRepairMissing.value
  }
  if (recoveryCapabilityRepairSkill.value) {
    query.skill = recoveryCapabilityRepairSkill.value
  }
  return {
    name: 'capabilities',
    query,
  }
})

const recoveryRetainedContext = computed(() => {
  const snapshot = recoverySnapshot.value
  return snapshot ? buildToolRecoveryContextSummary(snapshot) : []
})

function buildRecoverySnapshotSubject(snapshot: TaskRecoveryCheckpoint | ToolCallSummary) {
  if (snapshot.executionKind === 'SKILL' || isSkillExecution(snapshot)) {
    const skillName = snapshot.subjectNames?.find(Boolean)
    return skillName ? `技能 ${skillName}` : '技能步骤'
  }

  const subjectNames = snapshot.subjectNames?.filter(Boolean) ?? []
  const action = snapshot.action
    ?? (snapshot.toolId ? resolveToolAction(snapshot.toolId) : undefined)
  const fallback = action
    || formatFailureCategory(snapshot.failureCategory)
    || snapshot.toolName
    || snapshot.toolId
    || '当前步骤'
  if (!subjectNames.length) {
    return fallback
  }
  return `${fallback}：${subjectNames.slice(0, 2).join('、')}`
}

const recoverySnapshotTitle = computed(() =>
  recoveryCanResume.value ? '卡住位置' : '等待位置',
)

const recoverySnapshotLines = computed(() => {
  const snapshot = recoverySnapshot.value
  if (!snapshot) return []
  const lines: Array<{ label: string, value: string }> = []
  lines.push({ label: '位置', value: buildRecoverySnapshotSubject(snapshot) })
  if (snapshot.outputSummary) {
    lines.push({ label: '卡点', value: snapshot.outputSummary })
  }
  if (snapshot.inputSummary) {
    lines.push({ label: '已尝试', value: snapshot.inputSummary })
  }
  if (snapshot.outputDetail && snapshot.outputDetail !== snapshot.outputSummary) {
    lines.push({ label: '原因', value: truncateCheckpointLine(snapshot.outputDetail) })
  }
  if (snapshot.generatedFilePath) {
    lines.push({ label: '产物', value: snapshot.generatedFilePath })
  }
  const artifactRefs = 'artifactRefs' in snapshot ? snapshot.artifactRefs : undefined
  const artifactLine = buildRecoveryArtifactLine(artifactRefs)
  if (artifactLine) {
    lines.push({ label: '产物引用', value: artifactLine })
  }
  return lines
})

function buildRecoveryArtifactLine(refs?: ToolRecoveryAction['artifactRefs']) {
  const visibleRefs = refs?.filter(ref => ref.artifactId).slice(0, 2) ?? []
  if (!visibleRefs.length) return ''
  const names = visibleRefs.map(ref => ref.fileName || ref.artifactId)
  const suffix = (refs?.length ?? 0) > visibleRefs.length
    ? ` 等 ${refs?.length} 个`
    : ''
  return `${names.join('、')}${suffix}`
}

const recoveryCopyLabel = computed(() =>
  recoverySnapshotLines.value.length ? '复制断点' : '复制计划',
)

const recoveryCopyAriaLabel = computed(() =>
  `${recoveryCopyLabel.value}：${recoveryPromptTitle.value}`,
)

const recoveryCopyText = computed(() => {
  const lines = [
    '[知微任务恢复]',
    `标题: ${recoveryPromptTitle.value}`,
    `说明: ${recoveryPromptDetail.value}`,
  ]
  if (recoveryContinuityText.value) {
    lines.push(`续接: ${recoveryContinuityText.value}`)
  }
  if (recoveryActionStrategyText.value) {
    lines.push(`动作: ${recoveryActionStrategyText.value}`)
  }
  if (recoveryRetainedContext.value.length) {
    lines.push('继续时保留:')
    recoveryRetainedContext.value.forEach(item => {
      lines.push(`- ${item}`)
    })
  }
  if (recoverySnapshotLines.value.length) {
    lines.push(`${recoverySnapshotTitle.value}:`)
    recoverySnapshotLines.value.forEach(line => {
      lines.push(`- ${line.label}: ${line.value}`)
    })
  }
  if (recoveryNextActions.value.length) {
    lines.push('下一步:')
    recoveryNextActions.value.forEach((action, index) => {
      lines.push(`${index + 1}. ${action}`)
    })
  }
  return lines.filter(Boolean).join('\n')
})

async function handleCopyRecoverySnapshot() {
  if (await copyToClipboard(recoveryCopyText.value)) {
    recoveryCopied.value = true
    window.setTimeout(() => { recoveryCopied.value = false }, 2000)
  }
}

function truncateCheckpointLine(value: string, maxLength = 220) {
  const text = value.trim()
  return text.length > maxLength ? `${text.slice(0, maxLength - 1)}…` : text
}

const a2uiPanelLabel = computed(() => (
  props.streaming
    ? '交互面板正在更新'
    : '工具 / 工作流结果'
))

const userStatusLabel = computed(() => {
  if (props.message.role !== 'user' || !props.message.status) {
    return ''
  }

  if (props.message.status === 'pending') {
    return '发送中'
  }

  if (props.message.status === 'error') {
    return '发送失败'
  }

  return ''
})

const displayContent = computed(() => {
  if (props.streaming) {
    return props.streamingContent ?? ''
  }
  return props.message.content
})

interface CapabilityFollowup {
  id: string
  label: string
  title: string
  icon: Component
  kind: 'remember' | 'save-knowledge' | 'prompt'
  prompt?: string
}

const normalizedDisplayContent = computed(() => displayContent.value.trim())

function hasSavedKnowledgeTarget() {
  return Boolean(props.savedKnowledgeBaseName?.trim())
}

function hasKnowledgeSaveTarget() {
  return Boolean(props.artifactKnowledgeBaseId?.trim())
}

function isMemoryFollowupCandidate(text: string) {
  if (text.length < 24) return false
  return [
    '偏好',
    '习惯',
    '以后',
    '下次',
    '记住',
    '希望',
    '喜欢',
    '倾向',
    '固定',
  ].some(term => text.includes(term))
}

function isListOrPlanLike(text: string) {
  return /(^|\n)\s*(?:[-*]|\d+[.、])\s*\S/.test(text)
    || ['步骤', '清单', '计划', '下一步', '待办', 'todo'].some(term => text.toLowerCase().includes(term))
}

function buildMemoryFollowup(): CapabilityFollowup {
  return {
    id: 'remember-key-point',
    label: '记住要点',
    title: '把这条回答放入输入框，确认后后台整理为记忆',
    icon: Brain,
    kind: 'remember',
  }
}

function buildKnowledgeFollowup(): CapabilityFollowup {
  const knowledgeBaseName = props.artifactKnowledgeBaseName?.trim() || '资料库'
  if (saveKnowledgeErrorMessage.value) {
    return {
      id: 'retry-save-as-knowledge',
      label: '重试存资料',
      title: `上次失败：${saveKnowledgeErrorMessage.value}。重试存入 ${knowledgeBaseName}`,
      icon: BookOpenCheck,
      kind: 'save-knowledge',
    }
  }
  return {
    id: 'save-as-knowledge',
    label: '存为资料',
    title: `存入 ${knowledgeBaseName}`,
    icon: BookOpenCheck,
    kind: 'save-knowledge',
  }
}

function buildExecutionFollowup(): CapabilityFollowup | null {
  if (successfulExecutions.value.length === 0 || failedExecutionCount.value > 0) {
    return null
  }

  const signals = completedExecutionSignals()
  if (signals.hasKnowledge || signals.hasResearch) {
    return {
      id: signals.hasKnowledge ? 'distill-knowledge-result' : 'distill-research-result',
      label: '提炼结论',
      title: signals.hasKnowledge
        ? '基于已读取的资料，整理结论、依据、风险和下一步'
        : '基于已查到的资料，整理结论、依据、风险和下一步',
      icon: signals.hasKnowledge ? BookOpenCheck : Globe2,
      kind: 'prompt',
      prompt: signals.hasKnowledge
        ? '基于上一条回答和已经读取的资料，整理成结论、依据、风险和下一步。'
        : '基于上一条回答和已经查到的资料，整理成结论、依据、风险和下一步。',
    }
  }

  if (fileArtifactRefs.value.length > 0 || imageArtifactRefs.value.length > 0 || signals.hasFileWork) {
    return {
      id: 'continue-output-work',
      label: '处理产物',
      title: '基于本轮生成的文件或产物继续检查、完善或转换',
      icon: FileText,
      kind: 'prompt',
      prompt: '基于上一条回答和本轮生成的文件或产物，继续检查、完善或转换，并说明下一步处理建议。',
    }
  }

  if (signals.hasValidation || signals.hasRepository) {
    return {
      id: 'continue-validation-work',
      label: '继续验证',
      title: '基于验证或仓库处理结果继续定位问题并给出复验步骤',
      icon: Terminal,
      kind: 'prompt',
      prompt: '基于上一条回答和本轮验证结果，继续定位问题、给出修复步骤，并列出需要复验的命令或检查点。',
    }
  }

  if (signals.hasSkill) {
    return {
      id: 'continue-skill-work',
      label: '按技能继续',
      title: '基于已经加载或执行的技能继续完成下一步',
      icon: Sparkles,
      kind: 'prompt',
      prompt: '基于上一条回答和已经加载或执行的技能，继续完成下一步，并说明需要保留的上下文。',
    }
  }

  if (signals.hasWorkflow || signals.hasIntegration || signals.hasAgent || signals.hasSchedule) {
    return {
      id: 'continue-orchestration-work',
      label: '推进下一步',
      title: '基于已完成的流程、连接器或协作结果继续推进',
      icon: Play,
      kind: 'prompt',
      prompt: '基于上一条回答和本轮已经完成的步骤，继续推进下一步，并说明需要我确认的事项。',
    }
  }

  if (signals.hasMemoryWork || signals.hasModel) {
    return {
      id: 'continue-analysis-work',
      label: '继续分析',
      title: '基于本轮处理结果继续分析下一步',
      icon: Sparkles,
      kind: 'prompt',
      prompt: '基于上一条回答和本轮处理结果，继续分析下一步，并给出可执行建议。',
    }
  }

  return {
    id: 'continue-task',
    label: '继续处理',
    title: '接着本轮结果继续处理剩余步骤',
    icon: Play,
    kind: 'prompt',
    prompt: '基于上一条回答和本轮已经完成的步骤，继续处理剩余部分。',
  }
}

const capabilityFollowups = computed<CapabilityFollowup[]>(() => {
  if (
    props.message.role !== 'assistant'
    || props.streaming
    || !(props.isLastAssistant ?? false)
    || isResumableAssistantTurn.value
  ) {
    return []
  }

  const text = normalizedDisplayContent.value
  if (!text) return []

  const actions: CapabilityFollowup[] = []
  const memoryAlreadySettled = memoryChanges.value.length > 0 || props.message.memoryChangeStatus === 'settled'
  const memoryReadyForManualSettle = props.message.memoryChangeStatus !== 'checking'
  const canSuggestMemory = !memoryAlreadySettled && memoryReadyForManualSettle && isMemoryFollowupCandidate(text)
  const canSuggestKnowledge = hasKnowledgeSaveTarget() && !hasSavedKnowledgeTarget() && text.length >= 80

  if (canSuggestKnowledge) {
    actions.push(buildKnowledgeFollowup())
  } else if (canSuggestMemory) {
    actions.push(buildMemoryFollowup())
  }

  const executionFollowup = buildExecutionFollowup()
  if (actions.length < 2 && executionFollowup) {
    actions.push(executionFollowup)
  }

  if (actions.length < 2 && isListOrPlanLike(text)) {
    actions.push({
      id: 'make-checklist',
      label: '整理成清单',
      title: '把上一条回答整理成可执行清单',
      icon: ListChecks,
      kind: 'prompt',
      prompt: '把上一条回答整理成可以执行的清单。',
    })
  }

  if (actions.length < 2 && text.length >= 140) {
    actions.push({
      id: 'continue-next-step',
      label: '继续下一步',
      title: '基于上一条回答继续给出下一步',
      icon: Play,
      kind: 'prompt',
      prompt: '基于上一条回答，继续给我下一步行动建议。',
    })
  }

  return actions.slice(0, 2)
})

function handleCapabilityFollowup(action: CapabilityFollowup) {
  if (action.kind === 'remember') {
    emit('remember', props.message)
    return
  }
  if (action.kind === 'save-knowledge') {
    emit('save-knowledge', props.message)
    return
  }
  if (action.prompt) {
    emit('follow-up', action.prompt)
  }
}

const activeReactSteps = computed<ReactStepDto[]>(() => {
  if (props.streaming && props.streamingReactSteps?.length) {
    return props.streamingReactSteps
  }
  return props.message.reactSteps ?? []
})

const activeReasoningEvents = computed<ReasoningEvent[]>(() => {
  if (props.streaming && props.streamingReasoningEvents?.length) {
    return props.streamingReasoningEvents
  }
  return props.message.reasoningEvents ?? []
})

const hasVisibleTaskSignal = computed(() =>
  activeReasoningEvents.value.some(isVisibleReasoningEvent)
  || activeReactSteps.value.some(isVisibleReactStep),
)

const hasVisibleStreamingTaskSignal = computed(() => (
  props.streaming
  && hasVisibleTaskSignal.value
))

const hasVisibleStreamingOutput = computed(() => (
  displayContent.value.trim().length > 0
  || pendingApprovals.value.length > 0
  || visibleA2uiComponents.value.length > 0
  || (imageArtifactRefs.value.length + fileArtifactRefs.value.length) > 0
))

const showStreamingWarmupStatus = computed(() => (
  props.streaming
  && !hasVisibleStreamingTaskSignal.value
  && !hasVisibleStreamingOutput.value
))

const showThinkingIndicator = computed(() => {
  if (props.streaming) {
    return hasVisibleStreamingTaskSignal.value
  }
  return hasVisibleTaskSignal.value
})

function readReasoningToolId(event: ReasoningEvent) {
  const value = event.extra?.toolId ?? event.extra?.tool_id ?? event.extra?.name
  return typeof value === 'string' ? value : undefined
}

function isVisibleReasoningEvent(event: ReasoningEvent) {
  if (event.type === 'TOOL_CALL') {
    return isVisibleExecutionStep(readReasoningToolId(event), event.toolName || event.title)
  }
  if (event.type === 'PROGRESS') {
    return !!normalizeTurnStatusText(event.description)
  }
  return false
}

function isVisibleReactStep(step: ReactStepDto) {
  if (step.type === 'TOOL_CALL') {
    return isVisibleExecutionStep(step.toolId, step.toolName)
  }
  if (step.type === 'PROGRESS') {
    return !!normalizeTurnStatusText((step as { content?: string }).content)
  }
  return false
}

const activePermissionApprovals = computed<Record<string, PermissionApprovalRequest>>(() => ({
  ...(props.message.permissionApprovals ?? {}),
  ...(props.streamingPermissionApprovals ?? {}),
}))

const activePermissionApprovalResolutions = computed<Record<string, 'approved' | 'rejected' | 'expired'>>(() => ({
  ...(props.message.permissionApprovalResolutions ?? {}),
  ...(props.streamingPermissionApprovalResolutions ?? {}),
}))

const pendingApprovals = computed(() =>
  Object.entries(activePermissionApprovals.value)
    .filter(([requestId]) => !activePermissionApprovalResolutions.value[requestId]),
)

const approvalLogs = computed<PermissionApprovalLog[]>(() => {
  const logs = [...(props.message.permissionApprovalLogs ?? [])]

  for (const [requestId, request] of Object.entries(activePermissionApprovals.value)) {
    const resolution = activePermissionApprovalResolutions.value[requestId]
    if (!resolution) {
      continue
    }
    logs.push({
      requestId,
      toolId: request.toolId,
      toolName: request.toolName,
      actionType: request.actionType,
      resolution,
      subjectType: request.recommendedSubjectType ?? undefined,
      reason: null,
      timestamp: request.timestamp,
    })
  }

  return logs
})

const hasNonApprovalAssistantBody = computed(() => (
  !!displayContent.value
  || imageAttachments.value.length > 0
  || fileAttachments.value.length > 0
  || audioAttachments.value.length > 0
  || (imageArtifactRefs.value.length + fileArtifactRefs.value.length) > 0
  || visibleA2uiComponents.value.length > 0
  || toolSummaries.value.length > 0
  || kbSources.value.length > 0
  || memorySources.value.length > 0
  || contextUsageItems.value.length > 0
  || memoryChanges.value.length > 0
  || showMemoryChangePanel.value
  || !!taskExecutionSummary.value
  || showRecoveryPrompt.value
  || hasVisibleTaskSignal.value
))

const isApprovalOnlyAssistant = computed(() => (
  props.message.role === 'assistant'
  && !hasNonApprovalAssistantBody.value
  && (pendingApprovals.value.length > 0 || approvalLogs.value.length > 0)
))

const isSilentAssistantMessage = computed(() =>
  props.message.role === 'assistant'
  && !props.streaming
  && !hasNonApprovalAssistantBody.value
  && pendingApprovals.value.length === 0
  && approvalLogs.value.length === 0,
)

const assistantBubbleClass = computed(() => {
  if (isApprovalOnlyAssistant.value) {
    return 'w-fit max-w-full rounded-none border-none bg-transparent px-0 py-0 text-foreground shadow-none'
  }

  return [
    'assistant-bubble px-0 py-0 text-foreground',
    props.streaming
      ? 'assistant-bubble-streaming'
      : 'assistant-bubble-idle',
  ].join(' ')
})

const userBubbleClass = computed(() => [
  'user-bubble rounded-2xl px-xl py-md text-foreground',
  'group-hover/message:-translate-y-0.5 group-hover/message:shadow-[0_14px_24px_-20px_hsl(var(--shadow-color)/0.26)]',
].join(' '))

function approvalLogTone(log: PermissionApprovalLog) {
  if (log.resolution === 'approved') {
    return 'border-emerald-200/80 bg-emerald-50/90 text-emerald-700'
  }
  if (log.resolution === 'expired') {
    return 'border-amber-200/80 bg-amber-50/90 text-amber-700'
  }
  return 'border-slate-200 bg-slate-50 text-slate-600'
}
</script>

<template>
  <div
    v-if="!isSilentAssistantMessage"
    class="group/message w-full"
    :class="message.role === 'user' ? 'flex flex-col items-end' : ''"
  >
    <div
      class="space-y-xs"
      :class="message.role === 'user' ? 'w-full flex flex-col items-end' : 'min-w-0'"
    >

      <div
        class="relative transition-all duration-200"
        :class="[
          message.role === 'user'
            ? (isEditing ? 'w-full overflow-visible' : 'w-fit overflow-hidden shadow-sm max-w-[65%]')
            : 'max-w-full overflow-visible',
          message.role === 'user' ? (isEditing ? '' : userBubbleClass) : assistantBubbleClass,
        ]"
      >
        <div class="relative z-[1]">
          <!-- 用户消息：编辑模式 / 展示模式 -->
          <template v-if="message.role === 'user'">
            <div v-if="isEditing" class="edit-container rounded-2xl border border-border/60 bg-background px-xl py-md shadow-sm">
              <textarea
                ref="editTextareaRef"
                v-model="editContent"
                class="w-full max-h-[50vh] resize-none overflow-y-auto border-none bg-transparent text-[15px] leading-relaxed text-foreground outline-none placeholder:text-muted-foreground/50"
                rows="1"
                @input="onEditInput"
                @keydown="onEditKeydown"
              />
              <div class="mt-sm flex items-center justify-end gap-sm">
                <button
                  type="button"
                  class="rounded-full px-lg py-xs text-sm text-muted-foreground transition-colors hover:bg-muted/60"
                  @click="cancelEditing"
                >
                  取消
                </button>
                <button
                  type="button"
                  class="rounded-full bg-primary px-lg py-xs text-sm text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-40"
                  :disabled="!editContent.trim()"
                  @click="submitEdit"
                >
                  发送
                </button>
              </div>
            </div>
            <p
              v-else
              class="whitespace-pre-wrap text-[15px] leading-relaxed"
              v-html="(message as any).highlightedContent ?? message.content"
            />
            <div
              v-if="contextUsageItems.length > 0"
              class="context-usage context-usage--user"
              aria-label="本轮上下文选择"
            >
              <span
                v-for="item in contextUsageItems"
                :key="item.id"
                class="context-usage__item"
                :class="{
                  'context-usage__item--success': item.tone === 'success',
                  'context-usage__item--warning': item.tone === 'warning',
                  'context-usage__item--checking': item.tone === 'checking',
                }"
              >
                <component :is="item.icon" class="context-usage__icon" />
                <span class="context-usage__label">
                  {{ item.label }}
                  <span v-if="item.detail" class="context-usage__detail">{{ item.detail }}</span>
                </span>
              </span>
            </div>
          </template>

          <template v-else>
            <div
              v-if="pendingApprovals.length > 0"
              class="mb-2 flex flex-col gap-2"
            >
              <PermissionApprovalBubble
                v-for="[requestId, request] in pendingApprovals"
                :key="requestId"
                :request="request"
                :resolved="!!activePermissionApprovalResolutions[requestId]"
                :resolution="activePermissionApprovalResolutions[requestId]"
                @resolve="(resolution: 'approved' | 'rejected' | 'expired', subjectType?: string) => emit('permission-approval-resolve', requestId, resolution, subjectType)"
              />
            </div>

            <div
              v-if="approvalLogs.length > 0"
              class="mb-2 flex max-w-full flex-wrap gap-1.5"
            >
              <div
                v-for="log in approvalLogs"
                :key="`${log.requestId}-${log.resolution}`"
                class="inline-flex max-w-full items-center gap-1.5 rounded-full border px-2.5 py-1 text-[11px] font-medium shadow-sm"
                :class="approvalLogTone(log)"
              >
                <span
                  class="size-1.5 shrink-0 rounded-full"
                  :class="log.resolution === 'approved'
                    ? 'bg-emerald-500'
                    : (log.resolution === 'expired' ? 'bg-amber-500' : 'bg-slate-400')"
                />
                <span class="truncate">{{ buildPermissionApprovalLog(log) }}</span>
              </div>
            </div>

            <div
              v-if="taskExecutionSummary"
              class="task-execution-summary"
              :class="`task-execution-summary--${taskExecutionSummary.tone}`"
              aria-label="本轮任务执行状态"
            >
              <span class="task-execution-summary__icon" aria-hidden="true">
                <component :is="taskExecutionSummary.icon" class="size-3.5" />
              </span>
              <span class="task-execution-summary__body">
                <span class="task-execution-summary__title">{{ taskExecutionSummary.title }}</span>
                <span class="task-execution-summary__detail">{{ taskExecutionSummary.detail }}</span>
                <span
                  v-if="capabilityWarningRepairText"
                  class="task-execution-summary__next"
                >
                  {{ capabilityWarningRepairText }}
                </span>
              </span>
              <RouterLink
                v-if="showCapabilityWarningRepairLink"
                :to="capabilityWarningRepairRoute"
                class="task-execution-summary__action"
                title="打开能力中心，检查工具、技能状态和 Skill 引用"
                aria-label="打开能力中心，检查工具、技能状态和 Skill 引用"
                :data-capability-missing="capabilityWarningRepairMissing"
                :data-capability-skill="capabilityWarningRepairSkill"
              >
                <ArrowUpRight class="size-3.5" />
                <span>能力中心</span>
              </RouterLink>
            </div>

            <div
              v-if="showStreamingWarmupStatus"
              class="streaming-warmup-status"
              role="status"
              aria-live="polite"
            >
              <span class="streaming-warmup-status__dot" />
              <span>已收到，正在处理…</span>
            </div>

            <ThinkingIndicator
              v-if="showThinkingIndicator"
              :reasoning-events="activeReasoningEvents"
              :react-steps="activeReactSteps"
              :streaming="streaming"
              @show-trace="emit('show-trace', message.id)"
            />

            <StreamingText
              v-if="displayContent"
              :content="displayContent"
              :streaming="streaming"
            />

            <div
              v-if="contextUsageItems.length > 0"
              class="context-usage"
              aria-label="本轮上下文和执行摘要"
            >
              <template v-for="item in contextUsageItems" :key="item.id">
                <RouterLink
                  v-if="item.knowledgeRoute"
                  :to="item.knowledgeRoute"
                  class="context-usage__item context-usage__item--button"
                  :class="{
                    'context-usage__item--success': item.tone === 'success',
                    'context-usage__item--warning': item.tone === 'warning',
                    'context-usage__item--checking': item.tone === 'checking',
                  }"
                  :title="contextUsageActionTitle(item)"
                  :aria-label="contextUsageActionTitle(item)"
                >
                  <component :is="item.icon" class="context-usage__icon" />
                  <span class="context-usage__label">
                    {{ item.label }}
                    <span v-if="item.detail" class="context-usage__detail">{{ item.detail }}</span>
                  </span>
                </RouterLink>
                <button
                  v-else-if="item.inspectMemorySource || item.showTrace || item.outputTarget || item.memoryChangeTarget"
                  type="button"
                  class="context-usage__item context-usage__item--button"
                  :class="{
                    'context-usage__item--success': item.tone === 'success',
                    'context-usage__item--warning': item.tone === 'warning',
                    'context-usage__item--checking': item.tone === 'checking',
                  }"
                  :title="contextUsageActionTitle(item)"
                  :aria-label="contextUsageActionTitle(item)"
                  @click="handleContextUsageItemClick(item)"
                >
                  <component :is="item.icon" class="context-usage__icon" />
                  <span class="context-usage__label">
                    {{ item.label }}
                    <span v-if="item.detail" class="context-usage__detail">{{ item.detail }}</span>
                  </span>
                </button>
                <span
                  v-else
                  class="context-usage__item"
                  :class="{
                    'context-usage__item--success': item.tone === 'success',
                    'context-usage__item--warning': item.tone === 'warning',
                    'context-usage__item--checking': item.tone === 'checking',
                  }"
                >
                  <component :is="item.icon" class="context-usage__icon" />
                  <span class="context-usage__label">
                    {{ item.label }}
                    <span v-if="item.detail" class="context-usage__detail">{{ item.detail }}</span>
                  </span>
                </span>
              </template>
            </div>

            <div
              v-if="capabilityFollowups.length > 0"
              class="capability-followups"
              aria-label="可以接着做"
            >
              <span class="capability-followups__prefix">可以接着</span>
              <button
                v-for="action in capabilityFollowups"
                :key="action.id"
                type="button"
                class="capability-followups__action"
                :title="action.title"
                :aria-label="action.title"
                @click="handleCapabilityFollowup(action)"
              >
                <component :is="action.icon" class="capability-followups__icon" />
                <span>{{ action.label }}</span>
              </button>
            </div>

            <div
              v-if="showRecoveryPrompt"
              class="recovery-prompt"
              role="status"
            >
              <div class="recovery-prompt__body">
                <div class="recovery-prompt__title">{{ recoveryPromptTitle }}</div>
                <div class="recovery-prompt__detail">{{ recoveryPromptDetail }}</div>
                <div
                  v-if="recoveryContinuityText"
                  class="recovery-prompt__continuity"
                  aria-label="恢复说明"
                >
                  <CheckCircle2 class="size-3.5" />
                  <span>{{ recoveryContinuityText }}</span>
                </div>
                <div
                  v-if="recoveryNextStepText"
                  class="recovery-prompt__next-step"
                  aria-label="建议下一步"
                >
                  <Play class="size-3.5" />
                  <span>{{ recoveryNextStepText }}</span>
                </div>
                <div
                  v-if="recoveryRetainedContext.length"
                  class="recovery-retained-context"
                  aria-label="继续时保留"
                >
                  <span class="recovery-retained-context__title">继续时保留</span>
                  <span
                    v-for="item in recoveryRetainedContext"
                    :key="item"
                    class="recovery-retained-context__item"
                  >
                    {{ item }}
                  </span>
                </div>
                <div
                  v-if="recoveryActionStrategyText"
                  class="recovery-prompt__strategy"
                  aria-label="恢复动作说明"
                >
                  {{ recoveryActionStrategyText }}
                </div>
                <div
                  v-if="recoverySnapshotLines.length"
                  class="recovery-checkpoint"
                  aria-label="卡住位置"
                >
                  <div class="recovery-checkpoint__title">{{ recoverySnapshotTitle }}</div>
                  <div
                    v-for="line in recoverySnapshotLines"
                    :key="line.label"
                    class="recovery-checkpoint__line"
                  >
                    <span class="recovery-checkpoint__label">{{ line.label }}</span>
                    <span class="recovery-checkpoint__value">{{ line.value }}</span>
                  </div>
                </div>
                <div
                  v-if="recoveryNextActions.length"
                  class="recovery-next-actions"
                  aria-label="续接计划"
                >
                  <div class="recovery-next-actions__title">接下来</div>
                  <ol class="recovery-next-actions__list">
                    <li
                      v-for="(action, index) in recoveryNextActions"
                      :key="action"
                      class="recovery-next-actions__item"
                    >
                      <span class="recovery-next-actions__index">{{ index + 1 }}</span>
                      <span class="recovery-next-actions__text">{{ action }}</span>
                    </li>
                  </ol>
                </div>
              </div>
              <div class="recovery-prompt__actions">
                <button
                  type="button"
                  class="recovery-prompt__button recovery-prompt__button--quiet"
                  :title="recoveryCopyAriaLabel"
                  :aria-label="recoveryCopyAriaLabel"
                  @click="handleCopyRecoverySnapshot"
                >
                  <Check v-if="recoveryCopied" class="size-3.5" />
                  <Copy v-else class="size-3.5" />
                  <span>{{ recoveryCopied ? '已复制' : recoveryCopyLabel }}</span>
                </button>
                <span v-if="recoveryStatusLabel" class="recovery-prompt__status">
                  {{ recoveryStatusLabel }}
                </span>
                <RouterLink
                  v-if="showCapabilityRepairLink"
                  :to="recoveryCapabilityRepairRoute"
                  class="recovery-prompt__button recovery-prompt__button--quiet"
                  title="打开能力中心，检查工具、技能状态和 Skill 引用"
                  aria-label="打开能力中心，检查工具、技能状态和 Skill 引用"
                  :data-capability-missing="recoveryCapabilityRepairMissing"
                  :data-capability-skill="recoveryCapabilityRepairSkill"
                  :data-return-session-id="recoveryReturnSessionId || undefined"
                  :data-return-turn-id="message.turnId || undefined"
                  :data-return-entry-id="message.id || undefined"
                >
                  <ArrowUpRight class="size-3.5" />
                  <span>能力中心</span>
                </RouterLink>
                <button
                  v-if="recoveryCanResume"
                  type="button"
                  class="recovery-prompt__button recovery-prompt__button--primary"
                  :title="recoveryResumeAriaLabel"
                  :aria-label="recoveryResumeAriaLabel"
                  @click="handleRecoveryPromptResume"
                >
                  <Play class="size-3.5" />
                  {{ recoveryActionLabel }}
                </button>
                <button
                  v-if="recoveryCanRestart"
                  type="button"
                  class="recovery-prompt__button"
                  :title="recoveryRestartAriaLabel"
                  :aria-label="recoveryRestartAriaLabel"
                  @click="handleRecoveryPromptRestart"
                >
                  <RefreshCw class="size-3.5" />
                </button>
              </div>
            </div>

            <!-- 图片产物：内嵌消息流，点击 lightbox 预览 -->
            <div
              v-if="imageArtifactRefs.length > 0"
              ref="imageOutputPanelRef"
              class="message-output-panel mt-3 flex flex-col gap-sm"
              tabindex="-1"
              aria-label="本轮生成的图片产物"
            >
              <ArtifactCard
                v-for="ref in imageArtifactRefs"
                :key="ref.artifactId"
                :artifact-id="ref.artifactId"
                :file-name="ref.fileName"
                :mime-type="ref.mimeType"
                :kind="ref.kind"
                :size="ref.size"
                :download-url="ref.downloadUrl"
                :knowledge-base-id="artifactKnowledgeBaseId"
                :knowledge-base-name="artifactKnowledgeBaseName"
                :saved-knowledge-base-name="savedKnowledgeBaseNameForArtifact(ref.artifactId)"
                :persist-knowledge-settlement="persistArtifactKnowledgeSettlement"
                @preview="(url: string) => { previewImageUrl = url; showImagePreview = true }"
                @saved-knowledge="handleArtifactSavedToKnowledge"
              />
            </div>

            <div v-if="imageAttachments.length > 0" class="mt-3 grid grid-cols-2 gap-sm">
              <button
                v-for="attachment in imageAttachments"
                :key="attachment.fileId"
                type="button"
                class="relative w-full overflow-hidden rounded-lg border border-border/50 transition-shadow hover:shadow-md focus:ring-2 focus:ring-ring focus:ring-offset-2 focus:outline-none"
                @click="previewImageUrl = attachment.url; showImagePreview = true"
              >
                <img :src="attachment.url" :alt="attachment.filename" class="block h-40 w-full object-cover" loading="lazy" />
              </button>
            </div>

            <div
              v-if="visibleA2uiComponents.length"
              ref="interactiveOutputPanelRef"
              class="message-output-panel mt-3 overflow-hidden rounded-[calc(var(--radius)+4px)] border border-border/70 bg-background/80 text-sm text-foreground shadow-sm"
              tabindex="-1"
              aria-label="本轮生成的交互结果"
            >
              <div class="flex items-center gap-2 border-b border-border px-3 py-2">
                <span class="text-xs font-medium text-muted-foreground">{{ a2uiPanelLabel }}</span>
              </div>
              <div class="p-3">
                <A2uiRenderer
                  :components="visibleA2uiComponents"
                  :message-id="message.id"
                  :trace-id="message.traceId"
                  :streaming="streaming"
                />
              </div>
            </div>

            <div v-if="kbSources.length" class="mt-3 flex flex-wrap gap-1.5">
              <KbSourceTag
                v-for="(source, index) in kbSources"
                :key="source.id"
                :source="source"
                :index="index"
              />
            </div>

            <div v-if="memorySources.length" class="mt-2 flex flex-wrap gap-1.5">
              <MemorySourceTag
                v-for="(source, index) in visibleMemorySources"
                :key="source.id"
                :source="source"
                :index="index"
                @inspect="inspectMemory"
              />
              <button
                v-if="hiddenMemorySourceCount > 0"
                type="button"
                class="memory-source-more"
                :title="`展开剩余 ${hiddenMemorySourceCount} 条${memoryReferenceNoun(memorySources)}`"
                :aria-label="`展开剩余 ${hiddenMemorySourceCount} 条${memoryReferenceNoun(memorySources)}`"
                @click="expandMemorySources"
              >
                +{{ hiddenMemorySourceCount }} 条
              </button>
            </div>

            <div
              v-if="showMemoryChangePanel"
              ref="memoryChangePanelRef"
              class="memory-change-panel"
              :class="{
                'memory-change-panel--checking': isMemoryChangeChecking,
                'memory-change-panel--failed': isMemoryChangeFailed,
                'memory-change-panel--disabled': isMemoryChangeDisabled,
              }"
              tabindex="-1"
              :aria-label="memoryChangePanelAriaLabel"
            >
              <div class="memory-change-panel__header">
                <div class="memory-change-panel__heading">
                  <span class="memory-change-panel__title">
                    <LoaderCircle
                      v-if="isMemoryChangeChecking"
                      class="memory-change-panel__spinner"
                      aria-hidden="true"
                    />
                    {{ memoryChangePanelTitle }}
                  </span>
                  <span v-if="memoryChangePanelCount" class="memory-change-panel__count">
                    {{ memoryChangePanelCount }}
                  </span>
                </div>
                <div class="memory-change-panel__actions">
                  <button
                    v-if="showMemoryChangeExplanationToggle"
                    type="button"
                    class="memory-change-panel__explain"
                    :title="`${showMemoryChangeExplanation ? '收起' : '展开'}${memoryChangeExplanationName}`"
                    :aria-label="`${showMemoryChangeExplanation ? '收起' : '展开'}${memoryChangeExplanationName}`"
                    :aria-expanded="showMemoryChangeExplanation"
                    @click="showMemoryChangeExplanation = !showMemoryChangeExplanation"
                  >
                    <component
                      :is="showMemoryChangeExplanation ? ChevronDown : ChevronRight"
                      class="size-3.5"
                    />
                    <span>为什么</span>
                  </button>
                  <button
                    v-if="!isMemoryChangeChecking && firstMemoryChange"
                    type="button"
                    class="memory-change-panel__inspect"
                    :title="memoryChangeInspectActionLabel"
                    :aria-label="memoryChangeInspectActionLabel"
                    @click="inspectFirstMemoryChange"
                  >
                    <Pencil class="size-3.5" />
                    <span>查看/调整</span>
                  </button>
                  <button
                    v-if="showManualMemorySettleAction"
                    type="button"
                    class="memory-change-panel__remember"
                    :title="memorySettleTitle"
                    :aria-label="memorySettleTitle"
                    @click="emit('remember', message)"
                  >
                    <Brain class="size-3.5" />
                    <span>手动记住</span>
                  </button>
                  <RouterLink
                    v-if="showMemoryManagementLink"
                    :to="memoryManagementRoute"
                    class="memory-change-panel__manage"
                    title="打开记忆管理"
                    aria-label="打开记忆管理"
                  >
                    <ArrowUpRight class="size-3.5" />
                    <span>记忆管理</span>
                  </RouterLink>
                </div>
              </div>
              <p v-if="showMemoryChangeExplanationBody && memoryChangePanelDetail" class="memory-change-panel__detail">
                {{ memoryChangePanelDetail }}
              </p>
              <p v-if="showMemoryChangeExplanationBody && memoryChangePanelImpact" class="memory-change-panel__impact">
                {{ memoryChangePanelImpactLabel }}：{{ memoryChangePanelImpact }}
              </p>
              <div
                v-if="!isMemoryChangeChecking && !isMemoryChangeFailed && !isMemoryChangeDisabled && memoryChanges.length > 0"
                class="memory-change-panel__items"
              >
                <MemorySourceTag
                  v-for="(source, index) in visibleMemoryChanges"
                  :key="source.id"
                  :source="source"
                  :index="index"
                  variant="change"
                  @inspect="inspectMemory"
                />
                <button
                  v-if="hiddenMemoryChangeCount > 0"
                  type="button"
                  class="memory-source-more"
                  :title="`展开剩余 ${hiddenMemoryChangeCount} 条本轮${memoryChangeNoun}`"
                  :aria-label="`展开剩余 ${hiddenMemoryChangeCount} 条本轮${memoryChangeNoun}`"
                  @click="expandMemoryChanges"
                >
                  +{{ hiddenMemoryChangeCount }} 条
                </button>
              </div>
            </div>

          </template>

          <div v-if="message.role === 'user' && imageAttachments.length > 0" class="mt-3 grid grid-cols-2 gap-sm">
            <button
              v-for="attachment in imageAttachments"
              :key="attachment.fileId"
              type="button"
              class="list-card relative w-full overflow-hidden rounded-lg focus:ring-2 focus:ring-ring focus:ring-offset-2 focus:outline-none"
              @click="previewImageUrl = attachment.url; showImagePreview = true"
            >
              <img :src="attachment.url" :alt="attachment.filename" class="block h-32 w-full object-cover" loading="lazy" />
            </button>
          </div>

          <div v-if="audioAttachments.length > 0" class="mt-3 flex flex-col gap-sm">
            <div
              v-for="attachment in audioAttachments"
              :key="attachment.fileId"
              class="list-card flex items-center gap-2 px-3 py-2 text-xs text-foreground"
            >
              <Mic class="size-3.5 shrink-0 text-muted-foreground" />
              <span class="shrink-0 text-muted-foreground">语音</span>
              <audio :src="attachment.url" controls class="h-8 w-full min-w-0" />
            </div>
          </div>

          <div v-if="fileAttachments.length > 0" class="mt-md flex flex-col gap-sm">
            <template v-for="attachment in fileAttachments" :key="attachment.fileId">
              <!-- 视频附件：保留原 list-card + <video> 播放器布局 -->
              <div
                v-if="attachment.type?.startsWith('video/')"
                class="list-card flex flex-col gap-sm px-md py-md text-xs text-foreground"
              >
                <div class="flex items-center justify-between gap-sm">
                  <span class="truncate">{{ attachment.filename }}</span>
                  <span class="shrink-0 text-xs text-muted-foreground">
                    {{ (attachment.size / 1024).toFixed(1) }} KB
                  </span>
                </div>
                <video
                  :src="attachment.url"
                  controls
                  class="mt-xs w-full max-w-full max-h-[360px] rounded-lg"
                >
                  当前浏览器不支持视频播放
                </video>
              </div>

              <!-- 文档附件：图标 + 「AI 可读取」徽标 + 下载链接 -->
              <div
                v-else
                class="flex items-center gap-sm rounded-md border border-border bg-muted/40 p-sm"
              >
                <component :is="documentIcon(attachment)" class="h-md w-md shrink-0 text-muted-foreground" />
                <div class="min-w-0 flex-1">
                  <div class="flex items-center gap-xs">
                    <span class="truncate text-sm">{{ attachment.filename }}</span>
                    <span
                      v-if="isParseableDocument(attachment)"
                      class="shrink-0 rounded-md bg-primary/10 px-xs py-xs text-xs text-primary"
                      title="AI 可直接读取此文档内容"
                    >AI 可读取</span>
                  </div>
                  <div class="text-xs text-muted-foreground">
                    {{ (attachment.size / 1024).toFixed(1) }} KB
                  </div>
                </div>
                <a
                  :href="attachment.url"
                  :download="attachment.filename"
                  class="shrink-0 text-xs text-primary hover:underline"
                >下载</a>
              </div>
            </template>
          </div>

          <!-- 非图片产物（附件卡片） -->
          <div
            v-if="fileArtifactRefs.length > 0"
            ref="fileOutputPanelRef"
            class="message-output-panel mt-md flex flex-col gap-sm"
            tabindex="-1"
            aria-label="本轮生成的文件产物"
          >
            <ArtifactCard
              v-for="ref in fileArtifactRefs"
              :key="ref.artifactId"
              :artifact-id="ref.artifactId"
              :file-name="ref.fileName"
              :mime-type="ref.mimeType"
              :kind="ref.kind"
              :size="ref.size"
              :download-url="ref.downloadUrl"
              :knowledge-base-id="artifactKnowledgeBaseId"
              :knowledge-base-name="artifactKnowledgeBaseName"
              :saved-knowledge-base-name="savedKnowledgeBaseNameForArtifact(ref.artifactId)"
              :persist-knowledge-settlement="persistArtifactKnowledgeSettlement"
              @preview="(url: string) => { previewImageUrl = url; showImagePreview = true }"
              @saved-knowledge="handleArtifactSavedToKnowledge"
            />
          </div>
        </div>
      </div>

      <template v-if="message.role === 'assistant' && !streaming">
        <div class="message-actions-row flex items-center gap-0.5 pl-xs">
          <MessageActions
            :message="message"
            :is-last-assistant="isLastAssistant ?? false"
            :show-recovery-actions="!showRecoveryPrompt"
            :recovery-resume-action="recoveryPrimaryAction"
            :recovery-restart-action="recoveryRestartAction"
            :knowledge-base-id="artifactKnowledgeBaseId"
            :knowledge-base-name="artifactKnowledgeBaseName"
            :saving-to-knowledge="savingToKnowledge"
            :saved-knowledge-base-name="savedKnowledgeBaseName"
            :save-knowledge-error="saveKnowledgeError"
            @copy="(content: string, success: boolean) => emit('copy', content, success)"
            @remember="(target: Message) => emit('remember', target)"
            @save-knowledge="(target: Message) => emit('save-knowledge', target)"
            @fork="(target: Message) => emit('fork', target)"
            @regenerate="(target: Message) => emit('regenerate', target)"
            @resume="(target: Message, action?: ToolRecoveryAction) => emit('resume', target, action)"
            @restart="(target: Message, action?: ToolRecoveryAction) => emit('restart', target, action)"
          />
          <MessageFeedback
            :message="message"
            @like="(target: Message) => emit('like', target)"
            @dislike="(target: Message, feedback?: string) => emit('dislike', target, feedback)"
          />
        </div>
      </template>

      <!-- 用户消息操作：复制 + 编辑 -->
      <div v-if="message.role === 'user' && message.status !== 'pending' && !isEditing" class="message-actions-row flex items-center justify-end gap-0.5 pr-xs">
        <button
          v-if="canRememberMessage"
          type="button"
          class="user-act-btn"
          :title="memorySettleTitle"
          :aria-label="memorySettleTitle"
          @click="emit('remember', message)"
        >
          <Brain class="size-3.5" />
        </button>
        <button
          type="button"
          class="user-act-btn"
          title="复制"
          @click="handleUserCopy"
        >
          <Check v-if="userCopied" class="size-3.5 text-primary" />
          <Copy v-else class="size-3.5" />
        </button>
        <button
          type="button"
          class="user-act-btn"
          title="编辑"
          @click="startEditing"
        >
          <Pencil class="size-3.5" />
        </button>
      </div>

      <MessageError
        v-if="message.role === 'user' && message.status === 'error'"
        :message="message"
        @retry="(target: Message) => emit('retry', target)"
      />

      <ImageLightbox
        :src="previewImageUrl ?? ''"
        :alt="'预览图片'"
        :open="showImagePreview && !!previewImageUrl"
        @close="showImagePreview = false; previewImageUrl = null"
      />
    </div>

  </div>
</template>

<style scoped>
.assistant-bubble {
  position: relative;
  border: none;
  background: transparent;
  box-shadow: none;
}

.task-execution-summary {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 4px 0 8px;
  padding: 7px 9px;
  border-radius: 10px;
  border: 1px solid hsl(from var(--border) h s l / 0.38);
  background: hsl(from var(--muted) h s l / 0.2);
  color: var(--muted-foreground);
  font-size: 12px;
  line-height: 1.45;
}

.task-execution-summary__icon {
  display: inline-flex;
  width: 20px;
  height: 20px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  background: hsl(from var(--background) h s l / 0.72);
  color: var(--primary);
}

.task-execution-summary__body {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  gap: 4px 8px;
}

.task-execution-summary__title {
  flex: 0 0 auto;
  font-weight: 600;
  color: var(--foreground);
}

.task-execution-summary__detail {
  min-width: 0;
  color: hsl(from var(--muted-foreground) h s l / 0.92);
}

.task-execution-summary__next {
  flex-basis: 100%;
  color: hsl(from var(--muted-foreground) h s l / 0.86);
  font-size: 11px;
  line-height: 1.45;
}

.task-execution-summary__action {
  display: inline-flex;
  min-height: 24px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  gap: 4px;
  margin-left: auto;
  border-radius: 999px;
  border: 1px solid hsl(38 92% 50% / 0.24);
  background: hsl(from var(--background) h s l / 0.68);
  padding: 0 7px;
  color: hsl(34 76% 30%);
  font-size: 11px;
  line-height: 1;
  white-space: nowrap;
  transition:
    border-color 140ms ease,
    background-color 140ms ease,
    color 140ms ease;
}

.task-execution-summary__action:hover {
  border-color: hsl(38 92% 50% / 0.34);
  background: hsl(38 92% 50% / 0.12);
}

.task-execution-summary__action:focus-visible {
  outline: 2px solid hsl(from var(--ring) h s l / 0.65);
  outline-offset: 2px;
}

.task-execution-summary--warning,
.task-execution-summary--waiting {
  border-color: hsl(38 92% 50% / 0.24);
  background: hsl(38 92% 50% / 0.08);
}

.task-execution-summary--warning .task-execution-summary__icon,
.task-execution-summary--waiting .task-execution-summary__icon {
  color: hsl(34 76% 36%);
}

.streaming-warmup-status {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  margin-top: 0.1rem;
  color: hsl(from var(--muted-foreground) h s l / 0.74);
  font-size: 12px;
  line-height: 1.7;
}

.streaming-warmup-status__dot {
  display: inline-block;
  width: 0.38rem;
  height: 0.38rem;
  flex: 0 0 auto;
  border-radius: 999px;
  background: hsl(from var(--primary) h s l / 0.68);
  animation: streaming-dot-pulse 1.4s ease-in-out infinite;
}

.context-usage {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin: 4px 0 8px;
}

.context-usage__item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  max-width: 100%;
  min-height: 22px;
  padding: 1px 2px;
  border-radius: 999px;
  border: 1px solid transparent;
  background: transparent;
  color: hsl(from var(--muted-foreground) h s l / 0.82);
  font-size: 11px;
  line-height: 1.35;
}

.context-usage__item--warning {
  border-color: hsl(38 92% 50% / 0.26);
  background: hsl(38 92% 50% / 0.1);
  color: hsl(34 78% 32%);
}

.context-usage__item--success {
  color: hsl(from var(--foreground) h s l / 0.72);
}

.context-usage__item--success .context-usage__icon {
  color: hsl(160 60% 36%);
}

.context-usage__item--button {
  cursor: pointer;
  transition:
    border-color 140ms var(--ease-fluid),
    background-color 140ms var(--ease-fluid),
    color 140ms var(--ease-fluid);
}

.context-usage__item--button:hover {
  border-color: hsl(from var(--primary) h s l / 0.22);
  background: hsl(from var(--primary) h s l / 0.06);
  color: hsl(from var(--foreground) h s l / 0.86);
}

.context-usage__item--button:focus-visible {
  outline: 2px solid hsl(from var(--ring) h s l / 0.62);
  outline-offset: 2px;
}

.context-usage__item--checking {
  border-color: hsl(from var(--border) h s l / 0.24);
  background: transparent;
  color: hsl(from var(--muted-foreground) h s l / 0.78);
}

.context-usage__item--checking .context-usage__icon {
  animation: context-checking-spin 900ms linear infinite;
}

.message-output-panel:focus-visible {
  outline: 2px solid hsl(from var(--ring) h s l / 0.62);
  outline-offset: 3px;
}

.context-usage__icon {
  width: 12px;
  height: 12px;
  flex: 0 0 auto;
  color: hsl(from var(--primary) h s l / 0.74);
}

.context-usage__label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.context-usage__detail {
  margin-left: 4px;
  color: hsl(from var(--muted-foreground) h s l / 0.68);
}

.capability-followups {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px;
  margin: 2px 0 8px;
  color: hsl(from var(--muted-foreground) h s l / 0.74);
  font-size: 11px;
  line-height: 1.35;
}

.capability-followups__prefix {
  flex: 0 0 auto;
  color: hsl(from var(--muted-foreground) h s l / 0.62);
}

.capability-followups__action {
  display: inline-flex;
  min-height: 24px;
  max-width: 100%;
  align-items: center;
  gap: 4px;
  border-radius: 999px;
  border: 1px solid hsl(from var(--border) h s l / 0.42);
  background: hsl(from var(--background) h s l / 0.72);
  padding: 2px 8px;
  color: hsl(from var(--foreground) h s l / 0.72);
  transition:
    border-color 140ms var(--ease-fluid),
    background-color 140ms var(--ease-fluid),
    color 140ms var(--ease-fluid),
    transform 140ms var(--ease-fluid);
}

.capability-followups__action:hover {
  transform: translateY(-1px);
  border-color: hsl(from var(--primary) h s l / 0.24);
  background: hsl(from var(--primary) h s l / 0.06);
  color: hsl(from var(--foreground) h s l / 0.88);
}

.capability-followups__action:focus-visible {
  outline: 2px solid hsl(from var(--ring) h s l / 0.62);
  outline-offset: 2px;
}

.capability-followups__icon {
  width: 12px;
  height: 12px;
  flex: 0 0 auto;
  color: hsl(from var(--primary) h s l / 0.76);
}

@keyframes context-checking-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .context-usage__item--checking .context-usage__icon {
    animation: none;
  }

  .memory-change-panel__spinner {
    animation: none;
  }

  .streaming-warmup-status__dot {
    animation: none;
  }
}

.recovery-prompt {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-top: 10px;
  padding: 9px 10px;
  border-radius: 10px;
  border: 1px solid hsl(38 92% 50% / 0.24);
  background: hsl(38 92% 50% / 0.08);
  color: hsl(34 76% 30%);
  font-size: 12px;
}

.recovery-prompt__body {
  min-width: 0;
  flex: 1;
}

.recovery-prompt__title {
  font-weight: 600;
  color: hsl(34 76% 25%);
}

.recovery-prompt__detail {
  margin-top: 2px;
  line-height: 1.45;
  color: hsl(from var(--muted-foreground) h s l / 0.92);
}

.recovery-prompt__continuity {
  display: inline-flex;
  max-width: 100%;
  align-items: flex-start;
  gap: 5px;
  margin-top: 7px;
  border-radius: 999px;
  background: hsl(from var(--background) h s l / 0.68);
  padding: 4px 8px;
  color: hsl(from var(--foreground) h s l / 0.74);
  font-size: 11px;
  line-height: 1.45;
}

.recovery-prompt__continuity svg {
  margin-top: 1px;
  flex: 0 0 auto;
  color: hsl(160 60% 34%);
}

.recovery-prompt__continuity span {
  min-width: 0;
  overflow-wrap: anywhere;
}

.recovery-prompt__next-step {
  display: inline-flex;
  max-width: 100%;
  align-items: flex-start;
  gap: 5px;
  margin-top: 6px;
  border-radius: 8px;
  border: 1px solid hsl(38 92% 50% / 0.2);
  background: hsl(from var(--background) h s l / 0.58);
  padding: 5px 8px;
  color: hsl(34 76% 28%);
  font-size: 11px;
  line-height: 1.45;
}

.recovery-prompt__next-step svg {
  margin-top: 1px;
  flex: 0 0 auto;
}

.recovery-prompt__next-step span {
  min-width: 0;
  overflow-wrap: anywhere;
}

.recovery-retained-context {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 7px;
}

.recovery-retained-context__title,
.recovery-retained-context__item {
  display: inline-flex;
  min-height: 22px;
  max-width: 100%;
  align-items: center;
  border-radius: 999px;
  padding: 3px 7px;
  font-size: 11px;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

.recovery-retained-context__title {
  background: hsl(38 92% 50% / 0.12);
  color: hsl(34 76% 30%);
  font-weight: 600;
}

.recovery-retained-context__item {
  border: 1px solid hsl(from var(--border) h s l / 0.52);
  background: hsl(from var(--background) h s l / 0.62);
  color: hsl(from var(--muted-foreground) h s l / 0.94);
}

.recovery-prompt__strategy {
  margin-top: 6px;
  color: hsl(from var(--muted-foreground) h s l / 0.86);
  font-size: 11px;
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.recovery-checkpoint {
  display: grid;
  gap: 0.28rem;
  margin-top: 0.55rem;
  border-top: 1px solid hsl(38 92% 50% / 0.2);
  padding-top: 0.55rem;
}

.recovery-checkpoint__title {
  font-size: 11px;
  font-weight: 600;
  color: hsl(34 76% 28%);
}

.recovery-checkpoint__line {
  display: grid;
  grid-template-columns: 2.5rem minmax(0, 1fr);
  gap: 0.4rem;
  line-height: 1.45;
}

.recovery-checkpoint__label {
  color: hsl(34 62% 36% / 0.92);
  font-weight: 500;
}

.recovery-checkpoint__value {
  min-width: 0;
  color: hsl(from var(--muted-foreground) h s l / 0.95);
  overflow-wrap: anywhere;
}

.recovery-next-actions {
  display: grid;
  gap: 0.35rem;
  margin-top: 0.55rem;
}

.recovery-next-actions__title {
  color: hsl(34 76% 28%);
  font-size: 11px;
  font-weight: 600;
}

.recovery-next-actions__list {
  display: grid;
  gap: 0.28rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.recovery-next-actions__item {
  display: grid;
  grid-template-columns: 1.1rem minmax(0, 1fr);
  align-items: start;
  gap: 0.35rem;
  color: hsl(from var(--muted-foreground) h s l / 0.95);
  font-size: 11px;
  line-height: 1.45;
}

.recovery-next-actions__index {
  display: inline-flex;
  width: 1.1rem;
  height: 1.1rem;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  background: hsl(38 92% 50% / 0.12);
  color: hsl(34 76% 30%);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 600;
}

.recovery-next-actions__text {
  min-width: 0;
  overflow-wrap: anywhere;
}

.recovery-prompt__actions {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 0.4rem;
}

.recovery-prompt__status {
  display: inline-flex;
  min-height: 28px;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  background: hsl(from var(--background) h s l / 0.62);
  padding: 0 9px;
  color: hsl(from var(--muted-foreground) h s l / 0.92);
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
}

.recovery-prompt__button {
  display: inline-flex;
  min-width: 30px;
  height: 28px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border-radius: 999px;
  border: 1px solid hsl(38 92% 50% / 0.22);
  background: hsl(from var(--background) h s l / 0.8);
  padding: 0 9px;
  color: hsl(34 76% 30%);
  font-size: 12px;
  font-weight: 500;
  transition:
    background 120ms ease,
    border-color 120ms ease;
}

.recovery-prompt__button:hover {
  border-color: hsl(38 92% 50% / 0.34);
  background: hsl(38 92% 50% / 0.12);
}

.recovery-prompt__button:focus-visible {
  outline: 2px solid hsl(from var(--ring) h s l / 0.65);
  outline-offset: 2px;
}

.recovery-prompt__button--primary {
  background: hsl(38 92% 50% / 0.14);
}

.recovery-prompt__button--quiet {
  border-color: hsl(from var(--border) h s l / 0.42);
  background: hsl(from var(--background) h s l / 0.62);
  color: hsl(from var(--muted-foreground) h s l / 0.95);
}

.recovery-prompt__button--quiet:hover {
  border-color: hsl(38 92% 50% / 0.26);
  color: hsl(34 76% 30%);
}

@media (max-width: 640px) {
  .recovery-prompt {
    flex-direction: column;
  }

  .recovery-prompt__actions {
    width: 100%;
    justify-content: flex-start;
  }
}

.memory-change-panel {
  display: grid;
  gap: 7px;
  margin-top: 10px;
  padding: 9px 10px;
  border-radius: 10px;
  border: 1px solid hsl(from var(--primary) h s l / 0.16);
  background: hsl(from var(--primary) h s l / 0.045);
}

.memory-change-panel:focus-visible {
  outline: 2px solid hsl(from var(--ring) h s l / 0.62);
  outline-offset: 3px;
}

.memory-change-panel--checking {
  border-color: hsl(from var(--border) h s l / 0.42);
  background: hsl(from var(--muted) h s l / 0.18);
}

.memory-change-panel--failed {
  border-color: hsl(38 92% 50% / 0.26);
  background: hsl(38 92% 50% / 0.08);
}

.memory-change-panel--disabled {
  border-color: hsl(from var(--border) h s l / 0.5);
  background: hsl(from var(--muted) h s l / 0.13);
}

.memory-change-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.memory-change-panel__heading {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 8px;
}

.memory-change-panel__title {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex: 0 0 auto;
  color: var(--foreground);
  font-size: 12px;
  font-weight: 600;
}

.memory-change-panel__spinner {
  width: 13px;
  height: 13px;
  color: hsl(from var(--primary) h s l / 0.82);
  animation: memory-panel-spin 900ms linear infinite;
}

.memory-change-panel__count {
  min-width: 0;
  overflow: hidden;
  color: hsl(from var(--muted-foreground) h s l / 0.82);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.memory-change-panel__actions {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 5px;
}

.memory-change-panel__inspect,
.memory-change-panel__manage,
.memory-change-panel__remember,
.memory-change-panel__explain {
  display: inline-flex;
  min-height: 24px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border-radius: 999px;
  border: 1px solid hsl(from var(--border) h s l / 0.4);
  background: hsl(from var(--background) h s l / 0.7);
  padding: 0 7px;
  color: hsl(from var(--muted-foreground) h s l / 0.9);
  cursor: pointer;
  font-size: 11px;
  line-height: 1;
  white-space: nowrap;
  transition:
    border-color 140ms ease,
    background-color 140ms ease,
    color 140ms ease;
}

.memory-change-panel__inspect:hover,
.memory-change-panel__manage:hover,
.memory-change-panel__remember:hover,
.memory-change-panel__explain:hover {
  border-color: hsl(from var(--primary) h s l / 0.28);
  background: hsl(from var(--primary) h s l / 0.08);
  color: hsl(from var(--primary) h s l / 0.95);
}

.memory-change-panel__inspect:focus-visible,
.memory-change-panel__manage:focus-visible,
.memory-change-panel__remember:focus-visible,
.memory-change-panel__explain:focus-visible {
  outline: 2px solid hsl(from var(--ring) h s l / 0.65);
  outline-offset: 2px;
}

.memory-change-panel__detail {
  margin: 0;
  color: hsl(from var(--muted-foreground) h s l / 0.88);
  font-size: 12px;
  line-height: 1.55;
}

.memory-change-panel__impact {
  margin: 0;
  color: hsl(from var(--foreground) h s l / 0.76);
  font-size: 12px;
  line-height: 1.55;
}

.memory-change-panel__items {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

@keyframes memory-panel-spin {
  to {
    transform: rotate(360deg);
  }
}

.memory-source-more {
  display: inline-flex;
  align-items: center;
  border: 0;
  min-height: 28px;
  padding: 3px 8px;
  border-radius: 999px;
  background: hsl(from var(--muted) h s l / 0.28);
  color: hsl(from var(--muted-foreground) h s l / 0.9);
  cursor: pointer;
  font-size: 11px;
  transition:
    background-color 140ms ease,
    color 140ms ease;
}

.memory-source-more:hover {
  background: hsl(from var(--primary) h s l / 0.08);
  color: hsl(from var(--primary) h s l / 0.95);
}

.memory-source-more:focus-visible {
  outline: 2px solid hsl(from var(--ring) h s l / 0.65);
  outline-offset: 2px;
}

.user-act-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.5rem;
  height: 1.5rem;
  border-radius: 0.3rem;
  color: var(--muted-foreground);
  transition: color 120ms ease;
}

.user-act-btn:hover {
  color: var(--foreground);
}

.user-bubble {
  position: relative;
  transform-origin: right bottom;
  border: none;
  background: var(--muted);
  box-shadow: none;
}

:global(.dark) .user-bubble {
  box-shadow: inset 0 0 0 1px hsl(0 0% 100% / 0.05);
}

.message-toolbar {}

/* 消息操作栏 hover 披露：默认隐藏，父级 group/message hover 或操作栏内部 focus 时浮现 */
.message-actions-row {
  opacity: var(--chat-action-visible-opacity, 0);
  transition: opacity 160ms ease;
}

.group\/message:hover .message-actions-row,
.message-actions-row:focus-within {
  opacity: var(--chat-action-hover-opacity, 1);
}

@keyframes streaming-dot-pulse {
  0%,
  100% {
    transform: scale(0.9);
    opacity: 0.8;
  }

  50% {
    transform: scale(1.15);
    opacity: 1;
  }
}



</style>
