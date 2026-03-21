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
  <div class="flex max-w-full self-end items-start gap-3 rounded-2xl border border-destructive/30 bg-destructive/[0.04] px-4 py-3 text-destructive shadow-sm md:max-w-[85%]">
    <AlertCircle :size="16" class="mt-0.5 shrink-0" />
    <div class="min-w-0 flex-1 space-y-2">
      <div class="space-y-1">
        <p class="text-sm font-medium leading-5">本轮消息发送失败</p>
        <p class="text-sm leading-6 text-destructive/90">{{ message.errorMessage || '发送失败' }}</p>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <button
          type="button"
          class="inline-flex h-8 items-center gap-1 rounded-full border border-destructive/25 px-3 text-xs font-medium transition-colors hover:bg-destructive/8"
          @click="emit('retry', message)"
        >
          <RotateCcw :size="12" />
          <span>重试发送</span>
        </button>
        <RouterLink
          v-if="message.traceId"
          :to="{ name: 'traces', query: { id: message.traceId } }"
          class="inline-flex h-8 items-center rounded-full border border-destructive/25 px-3 text-xs font-medium transition-colors hover:bg-destructive/8"
        >
          查看执行轨迹
        </RouterLink>
      </div>
    </div>
  </div>
</template>
