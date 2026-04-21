import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import DocumentXlsxDiffCard from './DocumentXlsxDiffCard.vue'

vi.mock('@/api/documents', async (importActual) => {
  const actual = await importActual<typeof import('@/api/documents')>()
  return {
    ...actual,
    getDocument: vi.fn(),
    getDiff: vi.fn(),
    commit: vi.fn(),
    discardWorkingCopy: vi.fn(),
    listVersions: vi.fn(),
    rollback: vi.fn(),
  }
})

import {
  getDocument,
  getDiff,
} from '@/api/documents'

const mockMeta = {
  id: 'x-1',
  fileName: '报表.xlsx',
  mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  fileSize: 2048,
  origin: 'user_local_file',
  sourcePath: 'D:/src/报表.xlsx',
  latestVersion: 1,
  createdAt: '2026-04-21T10:00:00Z',
}

function makeDiffJson(changes: Array<Record<string, unknown>>) {
  return JSON.stringify({
    documentId: 'x-1',
    fromVersion: 0,
    toVersion: 1,
    mime: 'xlsx',
    summary: '共 ' + changes.length + ' 处修改',
    changes,
  })
}

describe('DocumentXlsxDiffCard', () => {
  beforeEach(() => {
    vi.mocked(getDocument).mockReset()
    vi.mocked(getDiff).mockReset()
  })

  it('update_cell 显示 before → after 双列', async () => {
    vi.mocked(getDocument).mockResolvedValue(mockMeta)
    vi.mocked(getDiff).mockResolvedValue({
      diffJson: makeDiffJson([{
        patch_id: 'p1', op: 'update_cell', sheet: 'Sheet1', cell: 'B5',
        segments: [{ type: 'delete', text: '30' }, { type: 'insert', text: '15' }],
        reason: '缩短',
      }]),
    })
    const wrapper = mount(DocumentXlsxDiffCard, { props: { documentId: 'x-1' } })
    await flushPromises()
    await wrapper.find('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Sheet1!B5')
    expect(wrapper.text()).toContain('30')
    expect(wrapper.text()).toContain('15')
    expect(wrapper.text()).toContain('缩短')
  })

  it('insert_row 显示 sheet!row N 标签 + 单向 insert', async () => {
    vi.mocked(getDocument).mockResolvedValue(mockMeta)
    vi.mocked(getDiff).mockResolvedValue({
      diffJson: makeDiffJson([{
        patch_id: 'p2', op: 'insert_row', sheet: 'Sheet1', row: 5,
        segments: [{ type: 'insert', text: '新产品 | 100 | 2026-04-21' }],
        reason: '',
      }]),
    })
    const wrapper = mount(DocumentXlsxDiffCard, { props: { documentId: 'x-1' } })
    await flushPromises()
    await wrapper.find('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Sheet1!row 5')
    expect(wrapper.text()).toContain('新产品 | 100')
  })

  it('set_range 显示 rows×cols 规模', async () => {
    vi.mocked(getDocument).mockResolvedValue(mockMeta)
    vi.mocked(getDiff).mockResolvedValue({
      diffJson: makeDiffJson([{
        patch_id: 'p3', op: 'set_range', sheet: 'Sheet1', range: 'B2:D4',
        rows: 3, cols: 3,
        segments: [{ type: 'insert', text: '3x3 批量（预览略）' }],
        reason: '',
      }]),
    })
    const wrapper = mount(DocumentXlsxDiffCard, { props: { documentId: 'x-1' } })
    await flushPromises()
    await wrapper.find('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Sheet1!B2:D4')
    expect(wrapper.text()).toContain('(3×3)')
  })

  it('sourcePath 为 null 时不显示 "应用到原路径" 按钮', async () => {
    vi.mocked(getDocument).mockResolvedValue({ ...mockMeta, sourcePath: null })
    vi.mocked(getDiff).mockResolvedValue({
      diffJson: makeDiffJson([{
        patch_id: 'p1', op: 'update_cell', sheet: 'Sheet1', cell: 'A1',
        segments: [{ type: 'delete', text: 'x' }, { type: 'insert', text: 'y' }],
        reason: '',
      }]),
    })
    const wrapper = mount(DocumentXlsxDiffCard, { props: { documentId: 'x-1' } })
    await flushPromises()
    await wrapper.find('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).not.toContain('应用到原路径')
    expect(wrapper.text()).toContain('另存为')
  })
})
