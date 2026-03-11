<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink } from 'vue-router'
import { Copy, GitBranch, RefreshCw } from 'lucide-vue-next'
import type { Message } from '@/types'
import { copyToClipboard } from '@/utils/clipboard'

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

async function handleCopy(content: string) {
  const success = await copyToClipboard(content)
  copyLabel.value = success ? '已复制' : '复制失败'
  window.setTimeout(() => {
    copyLabel.value = '复制'
  }, 2000)
  emit('copy', content)
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
