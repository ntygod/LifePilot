/**
 * ProjectSection 组件测试 —— Plan 1 Task 18。
 *
 * 沿用仓库既有的测试风格（见 {@code stores/project.spec.ts}）：通过 {@code vi.mock}
 * 替换 API 模块，用真实 {@code createPinia()} 管理状态；不引入 {@code @pinia/testing}
 * （项目未依赖）。路由通过 {@code createMemoryHistory} 构造最小可用实例，并用
 * {@code vi.spyOn} 断言跳转参数。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'

vi.mock('@/api/project', () => ({
  listProjects: vi.fn().mockResolvedValue([]),
  getProject: vi.fn(),
  createProject: vi.fn(),
  updateProject: vi.fn(),
  deleteProject: vi.fn(),
}))

import * as projectApi from '@/api/project'
import ProjectSection from './ProjectSection.vue'
import { useProjectStore } from '@/stores/project'

const projectApiMock = vi.mocked(projectApi, { deep: true })

/** 构造最小可用路由器（仅用于提供 useRouter 依赖） */
function buildRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
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

describe('ProjectSection', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    projectApiMock.listProjects.mockResolvedValue([])
  })

  it('挂载后调用 store.fetchProjects 拉取项目列表', async () => {
    projectApiMock.listProjects.mockResolvedValueOnce([FIXTURE_PROJECT])
    const wrapper = mount(ProjectSection, {
      global: { plugins: [buildRouter()] },
    })
    // 等一个微任务让 onMounted + fetchProjects 落地
    await new Promise(resolve => setTimeout(resolve, 0))
    await wrapper.vm.$nextTick()

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

    // 通过 store 直接注入数据，避免异步拉取带来的时序噪音
    const store = useProjectStore()
    store.projects = [FIXTURE_PROJECT]

    const wrapper = mount(ProjectSection, {
      global: { plugins: [router] },
    })

    const btn = wrapper.findAll('button').find(b => b.text().includes('毕业论文'))
    expect(btn).toBeTruthy()
    await btn!.trigger('click')

    expect(pushSpy).toHaveBeenCalledWith('/projects/p-1')
  })
})
