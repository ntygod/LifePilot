/**
 * ProjectSection 组件测试 —— Plan 1 Task 18 + 2026-04-24 嵌套展开修订。
 *
 * 沿用仓库既有的测试风格（见 {@code stores/project.spec.ts}）：通过 {@code vi.mock}
 * 替换 API 模块，用真实 {@code createPinia()} 管理状态；不引入 {@code @pinia/testing}
 * （项目未依赖）。路由通过 {@code createMemoryHistory} 构造最小可用实例，并用
 * {@code vi.spyOn} 断言跳转参数。
 *
 * @author zsg
 * @since 2026-04-24
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'

vi.mock('@/api/project', () => ({
  listProjects: vi.fn().mockResolvedValue([]),
  getProject: vi.fn(),
  createProject: vi.fn(),
  updateProject: vi.fn(),
  deleteProject: vi.fn(),
}))

vi.mock('@/api/client', () => ({
  chatApi: {
    listSessions: vi.fn().mockResolvedValue([]),
    getSessionMessages: vi.fn().mockResolvedValue([]),
  },
}))

import * as projectApi from '@/api/project'
import { chatApi } from '@/api/client'
import ProjectSection from './ProjectSection.vue'
import { useChatStore } from '@/stores/chat'

const projectApiMock = vi.mocked(projectApi, { deep: true })
const chatApiMock = vi.mocked(chatApi, { deep: true })

/** 构造最小可用路由器，注册 projectDetail 与 conversationDetail 两个命名路由 */
function buildRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      {
        path: '/projects/:id',
        name: 'projectDetail',
        component: { template: '<div />' },
      },
      {
        path: '/conversations/:sessionId',
        name: 'conversationDetail',
        component: { template: '<div />' },
      },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
    ],
  })
}

const FIXTURE_PROJECT = {
  id: 'p-1',
  name: '毕业论文',
  instructions: '',
  isolation: 'ISOLATED' as const,
  memorySpaceId: 'ms-1',
  createdAt: '2026-04-23T00:00:00Z',
  updatedAt: '2026-04-23T00:00:00Z',
}

const FIXTURE_PROJECT_2 = {
  id: 'p-2',
  name: 'JAVA 学习',
  instructions: '',
  isolation: 'ISOLATED' as const,
  memorySpaceId: 'ms-2',
  createdAt: '2026-04-23T00:00:00Z',
  updatedAt: '2026-04-23T00:00:00Z',
}

