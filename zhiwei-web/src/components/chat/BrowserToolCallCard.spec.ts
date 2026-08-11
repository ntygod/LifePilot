import { describe, it, expect } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import BrowserToolCallCard from './BrowserToolCallCard.vue'
import type { ToolCallSummary } from '@/types'

/** 构造测试用 ToolCallSummary 的辅助函数，避免重复样板 */
function buildTool(overrides: Partial<ToolCallSummary>): ToolCallSummary {
  return {
    toolId: 'browser',
    success: true,
    latencyMs: 100,
    inputSummary: '',
    outputSummary: '',
    ...overrides,
  }
}

async function openEvidence(wrapper: VueWrapper) {
  const trigger = wrapper.find('[data-slot="accordion-trigger"]')
  expect(trigger.exists()).toBe(true)
  expect(trigger.attributes('data-state')).toBe('closed')

  await trigger.trigger('click')

  expect(trigger.attributes('data-state')).toBe('open')
}

describe('BrowserToolCallCard 浏览器工具特化卡片', () => {
  it('snapshot action 默认只露出摘要，展开后渲染可交互元素列表和截图', async () => {
    const wrapper = mount(BrowserToolCallCard, {
      props: {
        tool: buildTool({
          action: 'snapshot',
          latencyMs: 200,
          output: JSON.stringify({
            url: 'https://example.com',
            title: '示例页面',
            screenshot: 'AAAA',
            elements: [
              { index: 0, tag: 'a', text: '首页' },
              { index: 1, tag: 'button', text: '搜索' },
            ],
          }),
        }),
      },
    })

    expect(wrapper.text()).toContain('已记录页面截图，并识别 2 个可交互元素')
    expect(wrapper.find('img').exists()).toBe(false)

    await openEvidence(wrapper)

    expect(wrapper.text()).toContain('已识别 2 个可交互元素')
    expect(wrapper.text()).toContain('#0')
    expect(wrapper.text()).toContain('#1')
    expect(wrapper.text()).toContain('首页')
    expect(wrapper.text()).toContain('搜索')
    const img = wrapper.find('img')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('data:image/png;base64,AAAA')
  })

  it('navigate action 只显示 URL 与标题，不渲染 elements 列表', () => {
    const wrapper = mount(BrowserToolCallCard, {
      props: {
        tool: buildTool({
          action: 'navigate',
          latencyMs: 150,
          output: JSON.stringify({
            url: 'https://example.com',
            title: '示例页面',
          }),
        }),
      },
    })

    expect(wrapper.text()).toContain('https://example.com')
    expect(wrapper.text()).toContain('navigate')
    expect(wrapper.text()).not.toContain('已识别')
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('[data-slot="accordion-trigger"]').exists()).toBe(false)
  })

  it('output 字段为空时不崩溃且显示 action 名', () => {
    const wrapper = mount(BrowserToolCallCard, {
      props: {
        tool: buildTool({
          action: 'close',
          latencyMs: 50,
        }),
      },
    })

    expect(wrapper.text()).toContain('close')
    expect(wrapper.text()).toContain('已完成')
    expect(wrapper.find('img').exists()).toBe(false)
  })

  it('screenshotDataUri 字段原样使用不再拼前缀', async () => {
    const wrapper = mount(BrowserToolCallCard, {
      props: {
        tool: buildTool({
          action: 'screenshot',
          output: JSON.stringify({
            url: 'https://example.com',
            screenshotDataUri: 'data:image/png;base64,BBBB',
          }),
        }),
      },
    })

    expect(wrapper.text()).toContain('已记录页面截图')
    expect(wrapper.find('img').exists()).toBe(false)

    await openEvidence(wrapper)

    const img = wrapper.find('img')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('data:image/png;base64,BBBB')
  })

  it('output 为对象（非字符串）时能直接读取字段', async () => {
    const wrapper = mount(BrowserToolCallCard, {
      props: {
        tool: buildTool({
          action: 'snapshot',
          output: {
            url: 'https://example.com',
            elements: [{ index: 0, tag: 'a', text: '首页' }],
          },
        }),
      },
    })

    expect(wrapper.text()).toContain('已识别 1 个可交互元素')
    expect(wrapper.text()).not.toContain('首页')

    await openEvidence(wrapper)

    expect(wrapper.text()).toContain('首页')
  })

  it('失败时显示 outputSummary 中的错误文本，处理建议按需展开', async () => {
    const wrapper = mount(BrowserToolCallCard, {
      props: {
        tool: buildTool({
          action: 'click',
          success: false,
          outputSummary: '选择器未匹配到任何元素',
        }),
      },
    })

    expect(wrapper.text()).toContain('选择器未匹配到任何元素')
    expect(wrapper.text()).toContain('失败')
    expect(wrapper.text()).toContain('处理')
    expect(wrapper.text()).not.toContain('处理建议')

    await openEvidence(wrapper)

    expect(wrapper.text()).toContain('处理建议')
  })

  it('失败时展示恢复动作并带着浏览器上下文抛出', async () => {
    const wrapper = mount(BrowserToolCallCard, {
      props: {
        tool: buildTool({
          toolId: 'browser.click',
          callId: 'call-browser-click-1',
          toolName: '浏览器点击',
          action: 'click',
          interrupted: true,
          success: false,
          failureCategory: 'BROWSER',
          inputSummary: '点击 #submit',
          outputSummary: '选择器未匹配到任何元素',
          outputDetail: 'Timeout waiting for selector #submit',
          artifactRefs: [
            {
              artifactId: 'artifact-browser-screenshot',
              kind: 'IMAGE',
              fileName: '失败截图.png',
              mimeType: 'image/png',
              size: 4096,
              downloadUrl: '/api/artifacts/artifact-browser-screenshot/download',
            },
          ],
          recoveryHint: '浏览器操作没有完成，可以检查页面状态、登录或元素选择后继续。',
          recoveryActions: [
            {
              id: 'resume',
              label: '检查页面后继续',
              mode: 'resume',
              category: 'BROWSER',
            },
          ],
        }),
      },
    })

    expect(wrapper.text()).toContain('检查页面后继续')
    expect(wrapper.find('button[aria-label="检查页面后继续：浏览器点击，点击 #submit，选择器未匹配到任何元素"]').exists()).toBe(true)

    await openEvidence(wrapper)

    expect(wrapper.text()).toContain('浏览器操作没有完成，可以检查页面状态、登录或元素选择后继续。')
    expect(wrapper.text()).toContain('检查浏览器页面状态、登录或元素选择')
    expect(wrapper.text()).toContain('保留当前上下文并从失败页面操作继续')
    expect(wrapper.text()).toContain('继续时保留')
    expect(wrapper.text()).toContain('带上原始输入')
    expect(wrapper.text()).toContain('带上失败输出')
    expect(wrapper.text()).toContain('保留产物 失败截图.png')
    expect(wrapper.text()).toContain('从中断位置接上')

    await wrapper.find('button[title="检查页面后继续"]').trigger('click')

    expect(wrapper.emitted('recover')?.[0]).toEqual([
      expect.objectContaining({
        toolId: 'browser.click',
        callId: 'call-browser-click-1',
        toolName: '浏览器点击',
        action: 'click',
        interrupted: true,
        category: 'BROWSER',
        inputSummary: '点击 #submit',
        outputSummary: '选择器未匹配到任何元素',
        outputDetail: 'Timeout waiting for selector #submit',
        artifactRefs: [
          expect.objectContaining({
            artifactId: 'artifact-browser-screenshot',
            fileName: '失败截图.png',
          }),
        ],
        recoveryHint: '浏览器操作没有完成，可以检查页面状态、登录或元素选择后继续。',
      }),
    ])
  })

  it('浏览器恢复动作会合并动作级产物和工具级产物', async () => {
    const wrapper = mount(BrowserToolCallCard, {
      props: {
        tool: buildTool({
          toolId: 'browser.click',
          toolName: '浏览器点击',
          action: 'click',
          success: false,
          outputSummary: '点击失败',
          artifactRefs: [
            {
              artifactId: 'artifact-browser-screenshot',
              kind: 'IMAGE',
              fileName: '失败截图.png',
              mimeType: 'image/png',
              size: 4096,
              downloadUrl: '/api/artifacts/artifact-browser-screenshot/download',
            },
          ],
          recoveryActions: [
            {
              id: 'resume',
              label: '检查页面后继续',
              mode: 'resume',
              category: 'BROWSER',
              artifactRefs: [
                {
                  artifactId: 'artifact-browser-dom',
                  kind: 'FILE',
                  fileName: '页面快照.json',
                  mimeType: 'application/json',
                  size: 2048,
                  downloadUrl: '/api/artifacts/artifact-browser-dom/download',
                },
              ],
            },
          ],
        }),
      },
    })

    await wrapper.find('button[title="检查页面后继续"]').trigger('click')

    expect(wrapper.emitted('recover')?.[0]?.[0]).toMatchObject({
      artifactRefs: [
        {
          artifactId: 'artifact-browser-dom',
          fileName: '页面快照.json',
        },
        {
          artifactId: 'artifact-browser-screenshot',
          fileName: '失败截图.png',
        },
      ],
    })
  })

  it('可隐藏恢复动作，保留失败摘要', () => {
    const wrapper = mount(BrowserToolCallCard, {
      props: {
        showRecoveryActions: false,
        tool: buildTool({
          toolId: 'browser.click',
          action: 'click',
          success: false,
          outputSummary: '选择器未匹配到任何元素',
          recoveryActions: [
            {
              id: 'resume',
              label: '检查页面后继续',
              mode: 'resume',
              category: 'BROWSER',
            },
          ],
        }),
      },
    })

    expect(wrapper.text()).toContain('选择器未匹配到任何元素')
    expect(wrapper.find('button[title="检查页面后继续"]').exists()).toBe(false)
  })
})
