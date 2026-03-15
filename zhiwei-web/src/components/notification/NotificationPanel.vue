<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { Loader2 } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { useNotificationStore } from '@/stores/notification'
import { parseNotificationContent } from '@/utils/notificationContent'
import type { NotificationItem, NotificationUrgency } from '@/types'

const notificationStore = useNotificationStore()
const { notifications, loading, unreadCount } = storeToRefs(notificationStore)

/** 当前分页页码 */
const currentPage = ref(0)
/** 是否还有更多数据可加载 */
const hasMore = ref(true)

/** 紧急程度 badge 样式映射 */
const urgencyConfig: Record<NotificationUrgency, { label: string; variant: 'destructive' | 'default' | 'secondary' }> = {
  HIGH: { label: '紧急', variant: 'destructive' },
  MEDIUM: { label: '中等', variant: 'default' },
  LOW: { label: '低', variant: 'secondary' },
}

/** 计算相对时间 */
function formatRelativeTime(sentAt: string): string {
  const now = Date.now()
  const sent = new Date(sentAt).getTime()
  const diffMs = now - sent

  if (diffMs < 0 || diffMs < 60_000) return '刚刚'

  const minutes = Math.floor(diffMs / 60_000)
  if (minutes < 60) return `${minutes}分钟前`

  const hours = Math.floor(diffMs / 3_600_000)
  if (hours < 24) return `${hours}小时前`

  const days = Math.floor(diffMs / 86_400_000)
  return `${days}天前`
}

/** 获取通知内容摘要 */
function getSummary(item: NotificationItem): string {
  return parseNotificationContent(item.contentJson).summary
}

/** 点击通知条目，标记已读 */
function handleClickNotification(item: NotificationItem) {
  if (item.readStatus === 'UNREAD') {
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
  await notificationStore.fetchNotifications(nextPage)
  // 如果加载后列表长度没变，说明没有更多数据
  if (notifications.value.length === prevLength) {
    hasMore.value = false
  } else {
    currentPage.value = nextPage
  }
}

// 组件挂载时加载初始数据
onMounted(() => {
  notificationStore.fetchNotifications()
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
        class="flex items-center justify-center py-8"
      >
        <Loader2 class="size-5 animate-spin text-muted-foreground" />
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
          class="flex cursor-pointer gap-3 border-b px-4 py-3 transition-colors last:border-b-0 hover:bg-accent/50"
          :class="{ 'bg-accent/20': item.readStatus === 'UNREAD' }"
          @click="handleClickNotification(item)"
        >
          <!-- 未读蓝色圆点 -->
          <div class="flex shrink-0 pt-1.5">
            <span
              v-if="item.readStatus === 'UNREAD'"
              class="block h-2 w-2 rounded-full bg-blue-500"
            />
            <span v-else class="block h-2 w-2" />
          </div>

          <!-- 通知内容 -->
          <div class="flex min-w-0 flex-1 flex-col gap-1">
            <p class="truncate text-sm">{{ getSummary(item) }}</p>
            <div class="flex items-center gap-2">
              <Badge
                :variant="urgencyConfig[item.urgency].variant"
                class="px-1.5 py-0 text-[10px]"
              >
                {{ urgencyConfig[item.urgency].label }}
              </Badge>
              <span class="text-xs text-muted-foreground">
                {{ formatRelativeTime(item.sentAt) }}
              </span>
            </div>
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
