<script setup lang="ts">
import { ref, nextTick, watch } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useChat } from '@/composables/useChat'
import MessageList from '@/components/chat/MessageList.vue'
import ChatInput from '@/components/chat/ChatInput.vue'

const chatStore = useChatStore()
const { sendMessage, isStreaming, error, abort } = useChat()
const scrollContainer = ref<HTMLElement>()

// 自动滚动到底部
function scrollToBottom() {
  nextTick(() => {
    if (scrollContainer.value) {
      scrollContainer.value.scrollTop = scrollContainer.value.scrollHeight
    }
  })
}

// 消息变化或流式内容变化时自动滚动
watch(() => chatStore.messages.length, scrollToBottom)
watch(() => chatStore.streamingContent, scrollToBottom)

async function handleSend(content: string) {
  await sendMessage(content)
}
</script>

<template>
  <div class="flex flex-col h-full">
    <!-- 消息区域 -->
    <div ref="scrollContainer" class="flex-1 overflow-y-auto">
      <!-- 空状态 -->
      <div
        v-if="chatStore.messages.length === 0 && !isStreaming"
        class="flex items-center justify-center h-full text-muted-foreground"
      >
        开始新对话
      </div>

      <!-- 消息列表 -->
      <div v-else class="max-w-3xl mx-auto py-4">
        <MessageList
          :messages="chatStore.messages"
          :is-streaming="isStreaming"
          :streaming-content="chatStore.streamingContent"
        />
      </div>
    </div>

    <!-- 错误提示 -->
    <div v-if="error" class="px-4 py-2 bg-destructive/10 text-destructive text-sm text-center">
      {{ error }}
      <button class="ml-2 underline" @click="error = null">关闭</button>
    </div>

    <!-- 流式进行中提示 -->
    <div v-if="isStreaming" class="flex justify-center py-1">
      <button
        class="text-xs text-muted-foreground hover:text-foreground transition-colors"
        @click="abort"
      >
        停止生成
      </button>
    </div>

    <!-- 输入框 -->
    <ChatInput :disabled="isStreaming" @send="handleSend" />
  </div>
</template>
