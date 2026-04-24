/**
 * ScheduledTasksView 组件测试 —— Plan 2+3 Task A5 + UI 增强。
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
  listScheduledTaskLogs: vi.fn(),
  listTodayScheduledTaskLogs: vi.fn(),
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

/** 构造一个任务 DTO——填充所有必需字段，让 spec 里写得少些。 */
function buildTask(overrides: Partial<taskApi.ScheduledTaskDto> = {}): taskApi.ScheduledTaskDto {
  return {
    id: 't1',
    name: '任务',
    schedule: '0 0 * * * *',
    instruction: '',
    status: 'active',
    skillIds: null,
    projectId: null,
    createdAt: '',
    updatedAt: '',
    nextExecutionAt: null,
    ...overrides,
  }
}

describe('ScheduledTasksView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    // 统一给 todayLogs 一个空默认值，避免每个用例重复 mock；单测需要具体 logs 时按需 override
    taskApiMock.listTodayScheduledTaskLogs.mockResolvedValue([])
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
      buildTask({
        id: 't1',
        name: '周报提醒',
        schedule: '0 0 21 ? * SUN',
        instruction: '写周报',
        status: 'active',
        projectId: 'p-1',
      }),
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
      buildTask({ id: 't2', name: '天气', schedule: '0 0 8 * * *' }),
    ])
    projectApiMock.listProjects.mockResolvedValue([])
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('主')
  })

  it('点击暂停按钮调 store.pauseTask', async () => {
    taskApiMock.listScheduledTasks.mockResolvedValue([
      buildTask({ id: 't1', name: '任务', schedule: '0 0 * * * *', status: 'active' }),
    ])
    projectApiMock.listProjects.mockResolvedValue([])
    taskApiMock.updateScheduledTask.mockResolvedValue(
      buildTask({ id: 't1', name: '任务', schedule: '0 0 * * * *', status: 'paused' }),
    )
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-testid="pause-t1"]').trigger('click')
    await flushPromises()
    expect(taskApiMock.updateScheduledTask).toHaveBeenCalledWith('t1', { status: 'paused' })
  })

  it('点击删除按钮_确认弹窗取消_不调 store', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    taskApiMock.listScheduledTasks.mockResolvedValue([buildTask({ id: 't1' })])
    projectApiMock.listProjects.mockResolvedValue([])
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-testid="delete-t1"]').trigger('click')
    expect(taskApiMock.deleteScheduledTask).not.toHaveBeenCalled()
  })

  it('点击删除按钮_确认弹窗确定_调 store.deleteTask', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    taskApiMock.listScheduledTasks.mockResolvedValue([buildTask({ id: 't1' })])
    projectApiMock.listProjects.mockResolvedValue([])
    taskApiMock.deleteScheduledTask.mockResolvedValue(undefined)
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-testid="delete-t1"]').trigger('click')
    await flushPromises()
    expect(taskApiMock.deleteScheduledTask).toHaveBeenCalledWith('t1')
  })

  it('active 任务 + nextExecutionAt 展示"下次"文本', async () => {
    // 明天 08:00 当地时间
    const tomorrow = new Date()
    tomorrow.setDate(tomorrow.getDate() + 1)
    tomorrow.setHours(8, 0, 0, 0)
    taskApiMock.listScheduledTasks.mockResolvedValue([
      buildTask({
        id: 't1',
        name: '天气',
        status: 'active',
        nextExecutionAt: tomorrow.toISOString(),
      }),
    ])
    projectApiMock.listProjects.mockResolvedValue([])
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('[data-testid="next-execution-t1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="next-execution-t1"]').text()).toContain('明天')
    expect(wrapper.find('[data-testid="next-execution-t1"]').text()).toContain('08:00')
  })

  it('paused 任务不展示"下次"文本', async () => {
    taskApiMock.listScheduledTasks.mockResolvedValue([
      buildTask({
        id: 't1',
        status: 'paused',
        nextExecutionAt: new Date(Date.now() + 86_400_000).toISOString(),
      }),
    ])
    projectApiMock.listProjects.mockResolvedValue([])
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('[data-testid="next-execution-t1"]').exists()).toBe(false)
  })

  it('点击编辑按钮打开编辑弹窗', async () => {
    taskApiMock.listScheduledTasks.mockResolvedValue([
      buildTask({ id: 't1', name: '任务', schedule: '0 0 * * * *' }),
    ])
    projectApiMock.listProjects.mockResolvedValue([])
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-testid="edit-t1"]').trigger('click')
    await flushPromises()
    // Dialog 挂载到 body，通过 document 查询
    expect(document.querySelector('[data-testid="scheduled-task-edit-dialog"]')).not.toBeNull()
    // Unmount 后清理 Dialog
    wrapper.unmount()
  })

  it('点击卡片展开_触发 listScheduledTaskLogs + 展示历史', async () => {
    taskApiMock.listScheduledTasks.mockResolvedValue([
      buildTask({ id: 't1', name: '任务', instruction: '执行指令' }),
    ])
    projectApiMock.listProjects.mockResolvedValue([])
    taskApiMock.listScheduledTaskLogs.mockResolvedValue([
      {
        id: 'log-1',
        taskId: 't1',
        executedAt: new Date().toISOString(),
        status: 'success',
        durationMs: 1234,
        tokensUsed: 100,
        summary: '完成',
      },
    ])
    const wrapper = mountView()
    await flushPromises()
    // 点卡片主体（toggle 区域）
    await wrapper.find('[data-testid="task-card-toggle-t1"]').trigger('click')
    await flushPromises()
    expect(taskApiMock.listScheduledTaskLogs).toHaveBeenCalledWith('t1', 5)
    // 展开区域应可见
    expect(wrapper.find('[data-testid="task-expanded-t1"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('执行指令')
    expect(wrapper.text()).toContain('1234 ms')
    expect(wrapper.text()).toContain('完成')
  })

  it('展开时日志 API 失败_展示错误提示', async () => {
    taskApiMock.listScheduledTasks.mockResolvedValue([buildTask({ id: 't1' })])
    projectApiMock.listProjects.mockResolvedValue([])
    taskApiMock.listScheduledTaskLogs.mockRejectedValue({ message: '服务端挂了' })
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-testid="task-card-toggle-t1"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="logs-error-t1"]').text()).toContain('服务端挂了')
  })

  it('点击项目 tag_不触发卡片展开_跳转项目详情', async () => {
    taskApiMock.listScheduledTasks.mockResolvedValue([
      buildTask({ id: 't1', projectId: 'p-1' }),
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
    const router = buildRouter()
    const pushSpy = vi.spyOn(router, 'push')
    const wrapper = mountView(router)
    await flushPromises()
    await wrapper.find('[data-testid="project-tag-t1"]').trigger('click')
    await flushPromises()
    expect(pushSpy).toHaveBeenCalledWith({ name: 'projectDetail', params: { id: 'p-1' } })
    // 卡片不该展开
    expect(wrapper.find('[data-testid="task-expanded-t1"]').exists()).toBe(false)
    // 日志 API 不应被调用
    expect(taskApiMock.listScheduledTaskLogs).not.toHaveBeenCalled()
  })

  // ────────── 2026-04-24 视觉重做：新增 UI 元素测试 ──────────

  it('Hero KPI 行按真实任务数派生「总任务」', async () => {
    taskApiMock.listScheduledTasks.mockResolvedValue([
      buildTask({ id: 't1', status: 'active' }),
      buildTask({ id: 't2', status: 'active' }),
      buildTask({ id: 't3', status: 'paused' }),
    ])
    projectApiMock.listProjects.mockResolvedValue([])
    const wrapper = mountView()
    await flushPromises()
    const kpiRow = wrapper.find('[data-testid="kpi-row"]')
    expect(kpiRow.exists()).toBe(true)
    expect(kpiRow.text()).toContain('总任务')
    // 总任务值 3；caption 显示 2 运行 · 1 暂停 · 0 草稿
    expect(kpiRow.text()).toContain('3')
    expect(kpiRow.text()).toContain('2 运行')
    expect(kpiRow.text()).toContain('1 暂停')
  })

  it('过滤 pill 点击_按 status 过滤卡片', async () => {
    taskApiMock.listScheduledTasks.mockResolvedValue([
      buildTask({ id: 't1', name: '活动任务', status: 'active' }),
      buildTask({ id: 't2', name: '暂停任务', status: 'paused' }),
    ])
    projectApiMock.listProjects.mockResolvedValue([])
    const wrapper = mountView()
    await flushPromises()
    // 默认「全部」显示两个卡片
    expect(wrapper.find('[data-testid="task-card-t1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="task-card-t2"]').exists()).toBe(true)
    // 点「已暂停」
    await wrapper.find('[data-testid="filter-paused"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="task-card-t1"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="task-card-t2"]').exists()).toBe(true)
  })

  it('搜索框_按关键字过滤任务名', async () => {
    taskApiMock.listScheduledTasks.mockResolvedValue([
      buildTask({ id: 't1', name: '每日天气播报' }),
      buildTask({ id: 't2', name: '周报提醒' }),
    ])
    projectApiMock.listProjects.mockResolvedValue([])
    const wrapper = mountView()
    await flushPromises()
    const input = wrapper.find('[data-testid="search-input"]')
    await input.setValue('周报')
    await flushPromises()
    expect(wrapper.find('[data-testid="task-card-t1"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="task-card-t2"]').exists()).toBe(true)
  })

  it('视图切换_时间表/执行历史显示占位', async () => {
    taskApiMock.listScheduledTasks.mockResolvedValue([buildTask({ id: 't1' })])
    projectApiMock.listProjects.mockResolvedValue([])
    const wrapper = mountView()
    await flushPromises()
    // 切到时间表
    await wrapper.find('[data-testid="view-tab-timetable"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="placeholder-timetable"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('即将推出')
    // 切到执行历史
    await wrapper.find('[data-testid="view-tab-history"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="placeholder-history"]').exists()).toBe(true)
  })

  it('顶部「新建任务」按钮_显示 placeholder toast', async () => {
    taskApiMock.listScheduledTasks.mockResolvedValue([])
    projectApiMock.listProjects.mockResolvedValue([])
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-testid="create-task"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('试试在对话中对微微')
  })

  it('详情面板_展开后点击关闭恢复折叠', async () => {
    taskApiMock.listScheduledTasks.mockResolvedValue([buildTask({ id: 't1' })])
    projectApiMock.listProjects.mockResolvedValue([])
    taskApiMock.listScheduledTaskLogs.mockResolvedValue([])
    const wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-testid="task-card-toggle-t1"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="task-expanded-t1"]').exists()).toBe(true)
    await wrapper.find('[data-testid="detail-close"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="task-expanded-t1"]').exists()).toBe(false)
  })
})
