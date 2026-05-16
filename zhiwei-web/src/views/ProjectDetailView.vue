<script setup lang="ts">
/**
 * 项目详情页 —— Plan 1 Task 20（polish 2026-04-24 修订）。
 *
 * 布局对齐 Qwen 式就地输入：
 * - 顶栏：Folder 图标 + 项目名 + 右上角「资料 / 设置」入口
 * - 主区居中：项目名（大字）+ 就地 {@link ChatInput}，用户输入即可创建新对话
 * - 下方：本项目下的对话列表（点击跳转）；空态引导用上方输入框开始
 *
 * 发送流程（就地 → 新会话）：
 * 1. 用户提交：调 {@link useChatStore.startNewSession} 带 projectId 创建会话
 * 2. 把首轮输入写入 {@code chatStore.pendingFirstSend}（含附件 / sessionConfig）
 * 3. {@code router.push} 到 {@code conversationDetail}，{@link ChatView} 的
 *    {@code onMounted} 会消费 pendingFirstSend 触发流式发送
 *
 * @author zsg
 * @since 2026-04-24
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Folder, MessageSquare, Paperclip, Settings } from 'lucide-vue-next'
import { chatApi } from '@/api/client'
import { logger } from '@/utils/logger'
import ChatInput from '@/components/chat/ChatInput.vue'
import ProjectResourcePanel from '@/components/project/ProjectResourcePanel.vue'
import ProjectSettingsPanel from '@/components/project/ProjectSettingsPanel.vue'
import { useChatStore } from '@/stores/chat'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'
import { useProjectStore } from '@/stores/project'
import type { ChatAttachment, ChatSession, SessionConfigOverride } from '@/types'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const chatStore = useChatStore()
const kbStore = useKnowledgeBaseStore()

const projectId = computed(() => route.params.id as string)
const project = computed(() =>
  projectStore.projects.find(p => p.id === projectId.value),
)

/** 项目资料抽屉 */
const showResource = ref(false)
/** 项目设置抽屉 */
const showSettings = ref(false)

/** 本项目下的会话列表 —— 由 {@link loadProjectSessions} 按 projectId 拉取 */
const projectSessions = ref<ChatSession[]>([])
const sessionsLoading = ref(false)
const sessionsError = ref<string | null>(null)

/** 提交中 —— 会话创建 / 跳转尚未完成时禁用输入框，避免重复点击 */
const submitting = ref(false)
const submitError = ref<string | null>(null)

async function ensureProject() {
  if (projectStore.projects.length === 0) {
    try {
      await projectStore.fetchProjects()
    } catch {
      // 错误已落到 store.error，这里不阻断渲染
    }
  }
  if (!project.value) {
    await router.replace({ name: 'home' })
  }
}

async function loadProjectSessions(id: string) {
  sessionsLoading.value = true
  sessionsError.value = null
  try {
    projectSessions.value = await chatApi.listSessions(id)
  } catch (err: any) {
    sessionsError.value = err?.message ?? '加载对话列表失败'
    logger.warn('加载项目会话列表失败:', err)
  } finally {
    sessionsLoading.value = false
  }
}

onMounted(async () => {
  await ensureProject()

  if (project.value) {
    void loadProjectSessions(projectId.value)
    void kbStore.fetchList()
  }
})

// 切项目（路由参数变化）时刷新会话列表
watch(projectId, (id, prev) => {
  if (id && id !== prev && project.value) {
    void loadProjectSessions(id)
  }
})

/**
 * 处理 {@link ChatInput} 发送事件：
 * 1. 若当前仍在提交，忽略重复触发
 * 2. 在项目下创建新会话（带 projectId）
 * 3. 将首轮输入暂存到 {@code chatStore.pendingFirstSend}
 * 4. 跳转到对话详情路由，交由 {@link ChatView} 消费并触发流式发送
 */
async function handleSend(payload: {
  content: string
  attachmentIds?: string[]
  attachments?: ChatAttachment[]
  singleTurnOverride?: SessionConfigOverride | null
}) {
  if (submitting.value) return

  const content = payload.content?.trim() ?? ''
  const hasAttachments = (payload.attachmentIds?.length ?? 0) > 0
  if (!content && !hasAttachments) {
    return
  }

  submitting.value = true
  submitError.value = null

  try {
    const session = await chatStore.startNewSession(undefined, projectId.value)

    chatStore.pendingFirstSend = {
      content,
      attachmentIds: payload.attachmentIds,
      attachments: payload.attachments,
      singleTurnOverride: payload.singleTurnOverride,
    }

    await router.push({
      name: 'conversationDetail',
      params: { sessionId: session.id },
    })
  } catch (err: any) {
    submitError.value = err?.message ?? '创建对话失败，请重试'
    logger.error('项目就地输入创建会话失败:', err)
  } finally {
    submitting.value = false
  }
}

/** 点击列表项 —— 跳到对应会话详情 */
function openSession(session: ChatSession) {
  router.push({
    name: 'conversationDetail',
    params: { sessionId: session.id },
  })
}

