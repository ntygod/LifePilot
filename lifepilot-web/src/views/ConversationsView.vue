<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import type { ChatSession } from '@/types'
import { Pin, Archive, Trash2, Edit2, Plus, Search, Filter, X } from 'lucide-vue-next'
import SettingSelect from '@/components/settings/SettingSelect.vue'

const router = useRouter()
const chatStore = useChatStore()

// 搜索和过滤
const searchQuery = ref('')
const showArchived = ref(false)
const filterPinned = ref<'all' | 'pinned' | 'unpinned'>('all')
const timeRange = ref<'all' | '7d' | '30d' | 'custom'>('all')

// 操作状态
const renamingId = ref<string | null>(null)
const renameTitle = ref('')
const selectedIds = ref<Set<string>>(new Set())
const deleteTarget = ref<string | null>(null)
const loading = ref(false)

// 加载会话列表
onMounted(async () => {
  loading.value = true
  try {
    await chatStore.loadSessions()
  } finally {
    loading.value = false
  }
})

// 过滤后的会话列表
const filteredSessions = computed(() => {
  let result = [...chatStore.sessions]

  // 归档过滤
  if (!showArchived.value) {
    result = result.filter(s => !s.archived)
  } else {
    result = result.filter(s => s.archived)
  }

  // 置顶过滤
  if (filterPinned.value === 'pinned') {
    result = result.filter(s => s.pinned)
  } else if (filterPinned.value === 'unpinned') {
    result = result.filter(s => !s.pinned)
  }

  // 搜索过滤
  if (searchQuery.value.trim()) {
    const query = searchQuery.value.toLowerCase()
    result = result.filter(s => {
      const titleMatch = s.title.toLowerCase().includes(query)
      const messageMatch = s.lastMessagePreview?.toLowerCase().includes(query)
      return titleMatch || messageMatch
    })
  }

  // 时间范围过滤
  if (timeRange.value !== 'all') {
    const now = Date.now()
    const days = timeRange.value === '7d' ? 7 : 30
    const cutoff = now - days * 24 * 60 * 60 * 1000
    result = result.filter(s => {
      const updated = new Date(s.updatedAt).getTime()
      return updated >= cutoff
    })
  }

  // 排序：置顶优先 + 更新时间倒序
  return result.sort((a, b) => {
    if (a.pinned && !b.pinned) return -1
    if (!a.pinned && b.pinned) return 1
    return new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime()
  })
})

