<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import {
  Archive,
  ChevronRight,
  Clock3,
  Pencil,
  Pin,
  Plus,
  Search,
  Settings,
  Trash2,
} from 'lucide-vue-next'
import ZhiweiMark from '@/components/brand/ZhiweiMark.vue'
import NotificationBell from '@/components/notification/NotificationBell.vue'
import ThemeToggle from '@/components/global/ThemeToggle.vue'
import WhisperDownloadCard from '@/components/global/WhisperDownloadCard.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { useChatStore } from '@/stores/chat'
import type { ChatSession } from '@/types'
import { sidebarNavGroups, isNavItemActive } from './appNavigation'

const emit = defineEmits<{
  close: []
}>()

const route = useRoute()
const router = useRouter()
const chatStore = useChatStore()

const searchQuery = ref('')
const renamingId = ref<string | null>(null)
const renameTitle = ref('')
const showArchived = ref(false)
/** 各分组的折叠状态，默认全部展开 */
const collapsedGroups = ref<Set<string>>(new Set())

onMounted(async () => {
  if (chatStore.sessions.length === 0) {
    await chatStore.loadSessions()
  }
})

watch(
  () => route.params.sessionId as string | undefined,
  sessionId => {
    if (sessionId && chatStore.activeSessionId !== sessionId) {
      chatStore.activeSessionId = sessionId
    }
  },
  { immediate: true },
)

/* ── 会话列表 ── */

const sortedSessions = computed(() => [...chatStore.sessions].sort((left, right) => {
  if (left.pinned && !right.pinned) return -1
  if (!left.pinned && right.pinned) return 1
  return new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime()
}))

function matchesSearch(session: ChatSession) {
  const query = searchQuery.value.trim().toLowerCase()
  if (!query) return true
  return session.title.toLowerCase().includes(query)
    || session.lastMessagePreview?.toLowerCase().includes(query)
}

const activeSessions = computed(() => sortedSessions.value.filter(s => !s.archived && matchesSearch(s)))
const archivedSessions = computed(() => sortedSessions.value.filter(s => s.archived && matchesSearch(s)))
/** 侧边栏只显示最近若干条，"查看全部"跳转到 ConversationsView */
const visibleSessions = computed(() => activeSessions.value.slice(0, 8))

function formatRelativeTime(isoString?: string) {
  if (!isoString) return ''
  const date = new Date(isoString)
  const diffMs = Date.now() - date.getTime()
  const diffMin = Math.floor(diffMs / 60000)
  const diffHour = Math.floor(diffMs / 3600000)
  const diffDay = Math.floor(diffMs / 86400000)
  if (diffMin < 1) return '刚刚'
  if (diffMin < 60) return `${diffMin} 分钟前`
  if (diffHour < 24) return `${diffHour} 小时前`
  if (diffDay === 1) return '昨天'
  if (diffDay < 7) return `${diffDay} 天前`
  return `${date.getMonth() + 1}月${date.getDate()}日`
}

/* ── 会话操作 ── */

function handleNewConversation() {
  router.push({ name: 'newConversation' })
  emit('close')
}

function selectSession(sessionId: string) {
  chatStore.activeSessionId = sessionId
  router.push({ name: 'conversationDetail', params: { sessionId } })
  emit('close')
}

function isCurrentSession(sessionId: string) {
  return route.name === 'conversationDetail' && chatStore.activeSessionId === sessionId
}

function startRename(session: ChatSession) {
  renamingId.value = session.id
  renameTitle.value = session.title || '新对话'
}

async function confirmRename(sessionId: string) {
  const title = renameTitle.value.trim()
  renamingId.value = null
  if (!title) return
  await chatStore.updateSession(sessionId, { title })
}

function cancelRename() {
  renamingId.value = null
}

async function handleDelete(sessionId: string) {
  await chatStore.deleteSession(sessionId)
  if (route.params.sessionId === sessionId) {
    if (chatStore.activeSessionId) {
      router.replace({ name: 'conversationDetail', params: { sessionId: chatStore.activeSessionId } })
    } else {
      router.replace({ name: 'conversations' })
    }
  }
}

