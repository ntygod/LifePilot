/**
 * CreateProjectDialog 组件测试 —— Plan 1 Task 19 + Plan 2 polish。
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

vi.mock('@/api/client', () => ({
  knowledgeBaseApi: {
    uploadDocument: vi.fn(),
  },
}))

import * as projectApi from '@/api/project'
import { knowledgeBaseApi } from '@/api/client'
import CreateProjectDialog from './CreateProjectDialog.vue'

const projectApiMock = vi.mocked(projectApi, { deep: true })
const knowledgeBaseApiMock = vi.mocked(knowledgeBaseApi, { deep: true })

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

/** 创建一个带 KB id 的项目 DTO（用于单文件上传测试） */
const CREATED_PROJECT = {
  id: 'p-new',
  name: '新项目',
  instructions: '',
  isolation: 'ISOLATED' as const,
  memorySpaceId: 'ms-new',
  knowledgeBaseIds: ['kb-new'],
  createdAt: '2026-04-24T00:00:00Z',
  updatedAt: '2026-04-24T00:00:00Z',
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

  it('选中文件后展开列表并可单独移除', async () => {
    await mountOpen()

    // 展开高级面板才能看到文件输入
    document.body.querySelector<HTMLButtonElement>('[data-testid="create-project-advanced-toggle"]')!.click()
    await flushPromises()

    // 直接触发 change 事件（绕过 triggerFilePicker 的 input.click）
    const input = document.body.querySelector<HTMLInputElement>('[data-testid="create-project-file-input"]')!
    const file1 = new File(['content-a'], 'a.md', { type: 'text/markdown' })
    const file2 = new File(['content-b'], 'b.txt', { type: 'text/plain' })
    Object.defineProperty(input, 'files', { value: [file1, file2], configurable: true })
    input.dispatchEvent(new Event('change', { bubbles: true }))
    await flushPromises()

    const list = document.body.querySelector('[data-testid="create-project-file-list"]')
    expect(list).not.toBeNull()
    expect(list!.querySelectorAll('li')).toHaveLength(2)

    // 移除第一个
    document.body.querySelector<HTMLButtonElement>('[data-testid="create-project-file-remove-0"]')!.click()
    await flushPromises()
    expect(document.body.querySelectorAll('[data-testid^="create-project-file-item-"]')).toHaveLength(1)
  })

  it('不支持的文件类型被前端拒绝并提示', async () => {
    await mountOpen()
    document.body.querySelector<HTMLButtonElement>('[data-testid="create-project-advanced-toggle"]')!.click()
    await flushPromises()

    const input = document.body.querySelector<HTMLInputElement>('[data-testid="create-project-file-input"]')!
    const bad = new File(['oops'], 'a.exe', { type: 'application/octet-stream' })
    const good = new File(['ok'], 'b.md', { type: 'text/markdown' })
    Object.defineProperty(input, 'files', { value: [bad, good], configurable: true })
    input.dispatchEvent(new Event('change', { bubbles: true }))
    await flushPromises()

    const items = document.body.querySelectorAll('[data-testid^="create-project-file-item-"]')
    expect(items).toHaveLength(1)
    const err = document.body.querySelector('[data-testid="create-project-error"]')!
    expect(err.textContent).toContain('a.exe')
  })

  it('选中文件后提交依次上传到项目默认知识库并跳转', async () => {
    projectApiMock.createProject.mockResolvedValueOnce(CREATED_PROJECT)
    knowledgeBaseApiMock.uploadDocument.mockResolvedValue({} as any)
    const router = buildRouter()
    await router.push('/')
    await router.isReady()
    const pushSpy = vi.spyOn(router, 'push')
    const { wrapper } = await mountOpen(router)

    // 1) 填项目名
    const nameInput = document.body.querySelector<HTMLInputElement>('[data-testid="create-project-name"]')!
    nameInput.value = '新项目'
    nameInput.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()

    // 2) 展开高级并选两个文件
    document.body.querySelector<HTMLButtonElement>('[data-testid="create-project-advanced-toggle"]')!.click()
    await flushPromises()
    const fileInput = document.body.querySelector<HTMLInputElement>('[data-testid="create-project-file-input"]')!
    const f1 = new File(['a'], 'a.md', { type: 'text/markdown' })
    const f2 = new File(['b'], 'b.pdf', { type: 'application/pdf' })
    Object.defineProperty(fileInput, 'files', { value: [f1, f2], configurable: true })
    fileInput.dispatchEvent(new Event('change', { bubbles: true }))
    await flushPromises()

    // 3) 提交
    document.body.querySelector<HTMLButtonElement>('[data-testid="create-project-submit"]')!.click()
    await flushPromises()

    // 验证调用顺序：先 createProject 后两次 uploadDocument，最后关闭对话框 + 跳转
    expect(projectApiMock.createProject).toHaveBeenCalledTimes(1)
    expect(knowledgeBaseApiMock.uploadDocument).toHaveBeenCalledTimes(2)
    expect(knowledgeBaseApiMock.uploadDocument).toHaveBeenNthCalledWith(1, 'kb-new', f1)
    expect(knowledgeBaseApiMock.uploadDocument).toHaveBeenNthCalledWith(2, 'kb-new', f2)

    const events = wrapper.emitted('update:open')
    expect(events![events!.length - 1]).toEqual([false])
    expect(pushSpy).toHaveBeenCalledWith('/projects/p-new')
  })

  it('部分文件上传失败时_项目不回滚_对话框保持打开且展示错误', async () => {
    projectApiMock.createProject.mockResolvedValueOnce(CREATED_PROJECT)
    // 第一个成功，第二个失败 → 期望对话框不关闭
    knowledgeBaseApiMock.uploadDocument
      .mockResolvedValueOnce({} as any)
      .mockRejectedValueOnce({ code: 500, message: '服务端炸了', timestamp: '2026-04-24' } as any)
    const router = buildRouter()
    await router.push('/')
    await router.isReady()
    const pushSpy = vi.spyOn(router, 'push')
    const { wrapper } = await mountOpen(router)

    const nameInput = document.body.querySelector<HTMLInputElement>('[data-testid="create-project-name"]')!
    nameInput.value = '新项目'
    nameInput.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()

    document.body.querySelector<HTMLButtonElement>('[data-testid="create-project-advanced-toggle"]')!.click()
    await flushPromises()
    const fileInput = document.body.querySelector<HTMLInputElement>('[data-testid="create-project-file-input"]')!
    const f1 = new File(['a'], 'a.md', { type: 'text/markdown' })
    const f2 = new File(['b'], 'bad.pdf', { type: 'application/pdf' })
    Object.defineProperty(fileInput, 'files', { value: [f1, f2], configurable: true })
    fileInput.dispatchEvent(new Event('change', { bubbles: true }))
    await flushPromises()

    document.body.querySelector<HTMLButtonElement>('[data-testid="create-project-submit"]')!.click()
    await flushPromises()

    // 期望：项目创建成功 + 两次上传调用；但对话框未关闭、未跳转、错误框显示失败文件名
    expect(projectApiMock.createProject).toHaveBeenCalledTimes(1)
    expect(knowledgeBaseApiMock.uploadDocument).toHaveBeenCalledTimes(2)

    // 没有 emit update:open(false)
    const events = wrapper.emitted('update:open')
    expect(events).toBeUndefined()
    // 路由未跳转
    expect(pushSpy).not.toHaveBeenCalledWith('/projects/p-new')
    // 错误提示带失败文件名
    const err = document.body.querySelector('[data-testid="create-project-error"]')!
    expect(err.textContent).toContain('bad.pdf')
  })

  it('后端没返回 knowledgeBaseIds 时_跳过上传_仍然关闭并跳转', async () => {
    projectApiMock.createProject.mockResolvedValueOnce({
      ...CREATED_PROJECT,
      knowledgeBaseIds: [],
    })
    const router = buildRouter()
    await router.push('/')
    await router.isReady()
    const pushSpy = vi.spyOn(router, 'push')
    const { wrapper } = await mountOpen(router)

    const nameInput = document.body.querySelector<HTMLInputElement>('[data-testid="create-project-name"]')!
    nameInput.value = '新项目'
    nameInput.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()

    document.body.querySelector<HTMLButtonElement>('[data-testid="create-project-advanced-toggle"]')!.click()
    await flushPromises()
    const fileInput = document.body.querySelector<HTMLInputElement>('[data-testid="create-project-file-input"]')!
    const f1 = new File(['a'], 'a.md', { type: 'text/markdown' })
    Object.defineProperty(fileInput, 'files', { value: [f1], configurable: true })
    fileInput.dispatchEvent(new Event('change', { bubbles: true }))
    await flushPromises()

    document.body.querySelector<HTMLButtonElement>('[data-testid="create-project-submit"]')!.click()
    await flushPromises()

    // 没 KB id → 不上传
    expect(knowledgeBaseApiMock.uploadDocument).not.toHaveBeenCalled()
    // 但错误提示提醒用户文件未上传
    const err = document.body.querySelector('[data-testid="create-project-error"]')!
    expect(err.textContent).toContain('文件未上传')
    // 仍然关闭 + 跳转
    const events = wrapper.emitted('update:open')
    expect(events![events!.length - 1]).toEqual([false])
    expect(pushSpy).toHaveBeenCalledWith('/projects/p-new')
  })
})
