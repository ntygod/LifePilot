<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import {
  LibraryBig,
  MessageSquareText,
  Puzzle,
  Search,
  Settings2,
  SlidersHorizontal,
  Sparkles,
  Square,
} from 'lucide-vue-next'
import { chatApi, llmProviderApi } from '@/api/client'
import type { LlmProvider } from '@/api/client'
import type { ChatAttachment, Message, SessionConfig } from '@/types'
import MetricCard from '@/components/common/MetricCard.vue'
import StatePanel from '@/components/common/StatePanel.vue'
import PageContainer from '@/components/layout/PageContainer.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import ChatInput from '@/components/chat/ChatInput.vue'
import DebugDrawer from '@/components/chat/DebugDrawer.vue'
import EmptyState from '@/components/chat/EmptyState.vue'
import MessageList from '@/components/chat/MessageList.vue'
import SessionConfigPanel from '@/components/chat/SessionConfigPanel.vue'
import SessionSidebar from '@/components/chat/SessionSidebar.vue'
import { useChat } from '@/composables/useChat'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'
import { useChatStore } from '@/stores/chat'
import { useSkillStore } from '@/stores/skill'
import { useUiStore } from '@/stores/ui'
import { copyToClipboard } from '@/utils/clipboard'

const route = useRoute()
const router = useRouter()
const chatStore = useChatStore()
const kbStore = useKnowledgeBaseStore()
const skillStore = useSkillStore()
const uiStore = useUiStore()
const {
  sendMessage,
  isStreaming,
  error,
  abort,
  lastModelId,
  lastTokenUsage,
  lastPrompt,
  reasoningStatusText,
  reasoningEvents,
  streamingA2uiComponents,
} = useChat()

const scrollContainer = ref<HTMLElement | null>(null)
const searchQuery = ref('')
const showDebugDrawer = ref(false)
const showSessionSidebar = ref(false)
const showConfigPanel = ref(false)
const providers = ref<LlmProvider[]>([])

const chatProviders = computed(() =>
  providers.value.filter(provider =>
    !provider.capabilities
    || provider.capabilities.length === 0
    || provider.capabilities.some(capability => capability.toLowerCase() === 'chat'),
  ),
)

const hasKnowledgeBases = computed(() => kbStore.list.length > 0)
const hasSkills = computed(() => skillStore.skills.length > 0)

const currentSession = computed(() => {
  if (!chatStore.activeSessionId) return null
  return chatStore.sessions.find(session => session.id === chatStore.activeSessionId) || null
})

const hasMessageSearch = computed(() => searchQuery.value.trim().length > 0)
const matchedMessageCount = computed(() => {
  const query = searchQuery.value.trim().toLowerCase()
  if (!query) return chatStore.messages.length

  return chatStore.messages.filter(message => message.content.toLowerCase().includes(query)).length
})

const lastAssistantMessage = computed(() => {
  for (let index = chatStore.messages.length - 1; index >= 0; index -= 1) {
    if (chatStore.messages[index].role === 'assistant') return chatStore.messages[index]
  }
  return null
})

const latestTraceMessage = computed(() => {
  for (let index = chatStore.messages.length - 1; index >= 0; index -= 1) {
    if (chatStore.messages[index].traceId) return chatStore.messages[index]
  }
  return null
})

const latestUserErrorMessage = computed(() => {
  for (let index = chatStore.messages.length - 1; index >= 0; index -= 1) {
    const message = chatStore.messages[index]
    if (message.role === 'user' && message.status === 'error') return message
  }
  return null
})

const showGlobalErrorPanel = computed(() => (
  Boolean(error.value)
  && (!latestUserErrorMessage.value || latestUserErrorMessage.value.errorMessage !== error.value)
))

const lastToolsSummary = computed(() => lastAssistantMessage.value?.toolsSummary ?? [])
const lastSources = computed(() => lastAssistantMessage.value?.sources ?? [])
const lastKbSources = computed(() => lastSources.value.filter(source => source.type === 'knowledgeBase'))
const lastTraceTarget = computed(() => (
  latestTraceMessage.value?.traceId
    ? { name: 'traces', query: { id: latestTraceMessage.value.traceId } }
    : { name: 'traces' }
))

