<script setup lang="ts">
import { ref, onMounted, type Component } from 'vue'
import { storeToRefs } from 'pinia'
import {
  Lightbulb, GitBranch, Settings, Bell, Loader2, AlertCircle,
  MessageCircleQuestion, Sparkles, ClipboardCheck, FileBarChart,
  BookOpenCheck, BellRing,
} from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { useNotificationStore } from '@/stores/notification'
import { parseNotificationContent } from '@/utils/notificationContent'
import { formatRelativeTime } from '@/utils/relativeTime'
import { getBehaviorTheme, getBehaviorLabel, behaviorBadgeStyle } from '@/constants/behaviorTheme'
import NotificationDetail from '@/components/notification/NotificationDetail.vue'
import type { NotificationItem } from '@/types'

const notificationStore = useNotificationStore()
const { notifications, loading, unreadCount } = storeToRefs(notificationStore)

/** 当前分页页码 */
const currentPage = ref(0)
/** 是否还有更多数据可加载 */
const hasMore = ref(true)

/** 错误状态 */
const error = ref<string | null>(null)

/** 当前展开的通知 ID */
const expandedId = ref<string | null>(null)

/** 通知类型图标映射 */
const typeIconMap: Record<string, Component> = {
  workflow: GitBranch,
  system: Settings,
  task: Lightbulb,
}

/** 行为插件图标映射 */
const behaviorIconMap: Record<string, Component> = {
  'follow-up': MessageCircleQuestion,
  'insight': Sparkles,
  'clipboard': ClipboardCheck,
  'report': FileBarChart,
  'memory-attention': BookOpenCheck,
  'reminder': BellRing,
}

/** 从通知项中提取行为名 */
function getBehaviorFromItem(item: NotificationItem): string | undefined {
  if (item.typeId !== 'proactive_action' && item.typeId !== 'proactive_reminder') return undefined
  try {
    const metadata = item.metadataJson ? JSON.parse(item.metadataJson) : {}
    return metadata.behaviorName as string | undefined
  } catch { return undefined }
}

/** 获取通知类型图标，主动推送优先使用行为图标 */
function getTypeIcon(item: NotificationItem): Component {
  const behavior = getBehaviorFromItem(item)
  if (behavior && behaviorIconMap[behavior]) return behaviorIconMap[behavior]
  return (item.typeId && typeIconMap[item.typeId]) || Bell
}

/** 获取通知内容摘要 */
function getSummary(item: NotificationItem): string {
  return parseNotificationContent(item.contentJson).summary
}

/** 点击通知条目，切换展开/收起，展开未读通知时自动标记已读 */
function handleClickNotification(item: NotificationItem) {
  expandedId.value = expandedId.value === item.id ? null : item.id
  if (expandedId.value === item.id && item.readStatus === 'UNREAD') {
    notificationStore.markAsRead(item.id)
  }
}

/** 全部标记已读 */
function handleMarkAllAsRead() {
  notificationStore.markAllAsRead()
}

/** 滚动到底部加载更多 */
function handleScroll(event: Event) {
  if (loading.value || !hasMore.value) return

  const target = event.target as HTMLElement
  const threshold = 20
  if (target.scrollTop + target.clientHeight >= target.scrollHeight - threshold) {
    loadMore()
  }
}

/** 加载下一页 */
async function loadMore() {
  const nextPage = currentPage.value + 1
  const prevLength = notifications.value.length
  try {
    await notificationStore.fetchNotifications(nextPage)
    if (notifications.value.length === prevLength) {
      hasMore.value = false
    } else {
      currentPage.value = nextPage
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载更多通知失败'
  }
}

/** 重试加载 */
async function handleRetry() {
  error.value = null
  try {
    await notificationStore.fetchNotifications(currentPage.value)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载通知列表失败'
  }
}

// 组件挂载时加载初始数据
onMounted(async () => {
  try {
    await notificationStore.fetchNotifications(0)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载通知列表失败'
  }
})
</script>

<template>
  <div class="flex flex-col">
    <!-- 顶部标题栏 -->
    <div class="flex items-center justify-between border-b px-4 py-3">
      <h3 class="text-sm font-semibold">通知</h3>
      <Button
        v-if="unreadCount > 0"
        variant="ghost"
        size="sm"
        class="text-xs text-muted-foreground"
        @click="handleMarkAllAsRead"
      >
        全部已读
      </Button>
    </div>

    <!-- 通知列表 -->
    <div
      class="max-h-96 overflow-y-auto"
      @scroll="handleScroll"
    >
      <!-- 加载中（首次加载） -->
      <div
        v-if="loading && notifications.length === 0"
        class="flex flex-col items-center justify-center gap-2 py-8"
      >
        <Loader2 class="size-5 animate-spin text-muted-foreground" />
        <span class="text-sm text-muted-foreground">加载中...</span>
      </div>

      <!-- 错误状态 -->
      <div
        v-else-if="error"
        class="flex flex-col items-center justify-center gap-2 py-8"
      >
        <AlertCircle class="size-5 text-destructive" />
        <span class="text-sm text-destructive">{{ error }}</span>
        <Button variant="outline" size="sm" @click="handleRetry">
          重试
        </Button>
      </div>

      <!-- 空状态 -->
      <div
        v-else-if="notifications.length === 0"
        class="flex items-center justify-center py-8 text-sm text-muted-foreground"
      >
        暂无通知
      </div>

      <!-- 通知条目列表 -->
      <template v-else>
        <div
          v-for="item in notifications"
          :key="item.id"
        >
          <!-- 通知条目行 -->
          <div
            class="flex cursor-pointer gap-3 border-b px-4 py-3 transition-colors last:border-b-0 hover:bg-accent/50"
            :class="{ 'bg-accent/20': item.readStatus === 'UNREAD' }"
            @click="handleClickNotification(item)"
          >
            <!-- 类型图标 + 未读圆点 -->
            <div class="flex shrink-0 items-start gap-1.5 pt-0.5">
              <span
                v-if="item.readStatus === 'UNREAD'"
                class="mt-1 block h-2 w-2 shrink-0 rounded-full bg-blue-500"
              />
              <span v-else class="mt-1 block h-2 w-2 shrink-0" />
              <component :is="getTypeIcon(item)" class="size-4 text-muted-foreground" />
            </div>

            <!-- 通知内容 -->
            <div class="flex min-w-0 flex-1 flex-col gap-1">
              <div class="flex items-center gap-sm">
                <span
                  v-if="getBehaviorFromItem(item)"
                  class="inline-flex shrink-0 items-center rounded-md px-xs text-[11px] font-semibold leading-5"
                  :style="behaviorBadgeStyle(getBehaviorFromItem(item))"
                >
                  {{ getBehaviorLabel(getBehaviorFromItem(item)) }}
                </span>
                <p class="truncate text-sm">{{ getSummary(item) }}</p>
              </div>
              <div class="flex items-center gap-2 text-xs text-muted-foreground">
                <span>{{ formatRelativeTime(item.sentAt) }}</span>
              </div>
            </div>
          </div>

          <!-- 展开的通知详情 -->
          <div
            v-if="expandedId === item.id"
            class="border-b bg-muted/30"
          >
            <NotificationDetail :content-json="item.contentJson" :notification-item="item" />
          </div>
        </div>

        <!-- 底部加载更多 spinner -->
        <div
          v-if="loading"
          class="flex items-center justify-center py-3"
        >
          <Loader2 class="size-4 animate-spin text-muted-foreground" />
        </div>
      </template>
    </div>
  </div>
</template>
