<script setup lang="ts">
import { ref, watch } from 'vue'
import type { Message } from '@/types'
import { chatApi } from '@/api/client'
import { ThumbsUp, ThumbsDown } from 'lucide-vue-next'

const props = defineProps<{
  message: Message
}>()

const emit = defineEmits<{
  (e: 'like', message: Message): void
  (e: 'dislike', message: Message, feedback?: string): void
}>()

// 本地反馈状态，初始化自 message.feedbackStatus
const feedbackStatus = ref<'liked' | 'disliked' | null>(props.message.feedbackStatus ?? null)
const showFeedbackInput = ref(false)
const feedbackText = ref('')

// 同步外部 prop 变化
watch(() => props.message.feedbackStatus, (val) => {
  feedbackStatus.value = val ?? null
})

async function handleLike() {
  const prev = feedbackStatus.value
  feedbackStatus.value = feedbackStatus.value === 'liked' ? null : 'liked'
  showFeedbackInput.value = false
  feedbackText.value = ''

  try {
    await chatApi.submitFeedback(props.message.id, 'like')
    emit('like', props.message)
  } catch {
    // 回滚
    feedbackStatus.value = prev
  }
}

async function handleDislike() {
  const prev = feedbackStatus.value
  if (feedbackStatus.value === 'disliked') {
    feedbackStatus.value = null
    showFeedbackInput.value = false
    feedbackText.value = ''
  } else {
    feedbackStatus.value = 'disliked'
    showFeedbackInput.value = true
  }

  try {
    await chatApi.submitFeedback(props.message.id, 'dislike')
  } catch {
    feedbackStatus.value = prev
    showFeedbackInput.value = prev === 'disliked'
  }
}

async function submitFeedback() {
  try {
    await chatApi.submitFeedback(props.message.id, 'dislike', feedbackText.value)
    emit('dislike', props.message, feedbackText.value)
    showFeedbackInput.value = false
    feedbackText.value = ''
  } catch {
    // 保持输入框打开，让用户重试
  }
}
</script>

<template>
  <div class="space-y-2">
    <div class="flex items-center gap-md">
      <button
        type="button"
        class="flex items-center gap-xs text-xs text-muted-foreground hover:text-foreground transition-colors"
        :class="feedbackStatus === 'liked' ? 'text-primary' : ''"
        @click="handleLike"
      >
        <ThumbsUp :size="14" />
        <span>有帮助</span>
      </button>
      <button
        type="button"
        class="flex items-center gap-xs text-xs text-muted-foreground hover:text-foreground transition-colors"
        :class="feedbackStatus === 'disliked' ? 'text-destructive' : ''"
        @click="handleDislike"
      >
        <ThumbsDown :size="14" />
        <span>无帮助</span>
      </button>
    </div>

    <!-- 点踩反馈输入框 -->
    <div v-if="showFeedbackInput">
      <textarea
        v-model="feedbackText"
        placeholder="请描述问题或建议（可选）"
        rows="2"
        class="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm
               placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring
               focus:border-transparent resize-none transition-all duration-200"
      />
      <div class="mt-2 flex justify-end gap-2">
        <button
          type="button"
          class="px-4 py-2 rounded-lg text-sm font-medium border border-input
                 hover:bg-accent transition-all duration-200"
          @click="showFeedbackInput = false; feedbackText = ''"
        >
          取消
        </button>
        <button
          type="button"
          class="px-4 py-2 rounded-lg text-sm font-medium bg-primary text-primary-foreground
                 hover:bg-primary/90 transition-all duration-200"
          @click="submitFeedback"
        >
          提交反馈
        </button>
      </div>
    </div>
  </div>
</template>
