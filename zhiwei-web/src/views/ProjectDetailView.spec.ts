/**
 * ProjectDetailView 组件测试 —— Plan 1 Task 20（polish 2026-04-24 修订）。
 *
 * 沿用仓库既有测试风格（见 {@code ProjectSettingsPanel.spec.ts}）：
 * - {@code vi.mock} 替换 {@code @/api/project} 与 {@code @/api/client}
 * - 用真实 {@code createPinia()} 管理 chatStore / projectStore 状态
 * - 用 {@code createMemoryHistory} 构造最小路由，{@code vi.spyOn} 断言跳转
 *
 * 本 polish 把「大按钮跳转」改成「就地输入 → 懒创建 → 跳转」。
 * {@code ChatInput} 直接挂载过重（带附件/语音等副作用），用 global stub 替换为
 * 仅负责 emit 的桩组件，测试聚焦父组件流程本身。
 *
 * @author zsg
 * @since 2026-04-24
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { defineComponent } from 'vue'

vi.mock('@/api/project', () => ({
  listProjects: vi.fn(),
  getProject: vi.fn(),
  createProject: vi.fn(),
  updateProject: vi.fn(),
  deleteProject: vi.fn(),
}))

vi.mock('@/api/client', () => ({
  chatApi: {
    listSessions: vi.fn(),
    createSession: vi.fn(),
  },
  // 其余 API 在本测试里不会被调用，但部分内部 store 初始化会引用它们
  knowledgeBaseApi: {
    list: vi.fn().mockResolvedValue([]),
  },
}))

import * as projectApi from '@/api/project'
import { chatApi } from '@/api/client'
import ProjectDetailView from './ProjectDetailView.vue'
import { useProjectStore } from '@/stores/project'
import { useChatStore } from '@/stores/chat'

const projectApiMock = vi.mocked(projectApi, { deep: true })
const chatApiMock = vi.mocked(chatApi, { deep: true })

const FIXTURE_PROJECT = {
  id: 'p-1',
  name: '毕业论文-MT 评估',
  instructions: '',
  isolation: 'ISOLATED' as const,
  memorySpaceId: 'ms-1',
  createdAt: '2026-04-23T00:00:00Z',
  updatedAt: '2026-04-23T00:00:00Z',
}

/**
 * 轻量 ChatInput 桩 —— 暴露一个按钮触发 send 事件，便于测试父组件流程；
 * 真正 ChatInput 的附件/语音等行为已在其自己的 spec 覆盖。
 */
const ChatInputStub = defineComponent({
  name: 'ChatInput',
  emits: ['send'],
  props: {
    disabled: Boolean,
    placeholder: String,
  },
  template: `
    <div data-testid="chat-input-stub">
      <button
        type="button"
        data-testid="chat-input-stub-send"
        :disabled="disabled"
        @click="$emit('send', { content: '你好项目', attachmentIds: undefined, attachments: undefined })"
      >
        发送
      </button>
    </div>
  `,
})

/** 资料 / 设置抽屉在本测试里不是关注点，用空 stub 避免真实组件初始化 */
const NoopStub = defineComponent({
  name: 'NoopStub',
  template: '<div />',
})

function buildRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      {
        path: '/conversations/new',
        name: 'newConversation',
        component: { template: '<div />' },
      },
      {
        path: '/conversations/:sessionId',
        name: 'conversationDetail',
        component: { template: '<div />' },
      },
      {
        path: '/projects/:id',
        name: 'projectDetail',
        component: { template: '<div />' },
      },
    ],
  })
}

