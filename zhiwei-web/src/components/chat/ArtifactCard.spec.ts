import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ArtifactCard from './ArtifactCard.vue'

const mocks = vi.hoisted(() => ({
  uploadDocument: vi.fn(),
  showToast: vi.fn(),
  copyToClipboard: vi.fn(),
}))

vi.mock('@/api/artifacts', () => ({
  buildArtifactDownloadUrl: (id: string) => `/api/artifacts/${id}/download`,
  getArtifactMetadata: vi.fn(),
}))

vi.mock('@/api/client', () => ({
  knowledgeBaseApi: {
    uploadDocument: mocks.uploadDocument,
  },
}))

vi.mock('@/stores/ui', () => ({
  useUiStore: () => ({
    showToast: mocks.showToast,
  }),
}))

vi.mock('@/utils/clipboard', () => ({
  copyToClipboard: mocks.copyToClipboard,
}))

vi.mock('@/composables/useSaveFilePicker', () => ({
  isTauriEnv: () => false,
  openDocumentPath: vi.fn(),
  revealInFileManager: vi.fn(),
}))

beforeEach(() => {
  mocks.uploadDocument.mockReset().mockResolvedValue({
    id: 'doc-1',
    knowledgeBaseId: 'kb-product',
    fileName: 'report.md',
    fileSize: 8,
    mimeType: 'text/markdown',
    status: 'READY',
    chunkCount: 1,
    createdAt: '2026-07-07T00:00:00Z',
    updatedAt: '2026-07-07T00:00:00Z',
  })
  mocks.showToast.mockReset()
  mocks.copyToClipboard.mockReset().mockResolvedValue(true)
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    ok: true,
    blob: vi.fn().mockResolvedValue(new Blob(['# report'], { type: 'text/markdown' })),
  }))
})

