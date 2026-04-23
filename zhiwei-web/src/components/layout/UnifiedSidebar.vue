<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import {
  Archive,
  ChevronRight,
  Clock,
  Pencil,
  Pin,
  Plus,
  Search,
  Settings,
  Trash2,
} from 'lucide-vue-next'
import ZhiweiMark from '@/components/brand/ZhiweiMark.vue'
import NotificationBell from '@/components/notification/NotificationBell.vue'
import WhisperDownloadCard from '@/components/global/WhisperDownloadCard.vue'
import ProjectSection from '@/components/sidebar/ProjectSection.vue'
import { Input } from '@/components/ui/input'
import { useChatStore } from '@/stores/chat'
import type { ChatSession } from '@/types'
import { manageNavGroups, manageRoutePrefixes, isNavItemActive } from './appNavigation'

const emit = defineEmits<{
  close: []
}>()

const route = useRoute()
const router = useRouter()
const chatStore = useChatStore()

const searchQuery = ref('')
const searchVisible = ref(false)
const renamingId = ref<string | null>(null)
const renameTitle = ref('')
const showArchived = ref(false)
/** 各管理分组的折叠状态，默认全部展开 */
const collapsedGroups = ref<Set<string>>(new Set())
/** 创建项目对话框显示状态 —— 由 Task 19 的 CreateProjectDialog 消费 */
const showCreateProjectDialog = ref(false)

type SidebarTab = 'chat' | 'manage'
const activeTab = ref<SidebarTab>('chat')

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

