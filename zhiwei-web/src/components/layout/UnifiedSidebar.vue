<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import {
  Archive,
  ChevronRight,
  Clock,
  Ellipsis,
  Monitor,
  Moon,
  Pencil,
  Pin,
  Plus,
  Search,
  Sun,
  Trash2,
} from 'lucide-vue-next'
import ZhiweiMark from '@/components/brand/ZhiweiMark.vue'
import NotificationBell from '@/components/notification/NotificationBell.vue'
import WhisperDownloadCard from '@/components/global/WhisperDownloadCard.vue'
import ProjectSection from '@/components/sidebar/ProjectSection.vue'
import CreateProjectDialog from '@/components/project/CreateProjectDialog.vue'
import {
  DropdownMenuRoot as DropdownMenu,
  DropdownMenuContent as DropdownMenuContentPrimitive,
  DropdownMenuItem as DropdownMenuItemPrimitive,
  DropdownMenuSeparator as DropdownMenuSeparatorPrimitive,
  DropdownMenuTrigger,
  DropdownMenuPortal,
} from 'reka-ui'
import { Input } from '@/components/ui/input'
import { useChatStore } from '@/stores/chat'
import { useTheme } from '@/composables/useTheme'
import type { ChatSession } from '@/types'
import { manageNavGroups, manageRoutePrefixes, isNavItemActive } from './appNavigation'

const emit = defineEmits<{
  close: []
}>()

const route = useRoute()
const router = useRouter()
const chatStore = useChatStore()
const theme = useTheme()

const searchQuery = ref('')
const searchVisible = ref(false)
const renamingId = ref<string | null>(null)
const renameTitle = ref('')
const showArchived = ref(false)
/** 各管理分组的折叠状态，默认全部展开 */
const collapsedGroups = ref<Set<string>>(new Set())
/** 创建项目对话框显示状态 */
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

/**
 * 侧栏"今天/昨天"分组只显示主账户对话（projectId 为空）。
 * 项目对话归属 {@link ProjectSection} 展开项嵌套展示，避免两处重复。
 */
