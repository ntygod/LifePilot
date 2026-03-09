/**
 * 轨迹回放相关工具函数
 */
import type { TraceStep } from '@/types'

/** 步骤类型 */
export type StepType = 'llm' | 'tool' | 'guardrail' | 'state'

/** 步骤类型对应的 Tailwind 背景色类名 */
export const STEP_COLORS: Record<StepType, string> = {
  llm: 'bg-blue-500',
  tool: 'bg-purple-500',
  guardrail: 'bg-amber-500',
  state: 'bg-slate-400',
}

/** 步骤类型中文标签 */
export const STEP_TYPE_LABELS: Record<StepType, string> = {
  llm: 'LLM 调用',
  tool: '工具调用',
  guardrail: '护栏检查',
  state: '阶段切换',
}

/**
 * 根据步骤属性推断步骤类型。
 *
 * 优先级：toolId → blocked/blockReason → phaseBefore !== phaseAfter → 默认 llm
 */
export function resolveStepType(step: TraceStep): StepType {
  if (step.toolId) return 'tool'
  if (step.blocked || step.blockReason) return 'guardrail'
  if (step.phaseBefore !== step.phaseAfter) return 'state'
  return 'llm'
}

/**
 * 计算步骤耗时条的宽度百分比字符串。
 *
 * 最小 1% 保证可见；totalMs <= 0 时返回 '0%'。
 */
export function calcBarWidth(stepMs: number, totalMs: number): string {
  if (totalMs <= 0) return '0%'
  const pct = Math.max(1, (stepMs / totalMs) * 100)
  return `${pct.toFixed(1)}%`
}