// 格式化时间
function formatTime(dateStr: string): string {
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

// 新建会话
async function handleNewConversation() {
  loading.value = true
  try {
    const now = new Date()
    const defaultTitle = `新会话 - ${now.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}`
    const session = await chatStore.createSession(defaultTitle)
    router.push({ name: 'conversationDetail', params: { sessionId: session.id } })
  } catch (error) {
    console.error('创建会话失败:', error)
  } finally {
    loading.value = false
  }
}

// 进入会话
function selectSession(session: ChatSession) {
  chatStore.activeSessionId = session.id
  router.push({ name: 'conversationDetail', params: { sessionId: session.id } })
}

// 重命名
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

// 置顶/取消置顶
async function togglePin(sessionId: string) {
  const session = chatStore.sessions.find(s => s.id === sessionId)
  if (!session) return
  try {
    await chatStore.updateSession(sessionId, { pinned: !session.pinned })
  } catch (error) {
    console.error('置顶操作失败:', error)
  }
}

// 归档/取消归档
async function toggleArchive(sessionId: string) {
  const session = chatStore.sessions.find(s => s.id === sessionId)
  if (!session) return
  try {
    await chatStore.updateSession(sessionId, { archived: !session.archived })
  } catch (error) {
    console.error('归档操作失败:', error)
  }
}

// 删除会话
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

// 批量操作
function toggleSelect(sessionId: string) {
  if (selectedIds.value.has(sessionId)) {
    selectedIds.value.delete(sessionId)
  } else {
    selectedIds.value.add(sessionId)
  }
}

function selectAll() {
  if (selectedIds.value.size === filteredSessions.value.length) {
    selectedIds.value.clear()
  } else {
    filteredSessions.value.forEach(s => selectedIds.value.add(s.id))
  }
}

async function batchPin() {
  const promises = Array.from(selectedIds.value).map(id => {
    const session = chatStore.sessions.find(s => s.id === id)
    if (session && !session.pinned) {
      return chatStore.updateSession(id, { pinned: true })
    }
  })
  await Promise.all(promises.filter(Boolean))
  selectedIds.value.clear()
}

async function batchArchive() {
  const promises = Array.from(selectedIds.value).map(id => {
    const session = chatStore.sessions.find(s => s.id === id)
    if (session && !session.archived) {
      return chatStore.updateSession(id, { archived: true })
    }
  })
  await Promise.all(promises.filter(Boolean))
  selectedIds.value.clear()
}

async function batchDelete() {
  const promises = Array.from(selectedIds.value).map(id => chatStore.deleteSession(id))
  await Promise.all(promises)
  selectedIds.value.clear()
}
</script>

<template>
  <div class="flex flex-col h-full">
    <!-- 顶部工具栏 -->
    <div class="border-b border-border bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
      <div class="container mx-auto px-6 py-4">
        <div class="flex items-center justify-between mb-4">
          <h2 class="text-2xl font-semibold text-foreground">会话列表</h2>
          <button
            class="inline-flex items-center gap-2 rounded-md text-sm font-medium h-9 px-4 bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
            :disabled="loading"
            @click="handleNewConversation"
          >
            <Plus :size="16" />
            新建会话
          </button>
        </div>

        <!-- 搜索和过滤栏 -->
        <div class="flex flex-wrap items-center gap-3">
          <!-- 搜索框 -->
          <div class="relative flex-1 min-w-[200px] max-w-[400px]">
            <Search class="absolute left-2 top-1/2 -translate-y-1/2 text-muted-foreground" :size="16" />
            <input
              v-model="searchQuery"
              type="search"
              placeholder="搜索会话名称或最近消息..."
              class="w-full h-9 pl-8 pr-3 rounded-md border border-input bg-background text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring"
            />
          </div>

          <!-- 置顶过滤 -->
          <div class="w-[120px]">
            <SettingSelect
              v-model="filterPinned"
              :options="[
                { value: 'all', label: '全部' },
                { value: 'pinned', label: '仅置顶' },
                { value: 'unpinned', label: '未置顶' }
              ]"
              placeholder="全部"
            />
          </div>

          <!-- 时间范围过滤 -->
          <div class="w-[140px]">
            <SettingSelect
              v-model="timeRange"
              :options="[
                { value: 'all', label: '全部时间' },
                { value: '7d', label: '最近 7 天' },
                { value: '30d', label: '最近 30 天' }
              ]"
              placeholder="全部时间"
            />
          </div>

          <!-- 显示归档开关 -->
          <label class="flex items-center gap-2 text-sm cursor-pointer shrink-0">
            <input
              v-model="showArchived"
              type="checkbox"
              class="rounded border-input"
            />
            <span class="text-muted-foreground whitespace-nowrap">显示归档</span>
          </label>
        </div>

        <!-- 批量操作栏 -->
        <div
          v-if="selectedIds.size > 0"
          class="mt-3 flex items-center gap-2 p-2 rounded-md bg-accent/50"
        >
          <span class="text-sm text-muted-foreground">已选择 {{ selectedIds.size }} 项</span>
          <div class="flex items-center gap-2 ml-auto">
            <button
              class="text-sm text-muted-foreground hover:text-foreground transition-colors"
              @click="batchPin"
            >
              批量置顶
            </button>
            <button
              class="text-sm text-muted-foreground hover:text-foreground transition-colors"
              @click="batchArchive"
            >
              批量归档
            </button>
            <button
              class="text-sm text-destructive hover:text-destructive/80 transition-colors"
              @click="batchDelete"
            >
              批量删除
            </button>
            <button
              class="text-sm text-muted-foreground hover:text-foreground transition-colors"
              @click="selectedIds.clear()"
            >
              取消选择
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 会话列表 -->
    <div class="flex-1 overflow-y-auto">
      <div class="container mx-auto px-6 py-4">
        <!-- 加载中 -->
        <div v-if="loading" class="flex items-center justify-center py-12">
          <div class="text-sm text-muted-foreground">加载中...</div>
        </div>

        <!-- 空状态 -->
        <div v-else-if="filteredSessions.length === 0" class="flex flex-col items-center justify-center py-12">
          <div class="text-sm text-muted-foreground text-center">
            <p class="text-base font-medium text-foreground mb-1">
              {{ showArchived ? '暂无归档会话' : searchQuery ? '未找到匹配的会话' : '暂无会话' }}
            </p>
            <p v-if="!showArchived && !searchQuery" class="mt-2">
              点击上方"新建会话"按钮开始对话
            </p>
          </div>
        </div>

        <!-- 会话列表 -->
        <div v-else class="space-y-2">
          <div
            v-for="session in filteredSessions"
            :key="session.id"
            class="group flex items-start gap-3 p-4 rounded-lg border border-border hover:bg-accent/50 transition-colors cursor-pointer"
            :class="{ 'bg-accent': selectedIds.has(session.id) }"
            @click="selectSession(session)"
          >
            <!-- 选择框 -->
            <input
              type="checkbox"
              :checked="selectedIds.has(session.id)"
              class="mt-1 rounded border-input"
              @click.stop="toggleSelect(session.id)"
            />

            <!-- 会话内容 -->
            <div class="flex-1 min-w-0">
              <div class="flex items-start gap-2 mb-1">
                <h3 class="font-medium text-foreground truncate flex items-center gap-2">
                  <span v-if="session.pinned" class="text-primary">
                    <Pin :size="14" fill="currentColor" />
                  </span>
                  <span v-if="session.archived" class="text-muted-foreground text-xs">[归档]</span>
                  <input
                    v-if="renamingId === session.id"
                    v-model="renameTitle"
                    class="flex-1 bg-background border border-input rounded px-2 py-0.5 text-sm focus:outline-none focus:ring-1 focus:ring-ring"
                    @click.stop
                    @keyup.enter.stop="confirmRename(session.id)"
                    @keyup.esc.stop="cancelRename"
                    @blur="confirmRename(session.id)"
                  />
                  <span v-else class="truncate">{{ session.title || '新会话' }}</span>
                </h3>
              </div>
              <p v-if="session.lastMessagePreview" class="text-sm text-muted-foreground line-clamp-2 mb-1">
                {{ session.lastMessagePreview }}
              </p>
              <div class="flex items-center gap-3 text-xs text-muted-foreground">
                <span>{{ formatTime(session.updatedAt) }}</span>
                <span v-if="session.type" class="px-1.5 py-0.5 rounded bg-muted text-muted-foreground">
                  {{ session.type }}
                </span>
              </div>
            </div>

            <!-- 操作按钮 -->
            <div class="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity shrink-0">
              <button
                class="p-1.5 rounded-md text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
                title="重命名"
                @click.stop="startRename(session)"
              >
                <Edit2 :size="14" />
              </button>
              <button
                class="p-1.5 rounded-md text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
                :title="session.pinned ? '取消置顶' : '置顶'"
                @click.stop="togglePin(session.id)"
              >
                <Pin :size="14" :fill="session.pinned ? 'currentColor' : 'none'" />
              </button>
              <button
                class="p-1.5 rounded-md text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
                :title="session.archived ? '取消归档' : '归档'"
                @click.stop="toggleArchive(session.id)"
              >
                <Archive :size="14" :fill="session.archived ? 'currentColor' : 'none'" />
              </button>
              <button
                class="p-1.5 rounded-md text-muted-foreground hover:text-destructive hover:bg-destructive/10 transition-colors"
                title="删除"
                @click.stop="requestDelete(session.id)"
              >
                <Trash2 :size="14" />
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 删除确认对话框 -->
    <div
      v-if="deleteTarget"
      class="fixed inset-0 bg-black/50 flex items-center justify-center z-50"
      @click.self="deleteTarget = null"
    >
      <div class="bg-card border border-border rounded-lg p-6 w-full max-w-sm shadow-lg">
        <h3 class="text-lg font-semibold text-foreground mb-2">确认删除</h3>
        <p class="text-sm text-muted-foreground mb-4">
          确定要删除此会话吗？此操作不可撤销。
        </p>
        <div class="flex justify-end gap-2">
          <button
            class="h-9 px-4 rounded-md text-sm border border-input hover:bg-accent transition-colors"
            @click="deleteTarget = null"
          >
            取消
          </button>
          <button
            class="h-9 px-4 rounded-md text-sm bg-destructive text-destructive-foreground hover:bg-destructive/90 transition-colors"
            @click="confirmDelete"
          >
            删除
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
