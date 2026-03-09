<script setup lang="ts">
import type { Message } from '@/types'
import { copyToClipboard } from '@/utils/clipboard'
import { Copy, GitBranch, RefreshCw } from 'lucide-vue-next'
import { ref } from 'vue'
import { RouterLink } from 'vue-router'

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
  if (success) {
    copyLabel.value = '已复制'
    setTimeout(() => { copyLabel.value = '复制' }, 2000)
  } else {
    copyLabel.value = '复制失败'
    setTimeout(() => { copyLabel.value = '复制' }, 2000)
  }
  emit('copy', content)
}
</script>

<template>
  <div class="flex items-center gap-md">
    <!-- 复制 -->
    <button
      type="button"
      class="flex items-center gap-xs text-xs text-muted-foreground hover:text-foreground transition-colors"
      @click="handleCopy(message.content)"
    >
      <Copy :size="14" />
      <span>{{ copyLabel }}</span>
    </button>

    <!-- 分叉 -->
    <button
      type="button"
      class="flex items-center gap-xs text-xs text-muted-foreground hover:text-foreground transition-colors"
      @click="emit('fork', message)"
    >
      <GitBranch :size="14" />
      <span>分叉</span>
    </button>

    <!-- 重新生成（仅最后一条 assistant 消息） -->
    <button
      v-if="isLastAssistant"
      type="button"
      class="flex items-center gap-xs text-xs text-muted-foreground hover:text-foreground transition-colors"
      @click="emit('regenerate', message)"
    >
      <RefreshCw :size="14" />
      <span>重新生成</span>
    </button>

    <!-- 查看轨迹 -->
    <RouterLink
      v-if="message.traceId"
      :to="{ name: 'traces', query: { id: message.traceId } }"
      class="ml-auto text-xs text-primary hover:underline underline-offset-2"
    >
      查看执行轨迹
    </RouterLink>
  </div>
</template>