/* ── 路由驱动 Tab 自动切换 ── */
watch(
  () => route.path,
  path => {
    if (manageRoutePrefixes.some(prefix => path === prefix || path.startsWith(prefix + '/'))) {
      activeTab.value = 'manage'
    } else {
      activeTab.value = 'chat'
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

/* ── 时间分组 ── */

interface SessionGroup {
  label: string
  sessions: ChatSession[]
}

/** 侧边栏最多显示的对话总数 */
const MAX_VISIBLE = 10

const groupedSessions = computed<SessionGroup[]>(() => {
  const now = new Date()
  const todayStart = startOfDay(now)
  const yesterdayStart = startOfDay(new Date(now.getTime() - 86400000))
  const weekStart = startOfDay(new Date(now.getTime() - 7 * 86400000))

  const buckets = { today: [] as ChatSession[], yesterday: [] as ChatSession[], week: [] as ChatSession[], earlier: [] as ChatSession[] }

  for (const s of activeSessions.value) {
    const d = new Date(s.updatedAt)
    if (d >= todayStart) buckets.today.push(s)
    else if (d >= yesterdayStart) buckets.yesterday.push(s)
    else if (d >= weekStart) buckets.week.push(s)
    else buckets.earlier.push(s)
  }

  // 按优先级分配配额，总共最多 MAX_VISIBLE 条
  const result: SessionGroup[] = []
  let remaining = MAX_VISIBLE
  for (const [label, items] of [['今天', buckets.today], ['昨天', buckets.yesterday], ['过去 7 天', buckets.week], ['更早', buckets.earlier]] as const) {
    if (items.length && remaining > 0) {
      result.push({ label, sessions: items.slice(0, remaining) })
      remaining -= Math.min(items.length, remaining)
    }
  }
  return result
})

function startOfDay(d: Date): Date {
  const r = new Date(d)
  r.setHours(0, 0, 0, 0)
  return r
}

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
  if (diffDay < 30) return `${diffDay} 天前`
  return `${date.getMonth() + 1}月${date.getDate()}日`
}

/* ── 会话操作 ── */

function handleNewConversation() {
  // 必须先清空 activeSessionId，否则同路由导航被忽略时消息会发到旧会话
  chatStore.activeSessionId = null
  router.push({ name: 'newConversation' }).catch(() => {
    // 已在 newConversation 路由，导航被忽略但状态已通过上方 activeSessionId=null 重置
  })
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

/** 从管理 Tab 切换到对话 Tab 时，导航到新建对话欢迎页 */
function switchToChatTab() {
  if (activeTab.value === 'manage') {
    chatStore.activeSessionId = null
    router.push({ name: 'newConversation' })
  }
  activeTab.value = 'chat'
}

/** 从对话 Tab 切换到管理 Tab 时，导航到通用设置页（管理首页） */
function switchToManageTab() {
  if (activeTab.value === 'chat') {
    router.push('/settings/general')
  }
  activeTab.value = 'manage'
}

/* ── 管理 Tab ── */

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

function openSettings() {
  activeTab.value = 'manage'
  router.push('/settings/general')
  emit('close')
}
</script>

<template>
  <div class="sidebar-panel flex h-full w-[var(--sidebar-width)] flex-col">
    <!-- Whisper 下载进度 -->
    <WhisperDownloadCard />

    <!-- 顶部：品牌 -->
    <div class="sidebar-header">
      <RouterLink to="/conversations/new" class="flex items-center gap-sm" @click="emit('close')">
        <ZhiweiMark class="size-[1.3rem] text-primary" />
        <span class="text-sm font-semibold tracking-tight text-foreground">知微</span>
      </RouterLink>
      <NotificationBell />
    </div>

    <!-- Tab 切换器 -->
    <div class="tab-bar">
      <button
        type="button"
        class="tab-trigger"
        :class="{ 'tab-trigger--active': activeTab === 'chat' }"
        @click="switchToChatTab"
      >
        对话
      </button>
      <button
        type="button"
        class="tab-trigger"
        :class="{ 'tab-trigger--active': activeTab === 'manage' }"
        @click="switchToManageTab"
      >
        管理
      </button>
    </div>

    <!-- ════════ 对话 Tab ════════ -->
    <template v-if="activeTab === 'chat'">
      <!-- 顶部操作行 -->
      <div class="qw-actions">
        <button type="button" class="qw-action-row" @click="handleNewConversation">
          <Plus class="qw-action-icon" />
          <span>新建对话</span>
        </button>
        <button type="button" class="qw-action-row qw-action-row--search" @click="searchVisible = !searchVisible">
          <Search class="qw-action-icon" />
          <span>搜索对话</span>
        </button>
        <!-- 定时任务入口占位 —— 路由由 Plan 2 建，当前点击会 404 -->
        <button
          type="button"
          class="qw-action-row"
          data-testid="scheduled-tasks-entry"
          @click="navigateTo('/scheduled-tasks')"
        >
          <Clock class="qw-action-icon" />
          <span>定时任务</span>
        </button>
      </div>

      <!-- 搜索框 -->
      <div v-if="searchVisible" class="px-lg pb-sm">
        <Input
          v-model="searchQuery"
          type="search"
          placeholder="输入关键词..."
          class="h-[34px] rounded-lg border-border/30 bg-background/80 text-sm shadow-none"
          autofocus
        />
      </div>

      <!-- 项目分组 -->
      <ProjectSection @create="showCreateProjectDialog = true" />

      <!-- TODO(Task 19): <CreateProjectDialog v-model:open="showCreateProjectDialog" /> -->

      <!-- 对话列表 -->
      <div class="flex-1 overflow-y-auto pb-sm scrollbar-thin">
        <div v-if="groupedSessions.length === 0" class="px-lg py-2xl text-center text-sm text-muted-foreground">
          {{ searchQuery ? '没有匹配的对话' : '还没有对话' }}
        </div>

        <section v-for="group in groupedSessions" :key="group.label">
          <div class="qw-section-label">{{ group.label }}</div>
          <div class="qw-session-list">
            <article
              v-for="session in group.sessions"
              :key="session.id"
              class="qw-session group"
              :class="{ 'qw-session--active': isCurrentSession(session.id) }"
              @click="selectSession(session.id)"
            >
              <Input
                v-if="renamingId === session.id"
                v-model="renameTitle"
                class="h-8 border-border/40 bg-background text-sm"
                @blur="confirmRename(session.id)"
                @click.stop
                @keyup.enter.stop="confirmRename(session.id)"
                @keyup.esc.stop="cancelRename"
              />
              <template v-else>
                <span class="qw-session-title">
                  {{ session.title || '新对话' }}
                </span>
                <Pin v-if="session.pinned" class="size-3 shrink-0 text-primary/60" />
                <!-- 默认：时间 / hover：操作按钮 -->
                <span class="qw-session-time group-hover:hidden">
                  {{ formatRelativeTime(session.updatedAt) }}
                </span>
                <div class="hidden shrink-0 items-center group-hover:flex">
                  <button
                    type="button"
                    class="qw-session-action"
                    title="重命名"
                    @click.stop="startRename(session)"
                  >
                    <Pencil class="size-3.5" />
                  </button>
                  <button
                    type="button"
                    class="qw-session-action hover:text-destructive"
                    title="删除"
                    @click.stop="handleDelete(session.id)"
                  >
                    <Trash2 class="size-3.5" />
                  </button>
                </div>
              </template>
            </article>
          </div>
        </section>

        <!-- 查看全部 -->
        <div v-if="activeSessions.length > MAX_VISIBLE" class="px-lg pt-sm">
          <button type="button" class="qw-link" @click="openAllConversations">
            查看全部 {{ activeSessions.length }} 个对话
            <ChevronRight class="size-3.5" />
          </button>
        </div>

        <!-- 归档 -->
        <div v-if="archivedSessions.length > 0" class="px-lg pt-xs">
          <button type="button" class="qw-link justify-between" @click="showArchived = !showArchived">
            <span class="flex items-center gap-sm">
              <Archive class="size-3.5" />
              已归档
            </span>
            <span>{{ archivedSessions.length }}</span>
          </button>
        </div>
        <Transition
          enter-active-class="transition-all duration-200 ease-out overflow-hidden"
          enter-from-class="max-h-0 opacity-0"
          enter-to-class="max-h-[400px] opacity-100"
          leave-active-class="transition-all duration-150 ease-in overflow-hidden"
          leave-from-class="max-h-[400px] opacity-100"
          leave-to-class="max-h-0 opacity-0"
        >
        <div v-if="showArchived" class="qw-session-list px-sm">
          <article
            v-for="session in archivedSessions"
            :key="session.id"
            class="qw-session opacity-50"
            @click="selectSession(session.id)"
          >
            <span class="qw-session-title">{{ session.title || '新对话' }}</span>
          </article>
        </div>
        </Transition>
      </div>
    </template>

    <!-- ════════ 管理 Tab ════════ -->
    <template v-else>
      <div class="flex-1 overflow-y-auto px-sm py-sm scrollbar-thin">
        <section v-for="group in manageNavGroups" :key="group.id" class="mb-sm">
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
    </template>

    <!-- 底部工具栏 -->
    <div class="flex items-center justify-between border-t border-sidebar-border/40 px-md py-sm">
      <div />
      <button
        type="button"
        class="flex items-center gap-xs rounded-xl px-sm py-xs text-xs text-muted-foreground transition-colors hover:bg-accent/60 hover:text-foreground"
        @click="openSettings"
      >
        <Settings class="size-4" />
        设置
      </button>
    </div>
  </div>
</template>

<style scoped>
/* ── 头部 ── */
.sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.875rem 1rem 0.875rem 1.125rem;
}

/* ── Tab 栏 ── */
.tab-bar {
  display: flex;
  gap: 2px;
  margin: 0 0.75rem 0.375rem;
  padding: 3px;
  border-radius: 0.5rem;
  background: hsl(from var(--muted) h s l / 0.5);
}

.tab-trigger {
  flex: 1;
  padding: 0.3rem 0;
  border-radius: 0.375rem;
  font-size: 13px;
  font-weight: 500;
  text-align: center;
  color: var(--muted-foreground);
  background: transparent;
  border: none;
  transition: all 160ms ease;
  cursor: pointer;
}

.tab-trigger:hover {
  color: var(--foreground);
}

.tab-trigger--active {
  color: var(--foreground);
  background: hsl(from var(--background) h s l / 0.95);
  box-shadow: 0 1px 2px hsl(var(--shadow-color) / 0.06);
}

/* ═══ Qwen 风格对话区 ═══ */

.qw-actions {
  padding: 0.25rem 0.625rem;
}

.qw-action-row {
  display: flex;
  align-items: center;
  gap: 0.65rem;
  width: 100%;
  padding: 0.55rem 0.75rem;
  border-radius: 0.5rem;
  border: none;
  background: transparent;
  font-size: 14px;
  color: var(--foreground);
  cursor: pointer;
  transition: background 120ms ease;
}

.qw-action-row:hover {
  background: hsl(from var(--muted) h s l / 0.5);
}

.qw-action-row--search {
  background: hsl(from var(--muted) h s l / 0.4);
}

.qw-action-icon {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
  color: var(--muted-foreground);
}

.qw-section-label {
  padding: 0.75rem 0.75rem 0.35rem 1.375rem;
  font-size: 13px;
  color: var(--muted-foreground);
}

.qw-session-list {
  padding: 0 0.625rem;
}

.qw-session {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.55rem 0.75rem;
  border-radius: 0.5rem;
  cursor: pointer;
  transition: background 120ms ease, box-shadow 120ms ease;
}

.qw-session:hover {
  background: hsl(from var(--background) h s l / 0.8);
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
}

.qw-session--active {
  background: hsl(from var(--background) h s l / 0.95);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.qw-session--active:hover {
  background: hsl(from var(--background) h s l / 0.98);
  box-shadow: 0 3px 10px rgba(0, 0, 0, 0.12);
}

.qw-session-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
  line-height: 22px;
  color: var(--foreground);
}

.qw-session-time {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--muted-foreground);
}

.qw-session-action {
  padding: 0.25rem;
  border-radius: 0.3rem;
  color: var(--muted-foreground);
  transition: color 120ms ease;
}

.qw-session-action:hover {
  color: var(--foreground);
}

.qw-link {
  display: flex;
  align-items: center;
  gap: 0.3rem;
  width: 100%;
  padding: 0;
  border: none;
  background: transparent;
  font-size: 13px;
  color: var(--muted-foreground);
  cursor: pointer;
  transition: color 120ms ease;
}

.qw-link:hover {
  color: var(--foreground);
}
</style>