/**
 * 格式化时间为「今天 / 昨天 / MM-DD」。
 *
 * 列表里的时间是辅助信息，只需展示近期可读性，具体时分在会话详情中展示。
 */
function formatSessionTime(iso: string): string {
  if (!iso) return ''
  const time = new Date(iso)
  if (Number.isNaN(time.getTime())) return ''

  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const startOfYesterday = new Date(startOfToday)
  startOfYesterday.setDate(startOfYesterday.getDate() - 1)

  if (time >= startOfToday) return '今天'
  if (time >= startOfYesterday) return '昨天'

  const month = (time.getMonth() + 1).toString().padStart(2, '0')
  const day = time.getDate().toString().padStart(2, '0')
  return `${month}-${day}`
}
</script>

<template>
  <div v-if="project" class="flex h-full flex-col overflow-hidden">
    <header
      class="flex items-center justify-between border-b px-xl py-md"
      data-testid="project-detail-header"
    >
      <div class="flex min-w-0 items-center gap-sm">
        <Folder class="size-md shrink-0 text-muted-foreground" />
        <span class="truncate text-lg font-semibold">{{ project.name }}</span>
      </div>

      <div class="flex shrink-0 items-center gap-sm">
        <button
          type="button"
          class="rounded-md p-xs transition-colors hover:bg-accent"
          title="项目资料"
          data-testid="open-resource-btn"
          @click="showResource = true"
        >
          <Paperclip class="size-md" />
        </button>
        <button
          type="button"
          class="rounded-md p-xs transition-colors hover:bg-accent"
          title="项目设置"
          data-testid="open-settings-btn"
          @click="showSettings = true"
        >
          <Settings class="size-md" />
        </button>
      </div>
    </header>

    <div class="flex flex-1 flex-col items-center overflow-auto px-xl py-2xl">
      <!-- 项目名（主视觉） + 就地输入框 -->
      <section
        class="flex w-full max-w-xl flex-col items-center gap-lg"
        data-testid="project-inline-composer"
      >
        <div class="flex items-center gap-sm">
          <Folder class="size-lg text-primary" />
          <h1 class="text-xl font-semibold tracking-tight">{{ project.name }}</h1>
        </div>

        <div class="w-full">
          <ChatInput
            :disabled="submitting"
            placeholder="有什么我能帮您的吗？"
            :knowledge-bases="kbStore.list"
            data-testid="project-detail-chat-input"
            @send="handleSend"
          />
        </div>

        <div
          v-if="submitError"
          class="w-full rounded-md border border-destructive/40 bg-destructive/10 px-md py-xs text-sm text-destructive"
          data-testid="project-detail-error"
        >
          {{ submitError }}
        </div>
      </section>

      <!-- 本项目对话列表 -->
      <section
        class="mt-2xl w-full max-w-xl"
        data-testid="project-session-list"
      >
        <div class="mb-md flex items-center gap-sm text-sm font-semibold text-muted-foreground">
          <MessageSquare class="size-md" />
          聊天
        </div>

        <div
          v-if="sessionsLoading"
          class="text-sm text-muted-foreground"
          data-testid="project-session-loading"
        >
          加载中...
        </div>

        <div
          v-else-if="sessionsError"
          class="rounded-md border border-destructive/40 bg-destructive/10 px-md py-xs text-sm text-destructive"
          data-testid="project-session-error"
        >
          {{ sessionsError }}
        </div>

        <div
          v-else-if="projectSessions.length === 0"
          class="text-sm text-muted-foreground"
          data-testid="project-session-empty"
        >
          暂无对话。发送上方消息开始第一段对话。
        </div>

        <ul v-else class="flex flex-col gap-xs">
          <li
            v-for="session in projectSessions"
            :key="session.id"
          >
            <button
              type="button"
              class="flex w-full items-center justify-between gap-md rounded-md border border-transparent px-md py-sm text-left transition-colors hover:border-border/60 hover:bg-accent/40"
              data-testid="project-session-item"
              @click="openSession(session)"
            >
              <div class="min-w-0 flex-1">
                <div class="truncate text-sm font-medium text-foreground">
                  {{ session.title?.trim() || '新对话' }}
                </div>
                <div
                  v-if="session.lastMessagePreview"
                  class="mt-xs truncate text-xs text-muted-foreground"
                >
                  {{ session.lastMessagePreview }}
                </div>
              </div>
              <span class="shrink-0 text-xs text-muted-foreground">
                {{ formatSessionTime(session.updatedAt || session.createdAt) }}
              </span>
            </button>
          </li>
        </ul>
      </section>
    </div>

    <ProjectResourcePanel
      v-model:open="showResource"
      :project="project"
    />

    <ProjectSettingsPanel
      v-model:open="showSettings"
      :project="project"
    />
  </div>

  <div v-else class="flex h-full items-center justify-center p-xl text-sm text-muted-foreground">
    加载中...
  </div>
</template>
