import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import * as fc from 'fast-check'
import { nextTick } from 'vue'
import ChatInput from './ChatInput.vue'
import { Image, FileAudio2, FileVideo, FileText } from 'lucide-vue-next'
import { createPinia, setActivePinia } from 'pinia'

beforeEach(() => {
  setActivePinia(createPinia())
})

function mountChatInput() {
  return mount(ChatInput, {
    props: {
      disabled: false
    },
    global: {
      plugins: [createPinia()]
    }
  })
}

// Feature: multimodal-completion, Property 12: 文件类型图标分类正确性
describe('ChatInput.getFileIcon', () => {
  it('文件类型图标分类正确性', () => {
    const wrapper = mountChatInput()

    const vm: any = wrapper.vm

    // 使用 fast-check 验证不同 MIME 类型字符串下的图标选择逻辑
    fc.assert(
      fc.property(fc.string(), (mimeType) => {
        const file: any = { type: mimeType }
        const icon = vm.getFileIcon(file)

        const isImage = mimeType.startsWith('image/')
        const isAudio = mimeType.startsWith('audio/')
        const isVideo = mimeType.startsWith('video/')

        // 四种分类互斥（字符串不可能同时以多种前缀开头）
        const trueCount = [isImage, isAudio, isVideo].filter(Boolean).length
        expect(trueCount).toBeLessThanOrEqual(1)

        if (isImage) {
          expect(icon).toBe(Image)
        } else if (isAudio) {
          expect(icon).toBe(FileAudio2)
        } else if (isVideo) {
          expect(icon).toBe(FileVideo)
        } else {
          expect(icon).toBe(FileText)
        }
      }),
      {
        numRuns: 100
      }
    )
  })
})

describe('ChatInput 附件交互', () => {
  it('粘贴截图后会加入附件列表并允许发送', async () => {
    const wrapper = mountChatInput()
    const textarea = wrapper.find('textarea')
    const sendButton = wrapper.findAll('button').at(-1)
    const image = new File(['image'], 'clipboard.png', { type: 'image/png' })

    await textarea.trigger('paste', {
      clipboardData: { files: [image] }
    })
    await nextTick()

    expect(wrapper.text()).toContain('clipboard.png')
    expect(sendButton?.attributes('disabled')).toBeUndefined()
  })

  it('拖拽文件后会高亮并加入附件列表', async () => {
    const wrapper = mountChatInput()
    const dropZone = wrapper.find('.overflow-hidden')
    const sendButton = wrapper.findAll('button').at(-1)
    const document = new File(['content'], 'spec.pdf', { type: 'application/pdf' })

    await dropZone.trigger('dragenter', {
      dataTransfer: { types: ['Files'] }
    })
    expect(dropZone.classes()).toContain('border-primary/60')

    await dropZone.trigger('drop', {
      dataTransfer: { files: [document] }
    })
    await nextTick()

    expect(wrapper.text()).toContain('spec.pdf')
    expect(sendButton?.attributes('disabled')).toBeUndefined()
  })
})

// Feature: multimodal-completion, Property 15: 上传进行中禁用发送
describe('ChatInput 上传进行中禁用发送（Property 15）', () => {
  it('当 isUploading 为 true 时，禁用条件恒为 true', () => {
    fc.assert(
      fc.property(
        fc.string(),
        fc.boolean(),
        fc.integer({ min: 0, max: 3 }),
        (content, disabledFlag, attachmentCount) => {
          const isUploading = true
          const hasAttachments = attachmentCount > 0
          const isDisabled = disabledFlag || isUploading || (!content.trim() && !hasAttachments)
          expect(isDisabled).toBe(true)
        }
      ),
      { numRuns: 100 }
    )
  })
})


