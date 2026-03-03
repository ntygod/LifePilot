<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { 
  MessageSquare, 
  BookOpen, 
  Bot, 
  Workflow, 
  Puzzle, 
  Wrench, 
  Server, 
  BarChart3, 
  Settings,
  ChevronRight,
  Plus,
  X,
  Menu,
  Pencil,
  Trash2
} from 'lucide-vue-next'

interface Props {
  isMobile?: boolean
  isOpen?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  isMobile: false,
  isOpen: false
})

const emit = defineEmits<{
  close: []
}>()

const router = useRouter()
const route = useRoute()
const chatStore = useChatStore()
const renamingId = ref<string | null>(null)
const renameTitle = ref('')
const expandedGroups = ref<Set<string>>(new Set(['agents', 'analytics', 'settings']))

onMounted(() => {
  chatStore.loadSessions()
  // 根据当前路由自动展开对应的分组
  if (route.path.startsWith('/agents') || route.path.startsWith('/workflows') || 
      route.path.startsWith('/skills') || route.path.startsWith('/tools') || 
      route.path.startsWith('/mcp-servers')) {
    expandedGroups.value.add('agents')
  }
  if (route.path.startsWith('/analytics')) {
    expandedGroups.value.add('analytics')
  }
  if (route.path.startsWith('/settings')) {
    expandedGroups.value.add('settings')
  }
})

function selectSession(id: string) {
  chatStore.activeSessionId = id
  router.push({ name: 'conversationDetail', params: { sessionId: id } })
}

async function handleDelete(id: string) {
  await chatStore.deleteSession(id)
}

function newChat() {
  chatStore.activeSessionId = null
  router.push({ name: 'conversations' })
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
  // 目前后端暂未暴露重命名接口，这里先在前端本地更新，后续可接 PUT /chat/sessions/{id}
  const session = chatStore.sessions.find(s => s.id === id)
  if (session) {
    session.title = title
  }
  renamingId.value = null
}

function cancelRename() {
  renamingId.value = null
}

function toggleGroup(groupId: string) {
  if (expandedGroups.value.has(groupId)) {
    expandedGroups.value.delete(groupId)
  } else {
    expandedGroups.value.add(groupId)
  }
}

function isRouteActive(path: string): boolean {
  return route.path === path || route.path.startsWith(path + '/')
}

function isGroupActive(paths: string[]): boolean {
  return paths.some(path => isRouteActive(path))
}

// 导航项配置
const navItems = [
  {
    id: 'conversations',
    label: '对话',
    icon: MessageSquare,
    path: '/conversations',
    type: 'single'
  },
  {
    id: 'knowledge-bases',
    label: '知识库',
    icon: BookOpen,
    path: '/knowledge-bases',
    type: 'single'
  },
  {
    id: 'agents',
    label: 'Agent & 工作流',
    icon: Bot,
    type: 'group',
    children: [
      { label: 'Agents', path: '/agents', icon: Bot },
      { label: '工作流', path: '/workflows', icon: Workflow },
      { label: 'Skills', path: '/skills', icon: Puzzle },
      { label: '工具', path: '/tools', icon: Wrench },
      { label: 'MCP Servers', path: '/mcp-servers', icon: Server }
    ]
  },
  {
    id: 'analytics',
    label: '分析 / 用量',
    icon: BarChart3,
    type: 'group',
    children: [
      { label: '用量统计', path: '/analytics/usage', icon: BarChart3 },
      { label: 'Agent 分析', path: '/analytics/agents', icon: Bot }
    ]
  },
  {
    id: 'settings',
    label: '设置',
    icon: Settings,
    type: 'group',
    children: [
      { label: '偏好设置', path: '/settings/preferences', icon: Settings },
      { label: '模型配置', path: '/settings/models', icon: Bot },
      { label: '快捷键', path: '/settings/shortcuts', icon: Settings }
    ]
  }
]

const workspaceNavItems = navItems.filter(i => i.id === 'knowledge-bases' || i.id === 'agents')
const analyticsNavItems = navItems.filter(i => i.id === 'analytics')
const settingsNavItems = navItems.filter(i => i.id === 'settings')
</script>