const sortedSessions = computed(() => [...chatStore.sessions]
  .filter(s => !s.projectId)
  .sort((left, right) => {
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

async function togglePin(session: ChatSession) {
  await chatStore.updateSession(session.id, { pinned: !session.pinned })
}

async function archiveSession(sessionId: string) {
  await chatStore.updateSession(sessionId, { archived: true })
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

/** 从对话 Tab 切换到管理 Tab 时，导航到能力中心。 */
function switchToManageTab() {
  if (activeTab.value === 'chat') {
    router.push('/capabilities')
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

/* ── 展开/折叠过渡动画（基于真实高度，丝滑无跳变） ── */

function onBeforeEnter(el: Element) {
  const htmlEl = el as HTMLElement
  htmlEl.style.height = '0'
  htmlEl.style.opacity = '0'
  htmlEl.style.overflow = 'hidden'
}

function onEnter(el: Element, done: () => void) {
  const htmlEl = el as HTMLElement
  const height = htmlEl.scrollHeight
  void htmlEl.offsetHeight
  htmlEl.style.transition = 'height 280ms cubic-bezier(0.4, 0, 0.2, 1), opacity 280ms cubic-bezier(0.4, 0, 0.2, 1)'
  htmlEl.style.height = `${height}px`
  htmlEl.style.opacity = '1'
  htmlEl.addEventListener('transitionend', done, { once: true })
}

function onAfterEnter(el: Element) {
  const htmlEl = el as HTMLElement
  htmlEl.style.height = ''
  htmlEl.style.overflow = ''
  htmlEl.style.transition = ''
}

function onBeforeLeave(el: Element) {
  const htmlEl = el as HTMLElement
  htmlEl.style.height = `${htmlEl.scrollHeight}px`
  htmlEl.style.overflow = 'hidden'
  void htmlEl.offsetHeight
}

function onLeave(el: Element, done: () => void) {
  const htmlEl = el as HTMLElement
  htmlEl.style.transition = 'height 220ms cubic-bezier(0.4, 0, 0.2, 1), opacity 220ms cubic-bezier(0.4, 0, 0.2, 1)'
  htmlEl.style.height = '0'
  htmlEl.style.opacity = '0'
  htmlEl.addEventListener('transitionend', done, { once: true })
}

function onAfterLeave(el: Element) {
  const htmlEl = el as HTMLElement
  htmlEl.style.height = ''
  htmlEl.style.overflow = ''
  htmlEl.style.opacity = ''
  htmlEl.style.transition = ''
}
</script>

<template>
  <div class="sidebar-panel flex h-full w-[var(--sidebar-width)] flex-col">
    <!-- Whisper 下载进度 -->
    <WhisperDownloadCard />

    <!-- 顶部：品牌（logo + 产品名） -->
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
        <!-- 定时任务全局入口（Plan 2+3 Task A5 已落地 /scheduled-tasks 路由） -->
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

      <!-- 创建项目对话框（Task 19） -->
      <CreateProjectDialog v-model:open="showCreateProjectDialog" />

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
                <!-- 时间 + 更多按钮叠加在同一位置，避免布局跳动 -->
                <div class="relative flex shrink-0 items-center justify-end" style="width: 1.625rem; height: 1.625rem;" @click.stop>
                  <span class="qw-session-time absolute inset-0 flex items-center justify-end transition-opacity duration-150 group-hover:opacity-0">
                    {{ formatRelativeTime(session.updatedAt) }}
                  </span>
                  <div class="absolute inset-0 flex items-center justify-center opacity-0 transition-opacity duration-150 group-hover:opacity-100">
                    <DropdownMenu>
                      <DropdownMenuTrigger as-child>
                        <button type="button" class="qw-session-more" aria-label="更多操作">
                          <Ellipsis class="size-4" />
                        </button>
                      </DropdownMenuTrigger>
                      <DropdownMenuPortal>
                        <DropdownMenuContentPrimitive
                          align="end"
                          side="bottom"
                          :side-offset="4"
                          class="qw-context-menu z-[100]"
                        >
                          <DropdownMenuItemPrimitive class="qw-context-item" @click="startRename(session)">
                            <Pencil class="size-4 text-muted-foreground" />
                            <span>重命名</span>
                          </DropdownMenuItemPrimitive>
                          <DropdownMenuItemPrimitive class="qw-context-item" @click="togglePin(session)">
                            <Pin class="size-4 text-muted-foreground" />
                            <span>{{ session.pinned ? '取消置顶' : '置顶' }}</span>
                          </DropdownMenuItemPrimitive>
                          <DropdownMenuSeparatorPrimitive class="my-1 h-px bg-border/40" />
                          <DropdownMenuItemPrimitive class="qw-context-item qw-context-item--muted" @click="archiveSession(session.id)">
                            <Archive class="size-4" />
                            <span>归档</span>
                          </DropdownMenuItemPrimitive>
                          <DropdownMenuItemPrimitive class="qw-context-item qw-context-item--danger" @click="handleDelete(session.id)">
                            <Trash2 class="size-4" />
                            <span>删除对话</span>
                          </DropdownMenuItemPrimitive>
                        </DropdownMenuContentPrimitive>
                      </DropdownMenuPortal>
                    </DropdownMenu>
                  </div>
                </div>
              </template>
            </article>
          </div>
        </section>

        <!-- 查看全部 -->
        <div v-if="activeSessions.length > MAX_VISIBLE" class="px-lg pt-md">
          <button type="button" class="qw-view-all" @click="openAllConversations">
            <span>查看全部 {{ activeSessions.length }} 个对话</span>
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
          @before-enter="onBeforeEnter"
          @enter="onEnter"
          @after-enter="onAfterEnter"
          @before-leave="onBeforeLeave"
          @leave="onLeave"
          @after-leave="onAfterLeave"
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
      <div class="flex-1 overflow-y-auto px-sm py-md scrollbar-thin">
        <section v-for="(group, index) in manageNavGroups" :key="group.id" class="mb-lg">
          <!-- 分组间分割线（第一个分组不显示） -->
          <div
            v-if="index > 0"
            class="mx-md mb-md h-px bg-gradient-to-r from-transparent via-[hsl(from_var(--border)_h_s_l_/_0.5)] to-transparent"
          />

          <button
            type="button"
            class="group/grp flex w-full items-center justify-between rounded-lg px-md py-sm text-[11px] font-semibold uppercase tracking-wider text-muted-foreground/80 transition-colors hover:text-foreground"
            @click="toggleGroup(group.id)"
          >
            <span class="flex items-center gap-sm">
              <span class="inline-block size-1.5 rounded-full bg-current opacity-40" />
              {{ group.label }}
            </span>
            <ChevronRight
              class="size-3 text-muted-foreground/50 transition-transform duration-200"
              :class="{ 'rotate-90': !collapsedGroups.has(group.id) }"
            />
          </button>

          <Transition
            @before-enter="onBeforeEnter"
            @enter="onEnter"
            @after-enter="onAfterEnter"
            @before-leave="onBeforeLeave"
            @leave="onLeave"
            @after-leave="onAfterLeave"
          >
            <div v-if="!collapsedGroups.has(group.id)" class="mt-xs space-y-px px-xs">
              <RouterLink
                v-for="item in group.items"
                :key="item.path"
                :to="item.path"
                class="nav-link w-full"
                :class="{ 'nav-link-active': isNavItemActive(route.path, item) }"
                @click="emit('close')"
              >
                <component
                  :is="item.icon"
                  class="size-[16px] shrink-0"
                  :class="item.iconColor || group.iconColor || 'text-muted-foreground'"
                />
                <span class="truncate text-[13px]">{{ item.label }}</span>
              </RouterLink>
            </div>
          </Transition>
        </section>
      </div>
    </template>

    <!-- 底部：主题切换（高频功能独占，右对齐） -->
    <div class="sidebar-footer">
      <button
        type="button"
        class="theme-toggle"
        :title="`主题：${theme.mode.value === 'light' ? '浅色' : theme.mode.value === 'dark' ? '深色' : '跟随系统'}`"
        data-testid="sidebar-theme-toggle"
        @click="theme.cycleTheme()"
      >
        <Sun v-if="theme.mode.value === 'light'" class="size-4" />
        <Moon v-else-if="theme.mode.value === 'dark'" class="size-4" />
        <Monitor v-else class="size-4" />
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
  padding: 1rem 1rem 1rem 1.125rem;
}

/* ── Tab 栏 ── */
.tab-bar {
  display: flex;
  gap: 2px;
  margin: 0 0.75rem 0.5rem;
  padding: 3px;
  border-radius: 0.625rem;
  background: hsl(from var(--muted) h s l / 0.55);
  box-shadow: inset 0 1px 2px hsl(var(--shadow-color) / 0.04);
}

.tab-trigger {
  flex: 1;
  padding: 0.35rem 0;
  border-radius: 0.5rem;
  font-size: 13px;
  font-weight: 500;
  text-align: center;
  color: var(--muted-foreground);
  background: transparent;
  border: none;
  transition: all 180ms var(--ease-fluid);
  cursor: pointer;
}

.tab-trigger:hover {
  color: var(--foreground);
}

.tab-trigger--active {
  color: var(--foreground);
  background: hsl(from var(--background) h s l / 0.98);
  box-shadow:
    0 1px 3px hsl(var(--shadow-color) / 0.08),
    0 0 0 1px hsl(from var(--border) h s l / 0.12);
}

/* ═══ Qwen 风格对话区 ═══ */

.qw-actions {
  padding: 0.375rem 0.625rem;
}

.qw-action-row {
  display: flex;
  align-items: center;
  gap: 0.65rem;
  width: 100%;
  padding: 0.55rem 0.75rem;
  border-radius: 0.5rem;
  border: 1px solid transparent;
  background: transparent;
  font-size: 14px;
  color: var(--foreground);
  cursor: pointer;
  transition:
    background 160ms var(--ease-fluid),
    border-color 160ms var(--ease-fluid),
    transform 160ms var(--ease-fluid);
}

.qw-action-row:hover {
  background: linear-gradient(135deg, hsl(from var(--card) h s l / 0.6), hsl(from var(--muted) h s l / 0.4));
  border-color: hsl(from var(--border) h s l / 0.2);
  transform: translateX(2px);
}

.qw-action-row:hover .qw-action-icon {
  color: hsl(from var(--primary) h s l / 0.75);
}

.qw-action-row--search {
  background: hsl(from var(--muted) h s l / 0.35);
}

.qw-action-icon {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
  color: var(--muted-foreground);
  transition: color 160ms var(--ease-fluid);
}

.qw-section-label {
  padding: 0.85rem 0.75rem 0.4rem 1.375rem;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--muted-foreground);
  opacity: 0.75;
}

.qw-session-list {
  padding: 0 0.625rem;
}

.qw-session-list .qw-session {
  position: relative;
}

.qw-session {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.55rem 0.75rem;
  border-radius: 0.5rem;
  border: 1px solid transparent;
  cursor: pointer;
  transition:
    background 160ms var(--ease-fluid),
    border-color 160ms var(--ease-fluid),
    box-shadow 160ms var(--ease-fluid),
    transform 160ms var(--ease-fluid);
}

.qw-session:hover {
  background: linear-gradient(135deg, hsl(from var(--card) h s l / 0.7), hsl(from var(--background) h s l / 0.5));
  border-color: hsl(from var(--border) h s l / 0.25);
  box-shadow: 0 2px 6px -2px hsl(var(--shadow-color) / 0.06);
  transform: translateX(2px);
}

.qw-session--active {
  background: linear-gradient(135deg, hsl(from var(--primary) h s l / 0.08), hsl(from var(--card) h s l / 0.6));
  border-color: hsl(from var(--primary) h s l / 0.14);
  box-shadow:
    0 2px 8px -2px hsl(from var(--primary) h s l / 0.1),
    inset 0 0 0 1px hsl(from var(--primary) h s l / 0.04);
}

.qw-session--active:hover {
  background: linear-gradient(135deg, hsl(from var(--primary) h s l / 0.1), hsl(from var(--card) h s l / 0.7));
  border-color: hsl(from var(--primary) h s l / 0.18);
  box-shadow: 0 3px 10px -3px hsl(from var(--primary) h s l / 0.12);
  transform: translateX(2px);
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
  font-size: 12px;
  color: var(--muted-foreground);
  white-space: nowrap;
}

.qw-session-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.625rem;
  height: 1.625rem;
  border-radius: 0.375rem;
  border: 1px solid transparent;
  color: var(--muted-foreground);
  background: transparent;
  transition:
    color 160ms var(--ease-fluid),
    background 160ms var(--ease-fluid),
    border-color 160ms var(--ease-fluid),
    transform 160ms var(--ease-fluid);
}

.qw-session-action:hover {
  color: var(--foreground);
  background: hsl(from var(--muted) h s l / 0.6);
  border-color: hsl(from var(--border) h s l / 0.3);
  transform: scale(1.1);
}

.qw-session-action--danger:hover {
  color: hsl(from var(--destructive) h s l / 0.9);
  background: hsl(from var(--destructive) h s l / 0.08);
  border-color: hsl(from var(--destructive) h s l / 0.15);
}

.qw-session-more {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.625rem;
  height: 1.625rem;
  border-radius: 0.375rem;
  border: 1px solid transparent;
  color: var(--muted-foreground);
  background: transparent;
  cursor: pointer;
  transition:
    color 160ms var(--ease-fluid),
    background 160ms var(--ease-fluid),
    border-color 160ms var(--ease-fluid),
    transform 160ms var(--ease-fluid);
}

.qw-session-more:hover {
  color: var(--foreground);
  background: hsl(from var(--muted) h s l / 0.6);
  border-color: hsl(from var(--border) h s l / 0.3);
  transform: scale(1.08);
}

/* ── 右键/更多菜单 ── */
:global(.qw-context-menu) {
  min-width: 10rem;
  padding: 0.375rem;
  border-radius: 0.75rem;
  border: 1px solid hsl(from var(--border) h s l / 0.4);
  background: hsl(from var(--popover) h s l / 0.98);
  backdrop-filter: blur(12px);
  box-shadow:
    0 8px 24px -4px hsl(var(--shadow-color) / 0.12),
    0 2px 6px -1px hsl(var(--shadow-color) / 0.06);
}

:global(.qw-context-item) {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.55rem 0.75rem;
  border-radius: 0.5rem;
  font-size: 14px;
  font-weight: 450;
  color: var(--foreground);
  cursor: pointer;
  transition: background 120ms ease;
}

:global(.qw-context-item:hover),
:global(.qw-context-item[data-highlighted]) {
  background: hsl(from var(--accent) h s l / 0.7);
}

:global(.qw-context-item--danger) {
  color: hsl(from var(--destructive) h s l / 0.85);
}

:global(.qw-context-item--danger:hover),
:global(.qw-context-item--danger[data-highlighted]) {
  background: hsl(from var(--destructive) h s l / 0.06);
  color: var(--destructive);
}

:global(.qw-context-item--muted) {
  color: var(--muted-foreground);
}

:global(.qw-context-item--muted:hover),
:global(.qw-context-item--muted[data-highlighted]) {
  background: hsl(from var(--accent) h s l / 0.7);
  color: var(--muted-foreground);
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

.qw-view-all {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 0.5rem 0.75rem;
  border: 1px solid hsl(from var(--border) h s l / 0.3);
  border-radius: 0.5rem;
  background: linear-gradient(135deg, hsl(from var(--card) h s l / 0.4), hsl(from var(--muted) h s l / 0.2));
  font-size: 13px;
  font-weight: 500;
  color: var(--muted-foreground);
  cursor: pointer;
  transition:
    color 180ms var(--ease-fluid),
    background 180ms var(--ease-fluid),
    border-color 180ms var(--ease-fluid),
    box-shadow 180ms var(--ease-fluid),
    transform 180ms var(--ease-fluid);
}

.qw-view-all:hover {
  color: var(--foreground);
  background: linear-gradient(135deg, hsl(from var(--card) h s l / 0.6), hsl(from var(--muted) h s l / 0.35));
  border-color: hsl(from var(--border) h s l / 0.5);
  box-shadow: 0 2px 6px -2px hsl(var(--shadow-color) / 0.06);
  transform: translateY(-1px);
}

/* ═══ 底部 ═══ */

.sidebar-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 0.625rem 0.75rem 0.75rem;
  border-top: 1px solid hsl(from var(--sidebar-border) h s l / 0.3);
  background: linear-gradient(180deg, transparent, hsl(from var(--sidebar-background) h s l / 0.5));
}

.theme-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 2rem;
  height: 2rem;
  border-radius: 0.5rem;
  border: 1px solid hsl(from var(--border) h s l / 0.2);
  background: hsl(from var(--card) h s l / 0.3);
  color: var(--muted-foreground);
  cursor: pointer;
  transition:
    background 180ms var(--ease-fluid),
    color 180ms var(--ease-fluid),
    border-color 180ms var(--ease-fluid),
    transform 180ms var(--ease-fluid),
    box-shadow 180ms var(--ease-fluid);
}

.theme-toggle:hover {
  background: hsl(from var(--card) h s l / 0.6);
  border-color: hsl(from var(--border) h s l / 0.5);
  color: var(--foreground);
  transform: scale(1.05);
  box-shadow: 0 2px 8px -3px hsl(var(--shadow-color) / 0.1);
}
</style>
