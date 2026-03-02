<script setup lang="ts">
import { ref, nextTick, watch, onMounted, computed } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'
import { useSkillStore } from '@/stores/skill'
import { useChat } from '@/composables/useChat'
import { chatApi } from '@/api/client'
import type { Message, ChatAttachment } from '@/types'
import { Info, X } from 'lucide-vue-next'
import MessageList from '@/components/chat/MessageList.vue'
import ChatInput from '@/components/chat/ChatInput.vue'

const route = useRoute()
const router = useRouter()
const chatStore = useChatStore()
const kbStore = useKnowledgeBaseStore()
const skillStore = useSkillStore()
const {
  sendMessage,
  isStreaming,
  error,
  abort,
  lastModelId,
  lastTokenUsage,
  lastPrompt,
  reasoningStatusText,
  reasoningEvents
} = useChat()
const scrollContainer = ref<HTMLElement>()
const searchQuery = ref('')
// 右侧调试抽屉开关
const showDebugDrawer = ref(false)
// 右侧会话信息侧栏开关
const showSessionSidebar = ref(false)

// 顶部上下文指示条数据
const hasKnowledgeBases = computed(() => kbStore.list.length > 0)
const hasSkills = computed(() => skillStore.skills.length > 0)

// 当前会话信息
const currentSession = computed(() => {
  if (!chatStore.activeSessionId) return null
  return chatStore.sessions.find(s => s.id === chatStore.activeSessionId) || null
})

onMounted(async () => {
  // 首次进入时，根据路由参数确定当前会话
  const sessionId = route.params.sessionId as string | undefined
  if (sessionId && sessionId !== chatStore.activeSessionId) {
    chatStore.activeSessionId = sessionId
  }

  // 轻量拉取一次知识库 / Skill 列表，用于上下文指示条
  void kbStore.fetchList()
  void skillStore.fetchSkills()
})

// 路由变化时同步 activeSessionId，支持刷新 / 直接访问分享链接
watch(
  () => route.params.sessionId as string | undefined,
  (sessionId) => {
    if (!sessionId) {
      chatStore.activeSessionId = null
      return
    }
    if (sessionId !== chatStore.activeSessionId) {
      chatStore.activeSessionId = sessionId
    }
  }
)

// 自动滚动到底部
function scrollToBottom() {
  nextTick(() => {
    if (scrollContainer.value) {
      scrollContainer.value.scrollTop = scrollContainer.value.scrollHeight
    }
  })
}

// 消息变化或流式内容变化时自动滚动
watch(() => chatStore.messages.length, scrollToBottom)
watch(() => chatStore.streamingContent, scrollToBottom)

async function handleSend(payload: { content: string; attachmentIds?: string[]; attachments?: ChatAttachment[] }) {
  await sendMessage(payload.content, payload.attachmentIds, payload.attachments)
}

async function handleRetry(message: Message) {
  // 重试：删除失败的消息，然后重新发送
  if (message.status === 'error') {
    // 找到失败的消息并删除
    const index = chatStore.messages.findIndex(m => m.id === message.id)
    if (index !== -1) {
      chatStore.messages.splice(index, 1)
      // 如果失败的是用户消息，同时删除对应的 assistant 消息（如果有）
      if (message.role === 'user') {
        const nextMessage = chatStore.messages[index]
        if (nextMessage && nextMessage.role === 'assistant') {
          chatStore.messages.splice(index, 1)
        }
      }
    }
  }
  // 重新发送消息（重试不带附件，避免重复上传）
  await sendMessage(message.content)
}

// 清空当前会话
async function handleClearSession() {
  await chatStore.clearCurrentSessionMessages()
}

// 处理消息点赞
async function handleLike(message: Message) {
  // TODO: 调用后端API记录点赞
  console.log('点赞消息:', message.id)
}

// 处理消息点踩
async function handleDislike(message: Message, feedback?: string) {
  // TODO: 调用后端API记录点踩和反馈
  console.log('点踩消息:', message.id, '反馈:', feedback)
}

// 处理分叉会话
async function handleFork(message: Message) {
  if (!chatStore.activeSessionId) return
  
  try {
    // 获取当前会话到该消息为止的所有消息
    const messagesToFork = chatStore.messages.filter(
      m => m.timestamp <= message.timestamp
    )
    
    // 创建新会话
    const newSession = await chatApi.createSession(`分叉自: ${currentSession.value?.title || '会话'}`)
    
    // TODO: 复制消息到新会话（需要后端API支持）
    // 暂时只跳转到新会话
    router.push({ name: 'chat', params: { sessionId: newSession.id } })
  } catch (error) {
    console.error('分叉会话失败:', error)
  }
}

// 会话标题（可编辑）
const sessionTitle = computed({
  get: () => currentSession.value?.title || '',
  set: (value: string) => {
    if (currentSession.value) {
      currentSession.value.title = value
    }
  }
})

