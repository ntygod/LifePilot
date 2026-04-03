<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowRight,
  Archive,
  Clock3,
  Edit2,
  MessageSquareText,
  Pin,
  Plus,
  Search,
  Trash2,
} from 'lucide-vue-next'
import { logger } from '@/utils/logger'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import PageContainer from '@/components/layout/PageContainer.vue'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { useChatStore } from '@/stores/chat'
import type { ChatSession } from '@/types'

const router = useRouter()
const chatStore = useChatStore()

const searchQuery = ref('')
const showArchived = ref(false)
const filterPinned = ref<'all' | 'pinned' | 'unpinned'>('all')
const timeRange = ref<'all' | '7d' | '30d'>('all')

const renamingId = ref<string | null>(null)
const renameTitle = ref('')
const selectedIds = ref<Set<string>>(new Set())
const deleteTarget = ref<string | null>(null)
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  try {
    await chatStore.loadSessions()
  } finally {
    loading.value = false
  }
})

const filteredSessions = computed(() => {
  let result = [...chatStore.sessions]

  if (!showArchived.value) {
    result = result.filter(session => !session.archived)
  } else {
    result = result.filter(session => session.archived)
  }

  if (filterPinned.value === 'pinned') {
    result = result.filter(session => session.pinned)
  } else if (filterPinned.value === 'unpinned') {
    result = result.filter(session => !session.pinned)
  }

  if (searchQuery.value.trim()) {
    const query = searchQuery.value.trim().toLowerCase()
    result = result.filter(session => {
      const titleMatch = session.title.toLowerCase().includes(query)
      const messageMatch = session.lastMessagePreview?.toLowerCase().includes(query)
      return titleMatch || messageMatch
    })
  }

  if (timeRange.value !== 'all') {
    const now = Date.now()
    const days = timeRange.value === '7d' ? 7 : 30
    const cutoff = now - days * 24 * 60 * 60 * 1000
    result = result.filter(session => new Date(session.updatedAt).getTime() >= cutoff)
  }

  return result.sort((left, right) => {
    if (left.pinned && !right.pinned) return -1
    if (!left.pinned && right.pinned) return 1
    return new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime()
  })
})

const hasFilters = computed(() => {
  return Boolean(searchQuery.value.trim()) || showArchived.value || filterPinned.value !== 'all' || timeRange.value !== 'all'
})

const allSelected = computed(() => {
  return filteredSessions.value.length > 0 && filteredSessions.value.every(session => selectedIds.value.has(session.id))
})

const deleteDialogOpen = computed({
  get: () => Boolean(deleteTarget.value),
  set: (value: boolean) => {
    if (!value) {
      deleteTarget.value = null
    }
  },
})

function formatTime(dateStr: string) {
  const date = new Date(dateStr)
  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const diffMins = Math.floor(diffMs / 60000)
  const diffHours = Math.floor(diffMs / 3600000)
  const diffDays = Math.floor(diffMs / 86400000)

  if (diffMins < 1) return '刚刚'
  if (diffMins < 60) return `${diffMins} 分钟前`
  if (diffHours < 24) return `${diffHours} 小时前`
  if (diffDays < 7) return `${diffDays} 天前`
  return date.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })
}

async function handleNewConversation() {
  loading.value = true
  try {
    const now = new Date()
    const defaultTitle = `新会话 - ${now.toLocaleDateString('zh-CN', {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    })}`
    const session = await chatStore.createSession(defaultTitle)
    router.push({ name: 'conversationDetail', params: { sessionId: session.id } })
  } catch (error) {
    logger.error('创建会话失败:', error)
  } finally {
    loading.value = false
  }
}

function selectSession(session: ChatSession) {
  chatStore.activeSessionId = session.id
  router.push({ name: 'conversationDetail', params: { sessionId: session.id } })
}

function isCurrentSession(sessionId: string) {
  return chatStore.activeSessionId === sessionId
}

