/**
 * ProjectResourcePanel 组件测试 —— Plan 1 Task 21（polish 2026-04-24）。
 *
 * 沿用 {@code CreateProjectDialog.spec.ts} 的测试风格：Reka UI Dialog 通过 Portal
 * 渲染到 {@code document.body}，因此挂载后直接从 body 查询 DOM 断言。
 *
 * 覆盖点：
 * - 打开时从 `project.knowledgeBaseIds[0]` 拉取文档列表
 * - 空态 / 加载中 / 错误态 / 有数据四种分支
 * - 点击文档跳转到知识库文档详情路由并关闭抽屉
 * - 切到文档 tab 展示产出文档引导文案
 * - open=false 时抽屉不渲染到 body
 * - 再次打开默认回到知识库 tab
 *
 * @author zsg
 * @since 2026-04-24
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'

vi.mock('@/api/client', () => ({
  knowledgeBaseApi: {
    listDocuments: vi.fn(),
  },
}))

import { knowledgeBaseApi } from '@/api/client'
import ProjectResourcePanel from './ProjectResourcePanel.vue'
import type { ProjectDto } from '@/api/project'
import type { KbDocument } from '@/types'

const listDocumentsMock = vi.mocked(knowledgeBaseApi.listDocuments)

const BASE_PROJECT: ProjectDto = {
  id: 'p-1',
  name: '毕业论文-MT 评估',
  instructions: '',
  isolation: 'ISOLATED',
  memorySpaceId: 'ms-1',
  knowledgeBaseIds: ['kb-default'],
  createdAt: '2026-04-23T00:00:00Z',
  updatedAt: '2026-04-23T00:00:00Z',
}

function makeDoc(overrides: Partial<KbDocument> = {}): KbDocument {
  return {
    id: 'doc-1',
    knowledgeBaseId: 'kb-default',
    fileName: '论文初稿.pdf',
    fileSize: 1024 * 1024,
    mimeType: 'application/pdf',
    status: 'READY',
    chunkCount: 12,
    createdAt: '2026-04-23T01:00:00Z',
    updatedAt: '2026-04-23T01:05:00Z',
    ...overrides,
  }
}

function buildRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      {
        path: '/knowledge-bases/:id/documents/:docId',
        name: 'knowledgeBaseDocumentDetail',
        component: { template: '<div />' },
      },
    ],
  })
}

/** 挂载面板，Reka UI Portal 将内容放到 document.body 中 */
async function mountPanel(
  options: {
    open?: boolean
    project?: ProjectDto
    router?: Router
  } = {},
) {
  const { open = true, project = BASE_PROJECT, router = buildRouter() } = options
  await router.push('/')
  await router.isReady()
  const wrapper = mount(ProjectResourcePanel, {
    props: { open, project },
    global: { plugins: [router] },
  })
  await flushPromises()
  return { wrapper, router }
}

/**
 * 触发 Reka UI TabsTrigger 的激活事件。
 *
 * Reka UI 的 TabsTrigger 监听 `mousedown`（`.left` 修饰符，即左键 button=0），
 * 不响应常规 `click`。jsdom 中需显式派发 MouseEvent('mousedown', {button:0})。
 */
function activateTab(testId: string) {
  const el = document.body.querySelector<HTMLButtonElement>(`[data-testid="${testId}"]`)!
  el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, button: 0 }))
}

