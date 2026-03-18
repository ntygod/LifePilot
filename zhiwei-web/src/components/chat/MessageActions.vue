<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink } from 'vue-router'
import { Copy, GitBranch, Loader2, RefreshCw, Square, Volume2 } from 'lucide-vue-next'
import type { Message } from '@/types'
import { copyToClipboard } from '@/utils/clipboard'
import { useVoice } from '@/composables/useVoice'

defineProps<{
  message: Message
  isLastAssistant: boolean
}>()

const emit = defineEmits<{
  (e: 'copy', content: string): void
  (e: 'fork', message: Message): void
  (e: 'regenerate', message: Message): void
}>()

const copyLabel = ref('复制')
const ttsError = ref<string | null>(null)

const { playTts, stopTts, isPlaying, playbackProgress, isLoadingTts } = useVoice()

async function handleCopy(content: string) {
  const success = await copyToClipboard(content)
  copyLabel.value = success ? '已复制' : '复制失败'
  window.setTimeout(() => {
    copyLabel.value = '复制'
  }, 2000)
  emit('copy', content)
}

async function handleTts(messageId: string) {
  ttsError.value = null
  try {
    await playTts(messageId)
  } catch (e) {
    ttsError.value = '语音合成服务不可用'
    window.setTimeout(() => { ttsError.value = null }, 3000)
  }
}
</script>

<template>
  <div class="flex flex-wrap items-center gap-2">
    <button
      type="button"
      class="surface-chip transition-colors hover:border-border/70 hover:bg-background/80 hover:text-foreground"
      @click="handleCopy(message.content)"
    >
      <Copy class="size-3.5" />
      <span>{{ copyLabel }}</span>
    </button>

    <!-- TTS 语音朗读按钮 -->
    <button
      type="button"
      class="surface-chip transition-colors hover:border-border/70 hover:bg-background/80 hover:text-foreground"
      :disabled="isLoadingTts"
      :title="ttsError ?? (isPlaying ? '停止朗读' : '朗读')"
      @click="isPlaying ? stopTts() : handleTts(message.id)"
    >
      <Loader2 v-if="isLoadingTts" class="size-3.5 animate-spin" />
      <Square v-else-if="isPlaying" class="size-3.5" />
      <Volume2 v-else class="size-3.5" />
      <span>{{ isLoadingTts ? '加载中' : isPlaying ? '停止' : '朗读' }}</span>
      <span
        v-if="isPlaying && playbackProgress > 0"
        class="ml-1 text-[10px] text-muted-foreground"
      >{{ Math.round(playbackProgress * 100) }}%</span>
    </button>
    <span
      v-if="ttsError"
      class="text-[11px] text-destructive"
    >{{ ttsError }}</span>

    <button
      type="button"
      class="surface-chip transition-colors hover:border-border/70 hover:bg-background/80 hover:text-foreground"
      @click="emit('fork', message)"
    >
      <GitBranch class="size-3.5" />
      <span>分叉</span>
    </button>

    <button
      v-if="isLastAssistant"
      type="button"
      class="surface-chip transition-colors hover:border-border/70 hover:bg-background/80 hover:text-foreground"
      @click="emit('regenerate', message)"
    >
      <RefreshCw class="size-3.5" />
      <span>重新生成</span>
    </button>

    <RouterLink
      v-if="message.traceId"
      :to="{ name: 'traces', query: { id: message.traceId } }"
      class="surface-chip surface-chip-strong ml-0 transition-colors hover:opacity-85 sm:ml-auto"
    >
      查看执行轨迹
    </RouterLink>
  </div>
</template>
