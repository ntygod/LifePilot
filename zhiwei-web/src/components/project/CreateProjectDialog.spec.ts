/**
 * CreateProjectDialog 组件测试 —— Plan 1 Task 19。
 *
 * 沿用仓库既有测试风格（见 {@code stores/project.spec.ts}、{@code ProjectSection.spec.ts}）：
 * 通过 {@code vi.mock} 替换 API 模块，用真实 {@code createPinia()} 管理状态；路由通过
 * {@code createMemoryHistory} 构造最小实例并用 {@code vi.spyOn} 验证跳转。
 *
 * 注意：Reka UI Dialog 通过 Portal 渲染到 body，因此 {@code mount} 时需要 {@code attachTo}
 * 或直接在挂载后从 {@code document.body} 里查 DOM。这里采用后者。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
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
import CreateProjectDialog from './CreateProjectDialog.vue'

const projectApiMock = vi.mocked(projectApi, { deep: true })

function buildRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
}

/** 挂载打开状态的对话框，返回 wrapper + router；Portal 渲染会出现在 document.body 中 */
async function mountOpen(router = buildRouter()) {
  await router.push('/')
  await router.isReady()
  const wrapper = mount(CreateProjectDialog, {
    props: { open: true },
    global: { plugins: [router] },
  })
  await flushPromises()
  return { wrapper, router }
}

const CREATED_PROJECT = {
  id: 'p-new',
  name: '新项目',
  instructions: '',
  isolation: 'ISOLATED' as const,
  memorySpaceId: 'ms-new',
  createdAt: '2026-04-23T00:00:00Z',
  updatedAt: '2026-04-23T00:00:00Z',
}

describe('CreateProjectDialog', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  afterEach(() => {
    // Reka UI DialogPortal 会把内容挂到 document.body，不 unmount 会污染下一个用例
    document.body.innerHTML = ''
  })

  it('打开时默认选中"隔离"且高级设置折叠', async () => {
    await mountOpen()

    // 高级面板默认不渲染（advancedOpen = false）
    expect(document.body.querySelector('[data-testid="create-project-advanced-panel"]')).toBeNull()

    // 展开高级设置后才能看到隔离单选
    const toggle = document.body.querySelector<HTMLButtonElement>('[data-testid="create-project-advanced-toggle"]')!
    toggle.click()
    await flushPromises()

    const isolated = document.body.querySelector<HTMLInputElement>('[data-testid="isolation-isolated"]')!
    const shared = document.body.querySelector<HTMLInputElement>('[data-testid="isolation-shared"]')!
    expect(isolated.checked).toBe(true)
    expect(shared.checked).toBe(false)
  })

  it('项目名为空时提交按钮禁用', async () => {
    await mountOpen()
    const submit = document.body.querySelector<HTMLButtonElement>('[data-testid="create-project-submit"]')!
    expect(submit.disabled).toBe(true)
  })

  it('填写项目名后点击提交调用 store.createProject', async () => {
    projectApiMock.createProject.mockResolvedValueOnce(CREATED_PROJECT)
    await mountOpen()

    const input = document.body.querySelector<HTMLInputElement>('[data-testid="create-project-name"]')!
    input.value = '新项目'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()

    const submit = document.body.querySelector<HTMLButtonElement>('[data-testid="create-project-submit"]')!
    expect(submit.disabled).toBe(false)
    submit.click()
    await flushPromises()

    expect(projectApiMock.createProject).toHaveBeenCalledWith({
      name: '新项目',
      instructions: '',
      isolation: 'ISOLATED',
    })
  })

  it('提交成功后 emit update:open 关闭对话框并跳转到 /projects/:id', async () => {
    projectApiMock.createProject.mockResolvedValueOnce(CREATED_PROJECT)
    const router = buildRouter()
    await router.push('/')
    await router.isReady()
    const pushSpy = vi.spyOn(router, 'push')
    const { wrapper } = await mountOpen(router)

    const input = document.body.querySelector<HTMLInputElement>('[data-testid="create-project-name"]')!
    input.value = '新项目'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()

    document.body.querySelector<HTMLButtonElement>('[data-testid="create-project-submit"]')!.click()
    await flushPromises()

    const events = wrapper.emitted('update:open')
    expect(events).toBeTruthy()
    expect(events![events!.length - 1]).toEqual([false])
    expect(pushSpy).toHaveBeenCalledWith('/projects/p-new')
  })

  it('点击取消触发 emit update:open(false) 且不调用 createProject', async () => {
    const { wrapper } = await mountOpen()
    document.body.querySelector<HTMLButtonElement>('[data-testid="create-project-cancel"]')!.click()
    await flushPromises()

    expect(projectApiMock.createProject).not.toHaveBeenCalled()
    const events = wrapper.emitted('update:open')
    expect(events).toBeTruthy()
    expect(events![events!.length - 1]).toEqual([false])
  })
})
