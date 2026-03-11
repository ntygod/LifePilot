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
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import MetricCard from '@/components/common/MetricCard.vue'
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

const totalActive = computed(() => chatStore.sessions.filter(session => !session.archived).length)
const totalArchived = computed(() => chatStore.sessions.filter(session => session.archived).length)
const totalPinned = computed(() => chatStore.sessions.filter(session => session.pinned).length)
const updatedThisWeek = computed(() => {
  const cutoff = Date.now() - 7 * 24 * 60 * 60 * 1000
  return chatStore.sessions.filter(session => new Date(session.updatedAt).getTime() >= cutoff).length
})

const hasFilters = computed(() => {
  return Boolean(searchQuery.value.trim()) || showArchived.value || filterPinned.value !== 'all' || timeRange.value !== 'all'
})

const archiveScopeLabel = computed(() => showArchived.value ? '归档内容' : '活跃会话')
const pinnedScopeLabel = computed(() => {
  if (filterPinned.value === 'pinned') return '仅置顶'
  if (filterPinned.value === 'unpinned') return '未置顶'
  return '全部会话'
})
const timeRangeLabel = computed(() => {
  if (timeRange.value === '7d') return '最近 7 天'
  if (timeRange.value === '30d') return '最近 30 天'
  return '全部时间'
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
    console.error('创建会话失败:', error)
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
    console.error('重命名失败:', error)
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
    console.error('置顶操作失败:', error)
  }
}

