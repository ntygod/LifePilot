import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
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

describe('BrowserToolCallCard 浏览器工具特化卡片', () => {
  it('snapshot action 渲染可交互元素列表和截图', () => {
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

    // 元素数量说明
    expect(wrapper.text()).toContain('已识别 2 个可交互元素')
    // 元素条目含索引和 tag
    expect(wrapper.text()).toContain('#0')
    expect(wrapper.text()).toContain('#1')
    expect(wrapper.text()).toContain('首页')
    expect(wrapper.text()).toContain('搜索')
    // 截图拼成 data-uri
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
    // 无截图字段，不应出现 img
    expect(wrapper.find('img').exists()).toBe(false)
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

  it('screenshotDataUri 字段原样使用不再拼前缀', () => {
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

    const img = wrapper.find('img')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('data:image/png;base64,BBBB')
  })

  it('output 为对象（非字符串）时能直接读取字段', () => {
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
    expect(wrapper.text()).toContain('首页')
  })

  it('失败时显示 outputSummary 中的错误文本', () => {
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
  })
})
