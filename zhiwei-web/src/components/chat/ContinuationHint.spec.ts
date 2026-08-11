import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ContinuationHint from './ContinuationHint.vue'

describe('ContinuationHint 续接提示', () => {
  it('轻量展示最多两条续接计划', () => {
    const wrapper = mount(ContinuationHint, {
      props: {
        title: 'Shell 执行没有完成',
        detail: '命令失败，可以修正后继续。',
        checkpointLabel: '执行命令 · Shell 执行',
        checkpointDetail: '测试失败',
        retainedContext: ['带上原始输入', '带上失败输出', '工作目录 News', '保留产物 report.md'],
        nextActions: ['查看命令输出', '修正失败断言', '补跑相关测试'],
      },
    })

    expect(wrapper.text()).toContain('Shell 执行没有完成')
    expect(wrapper.text()).toContain('命令失败，可以修正后继续。')
    expect(wrapper.text()).toContain('执行命令 · Shell 执行')
    expect(wrapper.text()).toContain('测试失败')
    expect(wrapper.find('[aria-label="续接断点"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="继续时保留"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('继续时保留')
    expect(wrapper.text()).toContain('带上原始输入')
    expect(wrapper.text()).toContain('带上失败输出')
    expect(wrapper.text()).toContain('工作目录 News')
    expect(wrapper.text()).not.toContain('保留产物 report.md')
    expect(wrapper.text()).toContain('查看命令输出')
    expect(wrapper.text()).toContain('修正失败断言')
    expect(wrapper.text()).not.toContain('补跑相关测试')
    expect(wrapper.find('[aria-label="续接计划"]').exists()).toBe(true)
  })

  it('等待用户补充时只展示状态和输入续接提示', () => {
    const wrapper = mount(ContinuationHint, {
      props: {
        title: '等待你补充信息',
        detail: '缺少仓库地址。',
        inputHint: '在输入框补充，发送后会自动续接。',
        canResume: false,
        statusLabel: '等你补充',
      },
    })

    expect(wrapper.text()).toContain('缺少仓库地址。')
    expect(wrapper.text()).toContain('在输入框补充，发送后会自动续接。')
    expect(wrapper.find('[aria-label="输入续接提示"]').exists()).toBe(true)
    expect(wrapper.find('.continuation-hint__resume').exists()).toBe(false)
    expect(wrapper.find('.continuation-hint__status').text()).toContain('等你补充')
  })

  it('能力修复入口会作为轻量动作抛给上层', async () => {
    const wrapper = mount(ContinuationHint, {
      props: {
        title: '能力需要修复',
        detail: '依赖的工具或技能当前不可用，可以修复后继续。',
        repairLabel: '能力中心',
        repairTitle: '打开能力中心，检查工具、技能状态和 Skill 引用',
        actionLabel: '修复能力后继续',
        canResume: true,
      },
    })

    const repair = wrapper.find('button[aria-label="打开能力中心，检查工具、技能状态和 Skill 引用"]')
    expect(repair.exists()).toBe(true)
    expect(repair.text()).toContain('能力中心')
    expect(wrapper.find('.continuation-hint__resume').text()).toContain('修复能力后继续')

    await repair.trigger('click')

    expect(wrapper.emitted('repair')).toHaveLength(1)
  })
})
