<script setup lang="ts">
import { computed } from 'vue'
import type { Message } from '@/types'
import MessageBubble from './MessageBubble.vue'

const props = defineProps<{
  messages: Message[]
  isStreaming?: boolean
  streamingContent?: string
}>()

// 按 timestamp 升序排列
const sortedMessages = computed(() =>
  [...props.messages].sort((a, b) => a.timestamp - b.timestamp)
)
</script>

<template>
  <div class="flex flex-col">
    <MessageBubble
      v-for="(msg, index) in sortedMessages"
      :key="msg.id"
      :message="msg"
      :streaming="isStreaming && index === sortedMessages.length - 1 && msg.role === 'assistant'"
      :streaming-content="streamingContent"
    />

    <!-- 流式进行中但尚未有 assistant 消息时，显示占位 -->
    <MessageBubble
      v-if="isStreaming && (sortedMessages.length === 0 || sortedMessages[sortedMessages.length - 1]?.role === 'user')"
      :message="{ id: 'streaming', role: 'assistant', content: '', timestamp: Date.now() }"
      :streaming="true"
      :streaming-content="streamingContent"
    />
  </div>
</template>
