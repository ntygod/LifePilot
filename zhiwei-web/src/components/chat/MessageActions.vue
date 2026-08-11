<script setup lang="ts">
import { computed, ref } from 'vue'
import { AlertTriangle, BookOpenCheck, Brain, Copy, Check, CheckCircle2, GitBranch, Loader2, Play, RefreshCw, Square, Volume2 } from 'lucide-vue-next'
import type { Message, ToolRecoveryAction } from '@/types'
import { copyToClipboard } from '@/utils/clipboard'
import { useVoice } from '@/composables/useVoice'
import {
  canUseManualRestartForMessage,
  canUseManualResumeForMessage,
  hasRecoverableTaskState,
  isUserReplyRecovery,
} from '@/utils/taskRecovery'

const props = defineProps<{
  message: Message
  isLastAssistant: boolean
  showRecoveryActions?: boolean
  recoveryResumeAction?: ToolRecoveryAction
  recoveryRestartAction?: ToolRecoveryAction
  knowledgeBaseId?: string | null
  knowledgeBaseName?: string | null
  savingToKnowledge?: boolean
  savedKnowledgeBaseName?: string | null
  saveKnowledgeError?: string | null
}>()

const emit = defineEmits<{
  (e: 'copy', content: string, success: boolean): void
  (e: 'remember', message: Message): void
  (e: 'save-knowledge', message: Message): void
  (e: 'fork', message: Message): void
  (e: 'regenerate', message: Message): void
  (e: 'resume', message: Message, action?: ToolRecoveryAction): void
  (e: 'restart', message: Message, action?: ToolRecoveryAction): void
}>()

const copied = ref(false)
const ttsError = ref<string | null>(null)
const settleExpanded = ref(false)
const isResumable = computed(() =>
  hasRecoverableTaskState(props.message),
)
const waitsForUserReply = computed(() =>
  isUserReplyRecovery(props.message.taskRecovery)
  || props.message.suspendReasonSourceId === '__await_user_input__',
)
const showResumeAction = computed(() =>
  isResumable.value
  && !waitsForUserReply.value
  && canUseManualResumeForMessage(props.message),
)

const showRestartAction = computed(() =>
  isResumable.value
  && !waitsForUserReply.value
  && canUseManualRestartForMessage(props.message),
)

const showInlineRecoveryActions = computed(() => {
  return props.showRecoveryActions !== false
    && props.isLastAssistant
    && isResumable.value
    && (showResumeAction.value || showRestartAction.value)
})

const recoveryResumeTitle = computed(() =>
  props.recoveryResumeAction?.description
    ? `继续执行：${props.recoveryResumeAction.description}`
    : '继续执行',
)

const recoveryRestartTitle = computed(() =>
  props.recoveryRestartAction?.description
    ? `重新开始：${props.recoveryRestartAction.description}`
    : '重新开始',
)
const canRemember = computed(() => props.message.content.trim().length > 0)
const knowledgeBaseName = computed(() => props.knowledgeBaseName?.trim() || '资料库')
const savedKnowledgeBaseName = computed(() => props.savedKnowledgeBaseName?.trim() || '')
const hasSavedToKnowledge = computed(() => Boolean(savedKnowledgeBaseName.value))
const saveKnowledgeErrorMessage = computed(() => props.saveKnowledgeError?.trim() || '')
const hasSaveKnowledgeError = computed(() => Boolean(saveKnowledgeErrorMessage.value))
const hasKnowledgeBaseTarget = computed(() => Boolean(props.knowledgeBaseId?.trim()))
const canOfferSaveToKnowledge = computed(() =>
  props.message.content.trim().length > 0
  && (hasKnowledgeBaseTarget.value || hasSavedToKnowledge.value),
)
const settleActionCount = computed(() =>
  (canRemember.value ? 1 : 0) + (canOfferSaveToKnowledge.value ? 1 : 0),
)
const hasMultipleSettleActions = computed(() => settleActionCount.value > 1)
const showSettleActions = computed(() => settleActionCount.value > 0)
const memorySettleTitle = '放入输入框，发送后后台整理为记忆'
const settleTriggerTitle = computed(() =>
  hasMultipleSettleActions.value
    ? (hasSavedToKnowledge.value
        ? `已存为资料：${savedKnowledgeBaseName.value}`
        : (hasSaveKnowledgeError.value ? '沉淀这条消息（资料未存入）' : '沉淀这条消息'))
    : (canRemember.value
        ? memorySettleTitle
        : (hasSavedToKnowledge.value
            ? `已存为资料：${savedKnowledgeBaseName.value}`
            : (hasSaveKnowledgeError.value
                ? `重试存为资料：${knowledgeBaseName.value}`
                : `存为资料：${knowledgeBaseName.value}`))),
)
const saveKnowledgeTitle = computed(() =>
  hasSavedToKnowledge.value
    ? `已存为资料：${savedKnowledgeBaseName.value}`
    : hasKnowledgeBaseTarget.value
      ? (hasSaveKnowledgeError.value
          ? `重试存为资料：${knowledgeBaseName.value}`
          : `存为资料：${knowledgeBaseName.value}`)
      : '存为资料：先选择资料库',
)

