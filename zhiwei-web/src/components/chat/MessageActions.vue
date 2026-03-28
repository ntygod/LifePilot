<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { Copy, GitBranch, Loader2, Play, RefreshCw, Square, Volume2 } from 'lucide-vue-next'
import type { Message } from '@/types'
import { copyToClipboard } from '@/utils/clipboard'
import { useVoice } from '@/composables/useVoice'

const props = defineProps<{
  message: Message
  isLastAssistant: boolean
}>()

const emit = defineEmits<{
  (e: 'copy', content: string): void
  (e: 'fork', message: Message): void
  (e: 'regenerate', message: Message): void
  (e: 'resume', message: Message): void
  (e: 'restart', message: Message): void
}>()

const copyLabel = ref('复制')
const ttsError = ref<string | null>(null)
const isResumable = computed(() =>
  props.message.turnStatus === 'DEGRADED'
  || props.message.turnStatus === 'SUSPENDED'
  || props.message.completionMode === 'DEGRADED'
  || props.message.completionMode === 'SUSPENDED',
)
const waitsForUserReply = computed(() =>
  props.message.suspendReasonSourceId === '__await_user_input__',
)
const showResumeAction = computed(() =>
  isResumable.value && !waitsForUserReply.value,
)
const traceLinkVisible = computed(() => !!props.message.traceId)
const ttsButtonLabel = computed(() => (
  isLoadingTts.value ? '加载中' : isPlaying.value ? '停止' : '朗读'
))
const ttsTitle = computed(() => (
  ttsError.value ?? (isPlaying.value ? '停止朗读' : '朗读')
))

const { playTts, stopTts, isPlaying, playbackProgress, isLoadingTts } = useVoice()

async function handleCopy(content: string) {
  const success = await copyToClipboard(content)
  copyLabel.value = success ? '已复制' : '复制失败'
  window.setTimeout(() => {
    copyLabel.value = '复制'
  }, 2000)
  emit('copy', content)
}

async function handleTts(entryId: string) {
  ttsError.value = null
  try {
    await playTts(entryId)
  } catch (e) {
    ttsError.value = '语音合成服务不可用'
    window.setTimeout(() => { ttsError.value = null }, 3000)
  }
}
</script>

<template>
  <div class="message-actions-bar flex flex-wrap items-center gap-2">
    <button
      type="button"
      class="message-action-chip"
      @click="handleCopy(message.content)"
    >
      <span class="message-action-icon">
        <Copy class="size-3.5" />
      </span>
      <span>{{ copyLabel }}</span>
    </button>

    <button
      type="button"
      class="message-action-chip"
      :class="isPlaying ? 'message-action-chip-active' : ''"
      :disabled="isLoadingTts"
      :title="ttsTitle"
      @click="isPlaying ? stopTts() : handleTts(message.id)"
    >
      <span class="message-action-icon">
        <Loader2 v-if="isLoadingTts" class="size-3.5 animate-spin" />
        <Square v-else-if="isPlaying" class="size-3.5" />
        <Volume2 v-else class="size-3.5" />
      </span>
      <span>{{ ttsButtonLabel }}</span>
      <span
        v-if="isPlaying && playbackProgress > 0"
        class="message-action-meta"
      >{{ Math.round(playbackProgress * 100) }}%</span>
    </button>
    <span
      v-if="ttsError"
      class="message-action-error"
    >{{ ttsError }}</span>

    <button
      type="button"
      class="message-action-chip"
      @click="emit('fork', message)"
    >
      <span class="message-action-icon">
        <GitBranch class="size-3.5" />
      </span>
      <span>分叉</span>
    </button>

    <template v-if="isLastAssistant && isResumable">
      <button
        v-if="showResumeAction"
        type="button"
        class="message-action-chip message-action-chip-strong"
        title="从当前挂起进度继续执行，不会把整轮任务从头再跑一遍。"
        @click="emit('resume', message)"
      >
        <span class="message-action-icon">
          <Play class="size-3.5" />
        </span>
        <span>继续执行</span>
      </button>
      <button
        type="button"
        class="message-action-chip"
        @click="emit('restart', message)"
      >
        <span class="message-action-icon">
          <RefreshCw class="size-3.5" />
        </span>
        <span>重新开始</span>
      </button>
    </template>

    <button
      v-else-if="isLastAssistant"
      type="button"
      class="message-action-chip"
      @click="emit('regenerate', message)"
    >
      <span class="message-action-icon">
        <RefreshCw class="size-3.5" />
      </span>
      <span>重新生成</span>
    </button>

    <RouterLink
      v-if="traceLinkVisible"
      :to="{ name: 'traces', query: { id: message.traceId } }"
      class="message-action-chip message-action-chip-strong ml-0 sm:ml-auto"
    >
      <span class="message-action-icon">
        <Play class="size-3.5" />
      </span>
      查看执行轨迹
    </RouterLink>
  </div>
</template>

<style scoped>
.message-actions-bar {
  align-items: center;
}

.message-action-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  min-height: 2rem;
  border-radius: 999px;
  border: 1px solid hsl(from var(--border) h s l / 0.42);
  background: hsl(from var(--card) h s l / 0.76);
  padding: 0.34rem 0.78rem;
  font-size: 11px;
  line-height: 1.1;
  color: hsl(from var(--muted-foreground) h s l / 0.92);
  box-shadow: inset 0 1px 0 hsl(from var(--card) h s l / 0.28);
  transition:
    transform 180ms var(--ease-fluid),
    border-color 180ms var(--ease-fluid),
    background-color 180ms var(--ease-fluid),
    box-shadow 180ms var(--ease-fluid),
    color 180ms var(--ease-fluid);
}

.message-action-chip:hover {
  transform: translateY(-1px);
  border-color: hsl(from var(--border) h s l / 0.62);
  background: hsl(from var(--card) h s l / 0.88);
  color: var(--foreground);
  box-shadow:
    inset 0 1px 0 hsl(from var(--card) h s l / 0.32),
    0 10px 18px -22px hsl(var(--shadow-color) / 0.12);
}

.message-action-chip-active {
  border-color: hsl(from var(--primary) h s l / 0.2);
  background: hsl(from var(--primary) h s l / 0.1);
  color: hsl(from var(--primary) h s l / 0.92);
}

.message-action-chip-strong {
  border-color: hsl(from var(--primary) h s l / 0.2);
  background: hsl(from var(--primary) h s l / 0.1);
  color: hsl(from var(--primary) h s l / 0.92);
  box-shadow:
    inset 0 1px 0 hsl(from var(--card) h s l / 0.28),
    0 10px 18px -22px hsl(var(--shadow-color) / 0.12);
}

.message-action-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 1.2rem;
  width: 1.2rem;
  flex-shrink: 0;
}

.message-action-meta {
  border-radius: 999px;
  background: hsl(from var(--background) h s l / 0.78);
  padding: 0.12rem 0.38rem;
  font-size: 10px;
  color: hsl(from var(--muted-foreground) h s l / 0.84);
}

.message-action-error {
  font-size: 11px;
  color: hsl(from var(--destructive) h s l / 0.88);
}
</style>
