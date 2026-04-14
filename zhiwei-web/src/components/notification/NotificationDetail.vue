<script setup lang="ts">
import { computed, ref } from 'vue'
import { ThumbsUp, Check, X } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { parseNotificationDetail } from '@/utils/notificationContent'
import { useNotificationStore } from '@/stores/notification'
import type { NotificationItem } from '@/types'

/** 组件 Props */
interface Props {
  contentJson: string
  notificationItem?: NotificationItem
}

const props = defineProps<Props>()
const notificationStore = useNotificationStore()

/** 解析通知详情内容 */
const detail = computed(() => parseNotificationDetail(props.contentJson))

/** 是否为主动提醒类型 */
const isProactiveReminder = computed(
  () => props.notificationItem?.typeId === 'proactive_reminder' || props.notificationItem?.typeId === 'proactive_action'
)

/** 当前反馈类型（优先取 prop 中已有值，再取本地提交后的值） */
const localFeedbackType = ref<string | null>(null)
const feedbackType = computed(
  () => localFeedbackType.value ?? props.notificationItem?.feedbackType ?? null
)

/** 反馈提交中 */
const submitting = ref(false)

/** 提交反馈 */
async function handleFeedback(type: string) {
  if (!props.notificationItem || submitting.value || feedbackType.value) return
  submitting.value = true
  try {
    await notificationStore.submitFeedback(props.notificationItem.id, type)
    localFeedbackType.value = type
  } finally {
    submitting.value = false
  }
}

/** 反馈按钮配置 */
const feedbackButtons = [
  { type: 'ACTED', label: '有用', icon: ThumbsUp },
  { type: 'SNOOZED', label: '知道了', icon: Check },
  { type: 'NOT_RELEVANT', label: '不需要', icon: X },
]
</script>

<template>
  <div class="px-4 py-3">
    <!-- TEXT 类型：保留换行的纯文本 -->
    <pre
      v-if="detail.type === 'TEXT'"
      class="whitespace-pre-wrap text-sm text-muted-foreground"
    >{{ detail.text }}</pre>

    <!-- MARKDOWN 类型：渲染为 HTML -->
    <div
      v-else-if="detail.type === 'MARKDOWN'"
      class="prose prose-sm"
      v-html="detail.html"
    />

    <!-- CARD 类型：卡片布局 -->
    <div v-else-if="detail.type === 'CARD'" class="flex flex-col gap-2">
      <h4 v-if="detail.title" class="text-sm font-semibold">{{ detail.title }}</h4>
      <p v-if="detail.body" class="text-sm text-muted-foreground">{{ detail.body }}</p>
      <div v-if="detail.actions && detail.actions.length > 0" class="flex flex-wrap gap-2 pt-1">
        <Button
          v-for="(action, index) in detail.actions"
          :key="index"
          variant="outline"
          size="sm"
          as="a"
          :href="action.url"
          target="_blank"
          rel="noopener noreferrer"
        >
          {{ action.label }}
        </Button>
      </div>
    </div>

    <!-- UNKNOWN 类型：原始文本降级显示 -->
    <pre
      v-else
      class="whitespace-pre-wrap text-sm text-muted-foreground"
    >{{ detail.text }}</pre>

    <!-- 主动提醒反馈操作条 -->
    <div
      v-if="isProactiveReminder"
      class="mt-sm flex items-center gap-sm border-t pt-sm"
    >
      <span class="text-xs text-muted-foreground">这条提醒对你有帮助吗？</span>
      <div class="flex gap-xs">
        <Button
          v-for="btn in feedbackButtons"
          :key="btn.type"
          variant="ghost"
          size="sm"
          class="h-xl gap-xs px-sm text-xs"
          :class="{
            'bg-accent text-accent-foreground': feedbackType === btn.type,
            'opacity-40': feedbackType && feedbackType !== btn.type,
          }"
          :disabled="submitting || !!feedbackType"
          @click="handleFeedback(btn.type)"
        >
          <component :is="btn.icon" class="size-sm" />
          {{ btn.label }}
        </Button>
      </div>
    </div>
  </div>
</template>