<template>
  <!-- 移动端遮罩层 -->
  <div
    v-if="isMobile && isOpen"
    class="fixed inset-0 bg-background/80 backdrop-blur-sm z-40 md:hidden"
    @click="emit('close')"
  />
  
  <!-- 侧边栏 -->
  <aside
    class="w-[var(--sidebar-width)] border-r border-border bg-card flex flex-col h-full transition-transform duration-200 z-50"
    :class="{
      'fixed inset-y-0 left-0': isMobile,
      '-translate-x-full': isMobile && !isOpen,
      'translate-x-0': isMobile && isOpen
    }"
  >
    <!-- 移动端关闭按钮 -->
    <div v-if="isMobile" class="h-16 px-lg border-b border-border flex items-center justify-between">
      <h1 class="text-lg font-semibold text-foreground tracking-tight">LifePilot</h1>
      <button
        class="p-1.5 rounded-md text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
        @click="emit('close')"
      >
        <X :size="18" />
      </button>
    </div>
    <!-- 顶部标题 + 新建按钮（桌面端） -->
    <div v-if="!isMobile" class="h-16 px-lg border-b border-border flex items-center justify-between">
      <h1 class="text-lg font-semibold text-foreground tracking-tight">LifePilot</h1>
      <button
        class="p-1.5 rounded-md text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
        title="新建对话"
        @click="newChat"
      >
        <Plus :size="18" />
      </button>
    </div>
    
    <!-- 移动端新建按钮 -->
    <div v-if="isMobile" class="px-md py-sm border-b border-border">
      <button
        class="w-full flex items-center justify-center gap-2 px-4 py-2 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors text-sm"
        @click="newChat(); emit('close')"
      >
        <Plus :size="18" />
        新建对话
      </button>
    </div>

    <!-- 会话列表 -->
    <div class="flex-1 overflow-y-auto py-md px-sm space-y-sm">
      <button
        v-if="!isMobile"
        type="button"
        class="w-full flex items-center justify-center gap-sm px-md py-sm rounded-lg bg-muted text-muted-foreground hover:bg-accent hover:text-accent-foreground transition-all duration-200"
        @click="newChat"
      >
        <Plus :size="16" />
        新对话
      </button>

      <p v-if="chatStore.sessions.length === 0" class="text-sm text-muted-foreground p-sm">
        暂无会话
      </p>
      <div
        v-for="session in chatStore.sessions"
        :key="session.id"
        class="group flex items-center gap-xs px-md py-sm rounded-lg text-sm cursor-pointer transition-colors"
        :class="chatStore.activeSessionId === session.id
          ? 'bg-accent text-accent-foreground'
          : 'text-muted-foreground hover:bg-accent/50 hover:text-accent-foreground'"
        @click="selectSession(session.id)"
      >
        <!-- 标题 / 重命名输入 -->
        <div class="flex-1 min-w-0">
          <input
            v-if="renamingId === session.id"
            v-model="renameTitle"
            class="w-full bg-background/80 rounded px-1 py-0.5 text-xs focus:outline-none focus:ring-1 focus:ring-ring"
            @keyup.enter.stop="confirmRename(session.id)"
            @keyup.esc.stop="cancelRename"
            @click.stop
            @blur="confirmRename(session.id)"
          />
          <span v-else class="truncate">{{ session.title || '新对话' }}</span>
        </div>

        <!-- 操作按钮 -->
        <div class="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity shrink-0">
          <button
            class="text-muted-foreground hover:text-foreground transition-colors"
            title="重命名"
            @click.stop="startRename(session.id, session.title || '新对话')"
          >
            <Pencil :size="14" />
          </button>
          <button
            class="text-muted-foreground hover:text-destructive transition-colors"
            title="删除会话"
            @click.stop="handleDelete(session.id)"
          >
            <Trash2 :size="14" />
          </button>
        </div>
      </div>
    </div>

    <!-- 底部导航（对齐 stitch：分区标题 + 分组） -->
    <div class="p-sm border-t border-border space-y-md overflow-y-auto">
      <!-- 工作区 -->
      <div class="space-y-xs">
        <div class="px-md pb-xs text-xs font-medium text-muted-foreground uppercase tracking-wide">工作区</div>
        <template v-for="item in workspaceNavItems" :key="item.id">
          <router-link
            v-if="item.type === 'single' && item.path"
            :to="item.path"
            class="flex items-center gap-sm px-md py-sm rounded-lg text-sm transition-colors"
            :class="isRouteActive(item.path)
              ? 'bg-accent text-accent-foreground'
              : 'text-muted-foreground hover:bg-accent/50 hover:text-accent-foreground'"
          >
            <component :is="item.icon" :size="18" class="shrink-0 text-muted-foreground" />
            <span class="truncate">{{ item.label }}</span>
          </router-link>

          <div v-else-if="item.type === 'group'">
            <button
              class="w-full flex items-center gap-sm px-md py-sm rounded-lg text-sm transition-colors"
              :class="isGroupActive(item.children!.map(c => c.path))
                ? 'bg-accent/50 text-accent-foreground'
                : 'text-muted-foreground hover:bg-accent/50 hover:text-accent-foreground'"
              @click="toggleGroup(item.id)"
            >
              <component :is="item.icon" :size="18" class="shrink-0 text-muted-foreground" />
              <span class="flex-1 text-left truncate">{{ item.label }}</span>
              <ChevronRight
                :size="16"
                class="shrink-0 transition-transform"
                :class="{ 'rotate-90': expandedGroups.has(item.id) }"
              />
            </button>

            <div v-if="expandedGroups.has(item.id)" class="ml-md mt-xs space-y-xs">
              <router-link
                v-for="child in item.children"
                :key="child.path"
                :to="child.path"
                class="flex items-center gap-sm px-md py-xs rounded-lg text-sm transition-colors"
                :class="isRouteActive(child.path)
                  ? 'bg-accent text-accent-foreground'
                  : 'text-muted-foreground hover:bg-accent/50 hover:text-accent-foreground'"
              >
                <component :is="child.icon" :size="16" class="shrink-0 text-muted-foreground" />
                <span class="truncate">{{ child.label }}</span>
              </router-link>
            </div>
          </div>
        </template>
      </div>

      <!-- 分析统计 -->
      <div class="space-y-xs pt-sm">
        <div class="px-md pb-xs text-xs font-medium text-muted-foreground uppercase tracking-wide">分析统计</div>
        <template v-for="item in analyticsNavItems" :key="item.id">
          <div v-if="item.type === 'group'">
            <button
              class="w-full flex items-center gap-sm px-md py-sm rounded-lg text-sm transition-colors"
              :class="isGroupActive(item.children!.map(c => c.path))
                ? 'bg-accent/50 text-accent-foreground'
                : 'text-muted-foreground hover:bg-accent/50 hover:text-accent-foreground'"
              @click="toggleGroup(item.id)"
            >
              <component :is="item.icon" :size="18" class="shrink-0 text-muted-foreground" />
              <span class="flex-1 text-left truncate">{{ item.label }}</span>
              <ChevronRight
                :size="16"
                class="shrink-0 transition-transform"
                :class="{ 'rotate-90': expandedGroups.has(item.id) }"
              />
            </button>

            <div v-if="expandedGroups.has(item.id)" class="ml-md mt-xs space-y-xs">
              <router-link
                v-for="child in item.children"
                :key="child.path"
                :to="child.path"
                class="flex items-center gap-sm px-md py-xs rounded-lg text-sm transition-colors"
                :class="isRouteActive(child.path)
                  ? 'bg-accent text-accent-foreground'
                  : 'text-muted-foreground hover:bg-accent/50 hover:text-accent-foreground'"
              >
                <component :is="child.icon" :size="16" class="shrink-0 text-muted-foreground" />
                <span class="truncate">{{ child.label }}</span>
              </router-link>
            </div>
          </div>
        </template>
      </div>

      <!-- 设置 -->
      <div class="space-y-xs pt-sm">
        <div class="px-md pb-xs text-xs font-medium text-muted-foreground uppercase tracking-wide">设置</div>
        <template v-for="item in settingsNavItems" :key="item.id">
          <div v-if="item.type === 'group'">
            <button
              class="w-full flex items-center gap-sm px-md py-sm rounded-lg text-sm transition-colors"
              :class="isGroupActive(item.children!.map(c => c.path))
                ? 'bg-accent/50 text-accent-foreground'
                : 'text-muted-foreground hover:bg-accent/50 hover:text-accent-foreground'"
              @click="toggleGroup(item.id)"
            >
              <component :is="item.icon" :size="18" class="shrink-0 text-muted-foreground" />
              <span class="flex-1 text-left truncate">{{ item.label }}</span>
              <ChevronRight
                :size="16"
                class="shrink-0 transition-transform"
                :class="{ 'rotate-90': expandedGroups.has(item.id) }"
              />
            </button>

            <div v-if="expandedGroups.has(item.id)" class="ml-md mt-xs space-y-xs">
              <router-link
                v-for="child in item.children"
                :key="child.path"
                :to="child.path"
                class="flex items-center gap-sm px-md py-xs rounded-lg text-sm transition-colors"
                :class="isRouteActive(child.path)
                  ? 'bg-accent text-accent-foreground'
                  : 'text-muted-foreground hover:bg-accent/50 hover:text-accent-foreground'"
              >
                <component :is="child.icon" :size="16" class="shrink-0 text-muted-foreground" />
                <span class="truncate">{{ child.label }}</span>
              </router-link>
            </div>
          </div>
        </template>
      </div>
    </div>
  </aside>
</template>