const headerTitle = computed(() => currentSession.value?.title?.trim() || '新对话')
const headerDescription = computed(() => {
  if (chatStore.messages.length > 0) {
    return `本次对话共有 ${chatStore.messages.length} 条消息，继续追问时会沿用已有上下文和最近设置。`
  }
  return '从一个问题、片段或待办开始，发送后会自动保留上下文。'
})

const contextLabel = computed(() => lastModelId.value || '默认模型')
const skillLabel = computed(() => hasSkills.value ? `${skillStore.skills.length} 个可用` : '未启用')
const knowledgeLabel = computed(() => hasKnowledgeBases.value ? `${kbStore.list.length} 个知识库` : '未挂载')
const streamingLabel = computed(() => {
  if (isStreaming.value) return reasoningStatusText.value || '正在生成回答'
  return '待命'
})
const conversationStatusValue = computed(() => isStreaming.value ? '处理中' : '可继续')
const conversationStatusHint = computed(() => (
  isStreaming.value
    ? (reasoningStatusText.value || '系统正在整理上下文并生成回答。')
    : '上下文已经保留在当前会话里，可以继续追问。'
))
const messageSummaryHint = computed(() => (
  chatStore.messages.length > 0
    ? '当前会话已有内容沉淀，适合继续往前推进。'
    : '从一句需求、一段资料或一个待办开始就可以。'
))
const resourceSummaryHint = computed(() => {
  if (!hasSkills.value && !hasKnowledgeBases.value) return '还没挂载技能或知识库，先聊天也完全没问题。'
  return '需要时可以在会话配置里切换技能和知识库。'
})
const traceSummaryValue = computed(() => lastAssistantMessage.value?.traceId ? '已记录轨迹' : '等待新消息')
const traceSummaryHint = computed(() => (
  lastToolsSummary.value.length > 0
    ? `最近一轮调用工具 ${lastToolsSummary.value.length} 次，命中知识库 ${lastKbSources.value.length} 次。`
    : '发送消息后，这里会展示最近一轮执行情况。'
))
const messageSearchHint = computed(() => (
  hasMessageSearch.value
    ? `当前关键词命中 ${matchedMessageCount.value} 条消息，方便快速回看上下文。`
    : '可以按关键词回看当前会话里的结论、资料和历史提问。'
))

onMounted(async () => {
  const sessionId = route.params.sessionId as string | undefined
  if (sessionId && sessionId !== chatStore.activeSessionId) {
    chatStore.activeSessionId = sessionId
  } else if (!sessionId && !chatStore.activeSessionId) {
    try {
      await chatStore.startNewSession()
    } catch (event) {
      console.error('创建新会话失败:', event)
    }
  }

  void kbStore.fetchList()
  void skillStore.fetchSkills()
  try {
    providers.value = await llmProviderApi.listEnabledProviders()
  } catch {
    // Provider 列表拉取失败不阻塞页面。
  }
})

watch(
  () => route.params.sessionId as string | undefined,
  async (sessionId) => {
    if (!sessionId) {
      chatStore.activeSessionId = null
      try {
        await chatStore.startNewSession()
      } catch (event) {
        console.error('创建新会话失败:', event)
      }
      return
    }

    if (sessionId !== chatStore.activeSessionId) {
      chatStore.activeSessionId = sessionId
    }
  },
)

function scrollToBottom() {
  nextTick(() => {
    if (scrollContainer.value) {
      scrollContainer.value.scrollTop = scrollContainer.value.scrollHeight
    }
  })
}

watch(() => chatStore.messages.length, scrollToBottom)
watch(() => chatStore.streamingContent, scrollToBottom)

async function handleSend(payload: {
  content: string
  attachmentIds?: string[]
  attachments?: ChatAttachment[]
  sessionConfig?: {
    modelId?: string
    temperature?: number
    maxTokens?: number
    knowledgeBaseIds?: string[]
  }
}) {
  await sendMessage(payload.content, payload.attachmentIds, payload.attachments, payload.sessionConfig)
}

function handleEmptyStateSend(content: string) {
  void handleSend({ content })
}

