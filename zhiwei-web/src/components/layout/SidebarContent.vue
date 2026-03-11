<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AnimatePresence, motion } from 'motion-v'
import {
  BarChart3,
  BookOpen,
  Bot,
  ChevronRight,
  GitBranch,
  MessageSquare,
  Pencil,
  Pin,
  Plus,
  Puzzle,
  Server,
  Settings,
  ShoppingBag,
  Trash2,
  Workflow,
  Wrench,
} from 'lucide-vue-next'
import ThemeToggle from '@/components/global/ThemeToggle.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { useChatStore } from '@/stores/chat'

const MotionDiv = motion.div

interface Props {
  isMobile?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  isMobile: false,
})

const emit = defineEmits<{
  close: []
}>()

const router = useRouter()
const route = useRoute()
const chatStore = useChatStore()
const renamingId = ref<string | null>(null)
const renameTitle = ref('')
const expandedGroups = ref<Set<string>>(new Set(['workspace', 'analytics', 'settings']))

type NavLeaf = {
  label: string
  path: string
  icon: typeof Bot
}

type NavItem = {
  id: string
  label: string
  icon: typeof Bot
  path?: string
  children?: NavLeaf[]
}

const navSections: Array<{ title: string; items: NavItem[] }> = [
  {
    title: '工作区',
    items: [
      {
        id: 'knowledge-bases',
        label: '知识库',
        icon: BookOpen,
        path: '/knowledge-bases',
      },
      {
        id: 'workspace',
        label: '智能体与流程',
        icon: Bot,
        children: [
          { label: '智能体', path: '/agents', icon: Bot },
          { label: '工作流', path: '/workflows', icon: Workflow },
          { label: '技能', path: '/skills', icon: Puzzle },
          { label: '市场', path: '/marketplace', icon: ShoppingBag },
          { label: '工具', path: '/tools', icon: Wrench },
          { label: 'MCP 服务器', path: '/mcp-servers', icon: Server },
          { label: '依赖关系', path: '/dependencies', icon: GitBranch },
        ],
      },
    ],
  },
  {
    title: '分析',
    items: [
      {
        id: 'analytics',
        label: '用量分析',
        icon: BarChart3,
        children: [
          { label: '用量统计', path: '/analytics/usage', icon: BarChart3 },
          { label: '智能体分析', path: '/analytics/agents', icon: Bot },
          { label: '工具统计', path: '/analytics/tools', icon: Wrench },
        ],
      },
    ],
  },
  {
    title: '设置',
    items: [
      {
        id: 'settings',
        label: '偏好与模型',
        icon: Settings,
        children: [
          { label: '偏好设置', path: '/settings', icon: Settings },
          { label: '模型服务', path: '/settings/models', icon: Bot },
          { label: '快捷键', path: '/settings/shortcuts', icon: Settings },
        ],
      },
    ],
  },
] as const

onMounted(async () => {
  await chatStore.loadSessions()
  syncExpandedGroups()
})

watch(() => route.path, syncExpandedGroups)

function syncExpandedGroups() {
  if (
    route.path.startsWith('/agents')
    || route.path.startsWith('/workflows')
    || route.path.startsWith('/skills')
    || route.path.startsWith('/tools')
    || route.path.startsWith('/mcp-servers')
    || route.path.startsWith('/marketplace')
    || route.path.startsWith('/dependencies')
  ) {
    expandedGroups.value.add('workspace')
  }

  if (route.path.startsWith('/analytics')) {
    expandedGroups.value.add('analytics')
  }

  if (route.path.startsWith('/settings')) {
    expandedGroups.value.add('settings')
  }
}

function emitClose() {
  if (props.isMobile) {
    emit('close')
  }
}

function newChat() {
  chatStore.activeSessionId = null
  router.push({ name: 'conversations' })
  emitClose()
}

function openConversationList() {
  router.push('/conversations')
  emitClose()
}

function selectSession(id: string) {
  chatStore.activeSessionId = id
  router.push({ name: 'conversationDetail', params: { sessionId: id } })
  emitClose()
}

function startRename(id: string, currentTitle: string) {
  renamingId.value = id
  renameTitle.value = currentTitle
}

async function confirmRename(id: string) {
  const title = renameTitle.value.trim()
  if (!title) {
    renamingId.value = null
    return
  }

  const session = chatStore.sessions.find(item => item.id === id)
  if (session) {
    session.title = title
  }

  renamingId.value = null
}

function cancelRename() {
  renamingId.value = null
}

async function handleDelete(id: string) {
  await chatStore.deleteSession(id)
}

function toggleGroup(groupId: string) {
  if (expandedGroups.value.has(groupId)) {
    expandedGroups.value.delete(groupId)
  } else {
    expandedGroups.value.add(groupId)
  }
}

function isRouteActive(path: string) {
  return route.path === path || route.path.startsWith(`${path}/`)
}

function isGroupActive(paths: string[]) {
  return paths.some(path => isRouteActive(path))
}

function navigate(path: string) {
  router.push(path)
  emitClose()
}

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

const sessionCountLabel = computed(() => `${chatStore.sessions.length} 个会话`)
const pinnedSessionCount = computed(() => chatStore.sessions.filter(session => session.pinned).length)
</script>

