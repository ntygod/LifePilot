<script setup lang="ts">
import type { Message } from '@/types'
import { AlertCircle, RotateCcw } from 'lucide-vue-next'
import { RouterLink } from 'vue-router'

defineProps<{
  message: Message
}>()

const emit = defineEmits<{
  (e: 'retry', message: Message): void
}>()
</script>

<template>
  <div class="flex items-start gap-2 rounded-lg border border-destructive/40 bg-destructive/5 px-3 py-2 text-xs text-destructive">
    <AlertCircle :size="14" class="shrink-0 mt-0.5" />
    <div class="flex-1 space-y-1">
      <p>{{ message.errorMessage || '发送失败' }}</p>
      <div class="flex items-center gap-2">
        <button
          type="button"
          class="inline-flex items-center gap-1 hover:underline underline-offset-2 transition-colors"
          @click="emit('retry', message)"
        >
          <RotateCcw :size="12" />
          <span>重试</span>
        </button>
        <RouterLink
          v-if="message.traceId"
          :to="{ name: 'traces', query: { id: message.traceId } }"
          class="hover:underline underline-offset-2 transition-colors"
        >
          查看执行轨迹
        </RouterLink>
      </div>
    </div>
  </div>
</template>