async function handleRetry(message: Message) {
  if (message.status === 'error') {
    const index = chatStore.messages.findIndex(item => item.id === message.id)
    if (index !== -1) {
      chatStore.messages.splice(index, 1)
      if (message.role === 'user') {
        const nextMessage = chatStore.messages[index]
        if (nextMessage && nextMessage.role === 'assistant') {
          chatStore.messages.splice(index, 1)
        }
      }
    }
  }
  await sendMessage(message.content)
}

async function handleRegenerate(assistantMessage: Message) {
  const sorted = [...chatStore.messages].sort((left, right) => left.timestamp - right.timestamp)
  const assistantIndex = sorted.findIndex(message => message.id === assistantMessage.id)
  if (assistantIndex < 0) return

  let userMessage: Message | null = null
  for (let index = assistantIndex - 1; index >= 0; index -= 1) {
    if (sorted[index].role === 'user') {
      userMessage = sorted[index]
      break
    }
  }
  if (!userMessage) return

  const removeIndex = chatStore.messages.findIndex(message => message.id === assistantMessage.id)
  if (removeIndex !== -1) {
    chatStore.messages.splice(removeIndex, 1)
  }

  await sendMessage(userMessage.content)
}

async function handleFork(message: Message) {
  if (!chatStore.activeSessionId) return
  try {
    const newSession = await chatApi.forkSession(chatStore.activeSessionId, message.id)
    uiStore.showToast('success', '会话分叉成功')
    router.push({ name: 'conversationDetail', params: { sessionId: newSession.id } })
  } catch (event) {
    uiStore.showToast('error', '分叉会话失败，请稍后重试')
    console.error('分叉会话失败:', event)
  }
}

async function handleCopy(content: string) {
  const ok = await copyToClipboard(content)
  uiStore.showToast(ok ? 'success' : 'error', ok ? '已复制到剪贴板' : '复制失败')
}

async function handleLike(message: Message) {
  try {
    await chatApi.submitFeedback(message.id, 'like')
  } catch {
    uiStore.showToast('error', '反馈提交失败')
  }
}

async function handleDislike(message: Message, feedback?: string) {
  try {
    await chatApi.submitFeedback(message.id, 'dislike', feedback)
  } catch {
    uiStore.showToast('error', '反馈提交失败')
  }
}

async function handleClearSession() {
  await chatStore.clearCurrentSessionMessages()
}

async function handleConfigUpdate(config: SessionConfig) {
  if (!chatStore.activeSessionId) return
  try {
    await chatApi.updateSessionConfig(chatStore.activeSessionId, config)
    uiStore.showToast('success', '配置已更新')
  } catch {
    uiStore.showToast('error', '配置更新失败')
  }
}

async function handleUpdateSessionTitle(title: string) {
  if (!chatStore.activeSessionId || !title.trim()) return
  try {
    await chatApi.updateSession(chatStore.activeSessionId, { title: title.trim() })
    await chatStore.loadSessions()
  } catch {
    uiStore.showToast('error', '更新会话标题失败')
  }
}

function closeInspectorPanels() {
  showSessionSidebar.value = false
  showDebugDrawer.value = false
}
</script>