function startRename(session: ChatSession) {
  renamingId.value = session.id
  renameTitle.value = session.title
}

async function confirmRename(sessionId: string) {
  const title = renameTitle.value.trim()
  if (!title) {
    renamingId.value = null
    return
  }

  try {
    await chatStore.updateSession(sessionId, { title })
    renamingId.value = null
  } catch (error) {
    logger.error('重命名失败:', error)
  }
}

function cancelRename() {
  renamingId.value = null
}

async function togglePin(sessionId: string) {
  const session = chatStore.sessions.find(item => item.id === sessionId)
  if (!session) return

  try {
    await chatStore.updateSession(sessionId, { pinned: !session.pinned })
  } catch (error) {
    logger.error('置顶操作失败:', error)
  }
}

async function toggleArchive(sessionId: string) {
  const session = chatStore.sessions.find(item => item.id === sessionId)
  if (!session) return

  try {
    await chatStore.updateSession(sessionId, { archived: !session.archived })
  } catch (error) {
    logger.error('归档操作失败:', error)
  }
}

function requestDelete(sessionId: string) {
  deleteTarget.value = sessionId
}

async function confirmDelete() {
  if (!deleteTarget.value) return

  try {
    const targetId = deleteTarget.value
    await chatStore.deleteSession(targetId)
    selectedIds.value.delete(targetId)
    deleteTarget.value = null
  } catch (error) {
    logger.error('删除失败:', error)
  }
}

function toggleSelect(sessionId: string) {
  if (selectedIds.value.has(sessionId)) {
    selectedIds.value.delete(sessionId)
  } else {
    selectedIds.value.add(sessionId)
  }
}

function selectAll() {
  if (allSelected.value) {
    selectedIds.value.clear()
    return
  }

  filteredSessions.value.forEach(session => selectedIds.value.add(session.id))
}

function clearFilters() {
  searchQuery.value = ''
  showArchived.value = false
  filterPinned.value = 'all'
  timeRange.value = 'all'
}

async function batchPin() {
  const tasks = Array.from(selectedIds.value).map(id => {
    const session = chatStore.sessions.find(item => item.id === id)
    if (session && !session.pinned) {
      return chatStore.updateSession(id, { pinned: true })
    }
    return undefined
  })

  const validTasks = tasks.filter((task): task is ReturnType<typeof chatStore.updateSession> => Boolean(task))
  await Promise.all(validTasks)
  selectedIds.value.clear()
}

async function batchArchive() {
  const tasks = Array.from(selectedIds.value).map(id => {
    const session = chatStore.sessions.find(item => item.id === id)
    if (session && !session.archived) {
      return chatStore.updateSession(id, { archived: true })
    }
    return undefined
  })

  const validTasks = tasks.filter((task): task is ReturnType<typeof chatStore.updateSession> => Boolean(task))
  await Promise.all(validTasks)
  selectedIds.value.clear()
}

async function batchDelete() {
  await Promise.all(Array.from(selectedIds.value).map(id => chatStore.deleteSession(id)))
  selectedIds.value.clear()
}
</script>

