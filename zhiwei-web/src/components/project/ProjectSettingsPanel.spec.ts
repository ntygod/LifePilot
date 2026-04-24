/**
 * ProjectSettingsPanel 组件测试 —— Plan 1 Task 22（polish 2026-04-24 修订）。
 *
 * 沿用 {@code CreateProjectDialog.spec.ts} 的测试风格：Reka UI Sheet / AlertDialog 经由
 * Portal 渲染到 {@code document.body}，挂载后直接从 body 查 DOM 断言。
 *
 * 覆盖点：
 * - 打开时展示当前项目的 name / instructions；isolation 仅做只读展示
 * - 修改 name / instructions 后保存调用 {@link useProjectStore.updateProject}（不传 isolation）
 * - 保存成功后 {@code update:open=false}
 * - 点击「删除项目」弹出 ConfirmDialog，在弹窗取消时不调用 deleteProject
 * - 点击「删除项目」弹出 ConfirmDialog，确认后调用 deleteProject 并跳 home
 * - open 由 false → true 时表单回到 project prop 的最新值（避免残留）
 *
 * @author zsg
 * @since 2026-04-23
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import type { ProjectDto } from '@/api/project'

vi.mock('@/api/project', () => ({
  listProjects: vi.fn(),
  getProject: vi.fn(),
  createProject: vi.fn(),
  updateProject: vi.fn(),
  deleteProject: vi.fn(),
}))

import * as projectApi from '@/api/project'
import ProjectSettingsPanel from './ProjectSettingsPanel.vue'

const projectApiMock = vi.mocked(projectApi, { deep: true })

const BASE_PROJECT: ProjectDto = {
  id: 'p-1',
  name: '测试项目',
  instructions: '请始终使用中文回答',
  isolation: 'ISOLATED',
  memorySpaceId: 'space-p-1',
  createdAt: '2026-04-23T00:00:00Z',
  updatedAt: '2026-04-23T00:00:00Z',
}

function buildRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/projects/:id', name: 'projectDetail', component: { template: '<div />' } },
    ],
  })
}

async function mountPanel(
  open = true,
  project: ProjectDto = BASE_PROJECT,
  router = buildRouter(),
) {
  await router.push('/')
  await router.isReady()
  const wrapper = mount(ProjectSettingsPanel, {
    props: { open, project },
    global: { plugins: [router] },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('ProjectSettingsPanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  afterEach(() => {
    // Reka UI Sheet / AlertDialog Portal 会把内容挂到 document.body，不清理会污染下一个用例
    document.body.innerHTML = ''
  })

  it('打开时展示当前项目的 name / instructions，并只读展示 isolation', async () => {
    await mountPanel(true)

    const nameInput = document.body.querySelector<HTMLInputElement>('[data-testid="project-settings-name"]')!
    const instructionsArea = document.body.querySelector<HTMLTextAreaElement>('[data-testid="project-settings-instructions"]')!
    const isolationReadonly = document.body.querySelector<HTMLDivElement>('[data-testid="project-settings-isolation-readonly"]')!

    expect(nameInput.value).toBe('测试项目')
    expect(instructionsArea.value).toBe('请始终使用中文回答')
    expect(isolationReadonly).not.toBeNull()
    expect(isolationReadonly.textContent).toContain('隔离模式：隔离')
    expect(isolationReadonly.textContent).toContain('创建后不可修改')

    // 确认不再提供修改隔离的 radio
    expect(document.body.querySelector('[data-testid="project-settings-isolation-isolated"]')).toBeNull()
    expect(document.body.querySelector('[data-testid="project-settings-isolation-shared"]')).toBeNull()
  })

  it('SHARED 项目的只读展示显示「共享」', async () => {
    const shared: ProjectDto = { ...BASE_PROJECT, isolation: 'SHARED' }
    await mountPanel(true, shared)

    const isolationReadonly = document.body.querySelector<HTMLDivElement>('[data-testid="project-settings-isolation-readonly"]')!
    expect(isolationReadonly.textContent).toContain('隔离模式：共享')
  })

  it('修改字段后点击保存调用 store.updateProject（不传 isolation）', async () => {
    const updated: ProjectDto = { ...BASE_PROJECT, name: '改名后项目', instructions: '新指令' }
    projectApiMock.updateProject.mockResolvedValueOnce(updated)

    const { wrapper } = await mountPanel(true)

    const nameInput = document.body.querySelector<HTMLInputElement>('[data-testid="project-settings-name"]')!
    nameInput.value = '改名后项目'
    nameInput.dispatchEvent(new Event('input', { bubbles: true }))

    const instructionsArea = document.body.querySelector<HTMLTextAreaElement>('[data-testid="project-settings-instructions"]')!
    instructionsArea.value = '新指令'
    instructionsArea.dispatchEvent(new Event('input', { bubbles: true }))

    await flushPromises()

    document.body.querySelector<HTMLButtonElement>('[data-testid="project-settings-save"]')!.click()
    await flushPromises()

    // isolation 不再作为可编辑字段，保存时不应携带该字段；后端缺省时会保留原值
    expect(projectApiMock.updateProject).toHaveBeenCalledWith('p-1', {
      name: '改名后项目',
      instructions: '新指令',
    })

    const events = wrapper.emitted('update:open')
    expect(events).toBeTruthy()
    expect(events![events!.length - 1]).toEqual([false])
  })

  it('项目名被清空时保存按钮禁用', async () => {
    await mountPanel(true)

    const nameInput = document.body.querySelector<HTMLInputElement>('[data-testid="project-settings-name"]')!
    nameInput.value = ''
    nameInput.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()

    const save = document.body.querySelector<HTMLButtonElement>('[data-testid="project-settings-save"]')!
    expect(save.disabled).toBe(true)
    expect(projectApiMock.updateProject).not.toHaveBeenCalled()
  })

  it('点击删除弹出确认窗，确认弹窗取消时不调用 deleteProject', async () => {
    await mountPanel(true)

    document.body.querySelector<HTMLButtonElement>('[data-testid="project-settings-delete"]')!.click()
    await flushPromises()

    // 确认弹窗应出现
    const cancelBtn = document.body.querySelector<HTMLButtonElement>('[data-test="confirm-cancel"]')
    expect(cancelBtn).not.toBeNull()

    cancelBtn!.click()
    await flushPromises()

    expect(projectApiMock.deleteProject).not.toHaveBeenCalled()
  })

  it('点击删除确认后调用 deleteProject 并跳回首页', async () => {
    projectApiMock.deleteProject.mockResolvedValueOnce(undefined)
    const router = buildRouter()
    await router.push('/projects/p-1')
    await router.isReady()
    const replaceSpy = vi.spyOn(router, 'replace')

    const { wrapper } = await mountPanel(true, BASE_PROJECT, router)

    // 打开删除确认
    document.body.querySelector<HTMLButtonElement>('[data-testid="project-settings-delete"]')!.click()
    await flushPromises()

    // 点击「删除」按钮
    const confirmBtn = document.body.querySelector<HTMLButtonElement>('[data-test="confirm-action"]')!
    confirmBtn.click()
    await flushPromises()

    expect(projectApiMock.deleteProject).toHaveBeenCalledWith('p-1')
    expect(replaceSpy).toHaveBeenCalledWith({ name: 'home' })

    // 抽屉关闭事件
    const events = wrapper.emitted('update:open')
    expect(events).toBeTruthy()
    expect(events![events!.length - 1]).toEqual([false])
  })

  it('open 由 false → true 时表单恢复为 project prop 的最新值', async () => {
    const { wrapper } = await mountPanel(true)

    // 用户先改了名字但没保存
    const nameInput = document.body.querySelector<HTMLInputElement>('[data-testid="project-settings-name"]')!
    nameInput.value = '脏数据'
    nameInput.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()

    // 关闭抽屉
    await wrapper.setProps({ open: false })
    await flushPromises()

    // 再打开（传入同一 project prop）
    await wrapper.setProps({ open: true })
    await flushPromises()

    // 输入框应恢复为 prop 的原始值（已挂回 body）
    const reopenedInput = document.body.querySelector<HTMLInputElement>('[data-testid="project-settings-name"]')!
    expect(reopenedInput.value).toBe('测试项目')
  })
})
