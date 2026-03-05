<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import ThemeToggle from '@/components/global/ThemeToggle.vue'
import { Sheet, SheetContent } from '@/components/ui/sheet'
import { AnimatePresence, motion } from 'motion-v'
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
  Pencil,
  Trash2,
  ShoppingBag
} from 'lucide-vue-next'

// motion.div 组件引用，用于模板中的动态组件
const MotionDiv = motion.div

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
      route.path.startsWith('/mcp-servers') || route.path.startsWith('/marketplace')) {
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
      { label: 'Skill 市场', path: '/marketplace', icon: ShoppingBag },
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

// 移动端 Sheet 底部导航分区（复用 navItems 配置，避免模板重复）
const mobileSections = [
  { title: '工作区', items: workspaceNavItems },
  { title: '分析统计', items: analyticsNavItems },
  { title: '设置', items: settingsNavItems }
]
</script>

<template>
  <!-- 移动端：使用 shadcn-vue Sheet 替代手写 fixed + translate-x 滑入 -->
  <Sheet v-if="isMobile" :open="isOpen" @update:open="(val: boolean) => { if (!val) emit('close') }">
    <SheetContent side="left" class="w-[var(--sidebar-width)] p-0 bg-sidebar-background/80 backdrop-blur-xl border-sidebar-border/50">
      <!-- 移动端标题栏（Sheet 自带关闭按钮，此处仅展示标题） -->
      <div class="h-16 px-lg border-b border-border flex items-center">
        <h1 class="text-lg font-semibold text-foreground tracking-tight">LifePilot</h1>
      </div>

      <!-- 移动端新建按钮 -->
      <div class="px-md py-sm border-b border-border">
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
        <p v-if="chatStore.sessions.length === 0" class="text-sm text-muted-foreground p-sm">
          暂无会话
        </p>
        <div
          v-for="session in chatStore.sessions"
          :key="session.id"
          class="group flex items-center gap-xs px-md py-sm rounded-lg text-sm cursor-pointer transition-colors duration-200 ease-out"
          :class="chatStore.activeSessionId === session.id
            ? 'bg-accent text-accent-foreground'
            : 'text-muted-foreground hover:bg-accent/50 hover:text-accent-foreground'"
          @click="selectSession(session.id)"
        >
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
          <div class="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity duration-150 shrink-0">
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

      <!-- 底部导航 -->
      <div class="p-sm border-t border-border space-y-md overflow-y-auto">
        <template v-for="section in mobileSections" :key="section.title">
          <div class="space-y-xs" :class="{ 'pt-sm': section.title !== '工作区' }">
            <div class="px-md pb-xs text-xs font-medium text-muted-foreground uppercase tracking-wide">{{ section.title }}</div>
            <template v-for="item in section.items" :key="item.id">
              <router-link
                v-if="item.type === 'single' && item.path"
                :to="item.path"
                class="relative flex items-center gap-sm px-md py-sm rounded-lg text-sm transition-colors duration-200 ease-out"
                :class="isRouteActive(item.path)
                  ? 'nav-item-active bg-accent text-accent-foreground'
                  : 'text-muted-foreground hover:bg-accent/50 hover:text-accent-foreground'"
              >
                <component :is="item.icon" :size="18" class="shrink-0 text-muted-foreground" />
                <span class="truncate">{{ item.label }}</span>
              </router-link>

              <div v-else-if="item.type === 'group'">
                <button
                  class="w-full flex items-center gap-sm px-md py-sm rounded-lg text-sm transition-colors duration-200 ease-out"
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

                <AnimatePresence>
                  <MotionDiv
                    v-if="expandedGroups.has(item.id)"
                    :initial="{ height: 0, opacity: 0 }"
                    :animate="{ height: 'auto', opacity: 1 }"
                    :exit="{ height: 0, opacity: 0 }"
                    :transition="{ duration: 0.25 }"
                    style="overflow: hidden"
                  >
                    <div class="ml-md mt-xs space-y-xs">
                      <router-link
                        v-for="child in item.children"
                        :key="child.path"
                        :to="child.path"
                        class="relative flex items-center gap-sm px-md py-xs rounded-lg text-sm transition-colors duration-200 ease-out"
                        :class="isRouteActive(child.path)
                          ? 'nav-item-active bg-accent text-accent-foreground'
                          : 'text-muted-foreground hover:bg-accent/50 hover:text-accent-foreground'"
                      >
                        <component :is="child.icon" :size="16" class="shrink-0 text-muted-foreground" />
                        <span class="truncate">{{ child.label }}</span>
                      </router-link>
                    </div>
                  </MotionDiv>
                </AnimatePresence>
              </div>
            </template>
          </div>
        </template>
      </div>

      <!-- 主题切换 -->
      <div class="p-sm border-t border-border flex items-center justify-center">
        <ThemeToggle />
      </div>
    </SheetContent>
  </Sheet>

  <!-- 桌面端：静态侧边栏 -->
  <aside
    v-else
    class="w-[var(--sidebar-width)] border-r border-sidebar-border/50 bg-sidebar-background/80 backdrop-blur-xl flex flex-col h-full"
  >
    <!-- 顶部标题 + 新建按钮（桌面端） -->
    <div class="h-16 px-lg border-b border-border flex items-center justify-between">
      <h1 class="text-lg font-semibold text-foreground tracking-tight">LifePilot</h1>
      <button
        class="p-1.5 rounded-md text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
        title="新建对话"
        @click="newChat"
      >
        <Plus :size="18" />
      </button>
    </div>

    <!-- 会话列表 -->
    <div class="flex-1 overflow-y-auto py-md px-sm space-y-sm">
      <button
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
        class="group flex items-center gap-xs px-md py-sm rounded-lg text-sm cursor-pointer transition-colors duration-200 ease-out"
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
        <div class="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity duration-150 shrink-0">
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
            class="relative flex items-center gap-sm px-md py-sm rounded-lg text-sm transition-colors duration-200 ease-out"
            :class="isRouteActive(item.path)
              ? 'nav-item-active bg-accent text-accent-foreground'
              : 'text-muted-foreground hover:bg-accent/50 hover:text-accent-foreground'"
          >
            <component :is="item.icon" :size="18" class="shrink-0 text-muted-foreground" />
            <span class="truncate">{{ item.label }}</span>
          </router-link>

          <div v-else-if="item.type === 'group'">
            <button
              class="w-full flex items-center gap-sm px-md py-sm rounded-lg text-sm transition-colors duration-200 ease-out"
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

            <AnimatePresence>
              <MotionDiv
                v-if="expandedGroups.has(item.id)"
                :initial="{ height: 0, opacity: 0 }"
                :animate="{ height: 'auto', opacity: 1 }"
                :exit="{ height: 0, opacity: 0 }"
                :transition="{ duration: 0.25 }"
                style="overflow: hidden"
              >
                <div class="ml-md mt-xs space-y-xs">
                  <router-link
                    v-for="child in item.children"
                    :key="child.path"
                    :to="child.path"
                    class="relative flex items-center gap-sm px-md py-xs rounded-lg text-sm transition-colors duration-200 ease-out"
                    :class="isRouteActive(child.path)
                      ? 'nav-item-active bg-accent text-accent-foreground'
                      : 'text-muted-foreground hover:bg-accent/50 hover:text-accent-foreground'"
                  >
                    <component :is="child.icon" :size="16" class="shrink-0 text-muted-foreground" />
                    <span class="truncate">{{ child.label }}</span>
                  </router-link>
                </div>
              </MotionDiv>
            </AnimatePresence>
          </div>
        </template>
      </div>

      <!-- 分析统计 -->
      <div class="space-y-xs pt-sm">
        <div class="px-md pb-xs text-xs font-medium text-muted-foreground uppercase tracking-wide">分析统计</div>
        <template v-for="item in analyticsNavItems" :key="item.id">
          <div v-if="item.type === 'group'">
            <button
              class="w-full flex items-center gap-sm px-md py-sm rounded-lg text-sm transition-colors duration-200 ease-out"
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

            <AnimatePresence>
              <MotionDiv
                v-if="expandedGroups.has(item.id)"
                :initial="{ height: 0, opacity: 0 }"
                :animate="{ height: 'auto', opacity: 1 }"
                :exit="{ height: 0, opacity: 0 }"
                :transition="{ duration: 0.25 }"
                style="overflow: hidden"
              >
                <div class="ml-md mt-xs space-y-xs">
                  <router-link
                    v-for="child in item.children"
                    :key="child.path"
                    :to="child.path"
                    class="relative flex items-center gap-sm px-md py-xs rounded-lg text-sm transition-colors duration-200 ease-out"
                    :class="isRouteActive(child.path)
                      ? 'nav-item-active bg-accent text-accent-foreground'
                      : 'text-muted-foreground hover:bg-accent/50 hover:text-accent-foreground'"
                  >
                    <component :is="child.icon" :size="16" class="shrink-0 text-muted-foreground" />
                    <span class="truncate">{{ child.label }}</span>
                  </router-link>
                </div>
              </MotionDiv>
            </AnimatePresence>
          </div>
        </template>
      </div>

      <!-- 设置 -->
      <div class="space-y-xs pt-sm">
        <div class="px-md pb-xs text-xs font-medium text-muted-foreground uppercase tracking-wide">设置</div>
        <template v-for="item in settingsNavItems" :key="item.id">
          <div v-if="item.type === 'group'">
            <button
              class="w-full flex items-center gap-sm px-md py-sm rounded-lg text-sm transition-colors duration-200 ease-out"
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

            <AnimatePresence>
              <MotionDiv
                v-if="expandedGroups.has(item.id)"
                :initial="{ height: 0, opacity: 0 }"
                :animate="{ height: 'auto', opacity: 1 }"
                :exit="{ height: 0, opacity: 0 }"
                :transition="{ duration: 0.25 }"
                style="overflow: hidden"
              >
                <div class="ml-md mt-xs space-y-xs">
                  <router-link
                    v-for="child in item.children"
                    :key="child.path"
                    :to="child.path"
                    class="relative flex items-center gap-sm px-md py-xs rounded-lg text-sm transition-colors duration-200 ease-out"
                    :class="isRouteActive(child.path)
                      ? 'nav-item-active bg-accent text-accent-foreground'
                      : 'text-muted-foreground hover:bg-accent/50 hover:text-accent-foreground'"
                  >
                    <component :is="child.icon" :size="16" class="shrink-0 text-muted-foreground" />
                    <span class="truncate">{{ child.label }}</span>
                  </router-link>
                </div>
              </MotionDiv>
            </AnimatePresence>
          </div>
        </template>
      </div>
    </div>

    <!-- 主题切换 -->
    <div class="p-sm border-t border-border flex items-center justify-center">
      <ThemeToggle />
    </div>
  </aside>
</template>

<style scoped>
/* 选中导航项左侧 3px primary 色竖条指示器 */
.nav-item-active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 60%;
  border-radius: 0 2px 2px 0;
  background: var(--primary);
}
</style>