function openAllConversations() {
  router.push({ name: 'conversations' })
  emit('close')
}

/* ── 导航分组 ── */

function toggleGroup(groupId: string) {
  const next = new Set(collapsedGroups.value)
  if (next.has(groupId)) {
    next.delete(groupId)
  } else {
    next.add(groupId)
  }
  collapsedGroups.value = next
}

function navigateTo(path: string) {
  router.push(path)
  emit('close')
}
</script>

<template>
  <div class="sidebar-panel flex h-full w-[var(--sidebar-width)] flex-col">
    <!-- Whisper 下载进度 -->
    <WhisperDownloadCard />

    <!-- 顶部：品牌 + 新建对话 -->
    <div class="flex items-center justify-between border-b border-sidebar-border/40 px-lg py-md">
      <RouterLink to="/conversations/new" class="flex items-center gap-sm" @click="emit('close')">
        <ZhiweiMark class="size-[1.3rem] text-primary" />
        <span class="text-sm font-semibold tracking-tight text-foreground">知微</span>
      </RouterLink>
      <Button
        type="button"
        size="sm"
        class="h-8 rounded-xl px-3 text-xs"
        @click="handleNewConversation"
      >
        <Plus class="size-3.5" />
        新对话
      </Button>
    </div>

    <!-- 搜索框 -->
    <div class="px-md pt-md pb-xs">
      <div class="relative">
        <Search class="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
        <Input
          v-model="searchQuery"
          type="search"
          placeholder="搜索会话..."
          class="h-8 rounded-xl border-border/40 bg-background/60 pl-8 text-xs shadow-none"
        />
      </div>
    </div>

    <!-- 可滚动区域 -->
    <div class="flex-1 overflow-y-auto px-sm py-sm scrollbar-thin">
      <!-- 最近对话 -->
      <section class="mb-md">
        <div class="flex items-center justify-between px-sm py-xs">
          <span class="text-[11px] font-medium uppercase tracking-wider text-muted-foreground">最近对话</span>
        </div>

        <div v-if="visibleSessions.length === 0" class="px-sm py-md text-xs text-muted-foreground">
          {{ searchQuery ? '没有匹配的对话' : '还没有对话' }}
        </div>

        <div v-else class="space-y-0.5">
          <article
            v-for="session in visibleSessions"
            :key="session.id"
            class="session-item group cursor-pointer"
            :class="{ 'session-item-active': isCurrentSession(session.id) }"
            @click="selectSession(session.id)"
          >
            <div class="flex items-center gap-sm min-w-0">
              <div class="min-w-0 flex-1">
                <Input
                  v-if="renamingId === session.id"
                  v-model="renameTitle"
                  class="h-7 border-border/55 bg-background/75 text-xs"
                  @blur="confirmRename(session.id)"
                  @click.stop
                  @keyup.enter.stop="confirmRename(session.id)"
                  @keyup.esc.stop="cancelRename"
                />
                <template v-else>
                  <div class="flex items-center gap-xs">
                    <span class="truncate text-[13px] font-medium text-foreground">
                      {{ session.title || '新对话' }}
                    </span>
                    <Pin v-if="session.pinned" class="size-3 shrink-0 text-primary" />
                  </div>
                  <div class="mt-0.5 text-[11px] text-muted-foreground">
                    {{ formatRelativeTime(session.updatedAt) }}
                  </div>
                </template>
              </div>

              <div class="flex shrink-0 items-center gap-0.5 opacity-0 transition-opacity duration-150 group-hover:opacity-100">
                <button
                  type="button"
                  class="rounded-lg p-1 text-muted-foreground hover:bg-card/72 hover:text-foreground"
                  title="重命名"
                  @click.stop="startRename(session)"
                >
                  <Pencil class="size-3" />
                </button>
                <button
                  type="button"
                  class="rounded-lg p-1 text-muted-foreground hover:bg-destructive/8 hover:text-destructive"
                  title="删除"
                  @click.stop="handleDelete(session.id)"
                >
                  <Trash2 class="size-3" />
                </button>
              </div>
            </div>
          </article>
        </div>

        <!-- 查看全部 -->
        <button
          v-if="activeSessions.length > 8"
          type="button"
          class="mt-xs flex w-full items-center gap-xs rounded-xl px-sm py-xs text-xs text-muted-foreground transition-colors hover:bg-accent/60 hover:text-foreground"
          @click="openAllConversations"
        >
          查看全部 {{ activeSessions.length }} 个对话
          <ChevronRight class="size-3" />
        </button>

        <!-- 归档折叠 -->
        <button
          v-if="archivedSessions.length > 0"
          type="button"
          class="mt-xs flex w-full items-center justify-between rounded-xl px-sm py-xs text-xs text-muted-foreground transition-colors hover:bg-accent/60"
          @click="showArchived = !showArchived"
        >
          <span class="flex items-center gap-xs">
            <Archive class="size-3" />
            已归档
          </span>
          <span>{{ archivedSessions.length }}</span>
        </button>
        <Transition
          enter-active-class="transition-all duration-200 ease-out overflow-hidden"
          enter-from-class="max-h-0 opacity-0"
          enter-to-class="max-h-[400px] opacity-100"
          leave-active-class="transition-all duration-150 ease-in overflow-hidden"
          leave-from-class="max-h-[400px] opacity-100"
          leave-to-class="max-h-0 opacity-0"
        >
        <div v-if="showArchived" class="mt-xs space-y-0.5">
          <article
            v-for="session in archivedSessions"
            :key="session.id"
            class="session-item cursor-pointer opacity-70"
            @click="selectSession(session.id)"
          >
            <div class="flex items-center gap-sm">
              <span class="truncate text-[13px] text-foreground">{{ session.title || '新对话' }}</span>
            </div>
          </article>
        </div>
        </Transition>
      </section>

      <!-- 导航分组 -->
      <section v-for="group in sidebarNavGroups" :key="group.id" class="mb-sm">
        <button
          type="button"
          class="flex w-full items-center justify-between rounded-xl px-sm py-xs text-[11px] font-medium uppercase tracking-wider text-muted-foreground transition-colors hover:text-foreground"
          @click="toggleGroup(group.id)"
        >
          {{ group.label }}
          <ChevronRight
            class="size-3 transition-transform duration-200"
            :class="{ 'rotate-90': !collapsedGroups.has(group.id) }"
          />
        </button>

        <Transition
          enter-active-class="transition-all duration-200 ease-out overflow-hidden"
          enter-from-class="max-h-0 opacity-0"
          enter-to-class="max-h-[400px] opacity-100"
          leave-active-class="transition-all duration-150 ease-in overflow-hidden"
          leave-from-class="max-h-[400px] opacity-100"
          leave-to-class="max-h-0 opacity-0"
        >
          <div v-if="!collapsedGroups.has(group.id)" class="mt-0.5 space-y-0.5">
            <RouterLink
              v-for="item in group.items"
              :key="item.path"
              :to="item.path"
              class="nav-link w-full"
              :class="{ 'nav-link-active': isNavItemActive(route.path, item) }"
              @click="emit('close')"
            >
              <component :is="item.icon" class="size-[16px] shrink-0 text-muted-foreground" />
              <span class="truncate text-[13px]">{{ item.label }}</span>
            </RouterLink>
          </div>
        </Transition>
      </section>
    </div>

    <!-- 底部工具栏 -->
    <div class="flex items-center justify-between border-t border-sidebar-border/40 px-md py-sm">
      <div class="flex items-center gap-xs">
        <NotificationBell />
        <ThemeToggle />
      </div>
      <RouterLink
        to="/settings/general"
        class="flex items-center gap-xs rounded-xl px-sm py-xs text-xs text-muted-foreground transition-colors hover:bg-accent/60 hover:text-foreground"
        @click="emit('close')"
      >
        <Settings class="size-4" />
        设置
      </RouterLink>
    </div>
  </div>
</template>
