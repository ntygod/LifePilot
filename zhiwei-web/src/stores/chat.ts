import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import type { ChatAttachment, ChatSession, Message, SessionConfig } from '@/types'
import { chatApi } from '@/api/client'

/**
 * 首轮待发送消息 —— 承载从非 ChatView 页面（项目详情页、首屏等）
 * 跨路由到 ChatView 的用户输入。包含附件以支持项目详情页就地输入框。
 */
export interface PendingFirstSend {
  content: string
  attachmentIds?: string[]
  attachments?: ChatAttachment[]
  sessionConfig?: SessionConfig
  restoreSessionConfig?: SessionConfig
}

export const useChatStore = defineStore('chat', () => {
  // 会话列表
  const sessions = ref<ChatSession[]>([])
  // 当前激活会话 ID
  const activeSessionId = ref<string | null>(null)
  // 当前会话的消息列表
  const messages = ref<Message[]>([])
  // 是否处于流式接收中
  const isStreaming = ref(false)
  // 当前流式增量内容
  const streamingContent = ref('')
  // 首屏输入的待发送消息（纯文本形态，历史路径保留兼容）
  const pendingFirstMessage = ref<string | null>(null)
  // 首轮完整待发送结构（项目详情页等页面跨路由使用，支持附件 / sessionConfig）
  const pendingFirstSend = ref<PendingFirstSend | null>(null)

  /** 加载会话列表。 */
  async function loadSessions() {
    sessions.value = await chatApi.listSessions()
    // 当前未选中会话时，默认选中最近一个。
    if (!activeSessionId.value && sessions.value.length > 0) {
      activeSessionId.value = sessions.value[0]?.id ?? null
    }
  }

  /** 加载指定会话的历史消息。 */
  async function loadMessages(sessionId: string) {
    messages.value = await chatApi.getSessionMessages(sessionId)
  }

  /** 向当前消息列表追加一条消息。 */
  function addMessage(message: Message) {
    messages.value.push(message)
  }

  /** 按消息 ID 插入或覆盖消息。 */
  function upsertMessage(message: Message) {
    const index = messages.value.findIndex(item => item.id === message.id)
    if (index === -1) {
      messages.value.push(message)
      return
    }
    messages.value[index] = { ...messages.value[index], ...message }
  }

  /** 按消息 ID 局部更新消息。 */
  function updateMessage(id: string, patch: Partial<Message>) {
    const index = messages.value.findIndex(m => m.id === id)
    if (index === -1) return
    messages.value[index] = { ...messages.value[index], ...patch }
  }

  /** 用后端返回的持久化 entryId 替换前端临时 ID。 */
  function replaceMessageId(oldId: string, newId: string) {
    const index = messages.value.findIndex(m => m.id === oldId)
    if (index === -1) return
    messages.value[index] = { ...messages.value[index], id: newId }
  }

  /** 创建会话。可选传入 projectId 将会话归入指定项目。 */
  async function createSession(title?: string, projectId?: string | null): Promise<ChatSession> {
    const session = await chatApi.createSession(title, projectId)
    sessions.value.unshift(session)
    return session
  }

  /** 开始新对话，立即创建并激活一个新会话。可选传入 projectId 继承项目上下文。 */
  async function startNewSession(title?: string, projectId?: string | null): Promise<ChatSession> {
    const session = await createSession(title, projectId)
    // 新建会话没有历史消息，跳过 watch 中的 loadMessages 避免竞态覆盖
    skipNextLoad = true
    activeSessionId.value = session.id
    return session
  }

  /** 更新会话基础信息。 */
  async function updateSession(sessionId: string, updates: { title?: string; pinned?: boolean; archived?: boolean }) {
    const updated = await chatApi.updateSession(sessionId, updates)
    const index = sessions.value.findIndex(s => s.id === sessionId)
    if (index !== -1) {
      sessions.value[index] = updated
    }
    return updated
  }

  /** 删除会话。 */
  async function deleteSession(sessionId: string) {
    await chatApi.deleteSession(sessionId)
    sessions.value = sessions.value.filter(s => s.id !== sessionId)
    if (activeSessionId.value === sessionId) {
      activeSessionId.value = sessions.value[0]?.id ?? null
      messages.value = []
      streamingContent.value = ''
    }
  }

  /** 清空当前会话消息，保留会话本身。 */
  async function clearCurrentSessionMessages() {
    if (!activeSessionId.value) return
    await chatApi.clearSessionMessages(activeSessionId.value)
    messages.value = []
    streamingContent.value = ''
  }

  /** 重置流式状态。 */
  function resetStreaming() {
    isStreaming.value = false
    streamingContent.value = ''
  }

  /** 更新会话标题（由 SSE title-generated 事件触发）。 */
  function updateSessionTitle(sessionId: string, title: string) {
    const session = sessions.value.find(s => s.id === sessionId)
    if (session) {
      session.title = title
    } else {
      // session 尚未在本地列表中（可能是懒创建还未同步），异步刷新列表
      loadSessions().catch(() => {})
    }
  }

  // 新建会话时跳过 loadMessages 的竞态守卫
  let skipNextLoad = false

  // 切换会话时清空本地消息，并重新加载对应历史。
  watch(activeSessionId, async (newId) => {
    messages.value = []
    streamingContent.value = ''
    if (newId && !skipNextLoad) {
      await loadMessages(newId)
    }
    skipNextLoad = false
  })

  return {
    sessions,
    activeSessionId,
    messages,
    isStreaming,
    streamingContent,
    pendingFirstMessage,
    pendingFirstSend,
    loadSessions,
    loadMessages,
    addMessage,
    upsertMessage,
    updateMessage,
    replaceMessageId,
    createSession,
    startNewSession,
    updateSession,
    deleteSession,
    clearCurrentSessionMessages,
    resetStreaming,
    updateSessionTitle
  }
})
