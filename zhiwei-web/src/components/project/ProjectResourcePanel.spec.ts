/**
 * ProjectResourcePanel 组件测试 —— Plan 1 Task 21。
 *
 * 沿用 {@code CreateProjectDialog.spec.ts} 的测试风格：Reka UI Dialog 通过 Portal
 * 渲染到 {@code document.body}，因此挂载后直接从 body 查询 DOM 断言。
 *
 * 覆盖点：
 * - 默认激活「知识库」tab，占位文案可见
 * - 点击「文档」tab 切到文档占位
 * - open=false 时抽屉不渲染
 * - update:open 能通过抽屉 open 变化回传
 *
 * @author zsg
 * @since 2026-04-23
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

import ProjectResourcePanel from './ProjectResourcePanel.vue'

/** 挂载面板，Reka UI Portal 将内容放到 document.body 中 */
async function mountPanel(open = true) {
  const wrapper = mount(ProjectResourcePanel, {
    props: {
      open,
      projectId: 'p-1',
    },
  })
  await flushPromises()
  return wrapper
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
  })

  afterEach(() => {
    // Reka UI DialogPortal 会把内容挂到 document.body，不清理会污染下一个用例
    document.body.innerHTML = ''
  })

  it('open=true 时默认展示知识库 tab 与占位文案', async () => {
    await mountPanel(true)

    // 知识库占位可见
    const knowledgePlaceholder = document.body.querySelector('[data-testid="knowledge-placeholder"]')
    expect(knowledgePlaceholder).not.toBeNull()
    expect(knowledgePlaceholder!.textContent).toContain('知识库文件将在此展示')

    // tab 触发器存在
    expect(document.body.querySelector('[data-testid="tab-knowledge"]')).not.toBeNull()
    expect(document.body.querySelector('[data-testid="tab-documents"]')).not.toBeNull()
  })

  it('点击文档 tab 切换到文档占位', async () => {
    await mountPanel(true)

    activateTab('tab-documents')
    await flushPromises()

    const documentsPlaceholder = document.body.querySelector('[data-testid="documents-placeholder"]')
    expect(documentsPlaceholder).not.toBeNull()
    expect(documentsPlaceholder!.textContent).toContain('文档将在此展示')
  })

  it('open=false 时抽屉内容不渲染到 body', async () => {
    await mountPanel(false)

    // 关闭状态下 Reka UI Portal 不会把 SheetContent 挂到 body 中
    expect(document.body.querySelector('[data-testid="knowledge-placeholder"]')).toBeNull()
    expect(document.body.querySelector('[data-testid="documents-placeholder"]')).toBeNull()
    expect(document.body.querySelector('[data-testid="project-resource-tabs"]')).toBeNull()
  })

  it('父组件设置 open 从 true 回到 false 后占位消失', async () => {
    const wrapper = await mountPanel(true)
    expect(document.body.querySelector('[data-testid="knowledge-placeholder"]')).not.toBeNull()

    await wrapper.setProps({ open: false })
    await flushPromises()

    expect(document.body.querySelector('[data-testid="knowledge-placeholder"]')).toBeNull()
  })

  it('再次打开后默认回到知识库 tab', async () => {
    const wrapper = await mountPanel(true)

    // 切到文档 tab
    activateTab('tab-documents')
    await flushPromises()
    expect(document.body.querySelector('[data-testid="documents-placeholder"]')).not.toBeNull()

    // 关闭 → 再打开
    await wrapper.setProps({ open: false })
    await flushPromises()
    await wrapper.setProps({ open: true })
    await flushPromises()

    // 知识库占位再次成为默认
    expect(document.body.querySelector('[data-testid="knowledge-placeholder"]')).not.toBeNull()
  })
})
