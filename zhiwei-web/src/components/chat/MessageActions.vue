<script setup lang="ts">
import { computed, ref } from 'vue'
import { Copy, Check, GitBranch, Loader2, Play, RefreshCw, Square, Volume2 } from 'lucide-vue-next'
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

const copied = ref(false)
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

const { playTts, stopTts, isPlaying, isLoadingTts } = useVoice()

async function handleCopy(content: string) {
  const success = await copyToClipboard(content)
  if (success) {
    copied.value = true
    window.setTimeout(() => { copied.value = false }, 2000)
  }
  emit('copy', content)
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
</script>

<template>
  <div class="flex items-center gap-0.5">
    <!-- 复制 -->
    <button type="button" class="act-btn" title="复制" @click="handleCopy(message.content)">
      <Check v-if="copied" class="size-4 text-primary" />
      <Copy v-else class="size-4" />
    </button>

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
    <template v-if="isLastAssistant && isResumable">
      <button
        v-if="showResumeAction"
        type="button"
        class="act-btn"
        title="继续执行"
        @click="emit('resume', message)"
      >
        <Play class="size-4" />
      </button>
      <button type="button" class="act-btn" title="重新开始" @click="emit('restart', message)">
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

.act-btn:disabled {
  opacity: 0.5;
  pointer-events: none;
}
</style>
