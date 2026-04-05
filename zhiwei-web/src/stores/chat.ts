import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import type { ChatSession, Message } from '@/types'
import { chatApi } from '@/api/client'

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
  // 首屏输入的待发送消息（HomeView → ChatView 传递）
  const pendingFirstMessage = ref<string | null>(null)

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

  /** 创建会话。 */
  async function createSession(title?: string): Promise<ChatSession> {
    const session = await chatApi.createSession(title)
    sessions.value.unshift(session)
    return session
  }

  /** 开始新对话，立即创建并激活一个新会话。 */
  async function startNewSession(title?: string): Promise<ChatSession> {
    const session = await createSession(title)
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

  // 切换会话时清空本地消息，并重新加载对应历史。
  watch(activeSessionId, async (newId) => {
    messages.value = []
    streamingContent.value = ''
    if (newId) {
      await loadMessages(newId)
    }
  })

  return {
    sessions,
    activeSessionId,
    messages,
    isStreaming,
    streamingContent,
    pendingFirstMessage,
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
    resetStreaming
  }
})