<template>
  <div class="h-full overflow-hidden">
    <PageContainer size="wide" class="flex h-full min-h-0 flex-col py-4 sm:py-6">
      <div class="mx-auto flex h-full w-full max-w-[1460px] min-h-0 flex-col gap-4">
        <header class="space-y-4 border-b border-border/70 pb-4">
          <div class="flex flex-col gap-4 xl:flex-row xl:items-start xl:justify-between">
            <div class="max-w-4xl min-w-0 space-y-2">
              <div class="surface-label">对话</div>
              <h1 class="truncate text-2xl font-semibold tracking-tight text-foreground sm:text-3xl">
                {{ headerTitle }}
              </h1>
              <p class="text-sm leading-6 text-muted-foreground">
                {{ headerDescription }}
              </p>
            </div>

            <div class="flex flex-wrap items-center gap-2">
              <Button
                type="button"
                variant="outline"
                :class="showConfigPanel && 'status-btn-active'"
                @click="showConfigPanel = !showConfigPanel"
              >
                <Settings2 class="size-4" />
                {{ showConfigPanel ? '收起配置' : '会话配置' }}
              </Button>
              <Button
                v-if="chatStore.activeSessionId"
                type="button"
                variant="outline"
                :class="showSessionSidebar && 'status-btn-active'"
                @click="showSessionSidebar = !showSessionSidebar"
              >
                <LibraryBig class="size-4" />
                {{ showSessionSidebar ? '收起信息' : '会话信息' }}
              </Button>
              <Button
                type="button"
                variant="outline"
                :class="showDebugDrawer && 'status-btn-active'"
                @click="showDebugDrawer = !showDebugDrawer"
              >
                <SlidersHorizontal class="size-4" />
                {{ showDebugDrawer ? '收起调试' : '轨迹与调试' }}
              </Button>
              <Button v-if="isStreaming" type="button" variant="destructive" @click="abort">
                <Square class="size-4" />
                停止生成
              </Button>
            </div>
          </div>

          <div class="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
            <MetricCard label="当前状态" :value="conversationStatusValue" :hint="conversationStatusHint">
              <template #icon>
                <Sparkles class="size-5" />
              </template>
              <span class="surface-chip" :class="isStreaming && 'surface-chip-strong'">
                {{ streamingLabel }}
              </span>
            </MetricCard>

            <MetricCard label="消息" :value="chatStore.messages.length" :hint="messageSummaryHint">
              <template #icon>
                <MessageSquareText class="size-5" />
              </template>
            </MetricCard>

            <MetricCard label="模型与资源" :value="contextLabel" :hint="resourceSummaryHint">
              <template #icon>
                <Puzzle class="size-5" />
              </template>
              <div class="flex flex-wrap gap-2">
                <span class="surface-chip">技能 {{ skillLabel }}</span>
                <span class="surface-chip">知识库 {{ knowledgeLabel }}</span>
              </div>
            </MetricCard>

            <MetricCard label="最近执行" :value="traceSummaryValue" :hint="traceSummaryHint">
              <template #icon>
                <LibraryBig class="size-5" />
              </template>
              <RouterLink
                :to="lastTraceTarget"
                class="inline-flex items-center gap-1 text-sm font-medium text-primary transition-colors hover:text-primary/80"
              >
                查看轨迹
              </RouterLink>
            </MetricCard>
          </div>
        </header>

        <div class="flex min-h-0 flex-1 gap-4">
          <section class="flex min-h-0 flex-1 flex-col overflow-hidden rounded-[calc(var(--radius)+2px)] border border-border/70 bg-card/92">
            <div class="border-b border-border/70 px-4 py-4 sm:px-5">
              <div class="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
                <div class="relative w-full max-w-[24rem]">
                  <Search class="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    v-model="searchQuery"
                    type="search"
                    placeholder="搜索当前会话内容"
                    class="pl-9"
                  />
                </div>

                <div class="flex flex-wrap items-center gap-2">
                  <RouterLink
                    :to="lastTraceTarget"
                    class="inline-flex h-9 items-center justify-center rounded-lg border border-border/70 bg-background/80 px-4 text-sm font-medium text-muted-foreground transition-colors hover:bg-accent/75 hover:text-foreground"
                  >
                    查看轨迹
                  </RouterLink>
                  <Button
                    v-if="chatStore.activeSessionId && chatStore.messages.length > 0"
                    type="button"
                    variant="ghost"
                    @click="handleClearSession"
                  >
                    清空当前会话
                  </Button>
                </div>
              </div>

              <div class="mt-3 flex flex-wrap gap-2 text-xs">
                <span class="filter-pill">工具调用 {{ lastToolsSummary.length }} 次</span>
                <span class="filter-pill">知识库命中 {{ lastKbSources.length }} 次</span>
                <span class="filter-pill">最近模型 {{ contextLabel }}</span>
                <span v-if="hasMessageSearch" class="surface-chip surface-chip-strong">搜索命中 {{ matchedMessageCount }} 条</span>
              </div>

              <div class="mt-3 flex flex-col gap-2 rounded-[calc(var(--radius)+2px)] border border-dashed border-border/60 bg-background/50 px-3 py-3 sm:flex-row sm:items-center sm:justify-between">
                <p class="text-sm text-muted-foreground">
                  {{ messageSearchHint }}
                </p>
                <button
                  v-if="hasMessageSearch"
                  type="button"
                  class="inline-flex items-center gap-1 text-sm font-medium text-primary transition-colors hover:text-primary/80"
                  @click="searchQuery = ''"
                >
                  清空搜索
                </button>
              </div>

              <div v-if="showConfigPanel" class="mt-4">
                <SessionConfigPanel
                  :model-id="lastModelId ?? undefined"
                  :providers="chatProviders"
                  :knowledge-bases="kbStore.list"
                  @close="showConfigPanel = false"
                  @update="handleConfigUpdate"
                />
              </div>
            </div>

            <div
              ref="scrollContainer"
              class="min-h-0 flex-1 overflow-y-auto bg-grid-soft scrollbar-thin scrollbar-track-transparent scrollbar-thumb-border"
            >
              <div v-if="chatStore.messages.length === 0 && !isStreaming" class="flex h-full items-center justify-center p-6">
                <EmptyState @send="handleEmptyStateSend" />
              </div>

              <div v-else class="mx-auto w-full max-w-4xl p-4 pb-16 sm:p-6 xl:p-8">
                <MessageList
                  :messages="chatStore.messages"
                  :is-streaming="isStreaming"
                  :streaming-content="chatStore.streamingContent"
                  :streaming-reasoning-events="reasoningEvents"
                  :streaming-a2ui-components="streamingA2uiComponents"
                  :query="searchQuery"
                  @retry="handleRetry"
                  @like="handleLike"
                  @dislike="handleDislike"
                  @fork="handleFork"
                  @regenerate="handleRegenerate"
                  @copy="handleCopy"
                />
              </div>
            </div>
          </section>

          <div
            v-if="showSessionSidebar || showDebugDrawer"
            class="fixed inset-0 z-40 bg-background/62 p-3 backdrop-blur-sm xl:static xl:z-auto xl:w-[320px] xl:bg-transparent xl:p-0 xl:backdrop-blur-0"
            @click.self="closeInspectorPanels"
          >
            <div class="ml-auto flex h-full w-full max-w-[320px] flex-col gap-4 overflow-y-auto xl:h-auto xl:max-w-none xl:overflow-visible">
              <SessionSidebar
                v-if="showSessionSidebar && chatStore.activeSessionId"
                :session="currentSession"
                :knowledge-bases="kbStore.list"
                :message-count="chatStore.messages.length"
                @close="showSessionSidebar = false"
                @update-title="handleUpdateSessionTitle"
              />

              <DebugDrawer
                v-if="showDebugDrawer"
                :token-usage="lastTokenUsage"
                :model-id="lastModelId"
                :prompt="lastPrompt"
                :reasoning-events="reasoningEvents"
                :tools-summary="lastToolsSummary"
                :kb-sources="lastKbSources"
                :trace-id="lastAssistantMessage?.traceId"
                @close="showDebugDrawer = false"
              />
            </div>
          </div>
        </div>

        <div v-if="showGlobalErrorPanel || isStreaming" class="space-y-3">
          <StatePanel
            v-if="showGlobalErrorPanel"
            title="本轮对话出现错误"
            :description="error ?? undefined"
            tone="danger"
          >
            <template #actions>
              <Button type="button" variant="outline" @click="error = null">
                关闭
              </Button>
              <RouterLink
                :to="{ name: 'traces' }"
                class="inline-flex h-9 items-center justify-center rounded-lg border border-border/70 bg-background/80 px-4 text-sm font-medium text-muted-foreground transition-colors hover:bg-accent/75 hover:text-foreground"
              >
                查看执行轨迹
              </RouterLink>
            </template>
          </StatePanel>

          <StatePanel
            v-if="isStreaming"
            title="正在生成回答"
            :description="reasoningStatusText || '系统正在整理上下文、推理步骤和最终回答。'"
          >
            <template #actions>
              <Button type="button" variant="destructive" @click="abort">
                <Square class="size-4" />
                停止生成
              </Button>
            </template>
          </StatePanel>
        </div>

        <div class="overflow-hidden rounded-[calc(var(--radius)+2px)] border border-border/70 bg-card/92">
          <ChatInput :disabled="isStreaming" @send="handleSend" />
        </div>
      </div>
    </PageContainer>
  </div>
</template>
