<script setup lang="ts">
import { ref, nextTick, watch, onMounted, computed } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { useKnowledgeBaseStore } from '@/stores/knowledgeBase'
import { useSkillStore } from '@/stores/skill'
import { useChat } from '@/composables/useChat'
import MessageList from '@/components/chat/MessageList.vue'
import ChatInput from '@/components/chat/ChatInput.vue'

const route = useRoute()
const router = useRouter()
const chatStore = useChatStore()
const kbStore = useKnowledgeBaseStore()
const skillStore = useSkillStore()
const { sendMessage, isStreaming, error, abort, lastModelId, lastTokenUsage } = useChat()
const scrollContainer = ref<HTMLElement>()
const searchQuery = ref('')
// 右侧调试抽屉开关
const showDebugDrawer = ref(false)

// 顶部上下文指示条数据
const hasKnowledgeBases = computed(() => kbStore.list.length > 0)
const hasSkills = computed(() => skillStore.skills.length > 0)

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

async function handleSend(content: string) {
  await sendMessage(content)
}

function handleRetry(message: { content: string }) {
  // 基于指定消息内容重新发送一轮
  void sendMessage(message.content)
}

// 清空当前会话
async function handleClearSession() {
  await chatStore.clearCurrentSessionMessages()
}
</script>

<template>
  <div class="flex flex-col h-full">
    <!-- 顶部上下文指示条 + 最近一轮统计 -->
    <div class="px-4 py-1 border-b border-border bg-muted/40 text-[11px] text-muted-foreground space-y-1">
      <!-- 上下文指示条：当前模型 / 知识库 / Skill / 跳转 -->
      <div class="flex flex-wrap items-center gap-2">
        <span class="font-medium text-foreground/80">本会话上下文</span>
        <span v-if="lastModelId">· 当前模型：{{ lastModelId }}</span>
        <span v-else>· 当前模型：默认模型（从设置中继承）</span>
        <span>· 知识库：{{ hasKnowledgeBases ? `已配置 ${kbStore.list.length} 个` : '尚未配置' }}</span>
        <span>· Skill：{{ hasSkills ? `已注册 ${skillStore.skills.length} 个` : '尚未注册' }}</span>
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

        <!-- 右侧调试抽屉开关（仅在有最近一轮统计时高亮） -->
        <button
          type="button"
          class="ml-auto inline-flex items-center gap-1 px-2 py-0.5 rounded border border-border text-[11px]
                 hover:bg-background/60 transition-colors"
          :class="lastTokenUsage ? 'text-foreground' : 'text-muted-foreground'"
          @click="showDebugDrawer = !showDebugDrawer"
        >
          <span class="inline-block w-1.5 h-1.5 rounded-full"
                :class="showDebugDrawer ? 'bg-primary' : 'bg-muted-foreground/60'" />
          <span>{{ showDebugDrawer ? '收起调试视图' : '展开调试视图' }}</span>
        </button>
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

    <!-- 消息区域 + 右侧调试抽屉 -->
    <div class="flex flex-1 overflow-hidden">
      <!-- 消息区域 -->
      <div ref="scrollContainer" class="flex-1 overflow-y-auto">
        <!-- 空状态 -->
        <div v-if="chatStore.messages.length === 0 && !isStreaming" class="h-full flex items-center justify-center">
        <div class="text-center text-sm text-muted-foreground space-y-4">
          <div>
            <p class="text-base font-medium text-foreground mb-1">开始新对话</p>
            <p>向 AI 提问，或粘贴一段内容让它帮你总结、改写或分析。</p>
          </div>
          <div class="flex flex-col gap-2 max-w-md mx-auto">
            <button
              class="w-full rounded-md border border-dashed border-border px-3 py-2 text-left
                     hover:bg-accent hover:text-accent-foreground transition-colors"
            >
              帮我快速了解这个项目目前的能力和限制
            </button>
            <button
              class="w-full rounded-md border border-dashed border-border px-3 py-2 text-left
                     hover:bg-accent hover:text-accent-foreground transition-colors"
            >
              我想用自己的文档搭一个知识库助手，应该怎么开始？
            </button>
            <button
              class="w-full rounded-md border border-dashed border-border px-3 py-2 text-left
                     hover:bg-accent hover:text-accent-foreground transition-colors"
            >
              结合最近几条对话，帮我整理一份可以发给同事的总结
            </button>
          </div>
        </div>
        </div>

        <!-- 消息列表 + 顶部搜索 / 过滤条 -->
        <div v-else class="max-w-3xl mx-auto py-2 space-y-2">
          <div class="flex items-center gap-2 px-2">
            <input
              v-model="searchQuery"
              type="search"
              placeholder="在当前对话中搜索（按内容关键字）…"
              class="flex-1 h-7 rounded-md border border-input bg-background px-2 text-xs
                     placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring"
            />
            <span class="text-[11px] text-muted-foreground">
              共 {{ chatStore.messages.length }} 条
            </span>
            <button
              v-if="chatStore.activeSessionId && chatStore.messages.length > 0"
              type="button"
              class="ml-1 inline-flex items-center gap-1 px-2 py-0.5 rounded border border-border text-[11px]
                     text-muted-foreground hover:text-destructive hover:border-destructive/70 hover:bg-destructive/5
                     transition-colors"
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
          />
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
          <div class="text-[10px] text-muted-foreground/80">
            调试抽屉仅展示最近一轮对话的概要信息；如需查看完整工具调用与阶段详情，请前往“轨迹”页面。
          </div>
        </div>
      </div>
    </div>

    <!-- 错误提示 -->
    <div
      v-if="error"
      class="px-4 py-2 bg-destructive/10 text-destructive text-xs flex items-center justify-center gap-3"
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
    <div v-if="isStreaming" class="flex justify-center items-center gap-2 py-1 text-xs text-muted-foreground">
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
