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
  }

  /** 加载指定会话的历史消息 */
  async function loadMessages(sessionId: string) {
    messages.value = await chatApi.getSessionMessages(sessionId)
  }

  /** 添加消息到当前列表 */
  function addMessage(message: Message) {
    messages.value.push(message)
  }

  /** 删除会话 */
  async function deleteSession(sessionId: string) {
    await chatApi.deleteSession(sessionId)
    sessions.value = sessions.value.filter(s => s.id !== sessionId)
    if (activeSessionId.value === sessionId) {
      activeSessionId.value = sessions.value[0]?.id ?? null
    }
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
    deleteSession,
    resetStreaming
  }
})
