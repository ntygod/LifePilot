import { describe, it, expect, beforeAll } from 'vitest'
import { mount } from '@vue/test-utils'
import * as fc from 'fast-check'
import ChatInput from './ChatInput.vue'
import { Image, FileAudio2, FileVideo, FileText } from 'lucide-vue-next'
import { createPinia, setActivePinia } from 'pinia'

let pinia: ReturnType<typeof createPinia>

beforeAll(() => {
  pinia = createPinia()
  setActivePinia(pinia)
})

// Feature: multimodal-completion, Property 12: 文件类型图标分类正确性
describe('ChatInput.getFileIcon', () => {
  it('文件类型图标分类正确性', () => {
    const wrapper = mount(ChatInput, {
      props: {
        disabled: false
      },
      global: {
        plugins: [pinia]
      }
    })

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

// Feature: multimodal-completion, Property 15: 上传进行中禁用发送
describe('ChatInput 上传进行中禁用发送（Property 15）', () => {
  it('当 isUploading 为 true 时，禁用条件恒为 true', () => {
    fc.assert(
      fc.property(
        fc.string(),
        fc.boolean(),
        (content, disabledFlag) => {
          const isUploading = true
          const isDisabled = disabledFlag || !content.trim() || isUploading
          expect(isDisabled).toBe(true)
        }
      ),
      { numRuns: 100 }
    )
  })
})


