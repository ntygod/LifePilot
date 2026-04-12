<script setup lang="ts">
import { ref, watch } from 'vue'
import type { Message } from '@/types'
import { chatApi } from '@/api/client'
import { ThumbsUp, ThumbsDown } from 'lucide-vue-next'
import { Textarea } from '@/components/ui/textarea'

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
const isSubmitting = ref(false)

// 同步外部 prop 变化
watch(() => props.message.feedbackStatus, (val) => {
  feedbackStatus.value = val ?? null
})

async function handleLike() {
  if (isSubmitting.value) return
  const prev = feedbackStatus.value
  feedbackStatus.value = feedbackStatus.value === 'liked' ? null : 'liked'
  showFeedbackInput.value = false
  feedbackText.value = ''

  try {
    isSubmitting.value = true
    await chatApi.submitFeedback(
      props.message.id, 'like'
    )
    emit('like', props.message)
  } catch {
    feedbackStatus.value = prev
  } finally {
    isSubmitting.value = false
  }
}

function handleDislike() {
  if (isSubmitting.value) return
  if (feedbackStatus.value === 'disliked') {
    // 取消点踩
    feedbackStatus.value = null
    showFeedbackInput.value = false
    feedbackText.value = ''
  } else {
    // 展开反馈输入框，不立即调用 API
    feedbackStatus.value = 'disliked'
    showFeedbackInput.value = true
  }
}

async function submitDislikeFeedback() {
  if (isSubmitting.value) return
  try {
    isSubmitting.value = true
    await chatApi.submitFeedback(
      props.message.id, 'dislike', feedbackText.value || undefined
    )
    emit('dislike', props.message, feedbackText.value)
    showFeedbackInput.value = false
    feedbackText.value = ''
  } catch {
    // 保持输入框打开，让用户重试
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <div class="message-feedback space-y-2">
    <div class="flex items-center gap-0.5">
      <button
        type="button"
        class="act-btn"
        :class="feedbackStatus === 'liked' && 'act-btn--active'"
        :disabled="isSubmitting"
        title="有帮助"
        @click="handleLike"
      >
        <ThumbsUp class="size-4" />
      </button>
      <button
        type="button"
        class="act-btn"
        :class="feedbackStatus === 'disliked' && 'act-btn--disliked'"
        :disabled="isSubmitting"
        title="无帮助"
        @click="handleDislike"
      >
        <ThumbsDown class="size-4" />
      </button>
    </div>

    <Transition
      enter-active-class="transition-all duration-220 ease-out"
      enter-from-class="translate-y-2 opacity-0"
      enter-to-class="translate-y-0 opacity-100"
      leave-active-class="transition-all duration-160 ease-in"
      leave-from-class="translate-y-0 opacity-100"
      leave-to-class="translate-y-2 opacity-0"
    >
      <div v-if="showFeedbackInput" class="feedback-panel rounded-[1rem] border border-border/48 bg-background/62 p-3">
        <div class="mb-2 text-[11px] font-medium text-foreground/86">
          哪里不对？
        </div>
        <div class="mb-2 text-[10px] text-muted-foreground/82">
          选填，越简短越好。
        </div>
      <Textarea
        v-model="feedbackText"
        placeholder="例如：引用不准、回答太泛、漏了重点"
        rows="2"
        class="feedback-textarea resize-none"
      />
      <div class="mt-2.5 flex justify-end gap-2">
        <button
          type="button"
          class="feedback-action-btn feedback-action-btn-idle"
          :disabled="isSubmitting"
          @click="showFeedbackInput = false; feedbackText = ''; feedbackStatus = null"
        >
          取消
        </button>
        <button
          type="button"
          class="feedback-action-btn feedback-action-btn-primary"
          :disabled="isSubmitting"
          @click="submitDislikeFeedback"
        >
          提交反馈
        </button>
      </div>
      </div>
    </Transition>
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

.act-btn--active {
  color: var(--primary);
}

.act-btn--disliked {
  color: hsl(from var(--destructive) h s l / 0.8);
}

.feedback-panel {
  box-shadow:
    inset 0 1px 0 hsl(from var(--card) h s l / 0.28),
    0 12px 20px -26px hsl(var(--shadow-color) / 0.12);
}

.feedback-textarea {
  border-color: hsl(from var(--border) h s l / 0.52);
  background: hsl(from var(--card) h s l / 0.84);
}

.feedback-action-btn {
  min-height: 2rem;
  border-radius: 0.9rem;
  padding: 0.42rem 0.86rem;
  font-size: 11px;
  font-weight: 500;
  transition:
    transform 180ms var(--ease-fluid),
    border-color 180ms var(--ease-fluid),
    background-color 180ms var(--ease-fluid),
    box-shadow 180ms var(--ease-fluid),
    color 180ms var(--ease-fluid);
}

.feedback-action-btn:hover {
  transform: translateY(-1px);
}

.feedback-action-btn-idle {
  border: 1px solid hsl(from var(--border) h s l / 0.46);
  background: hsl(from var(--background) h s l / 0.7);
  color: hsl(from var(--muted-foreground) h s l / 0.9);
}

.feedback-action-btn-primary {
  border: 1px solid hsl(from var(--primary) h s l / 0.12);
  background: hsl(from var(--primary) h s l / 0.92);
  color: hsl(from var(--primary-foreground) h s l / 0.98);
  box-shadow: 0 12px 20px -18px hsl(var(--shadow-color) / 0.16);
}
</style>