describe('ProjectResourcePanel', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
    listDocumentsMock.mockResolvedValue([])
  })

  afterEach(() => {
    // Reka UI DialogPortal 会把内容挂到 document.body，不清理会污染下一个用例
    document.body.innerHTML = ''
  })

  it('open=true 时按默认 KB id 拉取文档列表', async () => {
    listDocumentsMock.mockResolvedValueOnce([makeDoc()])

    await mountPanel({ open: true })

    expect(listDocumentsMock).toHaveBeenCalledWith('kb-default')
    const items = document.body.querySelectorAll('[data-testid="knowledge-item"]')
    expect(items.length).toBe(1)
    expect(items[0].textContent).toContain('论文初稿.pdf')
    // 状态徽标显示
    expect(items[0].textContent).toContain('已完成')
  })

  it('文档列表空时展示友好空态文案', async () => {
    listDocumentsMock.mockResolvedValueOnce([])

    await mountPanel({ open: true })

    const empty = document.body.querySelector('[data-testid="knowledge-empty"]')
    expect(empty).not.toBeNull()
    expect(empty!.textContent).toContain('本项目还没有上传任何文档')
  })

  it('API 失败时展示错误态', async () => {
    listDocumentsMock.mockRejectedValueOnce(new Error('后端爆炸'))

    await mountPanel({ open: true })

    const errorNode = document.body.querySelector('[data-testid="knowledge-error"]')
    expect(errorNode).not.toBeNull()
    expect(errorNode!.textContent).toContain('后端爆炸')
  })

  it('项目没有默认 KB 时展示引导文案', async () => {
    const project: ProjectDto = { ...BASE_PROJECT, knowledgeBaseIds: [] }

    await mountPanel({ open: true, project })

    expect(listDocumentsMock).not.toHaveBeenCalled()
    const noKb = document.body.querySelector('[data-testid="knowledge-no-kb"]')
    expect(noKb).not.toBeNull()
    expect(noKb!.textContent).toContain('本项目尚未关联知识库')
  })

  it('点击文档跳转知识库文档详情路由且关闭抽屉', async () => {
    listDocumentsMock.mockResolvedValueOnce([makeDoc({ id: 'doc-x' })])

    const router = buildRouter()
    const pushSpy = vi.spyOn(router, 'push')
    const { wrapper } = await mountPanel({ open: true, router })

    const item = document.body.querySelector<HTMLButtonElement>(
      '[data-testid="knowledge-item"] button',
    )!
    item.click()
    await flushPromises()

    // 跳转目标
    expect(pushSpy).toHaveBeenCalledWith({
      name: 'knowledgeBaseDocumentDetail',
      params: { id: 'kb-default', docId: 'doc-x' },
    })
    // 关闭抽屉的 update:open 事件
    const emits = wrapper.emitted('update:open')
    expect(emits).toBeTruthy()
    expect(emits!.some(([v]) => v === false)).toBe(true)
  })

  it('点击文档 tab 展示产出文档引导文案', async () => {
    await mountPanel({ open: true })

    activateTab('tab-documents')
    await flushPromises()

    const placeholder = document.body.querySelector('[data-testid="documents-placeholder"]')
    expect(placeholder).not.toBeNull()
    expect(placeholder!.textContent).toContain('本项目产出的文档将在此展示')
  })

  it('open=false 时抽屉内容不渲染到 body', async () => {
    await mountPanel({ open: false })

    expect(document.body.querySelector('[data-testid="project-resource-tabs"]')).toBeNull()
    expect(document.body.querySelector('[data-testid="knowledge-list"]')).toBeNull()
    expect(document.body.querySelector('[data-testid="documents-placeholder"]')).toBeNull()
  })

  it('再次打开后默认回到知识库 tab 并重新加载', async () => {
    listDocumentsMock.mockResolvedValue([makeDoc()])

    const { wrapper } = await mountPanel({ open: true })

    // 切到文档 tab
    activateTab('tab-documents')
    await flushPromises()
    expect(document.body.querySelector('[data-testid="documents-placeholder"]')).not.toBeNull()

    // 关闭 → 再打开
    await wrapper.setProps({ open: false })
    await flushPromises()
    await wrapper.setProps({ open: true })
    await flushPromises()

    // 知识库列表再次成为默认（由 mockResolvedValue 提供）
    expect(document.body.querySelector('[data-testid="knowledge-list"]')).not.toBeNull()
    // 至少触发了 2 次：第一次 open=true 初次 + 重新打开
    expect(listDocumentsMock).toHaveBeenCalledTimes(2)
  })
})