async function toggleArchive(sessionId: string) {
  const session = chatStore.sessions.find(item => item.id === sessionId)
  if (!session) return

  try {
    await chatStore.updateSession(sessionId, { archived: !session.archived })
  } catch (error) {
    console.error('归档操作失败:', error)
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
    console.error('删除失败:', error)
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
    <PageContainer size="wide" class="py-6 sm:py-8">
      <div class="mx-auto flex max-w-[1180px] flex-col gap-6">
        <header class="space-y-4 border-b border-border/70 pb-5">
          <div class="flex flex-col gap-4 xl:flex-row xl:items-start xl:justify-between">
            <div class="max-w-3xl space-y-2">
              <div class="surface-label">会话</div>
              <h1 class="text-3xl font-semibold tracking-tight text-foreground">会话列表</h1>
              <p class="text-sm leading-6 text-muted-foreground">
                把还在推进的对话、已归档内容和置顶项放在一张工作清单里，方便继续跟进和整理。
              </p>
            </div>

            <Button type="button" :disabled="loading" @click="handleNewConversation">
              <Plus class="size-4" />
              新建会话
            </Button>
          </div>

          <div class="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <MetricCard label="活跃会话" :value="totalActive" hint="还在使用中的对话，会按更新时间继续往前排。">
              <template #icon>
                <MessageSquareText class="size-5" />
              </template>
            </MetricCard>
            <MetricCard label="最近 7 天更新" :value="updatedThisWeek" hint="最近有动作的会话，更适合优先回到上下文里。">
              <template #icon>
                <Clock3 class="size-5" />
              </template>
            </MetricCard>
            <MetricCard label="已置顶" :value="totalPinned" hint="需要反复回看的会话可以固定在前面。">
              <template #icon>
                <Pin class="size-5" />
              </template>
            </MetricCard>
            <MetricCard label="已归档" :value="totalArchived" hint="处理完成的内容可以先收起，但仍然保留记录。">
              <template #icon>
                <Archive class="size-5" />
              </template>
            </MetricCard>
          </div>
        </header>

        <section class="detail-card p-5">
          <div class="space-y-4">
            <div class="flex flex-col gap-3 xl:flex-row xl:items-end xl:justify-between">
              <div class="space-y-1">
                <div class="surface-label">筛选与整理</div>
                <p class="text-sm leading-6 text-muted-foreground">
                  先搜标题或最近消息，再根据置顶状态和更新时间缩小范围。
                </p>
              </div>

              <div class="flex flex-wrap gap-2 text-xs">
                <span class="filter-pill">当前视图：{{ archiveScopeLabel }}</span>
                <span class="filter-pill">置顶状态：{{ pinnedScopeLabel }}</span>
                <span class="filter-pill">时间范围：{{ timeRangeLabel }}</span>
              </div>
            </div>

            <div class="flex flex-col gap-3 xl:flex-row xl:items-center xl:justify-between">
              <div class="flex flex-1 flex-col gap-3 md:flex-row md:items-center">
                <div class="relative min-w-[220px] flex-1 xl:max-w-[28rem]">
                  <Search class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    v-model="searchQuery"
                    type="search"
                    placeholder="搜索会话标题或最近消息"
                    class="pl-9"
                  />
                </div>

                <div class="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:flex">
                  <Select v-model="filterPinned">
                    <SelectTrigger class="w-full lg:w-[150px]">
                      <SelectValue placeholder="置顶状态" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="all">全部会话</SelectItem>
                      <SelectItem value="pinned">仅置顶</SelectItem>
                      <SelectItem value="unpinned">未置顶</SelectItem>
                    </SelectContent>
                  </Select>

                  <Select v-model="timeRange">
                    <SelectTrigger class="w-full lg:w-[150px]">
                      <SelectValue placeholder="时间范围" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="all">全部时间</SelectItem>
                      <SelectItem value="7d">最近 7 天</SelectItem>
                      <SelectItem value="30d">最近 30 天</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
              </div>

              <div class="flex flex-wrap items-center gap-3">
                <label class="filter-pill cursor-pointer text-sm">
                  <Checkbox
                    :model-value="showArchived"
                    @update:model-value="showArchived = Boolean($event)"
                  />
                  <span>显示归档</span>
                </label>

                <Button type="button" variant="outline" @click="selectAll">
                  {{ allSelected ? '取消全选' : '选择当前结果' }}
                </Button>

                <Button v-if="hasFilters" type="button" variant="ghost" @click="clearFilters">
                  清空筛选
                </Button>
              </div>
            </div>

            <div
              v-if="selectedIds.size > 0"
              class="flex flex-col gap-3 rounded-[calc(var(--radius)+2px)] border border-primary/18 bg-primary/6 px-4 py-3 lg:flex-row lg:items-center lg:justify-between"
            >
              <div class="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
                <span class="surface-chip surface-chip-strong">已选 {{ selectedIds.size }}</span>
                <span>可以一次性置顶、归档或删除。</span>
              </div>
              <div class="flex flex-wrap items-center gap-2">
                <Button type="button" variant="outline" size="sm" @click="batchPin">批量置顶</Button>
                <Button type="button" variant="outline" size="sm" @click="batchArchive">批量归档</Button>
                <Button type="button" variant="destructive" size="sm" @click="batchDelete">批量删除</Button>
                <Button type="button" variant="ghost" size="sm" @click="selectedIds.clear()">取消选择</Button>
              </div>
            </div>
          </div>
        </section>

        <section class="space-y-4">
          <div class="flex items-center justify-between gap-3">
            <div>
              <h2 class="text-lg font-semibold text-foreground">全部会话</h2>
              <p class="text-sm text-muted-foreground">
                {{ showArchived ? '当前显示归档内容，可回看但不打扰日常工作。' : '按更新时间和置顶状态排序，方便继续往前处理。' }}
              </p>
            </div>
            <div class="text-sm text-muted-foreground">{{ filteredSessions.length }} 条结果</div>
          </div>

          <div v-if="loading">
            <StatePanel title="正在加载会话" description="会话列表载入中，请稍候。">
              <template #icon>
                <MessageSquareText class="size-5" />
              </template>
            </StatePanel>
          </div>

          <div v-else-if="filteredSessions.length === 0">
            <StatePanel
              :title="showArchived ? '暂无归档会话' : searchQuery ? '没有匹配的会话' : '还没有任何会话'"
              :description="showArchived
                ? '当前条件下没有找到归档会话。'
                : searchQuery
                  ? '可以换个关键词，或放宽置顶和时间条件。'
                  : '创建对话后，最近的会话会出现在列表中。'"
            >
              <template #icon>
                <MessageSquareText class="size-5" />
              </template>
              <template #actions>
                <Button v-if="!showArchived && !searchQuery" type="button" @click="handleNewConversation">
                  <Plus class="size-4" />
                  新建会话
                </Button>
                <Button v-else type="button" variant="outline" @click="clearFilters">
                  清空筛选
                </Button>
              </template>
            </StatePanel>
          </div>

          <div v-else class="grid gap-3">
            <article
              v-for="session in filteredSessions"
              :key="session.id"
              tabindex="0"
              class="list-card group cursor-pointer p-4 outline-none focus-visible:border-primary/28 focus-visible:shadow-[0_22px_38px_-28px_hsl(var(--shadow-color)/0.28)] sm:p-5"
              :class="[
                selectedIds.has(session.id) && 'border-primary/28 bg-primary/[0.045] shadow-[0_18px_34px_-28px_hsl(var(--shadow-color)/0.24)]',
                isCurrentSession(session.id) && 'border-primary/30 bg-primary/[0.052] shadow-[0_20px_38px_-28px_hsl(var(--shadow-color)/0.26)]',
              ]"
              @click="selectSession(session)"
              @keyup.enter.stop="selectSession(session)"
              @keyup.space.prevent.stop="selectSession(session)"
            >
              <div class="flex flex-col gap-4 xl:flex-row xl:items-start xl:justify-between">
                <div class="flex min-w-0 items-start gap-4">
                  <Checkbox
                    :model-value="selectedIds.has(session.id)"
                    class="mt-1"
                    @click.stop
                    @update:model-value="toggleSelect(session.id)"
                  />

                  <div class="min-w-0 flex-1 space-y-3">
                    <div class="flex flex-wrap items-center gap-2">
                      <span
                        v-if="session.pinned"
                        class="inline-flex items-center gap-1 rounded-full border border-primary/18 bg-primary/8 px-2.5 py-1 text-xs font-medium text-primary"
                      >
                        <Pin class="size-3.5" />
                        置顶
                      </span>
                      <span
                        v-if="session.archived"
                        class="inline-flex items-center gap-1 rounded-full border border-border/70 bg-muted/70 px-2.5 py-1 text-xs font-medium text-muted-foreground"
                      >
                        <Archive class="size-3.5" />
                        归档
                      </span>
                      <span
                        v-if="session.type"
                        class="surface-chip"
                      >
                        {{ session.type }}
                      </span>
                      <span
                        v-if="isCurrentSession(session.id)"
                        class="surface-chip surface-chip-strong"
                      >
                        当前会话
                      </span>
                    </div>

                    <div class="flex items-start gap-3">
                      <Input
                        v-if="renamingId === session.id"
                        v-model="renameTitle"
                        class="h-9 max-w-[36rem]"
                        @blur="confirmRename(session.id)"
                        @click.stop
                        @keyup.enter.stop="confirmRename(session.id)"
                        @keyup.esc.stop="cancelRename"
                      />
                      <h3 v-else class="truncate text-base font-semibold text-foreground sm:text-lg">
                        {{ session.title || '新会话' }}
                      </h3>

                      <div class="hidden items-center gap-1 text-xs font-medium text-primary xl:flex xl:translate-x-1 xl:opacity-0 xl:transition-all xl:duration-150 xl:group-hover:translate-x-0 xl:group-hover:opacity-100">
                        <span>继续处理</span>
                        <ArrowRight class="size-3.5" />
                      </div>
                    </div>

                    <p v-if="session.lastMessagePreview" class="line-clamp-2 text-sm leading-6 text-muted-foreground">
                      {{ session.lastMessagePreview }}
                    </p>

                    <div class="flex flex-wrap items-center gap-4 text-sm text-muted-foreground">
                      <span class="inline-flex items-center gap-2">
                        <Clock3 class="size-4" />
                        {{ formatTime(session.updatedAt) }}
                      </span>
                      <span class="inline-flex items-center gap-2">
                        <MessageSquareText class="size-4" />
                        {{ session.lastMessagePreview ? '有最近消息' : '暂无消息摘要' }}
                      </span>
                    </div>
                  </div>
                </div>

                <div class="flex shrink-0 items-center gap-1 xl:translate-y-1 xl:opacity-0 xl:transition-all xl:duration-150 xl:group-hover:translate-y-0 xl:group-hover:opacity-100">
                  <button
                    type="button"
                    class="rounded-lg p-2 text-muted-foreground transition-colors hover:bg-accent/80 hover:text-foreground"
                    title="重命名"
                    @click.stop="startRename(session)"
                  >
                    <Edit2 class="size-4" />
                  </button>
                  <button
                    type="button"
                    class="rounded-lg p-2 text-muted-foreground transition-colors hover:bg-accent/80 hover:text-foreground"
                    :title="session.pinned ? '取消置顶' : '置顶'"
                    @click.stop="togglePin(session.id)"
                  >
                    <Pin class="size-4" :fill="session.pinned ? 'currentColor' : 'none'" />
                  </button>
                  <button
                    type="button"
                    class="rounded-lg p-2 text-muted-foreground transition-colors hover:bg-accent/80 hover:text-foreground"
                    :title="session.archived ? '取消归档' : '归档'"
                    @click.stop="toggleArchive(session.id)"
                  >
                    <Archive class="size-4" :fill="session.archived ? 'currentColor' : 'none'" />
                  </button>
                  <button
                    type="button"
                    class="rounded-lg p-2 text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive"
                    title="删除"
                    @click.stop="requestDelete(session.id)"
                  >
                    <Trash2 class="size-4" />
                  </button>
                </div>
              </div>
            </article>
          </div>
        </section>
      </div>
    </PageContainer>

    <ConfirmDialog
      v-model:show="deleteDialogOpen"
      title="确认删除会话"
      message="确定要删除这个会话吗？删除后将无法恢复。"
      confirm-label="删除"
      cancel-label="取消"
      confirm-variant="destructive"
      @cancel="deleteTarget = null"
      @confirm="confirmDelete"
    />
  </div>
</template>
