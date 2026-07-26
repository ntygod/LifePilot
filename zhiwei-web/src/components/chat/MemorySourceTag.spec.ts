import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import MemorySourceTag from './MemorySourceTag.vue'
import type { SourceSummary } from '@/types'

describe('MemorySourceTag', () => {
  it('点击后发出 inspect 事件', async () => {
    const source: SourceSummary = {
      type: 'memory',
      id: 'memory-1',
      name: '主界面偏好',
      extra: {
        entityTypeLabel: '偏好',
        description: '用户希望主界面保持轻量。',
      },
    }

    const wrapper = mount(MemorySourceTag, {
      props: {
        source,
      },
    })
    const button = wrapper.find('button')

    expect(wrapper.find('[data-test="memory-source-open-icon"]').exists()).toBe(true)
    expect(button.attributes('title')).toContain('点击查看和编辑这条记忆')

    await button.trigger('click')

    expect(wrapper.emitted('inspect')?.[0]).toEqual([source])
  })

  it('引用记忆时直接露出轻量原因', () => {
    const source: SourceSummary = {
      type: 'memory',
      id: 'memory-1',
      name: '主界面偏好',
      extra: {
        entityTypeLabel: '偏好',
        usageReason: '这条回答参考了你希望主界面保持轻量的偏好。',
        usageImpact: '会影响语气、方案取舍和界面建议；长期生效；优先级较高',
        description: '用户希望主界面保持轻量。',
      },
    }

    const wrapper = mount(MemorySourceTag, {
      props: {
        source,
      },
    })
    const button = wrapper.find('button')

    expect(button.text()).toContain('因「这条回答参考了你希望主界面保持轻量的偏…」')
    expect(button.text()).toContain('会影响语气、方案取舍')
    expect(button.attributes('title')).toContain('为什么出现：这条回答参考了你希望主界面保持轻量的偏好。')
    expect(button.attributes('title')).toContain('后续影响：会影响语气、方案取舍和界面建议；长期生效；优先级较高')
    expect(button.attributes('aria-label')).toBe(button.attributes('title'))
  })

  it('本轮沉淀记忆会在轻提示里说明依据和可信度', () => {
    const source: SourceSummary = {
      type: 'memory',
      id: 'memory-1',
      name: '主界面偏好',
      extra: {
        operationLabel: '新增',
        entityType: 'PREFERENCE',
        entityTypeLabel: '偏好',
        evidenceExcerpt: '主界面做轻，做好交互',
        sourceKindLabel: '项目上下文',
        temporality: 'PERSISTENT',
        importanceScore: 0.91,
        trustLevel: 'EXPLICIT',
        trustScore: 0.91,
      },
    }

    const wrapper = mount(MemorySourceTag, {
      props: {
        source,
        variant: 'change',
      },
    })
    const button = wrapper.find('button')

    expect(button.text()).toContain('新增 · 偏好')
    expect(button.text()).toContain('因「主界面做轻，做好交互」')
    expect(button.text()).toContain('会影响语气、方案取舍')
    expect(button.attributes('title')).toContain('为什么出现：因为你本轮提到「主界面做轻，做好交互」，知微已在项目上下文新增这条偏好。')
    expect(button.attributes('title')).toContain('后续影响：会影响语气、方案取舍和界面建议；长期生效；优先级较高')
    expect(button.attributes('title')).toContain('上下文：项目上下文')
    expect(button.attributes('title')).toContain('依据：主界面做轻，做好交互')
    expect(button.attributes('title')).toContain('可信度：用户明确 · 91%')
    expect(button.attributes('aria-label')).toBe(button.attributes('title'))
    expect(button.classes()).toContain('memory-source-tag--change')
  })

  it('本轮忘记记忆时不再提示编辑已移除的记忆', () => {
    const source: SourceSummary = {
      type: 'memory',
      id: 'memory-1',
      name: '旧提醒偏好',
      extra: {
        operation: 'DELETE',
        operationLabel: '忘记',
        entityType: 'PREFERENCE',
        entityTypeLabel: '偏好',
        evidenceExcerpt: '以后不要再提醒我下午5点检查日志',
      },
    }

    const wrapper = mount(MemorySourceTag, {
      props: {
        source,
        variant: 'change',
      },
    })
    const button = wrapper.find('button')

    expect(button.text()).toContain('忘记 · 偏好')
    expect(button.text()).toContain('因「以后不要再提醒我下午5点检查日志」')
    expect(button.classes()).toContain('memory-source-tag--deleted')
    expect(wrapper.find('[data-test="memory-source-state-icon"]').exists()).toBe(true)
    expect(button.attributes('title')).toContain('为什么出现：因为你本轮提到「以后不要再提醒我下午5点检查日志」，知微已忘记这条偏好。')
    expect(button.attributes('title')).toContain('后续影响：后续不再默认参考这条记忆；需要时可以重新告诉知微要记住什么')
    expect(button.attributes('title')).toContain('点击查看这条记忆为什么被忘记')
    expect(button.attributes('title')).not.toContain('点击查看和编辑这条记忆')
  })

  it('技能型来源的轻提示不再固定写成记忆', () => {
    const source: SourceSummary = {
      type: 'memory',
      id: 'skill-backend',
      name: '后端开发',
      extra: {
        entityType: 'SKILL',
        entityTypeLabel: '技能',
        usageReason: '这条回答参考了你的后端开发技能。',
      },
    }

    const wrapper = mount(MemorySourceTag, {
      props: {
        source,
      },
    })
    const button = wrapper.find('button')

    expect(button.text()).toContain('技能')
    expect(button.attributes('title')).toContain('点击查看和编辑这条技能')
    expect(button.attributes('title')).not.toContain('点击查看和编辑这条记忆')
  })
})