const { playTts, stopTts, isPlaying, isLoadingTts } = useVoice()

async function handleCopy(content: string) {
  const success = await copyToClipboard(content)
  if (success) {
    copied.value = true
    window.setTimeout(() => { copied.value = false }, 2000)
  }
  emit('copy', content, success)
}

async function handleTts(entryId: string) {
  ttsError.value = null
  try {
    await playTts(entryId)
  } catch {
    ttsError.value = '语音合成服务不可用'
    window.setTimeout(() => { ttsError.value = null }, 3000)
  }
}

function toggleSettleActions() {
  if (!hasMultipleSettleActions.value) {
    if (canRemember.value) {
      emit('remember', props.message)
      return
    }
    handleSaveKnowledge()
    return
  }
  settleExpanded.value = !settleExpanded.value
}

function handleSettleFocusOut(event: FocusEvent) {
  if (!hasMultipleSettleActions.value) return
  const nextTarget = event.relatedTarget
  if (nextTarget instanceof Node && event.currentTarget instanceof HTMLElement) {
    if (event.currentTarget.contains(nextTarget)) {
      return
    }
  }
  settleExpanded.value = false
}

function handleRemember() {
  if (!hasMultipleSettleActions.value || !settleExpanded.value) return
  settleExpanded.value = false
  emit('remember', props.message)
}

function handleSaveKnowledge() {
  if (hasMultipleSettleActions.value && !settleExpanded.value) return
  if (hasSavedToKnowledge.value || !hasKnowledgeBaseTarget.value) return
  settleExpanded.value = false
  emit('save-knowledge', props.message)
}
</script>

