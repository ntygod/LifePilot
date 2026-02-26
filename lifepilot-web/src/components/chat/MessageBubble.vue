<script setup lang="ts">
import type { Message } from '@/types'
import StreamingText from './StreamingText.vue'

defineProps<{
  message: Message
  streaming?: boolean
  streamingContent?: string
}>()
</script>

<template>
  <div class="flex gap-3 px-4 py-3" :class="message.role === 'user' ? 'justify-end' : ''">
    <!-- Agent 头像 -->
    <div
      v-if="message.role === 'assistant'"
      class="shrink-0 w-8 h-8 rounded-full bg-primary/10 flex items-center justify-center text-xs font-medium text-primary"
    >
      AI
    </div>

    <!-- 消息内容 -->
    <div
      class="max-w-[70%] rounded-lg px-4 py-2"
      :class="message.role === 'user'
        ? 'bg-primary text-primary-foreground'
        : 'bg-muted text-foreground'"
    >
      <!-- 用户消息：纯文本 -->
      <p v-if="message.role === 'user'" class="text-sm whitespace-pre-wrap">
        {{ message.content }}
      </p>

      <!-- Agent 消息：Markdown 渲染 -->
      <template v-else>
        <StreamingText
          :content="streaming ? (streamingContent ?? '') : message.content"
          :streaming="streaming"
        />
      </template>
    </div>

    <!-- 用户头像 -->
    <div
      v-if="message.role === 'user'"
      class="shrink-0 w-8 h-8 rounded-full bg-secondary flex items-center justify-center text-xs font-medium"
    >
      你
    </div>
  </div>
</template>
