<script setup lang="ts">
import { ref, onMounted, type Component } from 'vue'
import { storeToRefs } from 'pinia'
import { Lightbulb, GitBranch, Settings, Bell, Loader2, AlertCircle } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { useNotificationStore } from '@/stores/notification'
import { parseNotificationContent } from '@/utils/notificationContent'
import { formatRelativeTime } from '@/utils/relativeTime'
import NotificationDetail from '@/components/notification/NotificationDetail.vue'
import type { NotificationItem, NotificationUrgency } from '@/types'

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

/** 紧急程度筛选标签定义 */
const urgencyFilters = [
  { label: '全部', value: undefined },
  { label: '紧急', value: 'HIGH' },
  { label: '中等', value: 'MEDIUM' },
  { label: '低', value: 'LOW' },
] as const

/** 当前激活的筛选值 */
const activeFilter = ref<string | undefined>(undefined)

/** 紧急程度 badge 样式映射 */
const urgencyConfig: Record<NotificationUrgency, { label: string; variant: 'destructive' | 'default' | 'secondary' }> = {
  HIGH: { label: '紧急', variant: 'destructive' },
  MEDIUM: { label: '中等', variant: 'default' },
  LOW: { label: '低', variant: 'secondary' },
}

/** 通知类型图标映射 */
const typeIconMap: Record<string, Component> = {
  workflow: GitBranch,
  system: Settings,
  task: Lightbulb,
}

/** 获取通知类型图标，未知 typeId 回退到 Bell */
function getTypeIcon(typeId?: string): Component {
  return (typeId && typeIconMap[typeId]) || Bell
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

/** 切换紧急程度筛选 */
async function handleFilterChange(value: string | undefined) {
  activeFilter.value = value
  currentPage.value = 0
  hasMore.value = true
  expandedId.value = null
  error.value = null
  try {
    await notificationStore.fetchNotifications(0, activeFilter.value)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载通知列表失败'
  }
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
    await notificationStore.fetchNotifications(nextPage, activeFilter.value)
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
    await notificationStore.fetchNotifications(currentPage.value, activeFilter.value)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载通知列表失败'
  }
}

// 组件挂载时加载初始数据
onMounted(async () => {
  try {
    await notificationStore.fetchNotifications(0, activeFilter.value)
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

    <!-- 紧急程度筛选标签栏 -->
    <div class="flex items-center gap-1 border-b px-4 py-2">
      <button
        v-for="filter in urgencyFilters"
        :key="filter.label"
        class="rounded-md px-2.5 py-1 text-xs transition-colors"
        :class="activeFilter === filter.value
          ? 'border-b-2 border-primary font-medium text-primary'
          : 'text-muted-foreground hover:text-foreground'"
        @click="handleFilterChange(filter.value)"
      >
        {{ filter.label }}
      </button>
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
        {{ activeFilter ? '该分类下暂无通知' : '暂无通知' }}
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
              <component :is="getTypeIcon(item.typeId)" class="size-4 text-muted-foreground" />
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

          <!-- 展开的通知详情 -->
          <div
            v-if="expandedId === item.id"
            class="border-b bg-muted/30"
          >
            <NotificationDetail :content-json="item.contentJson" />
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
