import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import CapabilityHintStrip from './CapabilityHintStrip.vue'

describe('CapabilityHintStrip', () => {
  it('没有后端能力建议时不渲染入口', async () => {
    const wrapper = mount(CapabilityHintStrip)

    expect(wrapper.text()).toBe('')
    expect(wrapper.findAll('button')).toHaveLength(0)
  })

  it('有后端能力建议时展示轻量只读状态', () => {
    const wrapper = mount(CapabilityHintStrip, {
      props: {
        activeCapabilities: [
          { id: 'git.query', label: '查看仓库', reason: '检测到 Git 查询或差异意图', kind: 'tool' },
          { id: 'research-assistant', label: '调研策略', reason: '检测到多源调研或事实核查', kind: 'skill' },
          { id: 'code', label: '运行代码', reason: '检测到计算、脚本或数据分析需求' },
        ],
      },
    })

    expect(wrapper.text()).toContain('知微正在调用能力')
    expect(wrapper.text()).toContain('查看仓库')
    expect(wrapper.text()).toContain('调研策略')
    expect(wrapper.text()).toContain('运行代码')
    expect(wrapper.find('.capability-hints__active-item--skill').exists()).toBe(true)
    expect(wrapper.findAll('button')).toHaveLength(0)
  })
})
