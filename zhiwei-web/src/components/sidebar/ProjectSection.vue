<script setup lang="ts">
/**
 * 侧栏"项目"分组 —— Plan 1 Task 18 + 2026-04-24 嵌套展开修订。
 *
 * 职责：
 * - 挂载时拉取项目列表（依赖 {@link useProjectStore}）
 * - 渲染"新建项目"按钮（通过 emit 交给父组件弹出创建对话框）
 * - 渲染项目条目，支持展开/折叠：
 *   - 折叠：仅显示 Folder 图标 + 项目名，右侧 ChevronRight
 *   - 展开：按需拉取 `GET /api/chat/sessions?projectId=xxx`，在项目下缩进展示对话
 * - 点击项目名本身切换展开/折叠（Qwen 式交互），点击项目图标区可独立跳转项目主页
 * - 当前路由所在项目默认展开，其他默认折叠
 *
 * 使用 Tailwind 命名尺度 + lucide-vue-next 图标，按项目前端规范书写。
 *
 * @author zsg
 * @since 2026-04-24
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChevronDown, ChevronRight, Folder, FolderPlus } from 'lucide-vue-next'
import { chatApi } from '@/api/client'
import { logger } from '@/utils/logger'
import { useChatStore } from '@/stores/chat'
import { useProjectStore } from '@/stores/project'
import type { ChatSession } from '@/types'

defineEmits<{ create: [] }>()

const store = useProjectStore()
const chatStore = useChatStore()
const router = useRouter()
const route = useRoute()

/** 展开的项目 ID 集合 —— 默认全部折叠，当前路由所在项目会被自动加入 */
const expandedProjects = ref<Set<string>>(new Set())

/** 每个项目的会话列表缓存（按需加载） */
const projectSessions = ref<Record<string, ChatSession[]>>({})

/** 各项目的加载状态 */
const loadingProjects = ref<Set<string>>(new Set())

/** 各项目的加载错误信息 */
const loadErrors = ref<Record<string, string>>({})

/** 从路由解析当前所在项目 ID（仅 /projects/:id 路由匹配） */
const currentProjectId = computed<string | null>(() => {
  if (route.name !== 'projectDetail') return null
  const id = route.params.id
  return typeof id === 'string' && id.length > 0 ? id : null
})

onMounted(() => {
  // 拉取失败的错误已经在 store 内部捕获并写入 store.error，这里不再处理
  store.fetchProjects().catch(() => {})
})

/**
 * 当前路由进入某项目时自动展开并拉取其会话列表，
 * 保证用户发完消息返回侧栏能立即看到新对话嵌在项目下。
 */
watch(
  currentProjectId,
  id => {
    if (!id) return
    if (!expandedProjects.value.has(id)) {
      expandedProjects.value = new Set([...expandedProjects.value, id])
    }
    void loadProjectSessions(id)
  },
  { immediate: true },
)

/**
 * 加载指定项目的会话列表（缓存复用，除非 {@code force=true}）。
 * 注：侧栏对话发送后新建的会话会通过 chatStore.sessions 同步更新，
 * 这里加载的是"初始快照"，之后的变化由 {@link sessionsByProject} 合并。
 */
async function loadProjectSessions(projectId: string, force = false): Promise<void> {
  if (!force && projectSessions.value[projectId]) return
  if (loadingProjects.value.has(projectId)) return

  loadingProjects.value = new Set([...loadingProjects.value, projectId])
  delete loadErrors.value[projectId]

  try {
    const list = await chatApi.listSessions(projectId)
    projectSessions.value = { ...projectSessions.value, [projectId]: list }
  } catch (err: any) {
    loadErrors.value = {
      ...loadErrors.value,
      [projectId]: err?.message ?? '加载对话失败',
    }
    logger.warn('侧栏加载项目会话失败:', err)
  } finally {
    const next = new Set(loadingProjects.value)
    next.delete(projectId)
    loadingProjects.value = next
  }
}

/**
 * 项目会话展示列表 —— 合并初始快照与 chatStore 中的项目会话
 * （后者会被新建/重命名实时更新）。去重 by id，按 updatedAt 倒序。
 */
function sessionsByProject(projectId: string): ChatSession[] {
  const cached = projectSessions.value[projectId] ?? []
  const fromStore = chatStore.sessions.filter(s => s.projectId === projectId)
  const merged = new Map<string, ChatSession>()
  for (const s of cached) merged.set(s.id, s)
  // store 中的是更新后的权威版本，覆盖快照
  for (const s of fromStore) merged.set(s.id, s)
  return [...merged.values()]
    .filter(s => !s.archived)
    .sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime())
}

