<script setup lang="ts">
import { computed } from 'vue'
import { Button } from '@/components/ui/button'
import { parseNotificationDetail } from '@/utils/notificationContent'

/** 组件 Props */
interface Props {
  contentJson: string
}

const props = defineProps<Props>()

/** 解析通知详情内容 */
const detail = computed(() => parseNotificationDetail(props.contentJson))
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
  </div>
</template>