describe('ArtifactCard 产物沉淀', () => {
  it('明确目标资料库时可把可解析文件存入资料库', async () => {
    const wrapper = mount(ArtifactCard, {
      props: {
        artifactId: 'artifact-report',
        fileName: 'report.md',
        mimeType: 'text/markdown',
        kind: 'FILE',
        size: 8,
        knowledgeBaseId: 'kb-product',
        knowledgeBaseName: '产品资料',
      },
    })

    await wrapper.find('button[title="存入资料库：产品资料"]').trigger('click')
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('/api/artifacts/artifact-report/download')
    expect(mocks.uploadDocument).toHaveBeenCalledWith('kb-product', expect.any(File))
    const uploadedFile = mocks.uploadDocument.mock.calls[0][1] as File
    expect(uploadedFile.name).toBe('report.md')
    expect(uploadedFile.type).toBe('text/markdown')
    expect(mocks.showToast).toHaveBeenCalledWith('success', '已存入资料库：产品资料')
    expect(wrapper.emitted('saved-knowledge')?.[0]).toEqual([{
      artifactId: 'artifact-report',
      fileName: 'report.md',
      knowledgeBaseId: 'kb-product',
      knowledgeBaseName: '产品资料',
    }])
    expect(wrapper.text()).toContain('已存入 产品资料')
    const savedButton = wrapper.find('button[title="已存入资料库：产品资料"]')
    expect(savedButton.exists()).toBe(true)
    expect(savedButton.attributes('disabled')).toBeDefined()

    await savedButton.trigger('click')

    expect(mocks.uploadDocument).toHaveBeenCalledTimes(1)
  })

  it('存入资料库失败后在卡片内显示原因并允许重试', async () => {
    mocks.uploadDocument.mockRejectedValueOnce(new Error('索引服务不可用'))

    const wrapper = mount(ArtifactCard, {
      props: {
        artifactId: 'artifact-report',
        fileName: 'report.md',
        mimeType: 'text/markdown',
        kind: 'FILE',
        size: 8,
        knowledgeBaseId: 'kb-product',
        knowledgeBaseName: '产品资料',
      },
    })

    await wrapper.find('button[title="存入资料库：产品资料"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('存入失败：索引服务不可用')
    expect(mocks.showToast).toHaveBeenCalledWith('error', '存入资料失败：索引服务不可用')

    const retryButton = wrapper.find('button[title="重试存入资料库：产品资料"]')
    expect(retryButton.exists()).toBe(true)
    expect(retryButton.text()).toContain('重试')

    await retryButton.trigger('click')
    await flushPromises()

    expect(mocks.uploadDocument).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).not.toContain('存入失败：索引服务不可用')
    expect(wrapper.text()).toContain('已存入 产品资料')
  })

  it('沉淀状态记录失败后不进入完成态，重试时不重复上传文件', async () => {
    const persistKnowledgeSettlement = vi.fn()
      .mockRejectedValueOnce(new Error('会话记录暂不可写'))
      .mockResolvedValueOnce(undefined)

    const wrapper = mount(ArtifactCard, {
      props: {
        artifactId: 'artifact-report',
        fileName: 'report.md',
        mimeType: 'text/markdown',
        kind: 'FILE',
        size: 8,
        knowledgeBaseId: 'kb-product',
        knowledgeBaseName: '产品资料',
        persistKnowledgeSettlement,
      },
    })

    await wrapper.find('button[title="存入资料库：产品资料"]').trigger('click')
    await flushPromises()

    expect(mocks.uploadDocument).toHaveBeenCalledTimes(1)
    expect(persistKnowledgeSettlement).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('存入失败：记录沉淀状态失败：会话记录暂不可写')
    expect(wrapper.text()).not.toContain('已存入 产品资料')
    expect(wrapper.emitted('saved-knowledge')).toBeUndefined()
    expect(mocks.showToast).toHaveBeenCalledWith('error', '存入资料失败：记录沉淀状态失败：会话记录暂不可写')

    await wrapper.find('button[title="重试存入资料库：产品资料"]').trigger('click')
    await flushPromises()

    expect(fetch).toHaveBeenCalledTimes(1)
    expect(mocks.uploadDocument).toHaveBeenCalledTimes(1)
    expect(persistKnowledgeSettlement).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('已存入 产品资料')
    expect(wrapper.emitted('saved-knowledge')?.[0]).toEqual([{
      artifactId: 'artifact-report',
      fileName: 'report.md',
      knowledgeBaseId: 'kb-product',
      knowledgeBaseName: '产品资料',
    }])
  })

  it('切换产物或资料库目标时重置已存入状态', async () => {
    const wrapper = mount(ArtifactCard, {
      props: {
        artifactId: 'artifact-report',
        fileName: 'report.md',
        mimeType: 'text/markdown',
        kind: 'FILE',
        size: 8,
        knowledgeBaseId: 'kb-product',
        knowledgeBaseName: '产品资料',
      },
    })

    await wrapper.find('button[title="存入资料库：产品资料"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('已存入 产品资料')

    await wrapper.setProps({
      artifactId: 'artifact-next',
      fileName: 'next.md',
    })

    expect(wrapper.text()).not.toContain('已存入 产品资料')
    expect(wrapper.find('button[title="存入资料库：产品资料"]').exists()).toBe(true)
  })

  it('没有明确资料库时不显示存资料入口', () => {
    const wrapper = mount(ArtifactCard, {
      props: {
        artifactId: 'artifact-report',
        fileName: 'report.md',
        mimeType: 'text/markdown',
        kind: 'FILE',
        size: 8,
      },
    })

    expect(wrapper.find('button[title^="存入资料库"]').exists()).toBe(false)
  })

  it('历史产物已有沉淀记录时直接显示完成态', () => {
    const wrapper = mount(ArtifactCard, {
      props: {
        artifactId: 'artifact-report',
        fileName: 'report.md',
        mimeType: 'text/markdown',
        kind: 'FILE',
        size: 8,
        savedKnowledgeBaseName: '产品资料',
      },
    })

    expect(wrapper.text()).toContain('已存入 产品资料')
    expect(wrapper.find('button[title^="存入资料库"]').exists()).toBe(false)
  })

  it('不可解析产物不显示存资料入口', () => {
    const wrapper = mount(ArtifactCard, {
      props: {
        artifactId: 'artifact-bin',
        fileName: 'archive.bin',
        mimeType: 'application/octet-stream',
        kind: 'FILE',
        size: 8,
        knowledgeBaseId: 'kb-product',
        knowledgeBaseName: '产品资料',
      },
    })

    expect(wrapper.find('button[title^="存入资料库"]').exists()).toBe(false)
  })
})
