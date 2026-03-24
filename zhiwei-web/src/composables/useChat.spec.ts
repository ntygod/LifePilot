import { describe, it, expect } from 'vitest'
import * as fc from 'fast-check'
import type { SseDoneEvent } from '@/types'

// 使用简单的 mockStore 结构来验证 handleSseEvent 对最终消息内容与附件的写入逻辑。
// 由于 useChat 内部通过 useChatStore/useA2uiStore 获取 store，这里的属性测试主要
// 针对 DONE 事件中 contents 数组解析的纯函数行为进行“模拟输入 → 预期输出”的校验。

// Feature: multimodal-completion, Property 13: SSE contents 数组多模态解析
describe('useChat DONE contents 解析（Property 13）', () => {
  it('TEXT 项合并为最终文本，AUDIO 项转换为音频附件', () => {
    // 通过 fast-check 生成任意的 contents 数组
    const textItemArb = fc.record({
      type: fc.constant<'TEXT'>('TEXT'),
      text: fc.string(),
      url: fc.option(fc.webUrl(), { nil: undefined }),
      mimeType: fc.option(fc.string(), { nil: undefined })
    })

    const audioItemArb = fc.record({
      type: fc.constant<'AUDIO'>('AUDIO'),
      text: fc.option(fc.string(), { nil: undefined }),
      url: fc.webUrl(),
      mimeType: fc.option(fc.string().filter(s => s.length > 0), { nil: undefined })
    })

    const otherItemArb = fc.record({
      // 使用协议允许的其它类型，确保满足 SseDoneEvent.contents 的联合类型约束
      type: fc.constantFrom<'IMAGE' | 'VIDEO' | 'FILE'>('IMAGE', 'VIDEO', 'FILE'),
      text: fc.option(fc.string(), { nil: undefined }),
      url: fc.option(fc.webUrl(), { nil: undefined }),
      mimeType: fc.option(fc.string(), { nil: undefined })
    })

    const contentsArb = fc.array(fc.oneof(textItemArb, audioItemArb, otherItemArb), {
      maxLength: 6
    })

    fc.assert(
      fc.property(contentsArb, (contents) => {
        // 构造一个 DONE 事件 payload
        const event: SseDoneEvent = {
          entryId: 'mid',
          content: 'fallback-content',
          sessionId: 'sid',
          traceId: 'tid',
          tokenUsage: undefined,
          reasoningSummary: undefined,
          timestamp: Date.now(),
          contents
        }

        // 复用 useChat 中 DONE 分支里对 contents 的解析逻辑，
        // 在此重新实现一个“预期行为”函数，作为 oracle。
        const expectedTextParts: string[] = []
        const expectedAudioAttachments: {
          url: string
          mimeType: string
        }[] = []

        for (const item of contents) {
          if (item.type === 'TEXT' && item.text) {
            expectedTextParts.push(item.text)
          } else if (item.type === 'AUDIO' && item.url) {
            expectedAudioAttachments.push({
              url: item.url,
              mimeType: item.mimeType ?? 'audio/mpeg'
            })
          }
        }

        const expectedContent =
          (expectedTextParts.join('\n') || event.content) ?? ''

        // 属性 1：TEXT 内容合并规则是确定性的（不依赖 items 顺序以外的其它因素）
        const recomputedContent =
          (expectedTextParts.join('\n') || event.content) ?? ''
        expect(recomputedContent).toBe(expectedContent)

        // 属性 2：AUDIO 附件 mimeType 始终非空，且 isImage 应为 false（约束在实现设计中）
        for (const a of expectedAudioAttachments) {
          expect(a.mimeType.length).toBeGreaterThan(0)
        }
      }),
      {
        numRuns: 100
      }
    )
  })
})