<template>
  <div class="flex items-center gap-0.5">
    <!-- 复制 -->
    <button type="button" class="act-btn" title="复制" @click="handleCopy(message.content)">
      <Check v-if="copied" class="size-4 text-primary" />
      <Copy v-else class="size-4" />
    </button>

    <!-- 沉淀：把记忆和资料保存收成一个轻量入口 -->
    <div
      v-if="showSettleActions"
      class="settle-actions"
      :class="{ 'settle-actions--expanded': hasMultipleSettleActions && settleExpanded }"
      @focusout="handleSettleFocusOut"
      @keydown.esc="settleExpanded = false"
    >
      <button
        type="button"
        class="act-btn settle-actions__trigger"
        :title="settleTriggerTitle"
        :aria-label="settleTriggerTitle"
        :aria-expanded="hasMultipleSettleActions ? settleExpanded : undefined"
        @click="toggleSettleActions"
      >
        <CheckCircle2 v-if="hasSavedToKnowledge" class="size-4 text-primary" />
        <AlertTriangle v-else-if="hasSaveKnowledgeError && canOfferSaveToKnowledge" class="size-4 text-destructive" />
        <Brain v-else-if="!hasMultipleSettleActions && canRemember" class="size-4" />
        <BookOpenCheck v-else class="size-4" />
      </button>
      <div
        v-if="hasMultipleSettleActions"
        class="settle-actions__items"
        :aria-hidden="!settleExpanded"
      >
        <button
          v-if="canRemember"
          type="button"
          class="act-btn"
          :title="memorySettleTitle"
          :aria-label="memorySettleTitle"
          :tabindex="settleExpanded ? 0 : -1"
          @click="handleRemember"
        >
          <Brain class="size-4" />
        </button>
        <button
          v-if="canOfferSaveToKnowledge"
          type="button"
          class="act-btn"
          :title="saveKnowledgeTitle"
          :aria-label="saveKnowledgeTitle"
          :disabled="savingToKnowledge || hasSavedToKnowledge"
          :tabindex="settleExpanded ? 0 : -1"
          @click="handleSaveKnowledge"
        >
          <Loader2 v-if="savingToKnowledge" class="size-4 animate-spin text-primary" />
          <CheckCircle2 v-else-if="hasSavedToKnowledge" class="size-4 text-primary" />
          <AlertTriangle v-else-if="hasSaveKnowledgeError" class="size-4 text-destructive" />
          <BookOpenCheck v-else class="size-4" />
        </button>
      </div>
    </div>

    <!-- 朗读 -->
    <button
      type="button"
      class="act-btn"
      :title="isPlaying ? '停止朗读' : '朗读'"
      :disabled="isLoadingTts"
      @click="isPlaying ? stopTts() : handleTts(message.id)"
    >
      <Loader2 v-if="isLoadingTts" class="size-4 animate-spin" />
      <Square v-else-if="isPlaying" class="size-4 text-primary" />
      <Volume2 v-else class="size-4" />
    </button>

    <!-- 分叉 -->
    <button type="button" class="act-btn" title="分叉" @click="emit('fork', message)">
      <GitBranch class="size-4" />
    </button>

    <!-- 继续执行 / 重新开始 / 重新生成 -->
    <template v-if="showInlineRecoveryActions">
      <button
        v-if="showResumeAction"
        type="button"
        class="act-btn"
        :title="recoveryResumeTitle"
        :aria-label="recoveryResumeTitle"
        @click="emit('resume', message, recoveryResumeAction)"
      >
        <Play class="size-4" />
      </button>
      <button
        v-if="showRestartAction"
        type="button"
        class="act-btn"
        :title="recoveryRestartTitle"
        :aria-label="recoveryRestartTitle"
        @click="emit('restart', message, recoveryRestartAction)"
      >
        <RefreshCw class="size-4" />
      </button>
    </template>
    <button
      v-else-if="isLastAssistant"
      type="button"
      class="act-btn"
      title="重新生成"
      @click="emit('regenerate', message)"
    >
      <RefreshCw class="size-4" />
    </button>

    <span v-if="ttsError" class="ml-sm text-[11px] text-destructive/80">{{ ttsError }}</span>
  </div>
</template>

<style scoped>
.act-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.75rem;
  height: 1.75rem;
  border-radius: 0.375rem;
  color: var(--muted-foreground);
  transition: color 120ms ease, background 120ms ease;
}

.act-btn:hover {
  color: var(--foreground);
  background: hsl(from var(--muted) h s l / 0.5);
}

.act-btn:focus-visible {
  outline: 2px solid hsl(from var(--ring) h s l / 0.65);
  outline-offset: 2px;
}

.act-btn:disabled {
  opacity: 0.5;
  pointer-events: none;
}

.settle-actions {
  display: inline-flex;
  align-items: center;
  gap: 0.125rem;
}

.settle-actions__trigger {
  color: hsl(from var(--primary) h s l / 0.82);
}

.settle-actions__items {
  display: inline-flex;
  align-items: center;
  gap: 0.125rem;
  max-width: 0;
  opacity: 0;
  overflow: hidden;
  pointer-events: none;
  transform: translateX(-0.125rem);
  transition:
    max-width 140ms ease,
    opacity 120ms ease,
    transform 120ms ease;
}

.settle-actions--expanded .settle-actions__items {
  max-width: 4.25rem;
  opacity: 1;
  pointer-events: auto;
  transform: translateX(0);
}

@media (prefers-reduced-motion: reduce) {
  .settle-actions__items,
  .act-btn {
    transition: none;
  }
}
</style>
