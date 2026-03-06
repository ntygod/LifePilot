import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import type { ChatSession, Message } from '@/types'
import { chatApi } from '@/api/client'

export const useChatStore = defineStore('chat', () => {
  // 会话列表
  const sessions = ref<ChatSession[]>([])
  // 当前活跃会话 ID
  const activeSessionId = ref<string | null>(null)
  // 当前会话的消息列表
  const messages = ref<Message[]>([])
  // 流式状态
  const isStreaming = ref(false)
  // 流式内容（增量拼接）
  const streamingContent = ref('')

  /** 加载会话列表 */
  async function loadSessions() {
    sessions.value = await chatApi.listSessions()
    // 如果当前没有激活会话，但后端已有列表，则默认选中最近一个
    if (!activeSessionId.value && sessions.value.length > 0) {
      activeSessionId.value = sessions.value[0]?.id ?? null
    }
  }

  /** 加载指定会话的历史消息 */
  async function loadMessages(sessionId: string) {
    messages.value = await chatApi.getSessionMessages(sessionId)
  }

  /** 添加消息到当前列表 */
  function addMessage(message: Message) {
    messages.value.push(message)
  }

  /** 按 ID 局部更新单条消息（用于状态 / 错误标记等） */
  function updateMessage(id: string, patch: Partial<Message>) {
    const index = messages.value.findIndex(m => m.id === id)
    if (index === -1) return
    messages.value[index] = { ...messages.value[index], ...patch }
  }

  /** 替换消息 ID（用于将前端临时 ID 替换为后端返回的持久化 ID） */
  function replaceMessageId(oldId: string, newId: string) {
    const index = messages.value.findIndex(m => m.id === oldId)
    if (index === -1) return
    messages.value[index] = { ...messages.value[index], id: newId }
  }

  /** 创建会话 */
  async function createSession(title?: string): Promise<ChatSession> {
    const session = await chatApi.createSession(title)
    sessions.value.unshift(session)
    return session
  }

  /** 开始新对话：立即创建会话并设置为活跃会话 */
  async function startNewSession(title?: string): Promise<ChatSession> {
    const session = await createSession(title)
    activeSessionId.value = session.id
    return session
  }

  /** 更新会话 */
  async function updateSession(sessionId: string, updates: { title?: string; pinned?: boolean; archived?: boolean }) {
    const updated = await chatApi.updateSession(sessionId, updates)
    const index = sessions.value.findIndex(s => s.id === sessionId)
    if (index !== -1) {
      sessions.value[index] = updated
    }
    return updated
  }

  /** 删除会话 */
  async function deleteSession(sessionId: string) {
    await chatApi.deleteSession(sessionId)
    sessions.value = sessions.value.filter(s => s.id !== sessionId)
    if (activeSessionId.value === sessionId) {
      activeSessionId.value = sessions.value[0]?.id ?? null
      // 重置当前消息列表
      messages.value = []
      streamingContent.value = ''
    }
  }

  /** 清空当前会话消息（保留会话本身） */
  async function clearCurrentSessionMessages() {
    if (!activeSessionId.value) return
    await chatApi.clearSessionMessages(activeSessionId.value)
    messages.value = []
    streamingContent.value = ''
  }

  /** 重置流式状态 */
  function resetStreaming() {
    isStreaming.value = false
    streamingContent.value = ''
  }

  // 会话切换时清空消息并重载
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
    loadSessions,
    loadMessages,
    addMessage,
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
