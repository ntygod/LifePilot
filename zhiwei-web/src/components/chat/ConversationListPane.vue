<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Archive,
  Clock3,
  Pencil,
  Pin,
  Plus,
  Search,
  Trash2,
} from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { useChatStore } from '@/stores/chat'
import type { ChatSession } from '@/types'

interface Props {
  isMobile?: boolean
}

withDefaults(defineProps<Props>(), {
  isMobile: false,
})

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

const activeSessions = computed(() => sortedSessions.value.filter(session => !session.archived && matchesSearch(session)))
const archivedSessions = computed(() => sortedSessions.value.filter(session => session.archived && matchesSearch(session)))

function formatRelativeTime(isoString?: string) {
  if (!isoString) return ''

  const date = new Date(isoString)
  const now = Date.now()
  const diffMs = now - date.getTime()
  const diffMin = Math.floor(diffMs / 60000)
  const diffHour = Math.floor(diffMs / 3600000)
  const diffDay = Math.floor(diffMs / 86400000)

  if (diffMin < 1) return '刚刚'
  if (diffMin < 60) return `${diffMin} 分钟前`
  if (diffHour < 24) return `${diffHour} 小时前`
  if (diffDay === 1) return '昨天'
  if (diffDay < 7) return `${diffDay} 天前`
  return `${date.getMonth() + 1} 月 ${date.getDate()} 日`
}

function emitClose() {
  emit('close')
}

async function handleNewConversation() {
  const session = await chatStore.startNewSession()
  router.push({ name: 'conversationDetail', params: { sessionId: session.id } })
  emitClose()
}

function openConversationWorkspace() {
  router.push({ name: 'conversations' })
  emitClose()
}

function selectSession(sessionId: string) {
  chatStore.activeSessionId = sessionId
  router.push({ name: 'conversationDetail', params: { sessionId } })
  emitClose()
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
</script>

<template>
  <div class="flex h-full flex-col">
    <div class="border-b border-sidebar-border/45 px-4 py-4">
      <div class="space-y-4">
        <div class="space-y-1">
          <div class="surface-label">对话</div>
          <div class="text-sm font-semibold text-foreground">会话历史</div>
          <p class="text-xs leading-5 text-muted-foreground">
            最近的任务、标题和上下文固定在左侧，不再和全局导航混在一起。
          </p>
        </div>

        <div class="grid grid-cols-2 gap-2">
          <Button type="button" class="justify-center" @click="handleNewConversation">
            <Plus class="size-4" />
            新对话
          </Button>
          <Button type="button" variant="outline" class="justify-center" @click="openConversationWorkspace">
            工作台
          </Button>
        </div>

        <div class="relative">
          <Search class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            v-model="searchQuery"
            type="search"
            placeholder="搜索会话"
            class="h-10 border-border/55 bg-background/72 pl-9 text-sm"
          />
        </div>
      </div>
    </div>

    <div class="flex-1 overflow-y-auto px-3 py-4 scrollbar-thin">
      <section class="space-y-2">
        <div class="flex items-center justify-between px-2">
          <div class="nav-section-title">最近会话</div>
          <span class="text-xs text-muted-foreground">{{ activeSessions.length }}</span>
        </div>

        <div
          v-if="activeSessions.length === 0"
          class="section-panel border-dashed px-4 py-5 text-sm text-muted-foreground"
        >
          {{ searchQuery ? '没有匹配的会话。' : '还没有会话，从上方开始一段新对话。' }}
        </div>

        <div v-else class="space-y-2">
          <article
            v-for="session in activeSessions"
            :key="session.id"
            class="session-item group"
            :class="{ 'session-item-active': isCurrentSession(session.id) }"
            @click="selectSession(session.id)"
          >
            <div class="flex items-start gap-2.5">
              <div class="min-w-0 flex-1">
                <Input
                  v-if="renamingId === session.id"
                  v-model="renameTitle"
                  class="h-8 border-border/55 bg-background/75 text-sm"
                  @blur="confirmRename(session.id)"
                  @click.stop
                  @keyup.enter.stop="confirmRename(session.id)"
                  @keyup.esc.stop="cancelRename"
                />
                <div v-else class="space-y-1.5">
                  <div class="flex items-center gap-2">
                    <span class="truncate text-sm font-medium text-foreground">
                      {{ session.title || '新对话' }}
                    </span>
                    <Pin v-if="session.pinned" class="size-3.5 shrink-0 text-primary" />
                  </div>
                  <p v-if="session.lastMessagePreview" class="line-clamp-2 text-xs leading-5 text-muted-foreground">
                    {{ session.lastMessagePreview }}
                  </p>
                  <div class="inline-flex items-center gap-1.5 text-[11px] text-muted-foreground">
                    <Clock3 class="size-3.5" />
                    {{ formatRelativeTime(session.updatedAt) }}
                  </div>
                </div>
              </div>

              <div class="flex shrink-0 items-center gap-1 opacity-0 transition-opacity duration-150 group-hover:opacity-100">
                <button
                  type="button"
                  class="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-card/80 hover:text-foreground"
                  title="重命名"
                  @click.stop="startRename(session)"
                >
                  <Pencil class="size-3.5" />
                </button>
                <button
                  type="button"
                  class="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive"
                  title="删除会话"
                  @click.stop="handleDelete(session.id)"
                >
                  <Trash2 class="size-3.5" />
                </button>
              </div>
            </div>
          </article>
        </div>
      </section>

      <section class="mt-5 space-y-2" v-if="archivedSessions.length > 0">
        <button
          type="button"
          class="flex w-full items-center justify-between rounded-xl px-2 py-1.5 text-left transition-colors hover:bg-muted/45"
          @click="showArchived = !showArchived"
        >
          <div class="flex items-center gap-2 text-xs font-medium text-muted-foreground">
            <Archive class="size-3.5" />
            已归档
          </div>
          <span class="text-xs text-muted-foreground">{{ archivedSessions.length }}</span>
        </button>

        <div v-if="showArchived" class="space-y-2">
          <article
            v-for="session in archivedSessions"
            :key="session.id"
            class="session-item opacity-80"
            @click="selectSession(session.id)"
          >
            <div class="flex items-center gap-2">
              <span class="truncate text-sm font-medium text-foreground">
                {{ session.title || '新对话' }}
              </span>
              <Pin v-if="session.pinned" class="size-3.5 shrink-0 text-primary" />
            </div>
            <div class="inline-flex items-center gap-1.5 text-[11px] text-muted-foreground">
              <Clock3 class="size-3.5" />
              {{ formatRelativeTime(session.updatedAt) }}
            </div>
          </article>
        </div>
      </section>
    </div>
  </div>
</template>
