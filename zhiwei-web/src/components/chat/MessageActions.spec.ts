import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import MessageActions from './MessageActions.vue'
import type { Message, ToolRecoveryAction } from '@/types'
import { copyToClipboard } from '@/utils/clipboard'

vi.mock('@/utils/clipboard', () => ({
  copyToClipboard: vi.fn().mockResolvedValue(true),
}))

const memorySettleTitle = '放入输入框，发送后后台整理为记忆'

describe('MessageActions 消息级恢复入口', () => {
  it('复制按钮只在本组件写剪贴板，并把结果上报给上层提示', async () => {
    const message: Message = {
      id: 'assistant-copy',
      role: 'assistant',
      content: '这段回复需要复制。',
      timestamp: Date.now(),
    } as any

    const wrapper = mount(MessageActions, {
      props: {
        message,
        isLastAssistant: false,
      },
    })

    await wrapper.find('button[title="复制"]').trigger('click')

    expect(copyToClipboard).toHaveBeenCalledTimes(1)
    expect(copyToClipboard).toHaveBeenCalledWith('这段回复需要复制。')
    expect(wrapper.emitted('copy')?.[0]).toEqual(['这段回复需要复制。', true])
  })

  it('把记住和存资料收进沉淀入口，点击后保持可操作', async () => {
    const message: Message = {
      id: 'assistant-settle',
      role: 'assistant',
      content: '用户希望沉淀入口轻一点。',
      timestamp: Date.now(),
    } as any

    const wrapper = mount(MessageActions, {
      props: {
        message,
        isLastAssistant: false,
        knowledgeBaseId: 'kb-product',
        knowledgeBaseName: '产品资料',
      },
    })

    const trigger = wrapper.find('button[title="沉淀这条消息"]')
    expect(trigger.exists()).toBe(true)
    expect(trigger.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('.settle-actions__items').attributes('aria-hidden')).toBe('true')
    expect(wrapper.find(`button[title="${memorySettleTitle}"]`).attributes('tabindex')).toBe('-1')
    expect(wrapper.text()).not.toContain('记住这条消息')

    await trigger.trigger('click')

    expect(trigger.attributes('aria-expanded')).toBe('true')
    expect(wrapper.find('.settle-actions__items').attributes('aria-hidden')).toBe('false')
    expect(wrapper.find(`button[title="${memorySettleTitle}"]`).exists()).toBe(true)
    expect(wrapper.find(`button[title="${memorySettleTitle}"]`).attributes('aria-label')).toBe(memorySettleTitle)
    expect(wrapper.find(`button[title="${memorySettleTitle}"]`).attributes('tabindex')).toBe('0')
    expect(wrapper.find('button[title="存为资料：产品资料"]').exists()).toBe(true)
  })

  it('折叠时不会误触发隐藏的沉淀动作', async () => {
    const message: Message = {
      id: 'assistant-collapsed-settle',
      role: 'assistant',
      content: '这条消息可以沉淀，但需要先展开入口。',
      timestamp: Date.now(),
    } as any

    const wrapper = mount(MessageActions, {
      props: {
        message,
        isLastAssistant: false,
        knowledgeBaseId: 'kb-product',
        knowledgeBaseName: '产品资料',
      },
    })

    await wrapper.find(`button[title="${memorySettleTitle}"]`).trigger('click')
    await wrapper.find('button[title="存为资料：产品资料"]').trigger('click')

    expect(wrapper.emitted('remember')).toBeUndefined()
    expect(wrapper.emitted('save-knowledge')).toBeUndefined()
  })

  it('记住按钮会把消息交给上层确认沉淀', async () => {
    const message: Message = {
      id: 'assistant-remember',
      role: 'assistant',
      content: '用户希望主界面保持轻量。',
      timestamp: Date.now(),
    } as any

    const wrapper = mount(MessageActions, {
      props: {
        message,
        isLastAssistant: false,
      },
    })

    await wrapper.find(`button[title="${memorySettleTitle}"]`).trigger('click')

    expect(wrapper.emitted('remember')?.[0]).toEqual([message])
  })

  it('有明确资料库目标时可以把助手回复交给上层存为资料', async () => {
    const message: Message = {
      id: 'assistant-save-knowledge',
      role: 'assistant',
      content: '这是一份可沉淀的结论。',
      timestamp: Date.now(),
    } as any

    const wrapper = mount(MessageActions, {
      props: {
        message,
        isLastAssistant: false,
        knowledgeBaseId: 'kb-product',
        knowledgeBaseName: '产品资料',
      },
    })

    await wrapper.find('button[title="沉淀这条消息"]').trigger('click')
    await wrapper.find('button[title="存为资料：产品资料"]').trigger('click')

    expect(wrapper.emitted('save-knowledge')?.[0]).toEqual([message])
  })

  it('没有明确资料库目标时只浮现可执行的记住入口', async () => {
    const message: Message = {
      id: 'assistant-save-knowledge-without-target',
      role: 'assistant',
      content: '这是一份值得沉淀的回复。',
      timestamp: Date.now(),
    } as any

    const wrapper = mount(MessageActions, {
      props: {
        message,
        isLastAssistant: false,
      },
    })

    expect(wrapper.find('button[title="存为资料：先选择资料库"]').exists()).toBe(false)
    expect(wrapper.find('button[title="沉淀这条消息"]').exists()).toBe(false)

    await wrapper.find(`button[title="${memorySettleTitle}"]`).trigger('click')

    expect(wrapper.emitted('remember')?.[0]).toEqual([message])
    expect(wrapper.emitted('save-knowledge')).toBeUndefined()
  })

  it('已存资料后沉淀入口留下完成态，并阻止重复保存', async () => {
    const message: Message = {
      id: 'assistant-saved-knowledge',
      role: 'assistant',
      content: '这份结论已经存进资料库。',
      timestamp: Date.now(),
    } as any

    const wrapper = mount(MessageActions, {
      props: {
        message,
        isLastAssistant: false,
        knowledgeBaseId: 'kb-product',
        knowledgeBaseName: '产品资料',
        savedKnowledgeBaseName: '产品资料',
      },
    })

    const trigger = wrapper.find('button[title="已存为资料：产品资料"]')
    expect(trigger.exists()).toBe(true)
    expect(trigger.attributes('aria-expanded')).toBe('false')

    await trigger.trigger('click')

    const savedButtons = wrapper.findAll('button[title="已存为资料：产品资料"]')
    expect(savedButtons).toHaveLength(2)
    expect(savedButtons[1].attributes('disabled')).toBeDefined()

    await savedButtons[1].trigger('click')

    expect(wrapper.emitted('save-knowledge')).toBeUndefined()
  })

  it('继续和重新开始按钮会携带结构化恢复动作', async () => {
    const message: Message = {
      id: 'assistant-actions-recovery',
      role: 'assistant',
      content: '测试失败，可以继续。',
      timestamp: Date.now(),
      turnStatus: 'DEGRADED',
    } as any
    const resumeAction: ToolRecoveryAction = {
      id: 'resume',
      label: '修正后继续',
      description: '保留已完成步骤，修正命令或代码错误后继续验证。',
      mode: 'resume',
      toolId: 'shell.exec',
      toolName: 'Shell 执行',
      category: 'COMMAND',
      outputSummary: '测试失败',
      nextActions: ['查看命令输出并修正报错原因', '从失败命令后继续执行验证'],
    }
    const restartAction: ToolRecoveryAction = {
      id: 'restart',
      label: '重新开始',
      description: '保留失败输出，重新开始并优先修正命令错误。',
      mode: 'restart',
      toolId: 'shell.exec',
      toolName: 'Shell 执行',
      category: 'COMMAND',
      outputSummary: '测试失败',
      nextActions: ['保留失败命令输出作为线索', '重新开始这一轮并优先修正命令错误'],
    }

    const wrapper = mount(MessageActions, {
      props: {
        message,
        isLastAssistant: true,
        showRecoveryActions: true,
        recoveryResumeAction: resumeAction,
        recoveryRestartAction: restartAction,
      },
    })

    await wrapper.find('button[title="继续执行：保留已完成步骤，修正命令或代码错误后继续验证。"]').trigger('click')
    await wrapper.find('button[title="重新开始：保留失败输出，重新开始并优先修正命令错误。"]').trigger('click')

    expect(wrapper.emitted('resume')?.[0]).toEqual([message, resumeAction])
    expect(wrapper.emitted('restart')?.[0]).toEqual([message, restartAction])
  })

  it('仅恢复摘要标记手动可恢复时也露出恢复动作', async () => {
    const message: Message = {
      id: 'assistant-actions-recovery-summary-only',
      role: 'assistant',
      content: '技能执行没有完成，可以检查后继续。',
      timestamp: Date.now(),
      taskRecovery: {
        status: 'DEGRADED',
        title: '技能 research-assistant 没有完成',
        detail: '技能执行没有完成，可以检查输入、依赖或技能步骤后继续。',
        actionLabel: '检查技能后继续',
        canResume: true,
        canRestart: true,
        resumeMode: 'manual',
      },
    } as any
    const resumeAction: ToolRecoveryAction = {
      id: 'resume-skill',
      label: '检查技能后继续',
      description: '保留当前进度并从失败技能步骤继续。',
      mode: 'resume',
      toolId: 'skill.run',
      toolName: '执行 Skill',
      category: 'SKILL',
    }
    const restartAction: ToolRecoveryAction = {
      id: 'restart-skill',
      label: '重新开始',
      description: '重新开始并优先检查技能输入。',
      mode: 'restart',
      toolId: 'skill.run',
      toolName: '执行 Skill',
      category: 'SKILL',
    }

    const wrapper = mount(MessageActions, {
      props: {
        message,
        isLastAssistant: true,
        showRecoveryActions: true,
        recoveryResumeAction: resumeAction,
        recoveryRestartAction: restartAction,
      },
    })

    const resumeButton = wrapper.find('button[title="继续执行：保留当前进度并从失败技能步骤继续。"]')
    const restartButton = wrapper.find('button[title="重新开始：重新开始并优先检查技能输入。"]')
    expect(resumeButton.exists()).toBe(true)
    expect(restartButton.exists()).toBe(true)

    await resumeButton.trigger('click')
    await restartButton.trigger('click')

    expect(wrapper.emitted('resume')?.[0]).toEqual([message, resumeAction])
    expect(wrapper.emitted('restart')?.[0]).toEqual([message, restartAction])
  })

  it('等待用户补充时不露出普通继续和重新开始入口', () => {
    const message: Message = {
      id: 'assistant-await-user-actions',
      role: 'assistant',
      content: '请补充仓库地址。',
      timestamp: Date.now(),
      turnStatus: 'SUSPENDED',
      completionMode: 'SUSPENDED',
      suspendReasonSourceId: '__await_user_input__',
      taskRecovery: {
        status: 'SUSPENDED',
        title: '等待你补充信息',
        detail: '你直接回复补充内容，知微会接着当前进度继续。',
        canResume: false,
        canRestart: true,
        resumeMode: 'user_reply',
      },
    } as any

    const wrapper = mount(MessageActions, {
      props: {
        message,
        isLastAssistant: true,
        showRecoveryActions: true,
      },
    })

    expect(wrapper.find('button[title^="继续执行"]').exists()).toBe(false)
    expect(wrapper.find('button[title^="重新开始"]').exists()).toBe(false)
    expect(wrapper.find('button[title="重新生成"]').exists()).toBe(true)
  })

  it('仅恢复摘要标记等待用户补充时不露出普通恢复入口', () => {
    const message: Message = {
      id: 'assistant-await-user-recovery-only-actions',
      role: 'assistant',
      content: '还缺发布渠道。',
      timestamp: Date.now(),
      taskRecovery: {
        status: 'SUSPENDED',
        title: '等待你补充信息',
        detail: '补充发布渠道后继续。',
        canResume: true,
        canRestart: true,
        reasonSourceId: '__await_user_input__',
      },
    } as any

    const wrapper = mount(MessageActions, {
      props: {
        message,
        isLastAssistant: true,
        showRecoveryActions: true,
      },
    })

    expect(wrapper.find('button[title^="继续执行"]').exists()).toBe(false)
    expect(wrapper.find('button[title^="重新开始"]').exists()).toBe(false)
    expect(wrapper.find('button[title="重新生成"]').exists()).toBe(true)
  })
})