/** 切换项目展开状态；展开时按需拉取会话列表 */
function toggleProject(projectId: string) {
  const next = new Set(expandedProjects.value)
  if (next.has(projectId)) {
    next.delete(projectId)
  } else {
    next.add(projectId)
    void loadProjectSessions(projectId)
  }
  expandedProjects.value = next
}

/** 跳转到项目详情页 */
function openProject(id: string) {
  router.push(`/projects/${id}`)
}

/** 跳转到项目下某个对话 */
function openSession(sessionId: string) {
  chatStore.activeSessionId = sessionId
  router.push({ name: 'conversationDetail', params: { sessionId } })
}

/** 判断对话是否为当前激活 */
function isActiveSession(sessionId: string): boolean {
  return route.name === 'conversationDetail' && chatStore.activeSessionId === sessionId
}

/* ── 展开/折叠过渡动画（基于真实高度） ── */

function onBeforeEnter(el: Element) {
  const htmlEl = el as HTMLElement
  htmlEl.style.height = '0'
  htmlEl.style.opacity = '0'
  htmlEl.style.overflow = 'hidden'
}

function onEnter(el: Element, done: () => void) {
  const htmlEl = el as HTMLElement
  // 先让浏览器计算出真实高度
  const height = htmlEl.scrollHeight
  // 强制 reflow
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
  // 强制 reflow
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
  <div class="flex flex-col gap-xs py-sm">
    <div class="px-md text-xs uppercase tracking-wider text-muted-foreground">项目</div>

    <button
      type="button"
      data-testid="create-project-btn"
      class="flex items-center gap-sm px-md py-xs rounded-md text-left hover:bg-accent"
      @click="$emit('create')"
    >
      <FolderPlus class="size-md" />
      <span class="text-sm">新建项目</span>
    </button>

    <div v-for="p in store.projects" :key="p.id" class="flex flex-col">
      <!-- 项目行：左侧 Folder + 项目名（点击跳转），右侧 Chevron（点击展开/折叠） -->
      <div
        class="group flex items-center gap-xs rounded-md hover:bg-accent"
        data-testid="project-row"
      >
        <button
          type="button"
          class="flex flex-1 items-center gap-sm px-md py-xs text-left"
          data-testid="project-open-btn"
          @click="openProject(p.id)"
        >
          <Folder class="size-md shrink-0" />
          <span class="text-sm truncate">{{ p.name }}</span>
        </button>
        <button
          type="button"
          class="flex size-md shrink-0 items-center justify-center rounded-sm text-muted-foreground hover:text-foreground mr-xs"
          :title="expandedProjects.has(p.id) ? '折叠' : '展开'"
          data-testid="project-toggle-btn"
          @click="toggleProject(p.id)"
        >
          <ChevronDown
            v-if="expandedProjects.has(p.id)"
            class="size-md"
          />
          <ChevronRight
            v-else
            class="size-md"
          />
        </button>
      </div>

      <!-- 展开后的嵌套会话列表 -->
      <Transition
        @before-enter="onBeforeEnter"
        @enter="onEnter"
        @after-enter="onAfterEnter"
        @before-leave="onBeforeLeave"
        @leave="onLeave"
        @after-leave="onAfterLeave"
      >
      <div
        v-if="expandedProjects.has(p.id)"
        class="flex flex-col gap-xs pl-xl"
        data-testid="project-session-nested"
      >
        <div
          v-if="loadingProjects.has(p.id) && !projectSessions[p.id]"
          class="px-md py-xs text-xs text-muted-foreground"
          data-testid="project-session-loading"
        >
          加载中...
        </div>

        <div
          v-else-if="loadErrors[p.id]"
          class="px-md py-xs text-xs text-destructive"
          data-testid="project-session-error"
        >
          {{ loadErrors[p.id] }}
        </div>

        <div
          v-else-if="sessionsByProject(p.id).length === 0"
          class="px-md py-xs text-xs text-muted-foreground"
          data-testid="project-session-empty"
        >
          暂无对话
        </div>

        <button
          v-else
          v-for="s in sessionsByProject(p.id)"
          :key="s.id"
          type="button"
          class="flex items-center gap-sm px-md py-xs rounded-md text-left hover:bg-accent"
          :class="{ 'bg-accent': isActiveSession(s.id) }"
          data-testid="project-session-item"
          @click="openSession(s.id)"
        >
          <span class="text-sm truncate">{{ s.title || '新对话' }}</span>
        </button>
      </div>
      </Transition>
    </div>
  </div>
</template>