// 更新会话标题
async function handleUpdateSessionTitle() {
  if (!chatStore.activeSessionId || !sessionTitle.value.trim()) return
  try {
    await chatApi.updateSession(chatStore.activeSessionId, {
      title: sessionTitle.value.trim()
    })
    // 更新本地会话列表
    await chatStore.loadSessions()
  } catch (error) {
    console.error('更新会话标题失败:', error)
  }
}
</script>

<template>
  <div class="flex flex-col h-full">
    <!-- 顶部上下文指示条 + 最近一轮统计 -->
    <div class="px-4 md:px-6 py-2 border-b border-border bg-muted/40 text-xs text-muted-foreground space-y-2">
      <!-- 上下文指示条：当前模型 / 知识库 / Skill / 跳转 -->
      <div class="flex flex-wrap items-center gap-2">
        <span class="font-medium text-foreground/80">本会话上下文</span>
        <span v-if="lastModelId">· 当前模型：{{ lastModelId }}</span>
        <span v-else>· 当前模型：默认模型（从设置中继承）</span>
        <span>· 知识库：{{ hasKnowledgeBases ? `已配置 ${kbStore.list.length} 个` : '尚未配置' }}</span>
        <span>· Skill：{{ hasSkills ? `已注册 ${skillStore.skills.length} 个` : '尚未注册' }}</span>
        <span v-if="reasoningStatusText">· 当前状态：{{ reasoningStatusText }}</span>
        <RouterLink
          :to="{ name: 'knowledgeBases' }"
          class="underline-offset-2 hover:underline"
        >
          管理知识库
        </RouterLink>
        <span>·</span>
        <RouterLink
          :to="{ name: 'skills' }"
          class="underline-offset-2 hover:underline"
        >
          管理 Skill
        </RouterLink>

        <!-- 右侧侧栏开关 -->
        <div class="ml-auto flex items-center gap-2">
          <button
            v-if="chatStore.activeSessionId"
            type="button"
            class="inline-flex items-center gap-2 px-3 py-1.5 rounded-lg border border-border text-xs font-medium
                   hover:bg-background/60 transition-all duration-200 focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
            :class="showSessionSidebar ? 'text-foreground bg-background/60' : 'text-muted-foreground'"
            @click="showSessionSidebar = !showSessionSidebar"
          >
            <Info :size="14" />
            <span>{{ showSessionSidebar ? '隐藏信息' : '会话信息' }}</span>
          </button>
          <button
            type="button"
            class="inline-flex items-center gap-2 px-3 py-1.5 rounded-lg border border-border text-xs font-medium
                   hover:bg-background/60 transition-all duration-200 focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
            :class="lastTokenUsage ? 'text-foreground' : 'text-muted-foreground'"
            @click="showDebugDrawer = !showDebugDrawer"
          >
            <span class="inline-block w-1.5 h-1.5 rounded-full"
                  :class="showDebugDrawer ? 'bg-primary' : 'bg-muted-foreground/60'" />
            <span>{{ showDebugDrawer ? '收起调试' : '调试视图' }}</span>
          </button>
        </div>
      </div>

      <!-- 最近一轮模型 / Token 使用摘要 -->
      <div
        v-if="lastTokenUsage"
        class="flex items-center justify-between"
      >
        <div class="flex flex-wrap items-center gap-2">
          <span class="font-medium text-foreground/80">本轮统计</span>
          <span>·</span>
          <span>模型：{{ lastModelId || '未知模型' }}</span>
          <span>·</span>
          <span>
            Tokens：{{ lastTokenUsage.totalTokens }}
            （提示 {{ lastTokenUsage.promptTokens }} / 回答 {{ lastTokenUsage.completionTokens }}）
          </span>
        </div>
        <RouterLink
          :to="{ name: 'traces' }"
          class="underline-offset-2 hover:underline"
        >
          查看更详细执行信息
        </RouterLink>
      </div>
    </div>

    <!-- 消息区域 + 右侧侧栏 -->
    <div class="flex flex-1 overflow-hidden">
      <!-- 消息区域 -->
      <div ref="scrollContainer" class="flex-1 overflow-y-auto">
        <!-- 空状态 -->
        <div v-if="chatStore.messages.length === 0 && !isStreaming" class="h-full flex items-center justify-center px-4 md:px-6">
          <div class="text-center max-w-md mx-auto space-y-6">
            <div class="w-16 h-16 rounded-full bg-primary/10 flex items-center justify-center mx-auto">
              <svg
                xmlns="http://www.w3.org/2000/svg"
                width="32"
                height="32"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
                class="text-primary"
              >
                <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
              </svg>
            </div>
            <div>
              <p class="text-xl font-semibold text-foreground mb-2 leading-tight">开始新对话</p>
              <p class="text-sm text-muted-foreground leading-normal">向 AI 提问，或粘贴一段内容让它帮你总结、改写或分析。</p>
            </div>
            <div class="flex flex-col gap-2">
              <button
                class="w-full rounded-lg border border-dashed border-border px-4 py-3 text-left text-sm
                       hover:bg-accent hover:text-accent-foreground transition-all duration-200"
              >
                帮我快速了解这个项目目前的能力和限制
              </button>
              <button
                class="w-full rounded-lg border border-dashed border-border px-4 py-3 text-left text-sm
                       hover:bg-accent hover:text-accent-foreground transition-all duration-200"
              >
                我想用自己的文档搭一个知识库助手，应该怎么开始？
              </button>
              <button
                class="w-full rounded-lg border border-dashed border-border px-4 py-3 text-left text-sm
                       hover:bg-accent hover:text-accent-foreground transition-all duration-200"
              >
                结合最近几条对话，帮我整理一份可以发给同事的总结
              </button>
            </div>
          </div>
        </div>

        <!-- 消息列表 + 顶部搜索 / 过滤条 -->
        <div v-else class="max-w-[768px] mx-auto px-4 md:px-6 py-6 space-y-4">
          <div class="flex items-center gap-2">
            <input
              v-model="searchQuery"
              type="search"
              placeholder="在当前对话中搜索（按内容关键字）…"
              class="flex-1 h-9 rounded-lg border border-input bg-background px-3 text-sm
                     placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent transition-all duration-200"
            />
            <span class="text-xs text-muted-foreground whitespace-nowrap">
              共 {{ chatStore.messages.length }} 条
            </span>
            <button
              v-if="chatStore.activeSessionId && chatStore.messages.length > 0"
              type="button"
              class="inline-flex items-center gap-1 px-3 py-1.5 rounded-lg border border-border text-xs font-medium
                     text-muted-foreground hover:text-destructive hover:border-destructive/70 hover:bg-destructive/5
                     transition-all duration-200 focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
              @click="handleClearSession"
            >
              清空当前会话
            </button>
          </div>
          <MessageList
            :messages="chatStore.messages"
            :is-streaming="isStreaming"
            :streaming-content="chatStore.streamingContent"
            :query="searchQuery"
            @retry="handleRetry"
            @like="handleLike"
            @dislike="handleDislike"
            @fork="handleFork"
          />
        </div>
      </div>

      <!-- 右侧会话信息侧栏 -->
      <div
        v-if="showSessionSidebar && chatStore.activeSessionId"
        class="hidden lg:flex w-80 border-l border-border bg-background text-sm flex-col"
      >
        <div class="px-4 py-3 border-b border-border flex items-center justify-between">
          <span class="font-medium text-foreground">会话信息</span>
          <button
            type="button"
            class="text-muted-foreground hover:text-foreground transition-colors"
            @click="showSessionSidebar = false"
          >
            <X :size="16" />
          </button>
        </div>
        <div class="flex-1 overflow-y-auto px-4 py-3 space-y-4">
          <!-- 会话名称（可编辑） -->
          <div>
            <label class="text-xs font-medium text-muted-foreground mb-1 block">会话名称</label>
            <input
              v-model="sessionTitle"
              type="text"
              class="w-full rounded-md border border-input bg-background px-3 py-1.5 text-sm
                     focus:outline-none focus:ring-1 focus:ring-ring"
              @blur="handleUpdateSessionTitle"
            />
          </div>
          
          <!-- 创建时间 -->
          <div>
            <label class="text-xs font-medium text-muted-foreground mb-1 block">创建时间</label>
            <p class="text-sm text-foreground">
              {{ currentSession?.createdAt ? new Date(currentSession.createdAt).toLocaleString() : '-' }}
            </p>
          </div>
          
          <!-- 更新时间 -->
          <div>
            <label class="text-xs font-medium text-muted-foreground mb-1 block">更新时间</label>
            <p class="text-sm text-foreground">
              {{ currentSession?.updatedAt ? new Date(currentSession.updatedAt).toLocaleString() : '-' }}
            </p>
          </div>
          
          <!-- 关联知识库 -->
          <div>
            <label class="text-xs font-medium text-muted-foreground mb-1 block">关联知识库</label>
            <div v-if="kbStore.list.length > 0" class="space-y-1">
              <div
                v-for="kb in kbStore.list"
                :key="kb.id"
                class="text-sm text-foreground flex items-center justify-between"
              >
                <span>{{ kb.name }}</span>
                <RouterLink
                  :to="{ name: 'knowledgeBases', query: { id: kb.id } }"
                  class="text-xs text-primary hover:underline"
                >
                  查看
                </RouterLink>
              </div>
            </div>
            <p v-else class="text-sm text-muted-foreground">未关联知识库</p>
          </div>
          
          <!-- 消息统计 -->
          <div>
            <label class="text-xs font-medium text-muted-foreground mb-1 block">消息统计</label>
            <p class="text-sm text-foreground">
              共 {{ chatStore.messages.length }} 条消息
            </p>
          </div>
        </div>
      </div>

      <!-- 右侧调试抽屉（桌面端优先展示） -->
      <div
        v-if="showDebugDrawer"
        class="hidden lg:flex w-80 border-l border-border bg-background/60 text-xs flex-col"
      >
        <div class="px-3 py-2 border-b border-border flex items-center justify-between">
          <span class="font-medium text-foreground/80 text-[11px]">最近一轮调试概要</span>
          <RouterLink
            :to="{ name: 'traces' }"
            class="text-[11px] underline-offset-2 hover:underline text-muted-foreground"
          >
            查看完整轨迹
          </RouterLink>
        </div>
        <div class="flex-1 overflow-y-auto px-3 py-2 space-y-3 text-[11px] text-muted-foreground">
          <div>
            <div class="font-medium text-foreground/80 mb-1">模型与 Token</div>
            <p v-if="lastTokenUsage">
              模型：{{ lastModelId || '未知模型' }}<br>
              Tokens：{{ lastTokenUsage.totalTokens }}
              （提示 {{ lastTokenUsage.promptTokens }} / 回答 {{ lastTokenUsage.completionTokens }}）
            </p>
            <p v-else>暂无最近一轮统计信息，发送一条消息后将在此展示。</p>
          </div>
          <div>
            <div class="font-medium text-foreground/80 mb-1">Prompt 摘要</div>
            <p v-if="lastPrompt">
              {{ lastPrompt }}
            </p>
            <p v-else class="text-muted-foreground">暂未记录本轮 Prompt 摘要。</p>
          </div>
          <div>
            <div class="font-medium text-foreground/80 mb-1">推理过程</div>
            <div
              v-if="reasoningEvents.length > 0"
              class="space-y-1"
            >
              <div
                v-for="event in reasoningEvents"
                :key="event.id"
                class="flex items-start gap-2"
              >
                <div
                  class="mt-[3px] w-1.5 h-1.5 rounded-full"
                  :class="[
                    event.type === 'ERROR'
                      ? 'bg-destructive'
                      : event.type === 'TOOL_CALL_START' || event.type === 'TOOL_CALL_END'
                        ? 'bg-primary'
                        : 'bg-muted-foreground/60'
                  ]"
                />
                <div class="space-y-0.5">
                  <div class="flex items-center gap-1.5">
                    <span class="text-[11px] text-foreground/90 font-medium">
                      {{ event.title }}
                    </span>
                    <span class="text-[10px] uppercase tracking-wide text-muted-foreground/80">
                      {{ event.type }}
                    </span>
                    <span
                      v-if="event.toolName"
                      class="px-1.5 py-0.5 rounded-full bg-muted text-[10px] text-muted-foreground"
                    >
                      工具：{{ event.toolName }}
                    </span>
                  </div>
                  <p
                    v-if="event.description"
                    class="text-[10px] text-muted-foreground/90"
                  >
                    {{ event.description }}
                  </p>
                </div>
              </div>
            </div>
            <p
              v-else
              class="text-muted-foreground"
            >
              暂无本轮推理事件，发送一条消息后将在此展示 Agent 的思考与工具调用过程。
            </p>
          </div>
          <div class="text-[10px] text-muted-foreground/80">
            调试抽屉仅展示最近一轮对话的概要信息；如需查看完整工具调用与阶段详情，请前往“轨迹”页面。
          </div>
        </div>
      </div>
    </div>

    <!-- 错误提示 -->
    <div
      v-if="error"
      class="px-4 md:px-6 py-3 bg-destructive/10 text-destructive text-sm flex items-center justify-center gap-3"
    >
      <span>{{ error }}</span>
      <button
        class="underline-offset-2 hover:underline"
        type="button"
        @click="error = null"
      >
        关闭
      </button>
      <RouterLink
        :to="{ name: 'traces' }"
        class="underline-offset-2 hover:underline"
      >
        查看执行轨迹
      </RouterLink>
    </div>

    <!-- 流式进行中提示 -->
    <div v-if="isStreaming" class="flex justify-center items-center gap-2 py-2 text-xs text-muted-foreground">
      <span class="inline-flex h-2 w-2 rounded-full bg-primary animate-pulse" />
      <span>正在生成回答…</span>
      <button
        class="underline-offset-2 hover:underline hover:text-foreground transition-colors"
        @click="abort"
      >
        停止生成
      </button>
    </div>

    <!-- 输入框 -->
    <ChatInput :disabled="isStreaming" @send="handleSend" />
  </div>
</template>