describe('ProjectSection', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    projectApiMock.listProjects.mockResolvedValue([])
    chatApiMock.listSessions.mockResolvedValue([])
  })

  it('挂载后调用 store.fetchProjects 拉取项目列表', async () => {
    projectApiMock.listProjects.mockResolvedValueOnce([FIXTURE_PROJECT])
    const wrapper = mount(ProjectSection, {
      global: { plugins: [buildRouter()] },
    })
    await flushPromises()

    expect(projectApiMock.listProjects).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('毕业论文')
  })

  it('展示"项目"分组标题与"新建项目"按钮', () => {
    const wrapper = mount(ProjectSection, {
      global: { plugins: [buildRouter()] },
    })
    expect(wrapper.text()).toContain('项目')
    expect(wrapper.find('[data-testid="create-project-btn"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('新建项目')
  })

  it('点击"新建项目"按钮触发 emit("create")', async () => {
    const wrapper = mount(ProjectSection, {
      global: { plugins: [buildRouter()] },
    })
    await wrapper.find('[data-testid="create-project-btn"]').trigger('click')

    expect(wrapper.emitted('create')).toBeTruthy()
    expect(wrapper.emitted('create')!.length).toBe(1)
  })

  it('点击项目条目跳转到 /projects/:id', async () => {
    const router = buildRouter()
    await router.push('/')
    await router.isReady()
    const pushSpy = vi.spyOn(router, 'push')

    projectApiMock.listProjects.mockResolvedValue([FIXTURE_PROJECT])

    const wrapper = mount(ProjectSection, {
      global: { plugins: [router] },
    })
    await flushPromises()

    const btn = wrapper.find('[data-testid="project-open-btn"]')
    expect(btn.exists()).toBe(true)
    await btn.trigger('click')

    expect(pushSpy).toHaveBeenCalledWith('/projects/p-1')
  })

  it('默认折叠项目，点击展开按钮后拉取会话列表并嵌套展示', async () => {
    const router = buildRouter()
    await router.push('/')
    await router.isReady()

    projectApiMock.listProjects.mockResolvedValue([FIXTURE_PROJECT])
    chatApiMock.listSessions.mockResolvedValueOnce([
      {
        id: 's-1',
        title: '论文结构讨论',
        createdAt: '2026-04-24T01:00:00Z',
        updatedAt: '2026-04-24T01:00:00Z',
        projectId: 'p-1',
      },
    ])

    const wrapper = mount(ProjectSection, {
      global: { plugins: [router] },
    })
    await flushPromises()

    // 默认折叠，不展示嵌套会话容器
    expect(wrapper.find('[data-testid="project-session-nested"]').exists()).toBe(false)

    // 点击展开按钮
    const toggleBtn = wrapper.find('[data-testid="project-toggle-btn"]')
    expect(toggleBtn.exists()).toBe(true)
    await toggleBtn.trigger('click')
    await flushPromises()

    expect(chatApiMock.listSessions).toHaveBeenCalledWith('p-1')
    expect(wrapper.find('[data-testid="project-session-nested"]').exists()).toBe(true)
    const items = wrapper.findAll('[data-testid="project-session-item"]')
    expect(items.length).toBe(1)
    expect(items[0].text()).toContain('论文结构讨论')
  })

  it('再次点击展开按钮则折叠，隐藏嵌套会话', async () => {
    const router = buildRouter()
    await router.push('/')
    await router.isReady()

    projectApiMock.listProjects.mockResolvedValue([FIXTURE_PROJECT])

    const wrapper = mount(ProjectSection, {
      global: { plugins: [router] },
    })
    await flushPromises()

    const toggleBtn = wrapper.find('[data-testid="project-toggle-btn"]')
    await toggleBtn.trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="project-session-nested"]').exists()).toBe(true)

    await toggleBtn.trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="project-session-nested"]').exists()).toBe(false)
  })

  it('进入 /projects/:id 路由时自动展开对应项目并拉取会话', async () => {
    const router = buildRouter()
    await router.push('/projects/p-1')
    await router.isReady()

    projectApiMock.listProjects.mockResolvedValue([FIXTURE_PROJECT, FIXTURE_PROJECT_2])

    chatApiMock.listSessions.mockResolvedValueOnce([
      {
        id: 's-1',
        title: '论文结构讨论',
        createdAt: '2026-04-24T01:00:00Z',
        updatedAt: '2026-04-24T01:00:00Z',
        projectId: 'p-1',
      },
    ])

    const wrapper = mount(ProjectSection, {
      global: { plugins: [router] },
    })
    await flushPromises()

    // 当前路由项目 p-1 应自动展开
    expect(chatApiMock.listSessions).toHaveBeenCalledWith('p-1')
    expect(wrapper.find('[data-testid="project-session-nested"]').exists()).toBe(true)
    const items = wrapper.findAll('[data-testid="project-session-item"]')
    expect(items.length).toBe(1)
  })

  it('chatStore.sessions 中的项目会话（如刚发完消息新增的）会合并进嵌套列表', async () => {
    const router = buildRouter()
    await router.push('/projects/p-1')
    await router.isReady()

    projectApiMock.listProjects.mockResolvedValue([FIXTURE_PROJECT])

    // API 返回为空，模拟"会话刚通过 chatStore.startNewSession 在本地创建"
    chatApiMock.listSessions.mockResolvedValueOnce([])

    const chatStore = useChatStore()
    chatStore.sessions = [
      {
        id: 's-fresh',
        title: '新对话',
        createdAt: '2026-04-24T10:00:00Z',
        updatedAt: '2026-04-24T10:00:00Z',
        projectId: 'p-1',
      },
    ]

    const wrapper = mount(ProjectSection, {
      global: { plugins: [router] },
    })
    await flushPromises()

    const items = wrapper.findAll('[data-testid="project-session-item"]')
    expect(items.length).toBe(1)
    expect(items[0].text()).toContain('新对话')
  })

  it('点击嵌套会话跳转到 conversationDetail 路由', async () => {
    const router = buildRouter()
    await router.push('/projects/p-1')
    await router.isReady()
    const pushSpy = vi.spyOn(router, 'push')

    projectApiMock.listProjects.mockResolvedValue([FIXTURE_PROJECT])
    chatApiMock.listSessions.mockResolvedValueOnce([
      {
        id: 's-1',
        title: '论文结构讨论',
        createdAt: '2026-04-24T01:00:00Z',
        updatedAt: '2026-04-24T01:00:00Z',
        projectId: 'p-1',
      },
    ])

    const wrapper = mount(ProjectSection, {
      global: { plugins: [router] },
    })
    await flushPromises()

    const item = wrapper.find('[data-testid="project-session-item"]')
    expect(item.exists()).toBe(true)
    await item.trigger('click')

    expect(pushSpy).toHaveBeenCalledWith({
      name: 'conversationDetail',
      params: { sessionId: 's-1' },
    })
  })

  it('展开空项目显示"暂无对话"空态', async () => {
    const router = buildRouter()
    await router.push('/')
    await router.isReady()

    projectApiMock.listProjects.mockResolvedValue([FIXTURE_PROJECT])
    chatApiMock.listSessions.mockResolvedValueOnce([])

    const wrapper = mount(ProjectSection, {
      global: { plugins: [router] },
    })
    await flushPromises()

    await wrapper.find('[data-testid="project-toggle-btn"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="project-session-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="project-session-empty"]').text()).toContain('暂无对话')
  })
})