<template>
  <div class="h-full overflow-y-auto">
    <!-- 真正的空状态：居中引导 -->
    <div v-if="!loading && chatStore.sessions.length === 0" class="flex h-full items-center justify-center px-6">
      <div class="flex max-w-sm flex-col items-center gap-md text-center">
        <div class="flex size-16 items-center justify-center rounded-2xl border border-border/60 bg-card/90 text-primary">
          <MessageSquareText class="size-7" />
        </div>
        <div class="space-y-2">
          <h2 class="text-lg font-semibold text-foreground">还没有会话</h2>
          <p class="text-sm leading-6 text-muted-foreground">开始第一次对话，知微会记住你的偏好和上下文。</p>
        </div>
        <Button type="button" size="default" @click="handleNewConversation">
          <Plus class="size-4" />
          新建会话
        </Button>
      </div>
    </div>

    <!-- 有会话时：正常列表 -->
    <PageContainer v-else size="wide" class="py-4 sm:py-5">
      <div class="mx-auto flex max-w-[1180px] flex-col gap-md">
        <!-- 标题行 -->
        <div class="flex items-center justify-between">
          <h1 class="text-xl font-semibold tracking-tight text-foreground">会话</h1>
          <Button type="button" size="sm" :disabled="loading" @click="handleNewConversation">
            <Plus class="size-4" />
            新建会话
          </Button>
        </div>

        <!-- 工具栏 -->
        <template v-if="chatStore.sessions.length > 0">
        <div class="flex flex-col gap-sm md:flex-row md:items-center md:justify-between">
          <div class="flex flex-1 items-center gap-sm">
            <div class="relative min-w-[180px] flex-1 md:max-w-[24rem]">
              <Search class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                v-model="searchQuery"
                type="search"
                placeholder="搜索会话…"
                class="h-8 pl-9 text-sm"
              />
            </div>
            <Select v-model="filterPinned">
              <SelectTrigger class="h-8 w-[120px] text-sm">
                <SelectValue placeholder="置顶" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">全部</SelectItem>
                <SelectItem value="pinned">仅置顶</SelectItem>
                <SelectItem value="unpinned">未置顶</SelectItem>
              </SelectContent>
            </Select>
            <Select v-model="timeRange">
              <SelectTrigger class="h-8 w-[120px] text-sm">
                <SelectValue placeholder="时间" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">全部时间</SelectItem>
                <SelectItem value="7d">近 7 天</SelectItem>
                <SelectItem value="30d">近 30 天</SelectItem>
              </SelectContent>
            </Select>
          </div>

          <div class="flex items-center gap-sm">
            <label class="flex cursor-pointer items-center gap-xs text-sm text-muted-foreground">
              <Checkbox
                :model-value="showArchived"
                @update:model-value="showArchived = Boolean($event)"
              />
              <span>归档</span>
            </label>
            <Button v-if="hasFilters" type="button" variant="ghost" size="sm" class="text-xs" @click="clearFilters">
              清空筛选
            </Button>
          </div>
        </div>

        <!-- 批量操作栏 -->
        <div
          v-if="selectedIds.size > 0"
          class="flex items-center justify-between rounded-lg border border-primary/18 bg-primary/6 px-md py-sm"
        >
          <span class="text-sm text-muted-foreground">已选 {{ selectedIds.size }} 项</span>
          <div class="flex items-center gap-xs">
            <Button type="button" variant="outline" size="sm" @click="batchPin">置顶</Button>
            <Button type="button" variant="outline" size="sm" @click="batchArchive">归档</Button>
            <Button type="button" variant="destructive" size="sm" @click="batchDelete">删除</Button>
            <Button type="button" variant="ghost" size="sm" @click="selectedIds.clear()">取消</Button>
          </div>
        </div>

        <!-- 列表头 -->
        <div class="flex items-center justify-between">
          <button
            type="button"
            class="text-xs text-muted-foreground/70 transition-colors hover:text-muted-foreground"
            @click="selectAll"
          >
            {{ allSelected ? '取消全选' : '全选' }}
          </button>
          <span class="text-xs text-muted-foreground/70">{{ filteredSessions.length }} 条</span>
        </div>
        </template>

        <!-- 内容区域 -->
        <div v-if="loading">
          <StatePanel title="加载中…" description="正在获取会话列表">
            <template #icon>
              <MessageSquareText class="size-5" />
            </template>
          </StatePanel>
        </div>

        <div v-else-if="filteredSessions.length === 0">
          <StatePanel
            :title="showArchived ? '暂无归档会话' : '没有匹配的会话'"
            :description="showArchived ? '归档后的会话会出现在这里。' : '换个关键词试试。'"
          >
            <template #icon>
              <MessageSquareText class="size-5" />
            </template>
            <template #actions>
              <Button type="button" variant="outline" size="sm" @click="clearFilters">
                清空筛选
              </Button>
            </template>
          </StatePanel>
        </div>

        <div v-else class="grid gap-sm">
          <article
            v-for="session in filteredSessions"
            :key="session.id"
            tabindex="0"
            class="list-card group cursor-pointer px-md py-sm outline-none transition-colors hover:border-border focus-visible:border-primary/24"
            :class="[
              selectedIds.has(session.id) && 'border-primary/24 bg-primary/[0.04]',
              isCurrentSession(session.id) && 'border-primary/26 bg-primary/[0.05]',
            ]"
            @click="selectSession(session)"
            @keyup.enter.stop="selectSession(session)"
            @keyup.space.prevent.stop="selectSession(session)"
          >
            <div class="flex items-center gap-sm">
              <Checkbox
                :model-value="selectedIds.has(session.id)"
                class="shrink-0"
                @click.stop
                @update:model-value="toggleSelect(session.id)"
              />

              <div class="min-w-0 flex-1">
                <div class="flex items-center gap-sm">
                  <Input
                    v-if="renamingId === session.id"
                    v-model="renameTitle"
                    class="h-7 max-w-[24rem] text-sm"
                    @blur="confirmRename(session.id)"
                    @click.stop
                    @keyup.enter.stop="confirmRename(session.id)"
                    @keyup.esc.stop="cancelRename"
                  />
                  <h3 v-else class="truncate text-sm font-medium text-foreground">
                    {{ session.title || '新会话' }}
                  </h3>

                  <Pin v-if="session.pinned" class="size-3.5 shrink-0 text-primary" />
                  <Archive v-if="session.archived" class="size-3.5 shrink-0 text-muted-foreground" />
                  <span
                    v-if="isCurrentSession(session.id)"
                    class="shrink-0 rounded-full bg-primary/10 px-2 py-0.5 text-[11px] font-medium text-primary"
                  >
                    当前
                  </span>
                </div>

                <div class="mt-xs flex items-center gap-md text-xs text-muted-foreground">
                  <span>{{ formatTime(session.updatedAt) }}</span>
                  <span v-if="session.lastMessagePreview" class="truncate">{{ session.lastMessagePreview }}</span>
                </div>
              </div>

              <div class="flex shrink-0 items-center gap-0.5 opacity-0 transition-opacity group-hover:opacity-100">
                <button
                  type="button"
                  class="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-accent/80 hover:text-foreground"
                  title="重命名"
                  @click.stop="startRename(session)"
                >
                  <Edit2 class="size-3.5" />
                </button>
                <button
                  type="button"
                  class="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-accent/80 hover:text-foreground"
                  :title="session.pinned ? '取消置顶' : '置顶'"
                  @click.stop="togglePin(session.id)"
                >
                  <Pin class="size-3.5" :fill="session.pinned ? 'currentColor' : 'none'" />
                </button>
                <button
                  type="button"
                  class="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-accent/80 hover:text-foreground"
                  :title="session.archived ? '取消归档' : '归档'"
                  @click.stop="toggleArchive(session.id)"
                >
                  <Archive class="size-3.5" :fill="session.archived ? 'currentColor' : 'none'" />
                </button>
                <button
                  type="button"
                  class="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive"
                  title="删除"
                  @click.stop="requestDelete(session.id)"
                >
                  <Trash2 class="size-3.5" />
                </button>
              </div>
            </div>
          </article>
        </div>
      </div>
    </PageContainer>

    <ConfirmDialog
      v-model:show="deleteDialogOpen"
      title="确认删除会话"
      message="删除后无法恢复，确定继续？"
      confirm-label="删除"
      cancel-label="取消"
      confirm-variant="destructive"
      @cancel="deleteTarget = null"
      @confirm="confirmDelete"
    />
  </div>
</template>
