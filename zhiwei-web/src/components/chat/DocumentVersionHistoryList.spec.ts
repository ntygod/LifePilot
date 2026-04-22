import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import DocumentVersionHistoryList from './DocumentVersionHistoryList.vue'

vi.mock('@/api/documents', () => ({
  listVersions: vi.fn(),
  rollback: vi.fn(),
}))

import { listVersions, rollback } from '@/api/documents'

describe('DocumentVersionHistoryList', () => {
  beforeEach(() => {
    // 组件内用 useUiStore().showToast 报错回滚失败，需先激活 Pinia
    setActivePinia(createPinia())
    vi.mocked(listVersions).mockReset()
    vi.mocked(rollback).mockReset()
  })

  it('展开后加载版本列表,倒序展示', async () => {
    vi.mocked(listVersions).mockResolvedValue({
      items: [
        { versionNo: 0, source: 'initial', patchSummary: null, createdAt: '2026-04-21T10:00:00Z' },
        { versionNo: 1, source: 'patch', patchSummary: '共 1 处修改', createdAt: '2026-04-21T10:05:00Z' },
        { versionNo: 2, source: 'patch', patchSummary: '共 2 处修改', createdAt: '2026-04-21T10:10:00Z' },
      ],
      total: 3, page: 1, pageSize: 100,
    })
    const wrapper = mount(DocumentVersionHistoryList, {
      props: { documentId: 'doc-1', currentVersion: 2 },
    })
    await wrapper.find('button').trigger('click')
    await flushPromises()

    const items = wrapper.findAll('li')
    expect(items.length).toBe(3)
    expect(items[0].text()).toContain('v2')
    expect(items[2].text()).toContain('v0')
  })

  it('当前版本不渲染回滚按钮', async () => {
    vi.mocked(listVersions).mockResolvedValue({
      items: [
        { versionNo: 0, source: 'initial', patchSummary: null, createdAt: '2026-04-21T10:00:00Z' },
        { versionNo: 1, source: 'patch', patchSummary: 'x', createdAt: '2026-04-21T10:05:00Z' },
      ],
      total: 2, page: 1, pageSize: 100,
    })
    const wrapper = mount(DocumentVersionHistoryList, {
      props: { documentId: 'doc-1', currentVersion: 1 },
    })
    await wrapper.find('button').trigger('click')
    await flushPromises()

    const items = wrapper.findAll('li')
    expect(items[0].text()).toContain('（当前）')
    expect(items[1].findAll('button').length).toBe(1)
  })

  it('点回滚打开 ConfirmDialog, 确认后触发 API 并 emit 事件', async () => {
    vi.mocked(listVersions).mockResolvedValue({
      items: [
        { versionNo: 0, source: 'initial', patchSummary: null, createdAt: '2026-04-21T10:00:00Z' },
        { versionNo: 1, source: 'patch', patchSummary: 'x', createdAt: '2026-04-21T10:05:00Z' },
      ],
      total: 2, page: 1, pageSize: 100,
    })
    vi.mocked(rollback).mockResolvedValue({ newVersion: 2, summary: '回滚到版本 0' })

    const wrapper = mount(DocumentVersionHistoryList, {
      props: { documentId: 'doc-1', currentVersion: 1 },
      global: {
        stubs: {
          // 把 ConfirmDialog 打成纯 emit 桩，避免 Reka AlertDialog 的 teleport 影响 find
          ConfirmDialog: {
            props: ['show', 'title', 'message', 'confirmVariant'],
            emits: ['confirm', 'cancel'],
            template:
              '<div data-test="confirm-stub">' +
              '<button data-test="confirm-ok" @click="$emit(\'confirm\')">确认</button>' +
              '</div>',
          },
        },
      },
    })
    await wrapper.find('button').trigger('click')
    await flushPromises()

    const rollbackBtn = wrapper.findAll('li')[1].findAll('button')[0]
    await rollbackBtn.trigger('click')
    await flushPromises()

    // 点回滚应只打开对话框，还没调 API
    expect(rollback).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="confirm-stub"]').exists()).toBe(true)

    // 确认后才真正调 rollback API
    await wrapper.find('[data-test="confirm-ok"]').trigger('click')
    await flushPromises()

    expect(rollback).toHaveBeenCalledWith('doc-1', 0)
    expect(wrapper.emitted('rollback-complete')).toEqual([[2]])
  })
})
