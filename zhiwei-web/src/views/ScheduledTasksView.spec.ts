/**
 * ScheduledTasksView 组件测试 —— Plan 2+3 Task A5。
 *
 * 沿用仓库既有测试风格（见 {@code ProjectDetailView.spec.ts}）：
 * 通过 {@code vi.mock} 替换 `@/api/scheduledTask` 与 `@/api/project`，用真实
 * {@code createPinia()} 管理状态；路由通过 {@code createMemoryHistory} 构造最小实例。
 *
 * @author zsg
 * @since 2026-04-23
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('@/api/scheduledTask', () => ({
  listScheduledTasks: vi.fn(),
  updateScheduledTask: vi.fn(),
  deleteScheduledTask: vi.fn(),
}))

vi.mock('@/api/project', () => ({
  listProjects: vi.fn(),
  getProject: vi.fn(),
  createProject: vi.fn(),
  updateProject: vi.fn(),
  deleteProject: vi.fn(),
}))

import * as taskApi from '@/api/scheduledTask'
import * as projectApi from '@/api/project'
import ScheduledTasksView from './ScheduledTasksView.vue'

const taskApiMock = vi.mocked(taskApi, { deep: true })
const projectApiMock = vi.mocked(projectApi, { deep: true })

function buildRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      {
        path: '/projects/:id',
        name: 'projectDetail',
        component: { template: '<div />' },
      },
    ],
  })
}

function mountView(router = buildRouter()) {
  return mount(ScheduledTasksView, {
    global: { plugins: [router] },
  })
}

describe('ScheduledTasksView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('页面挂载时 fetchAll 拉取任务', async () => {
    taskApiMock.listScheduledTasks.mockResolvedValue([])
    projectApiMock.listProjects.mockResolvedValue([])
    mountView()
    await flushPromises()
    expect(taskApiMock.listScheduledTasks).toHaveBeenCalled()
  })

  it('空列表时展示提示文案', async () => {
    taskApiMock.listScheduledTasks.mockResolvedValue([])
    projectApiMock.listProjects.mockResolvedValue([])
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('暂无定时任务')
  })

  it('展示任务 name / schedule / 项目 tag', async () => {
    taskApiMock.listScheduledTasks.mockResolvedValue([
      {
        id: 't1',
        name: '周报提醒',
        schedule: '0 0 21 ? * SUN',
        instruction: '写周报',
        status: 'active',
        skillIds: null,
        projectId: 'p-1',
        createdAt: '',
        updatedAt: '',
      },
    ])
    projectApiMock.listProjects.mockResolvedValue([
      {
        id: 'p-1',
        name: '毕业论文',
        instructions: '',
        isolation: 'ISOLATED',
        memorySpaceId: 'ms-1',
        createdAt: '',
        updatedAt: '',
      },
    ])
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('周报提醒')
    expect(wrapper.text()).toContain('0 0 21 ? * SUN')
    expect(wrapper.text()).toContain('毕业论文')
  })

  it('主账户任务（projectId=null）展示"主"tag', async () => {
    taskApiMock.listScheduledTasks.mockResolvedValue([
      {
        id: 't2',
        name: '天气',
        schedule: '0 0 8 * * *',
        instruction: '',
        status: 'active',
        skillIds: null,
        projectId: null,
        createdAt: '',
        updatedAt: '',
      },
    ])
    projectApiMock.listProjects.mockResolvedValue([])
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('主')
  })

  it('点击暂停按钮调 store.pauseTask', async () => {
    taskApiMock.listScheduledTasks.mockResolvedValue([
      {
        id: 't1',
        name: '任务',
        schedule: '0 0 * * * *',
        instruction: '',
        status: 'active',
        skillIds: null,
        projectId: null,
        createdAt: '',
        updatedAt: '',
      },
    ])
    projectApiMock.listProjects.mockResolvedValue([])
    taskApiMock.updateScheduledTask.mockResolvedValue({
      id: 't1',
      name: '任务',
      schedule: '0 0 * * * *',
      instruction: '',
      status: 'paused',
      skillIds: null,
      projectId: null,
      createdAt: '',
      updatedAt: '',
    })
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-testid="pause-t1"]').trigger('click')
    await flushPromises()
    expect(taskApiMock.updateScheduledTask).toHaveBeenCalledWith('t1', { status: 'paused' })
  })

  it('点击删除按钮_确认弹窗取消_不调 store', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    taskApiMock.listScheduledTasks.mockResolvedValue([
      {
        id: 't1',
        name: '任务',
        schedule: '0 0 * * * *',
        instruction: '',
        status: 'active',
        skillIds: null,
        projectId: null,
        createdAt: '',
        updatedAt: '',
      },
    ])
    projectApiMock.listProjects.mockResolvedValue([])
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-testid="delete-t1"]').trigger('click')
    expect(taskApiMock.deleteScheduledTask).not.toHaveBeenCalled()
  })

  it('点击删除按钮_确认弹窗确定_调 store.deleteTask', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    taskApiMock.listScheduledTasks.mockResolvedValue([
      {
        id: 't1',
        name: '任务',
        schedule: '0 0 * * * *',
        instruction: '',
        status: 'active',
        skillIds: null,
        projectId: null,
        createdAt: '',
        updatedAt: '',
      },
    ])
    projectApiMock.listProjects.mockResolvedValue([])
    taskApiMock.deleteScheduledTask.mockResolvedValue(undefined)
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-testid="delete-t1"]').trigger('click')
    await flushPromises()
    expect(taskApiMock.deleteScheduledTask).toHaveBeenCalledWith('t1')
  })
})