async function mountAt(projectId: string, router = buildRouter()) {
  await router.push(`/projects/${projectId}`)
  await router.isReady()
  const wrapper = mount(ProjectDetailView, {
    global: {
      plugins: [router],
      stubs: {
        ChatInput: ChatInputStub,
        ProjectResourcePanel: NoopStub,
        ProjectSettingsPanel: NoopStub,
      },
    },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('ProjectDetailView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    projectApiMock.listProjects.mockResolvedValue([])
    chatApiMock.listSessions.mockResolvedValue([])
  })

  it('顶栏展示项目名', async () => {
    const store = useProjectStore()
    store.projects = [FIXTURE_PROJECT]

    const { wrapper } = await mountAt('p-1')

    const header = wrapper.find('[data-testid="project-detail-header"]')
    expect(header.exists()).toBe(true)
    expect(header.text()).toContain('毕业论文-MT 评估')
  })

  it('主区就地展示项目名 + ChatInput，且移除了旧的「开始新对话」按钮', async () => {
    const store = useProjectStore()
    store.projects = [FIXTURE_PROJECT]

    const { wrapper } = await mountAt('p-1')

    // 新的就地输入区
    const composer = wrapper.find('[data-testid="project-inline-composer"]')
    expect(composer.exists()).toBe(true)
    expect(composer.text()).toContain('毕业论文-MT 评估')
    // ChatInput 桩内置了一个 send 按钮，以此判断 ChatInput 已被挂载
    expect(wrapper.find('[data-testid="chat-input-stub-send"]').exists()).toBe(true)

    // 旧的「开始新对话」大按钮不再存在
    expect(wrapper.find('[data-testid="new-conversation-btn"]').exists()).toBe(false)
  })

  it('点击项目资料按钮不抛错', async () => {
    const store = useProjectStore()
    store.projects = [FIXTURE_PROJECT]

    const { wrapper } = await mountAt('p-1')

    const btn = wrapper.find('[data-testid="open-resource-btn"]')
    expect(btn.exists()).toBe(true)
    await btn.trigger('click')
    expect(btn.attributes('title')).toBe('项目资料')
  })

  it('点击项目设置按钮不抛错', async () => {
    const store = useProjectStore()
    store.projects = [FIXTURE_PROJECT]

    const { wrapper } = await mountAt('p-1')

    const btn = wrapper.find('[data-testid="open-settings-btn"]')
    expect(btn.exists()).toBe(true)
    await btn.trigger('click')
    expect(btn.attributes('title')).toBe('项目设置')
  })

  it('路由直达且 store 为空时先拉取项目列表', async () => {
    projectApiMock.listProjects.mockResolvedValueOnce([FIXTURE_PROJECT])

    const { wrapper } = await mountAt('p-1')

    expect(projectApiMock.listProjects).toHaveBeenCalledTimes(1)
    await flushPromises()
    expect(wrapper.text()).toContain('毕业论文-MT 评估')
  })

  it('按 projectId 拉取本项目对话列表并渲染', async () => {
    const store = useProjectStore()
    store.projects = [FIXTURE_PROJECT]
    chatApiMock.listSessions.mockResolvedValueOnce([
      {
        id: 's-1',
        title: '记忆视野范围',
        createdAt: '2026-04-24T01:00:00Z',
        updatedAt: '2026-04-24T01:00:00Z',
      },
      {
        id: 's-2',
        title: '',
        createdAt: '2026-04-10T01:00:00Z',
        updatedAt: '2026-04-10T01:00:00Z',
      },
    ])

    const { wrapper } = await mountAt('p-1')

    expect(chatApiMock.listSessions).toHaveBeenCalledWith('p-1')

    const items = wrapper.findAll('[data-testid="project-session-item"]')
    expect(items.length).toBe(2)
    expect(items[0].text()).toContain('记忆视野范围')
    // 无标题时兜底显示「新对话」
    expect(items[1].text()).toContain('新对话')
  })

  it('对话列表为空时展示空态引导文案', async () => {
    const store = useProjectStore()
    store.projects = [FIXTURE_PROJECT]
    chatApiMock.listSessions.mockResolvedValueOnce([])

    const { wrapper } = await mountAt('p-1')

    const empty = wrapper.find('[data-testid="project-session-empty"]')
    expect(empty.exists()).toBe(true)
    expect(empty.text()).toContain('暂无对话')
  })

  it('ChatInput 提交 → 创建项目会话 → 写入 pendingFirstSend → 跳转对话详情', async () => {
    const store = useProjectStore()
    store.projects = [FIXTURE_PROJECT]

    chatApiMock.createSession.mockResolvedValueOnce({
      id: 's-new',
      title: '新对话',
      createdAt: '2026-04-24T10:00:00Z',
      updatedAt: '2026-04-24T10:00:00Z',
    })

    const router = buildRouter()
    const { wrapper } = await mountAt('p-1', router)
    const pushSpy = vi.spyOn(router, 'push')

    const chatStore = useChatStore()

    await wrapper.find('[data-testid="chat-input-stub-send"]').trigger('click')
    await flushPromises()

    // 创建 session 必须带 projectId
    expect(chatApiMock.createSession).toHaveBeenCalledWith(undefined, 'p-1')

    // 首轮消息进入 pendingFirstSend，供 ChatView 消费
    expect(chatStore.pendingFirstSend).toEqual({
      content: '你好项目',
      attachmentIds: undefined,
      attachments: undefined,
      singleTurnOverride: undefined,
    })

    // 跳转到新会话
    expect(pushSpy).toHaveBeenCalledWith({
      name: 'conversationDetail',
      params: { sessionId: 's-new' },
    })
  })

  it('创建会话失败时展示错误提示且不跳转', async () => {
    const store = useProjectStore()
    store.projects = [FIXTURE_PROJECT]
    chatApiMock.createSession.mockRejectedValueOnce(new Error('后端爆炸'))

    const router = buildRouter()
    const { wrapper } = await mountAt('p-1', router)
    const pushSpy = vi.spyOn(router, 'push')

    await wrapper.find('[data-testid="chat-input-stub-send"]').trigger('click')
    await flushPromises()

    expect(pushSpy).not.toHaveBeenCalled()
    const err = wrapper.find('[data-testid="project-detail-error"]')
    expect(err.exists()).toBe(true)
    expect(err.text()).toContain('后端爆炸')
  })
})
