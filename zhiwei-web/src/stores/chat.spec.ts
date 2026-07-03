/**
 * chat store 单元测试 —— 目前聚焦 Plan 1 Task 23：
 * 新建会话时透传 projectId，使"项目详情页 → 开始新对话 → ChatView"的项目上下文链路闭合。
 *
 * @author zsg
 * @since 2026-04-23
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatSession } from '@/types'

vi.mock('@/api/client', () => ({
  chatApi: {
    createSession: vi.fn(),
    listSessions: vi.fn(),
    getSessionMessages: vi.fn(),
    updateSession: vi.fn(),
    deleteSession: vi.fn(),
    clearSessionMessages: vi.fn(),
  },
}))

import { chatApi } from '@/api/client'
import { useChatStore } from './chat'

const chatApiMock = vi.mocked(chatApi, { deep: true })

function makeSession(id: string, overrides: Partial<ChatSession> = {}): ChatSession {
  return {
    id,
    title: `会话-${id}`,
    pinned: false,
    archived: false,
    createdAt: '2026-04-23T00:00:00Z',
    updatedAt: '2026-04-23T00:00:00Z',
    ...overrides,
  } as ChatSession
}

describe('useChatStore 项目上下文继承', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    chatApiMock.getSessionMessages.mockResolvedValue([])
  })

  it('createSession 未传 projectId 时以 undefined 透传给 chatApi（由 chatApi 层归一化为 null）', async () => {
    const session = makeSession('s-1')
    chatApiMock.createSession.mockResolvedValueOnce(session)

    const store = useChatStore()
    await store.createSession('新会话')

    expect(chatApiMock.createSession).toHaveBeenCalledWith('新会话', undefined)
  })

  it('createSession 传入 projectId 时原样透传给后端', async () => {
    const session = makeSession('s-2')
    chatApiMock.createSession.mockResolvedValueOnce(session)

    const store = useChatStore()
    await store.createSession('项目会话', 'p-1')

    expect(chatApiMock.createSession).toHaveBeenCalledWith('项目会话', 'p-1')
  })

  it('startNewSession 传入 projectId 时透传并立即激活', async () => {
    const session = makeSession('s-3')
    chatApiMock.createSession.mockResolvedValueOnce(session)

    const store = useChatStore()
    const created = await store.startNewSession(undefined, 'p-2')

    expect(chatApiMock.createSession).toHaveBeenCalledWith(undefined, 'p-2')
    expect(created.id).toBe('s-3')
    expect(store.activeSessionId).toBe('s-3')
    expect(store.sessions[0]?.id).toBe('s-3')
  })

  it('loadSessions 默认不自动激活最近会话，避免新对话页被历史会话抢占', async () => {
    chatApiMock.listSessions.mockResolvedValueOnce([
      makeSession('s-latest'),
    ])

    const store = useChatStore()
    await store.loadSessions()

    expect(store.sessions[0]?.id).toBe('s-latest')
    expect(store.activeSessionId).toBeNull()
  })

  it('loadSessions 显式 activateFirst 时才激活最近会话', async () => {
    chatApiMock.listSessions.mockResolvedValueOnce([
      makeSession('s-latest'),
    ])

    const store = useChatStore()
    await store.loadSessions({ activateFirst: true })

    expect(store.activeSessionId).toBe('s-latest')
  })
})
