import { describe, expect, it } from 'vitest'
import { normalizeTurnStatusText } from './turnPhase'

describe('turnPhase', () => {
  it('过滤内部意图和路由状态', () => {
    expect(normalizeTurnStatusText('意图识别中')).toBeNull()
    expect(normalizeTurnStatusText('router planning')).toBeNull()
    expect(normalizeTurnStatusText('知微正在判断')).toBeNull()
    expect(normalizeTurnStatusText('正在调用工具：搜索工具')).toBeNull()
    expect(normalizeTurnStatusText('调用核查')).toBeNull()
    expect(normalizeTurnStatusText('检查能力中')).toBeNull()
    expect(normalizeTurnStatusText('能力注册中')).toBeNull()
    expect(normalizeTurnStatusText('推理中')).toBeNull()
    expect(normalizeTurnStatusText('正在处理')).toBeNull()
    expect(normalizeTurnStatusText('正在回复')).toBeNull()
  })

  it('泛化准备状态保持安静', () => {
    expect(normalizeTurnStatusText('加载上下文中')).toBeNull()
    expect(normalizeTurnStatusText('preparing context')).toBeNull()
    expect(normalizeTurnStatusText('experience.match 后台增强中')).toBeNull()
    expect(normalizeTurnStatusText('Experience Matching')).toBeNull()
    expect(normalizeTurnStatusText('决策信号生成中')).toBeNull()
    expect(normalizeTurnStatusText('Decision Signal')).toBeNull()
    expect(normalizeTurnStatusText('上下文装配中')).toBeNull()
    expect(normalizeTurnStatusText('Context Assembly')).toBeNull()
    expect(normalizeTurnStatusText('Tool Search')).toBeNull()
    expect(normalizeTurnStatusText('Capability Check')).toBeNull()
    expect(normalizeTurnStatusText('Capability Registry')).toBeNull()
  })

  it('真实自然进展文本保留给消息气泡展示', () => {
    expect(normalizeTurnStatusText('正在整理记忆')).toBe('正在整理记忆')
    expect(normalizeTurnStatusText('正在查资料')).toBe('正在查资料')
    expect(normalizeTurnStatusText('正在执行验证')).toBe('正在执行验证')
  })
})
