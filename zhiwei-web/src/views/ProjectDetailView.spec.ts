/**
 * ProjectDetailView 组件测试 —— Plan 1 Task 20。
 *
 * 沿用仓库既有测试风格（见 {@code ProjectSection.spec.ts} / {@code CreateProjectDialog.spec.ts}）：
 * 通过 {@code vi.mock} 替换 `@/api/project`，用真实 {@code createPinia()} 管理状态；路由
 * 通过 {@code createMemoryHistory} 构造最小实例，并用 {@code vi.spyOn} 断言跳转参数。
 *
 * @author zsg
 * @since 2026-04-23
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'

vi.mock('@/api/project', () => ({
  listProjects: vi.fn(),
  getProject: vi.fn(),
  createProject: vi.fn(),
  updateProject: vi.fn(),
  deleteProject: vi.fn(),
}))

import * as projectApi from '@/api/project'
import ProjectDetailView from './ProjectDetailView.vue'
import { useProjectStore } from '@/stores/project'

const projectApiMock = vi.mocked(projectApi, { deep: true })

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
 * 构造最小可用路由器，预置 /projects/:id、home、newConversation 三条路由。
 *
 * 使用桩组件即可，真实视图组件由当前测试直接 mount。
 */
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
        path: '/projects/:id',
        name: 'projectDetail',
        component: { template: '<div />' },
      },
    ],
  })
}

/** 挂载视图到 /projects/:id，返回 wrapper 与 router */
async function mountAt(projectId: string, router = buildRouter()) {
  await router.push(`/projects/${projectId}`)
  await router.isReady()
  const wrapper = mount(ProjectDetailView, {
    global: { plugins: [router] },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('ProjectDetailView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    projectApiMock.listProjects.mockResolvedValue([])
  })

  it('顶栏展示项目名', async () => {
    // 预置 store 数据，避免等待 fetch 完成带来的时序噪音
    const store = useProjectStore()
    store.projects = [FIXTURE_PROJECT]

    const { wrapper } = await mountAt('p-1')

    const header = wrapper.find('[data-testid="project-detail-header"]')
    expect(header.exists()).toBe(true)
    expect(header.text()).toContain('毕业论文-MT 评估')
  })

  it('点击项目资料按钮打开 showResource 状态（按钮可见且可点击）', async () => {
    const store = useProjectStore()
    store.projects = [FIXTURE_PROJECT]

    const { wrapper } = await mountAt('p-1')

    const btn = wrapper.find('[data-testid="open-resource-btn"]')
    expect(btn.exists()).toBe(true)
    await btn.trigger('click')
    // 本 task 仅占位，这里断言不抛错即可；真正的抽屉由 Task 21 实装
    expect(btn.attributes('title')).toBe('项目资料')
  })

  it('点击项目设置按钮打开 showSettings 状态（按钮可见且可点击）', async () => {
    const store = useProjectStore()
    store.projects = [FIXTURE_PROJECT]

    const { wrapper } = await mountAt('p-1')

    const btn = wrapper.find('[data-testid="open-settings-btn"]')
    expect(btn.exists()).toBe(true)
    await btn.trigger('click')
    expect(btn.attributes('title')).toBe('项目设置')
  })

  it('路由直达且 store 为空时先拉取项目列表', async () => {
    // 模拟用户直接粘贴 URL：store.projects 为空，onMounted 调用 fetchProjects
    projectApiMock.listProjects.mockResolvedValueOnce([FIXTURE_PROJECT])

    const { wrapper } = await mountAt('p-1')

    expect(projectApiMock.listProjects).toHaveBeenCalledTimes(1)
    // 等一轮刷新让 computed 追上
    await flushPromises()
    expect(wrapper.text()).toContain('毕业论文-MT 评估')
  })

  it('点击"开始新对话"按钮跳转到 newConversation 并带 projectId', async () => {
    const router = buildRouter()
    const store = useProjectStore()
    store.projects = [FIXTURE_PROJECT]

    const { wrapper } = await mountAt('p-1', router)
    const pushSpy = vi.spyOn(router, 'push')

    await wrapper.find('[data-testid="new-conversation-btn"]').trigger('click')

    expect(pushSpy).toHaveBeenCalledWith({
      name: 'newConversation',
      query: { projectId: 'p-1' },
    })
  })
})