<template>
  <div class="sidebar-panel h-full">
    <div class="border-b border-sidebar-border/55 px-4 py-4">
      <div class="space-y-4">
        <div class="space-y-2">
          <div class="surface-label">工作台</div>
          <div class="space-y-1">
            <div class="text-sm font-semibold text-foreground">知微</div>
            <p class="text-xs leading-5 text-muted-foreground">
              常用入口、最近对话和设置都留在左侧，方便随时切换工作上下文。
            </p>
          </div>
          <div class="flex flex-wrap gap-2 text-[11px]">
            <span class="surface-chip">本地优先</span>
            <span class="surface-chip">{{ sessionCountLabel }}</span>
            <span v-if="pinnedSessionCount > 0" class="surface-chip">置顶 {{ pinnedSessionCount }}</span>
          </div>
        </div>

        <div class="section-panel p-2.5">
          <div class="grid grid-cols-2 gap-2">
            <Button type="button" class="justify-center" @click="newChat">
              <Plus class="size-4" />
              新建对话
            </Button>
            <Button type="button" variant="outline" class="justify-center" @click="openConversationList">
              <MessageSquare class="size-4" />
              全部会话
            </Button>
          </div>
        </div>
      </div>
    </div>

    <div class="flex-1 overflow-y-auto px-3 py-4 scrollbar-thin">
      <section class="space-y-3">
        <div class="flex items-center justify-between px-2">
          <div>
            <div class="nav-section-title">最近会话</div>
            <p class="mt-1 text-xs text-muted-foreground">直接回到最近处理中或需要接手的上下文。</p>
          </div>
        </div>

        <div
          v-if="chatStore.sessions.length === 0"
          class="section-panel border-dashed px-4 py-5 text-sm text-muted-foreground"
        >
          还没有会话，从上方入口开始一段新的对话。
        </div>

        <div v-else class="space-y-2">
          <div
            v-for="session in chatStore.sessions"
            :key="session.id"
            class="session-item group"
            :class="{ 'session-item-active': chatStore.activeSessionId === session.id }"
            @click="selectSession(session.id)"
          >
            <div class="flex items-start gap-2">
              <div class="min-w-0 flex-1">
                <Input
                  v-if="renamingId === session.id"
                  v-model="renameTitle"
                  class="h-8 bg-background/80 text-sm"
                  @blur="confirmRename(session.id)"
                  @click.stop
                  @keyup.enter.stop="confirmRename(session.id)"
                  @keyup.esc.stop="cancelRename"
                />
                <div v-else class="space-y-1">
                  <div class="flex items-center gap-2">
                    <span class="truncate text-sm font-medium text-foreground">
                      {{ session.title || '新对话' }}
                    </span>
                    <Pin v-if="session.pinned" class="size-3.5 shrink-0 text-primary" />
                  </div>
                  <span class="block text-xs text-muted-foreground/80">
                    {{ formatRelativeTime(session.updatedAt) }}
                  </span>
                </div>
              </div>

              <div class="flex shrink-0 items-center gap-1 opacity-0 transition-opacity duration-150 group-hover:opacity-100">
                <button
                  type="button"
                  class="rounded-md p-1.5 text-muted-foreground transition-colors hover:bg-card/80 hover:text-foreground"
                  title="重命名"
                  @click.stop="startRename(session.id, session.title || '新对话')"
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
          </div>
        </div>
      </section>

      <div class="my-5 h-px bg-border/80" />

      <div class="space-y-5">
        <section v-for="section in navSections" :key="section.title" class="space-y-2">
          <div class="px-2 nav-section-title">{{ section.title }}</div>

          <div class="space-y-1">
            <template v-for="item in section.items" :key="item.id">
              <button
                v-if="item.path"
                type="button"
                class="nav-link w-full"
                :class="{ 'nav-link-active': isRouteActive(item.path) }"
                @click="navigate(item.path)"
              >
                <component :is="item.icon" class="size-[18px] shrink-0 text-muted-foreground" />
                <span class="truncate">{{ item.label }}</span>
              </button>

              <div v-else class="space-y-1">
                <button
                  type="button"
                  class="nav-link w-full"
                  :class="{ 'nav-link-active': isGroupActive(item.children!.map(child => child.path)) }"
                  @click="toggleGroup(item.id)"
                >
                  <component :is="item.icon" class="size-[18px] shrink-0 text-muted-foreground" />
                  <span class="flex-1 truncate text-left">{{ item.label }}</span>
                  <ChevronRight
                    class="size-4 shrink-0 transition-transform duration-200"
                    :class="{ 'rotate-90': expandedGroups.has(item.id) }"
                  />
                </button>

                <AnimatePresence>
                  <MotionDiv
                    v-if="expandedGroups.has(item.id)"
                    :initial="{ height: 0, opacity: 0 }"
                    :animate="{ height: 'auto', opacity: 1 }"
                    :exit="{ height: 0, opacity: 0 }"
                    :transition="{ duration: 0.18, ease: 'easeOut' }"
                    class="overflow-hidden"
                  >
                    <div class="space-y-1 pl-4">
                      <button
                        v-for="child in item.children"
                        :key="child.path"
                        type="button"
                        class="nav-link w-full py-2.5 text-[0.9rem]"
                        :class="{ 'nav-link-active': isRouteActive(child.path) }"
                        @click="navigate(child.path)"
                      >
                        <component :is="child.icon" class="size-4 shrink-0 text-muted-foreground" />
                        <span class="truncate">{{ child.label }}</span>
                      </button>
                    </div>
                  </MotionDiv>
                </AnimatePresence>
              </div>
            </template>
          </div>
        </section>
      </div>
    </div>

    <div class="border-t border-sidebar-border/55 px-4 py-4">
      <div class="section-panel flex items-center justify-between px-3 py-2.5">
        <div>
          <div class="text-sm font-medium text-foreground">界面主题</div>
          <div class="text-xs text-muted-foreground">浅色、深色或跟随系统</div>
        </div>
        <ThemeToggle />
      </div>
    </div>
  </div>
</template>
