/**
 * 步骤类型元数据常量。
 * 定义 10 种步骤类型的图标、中文名称和描述，供 StepPalette、EditorCanvas、StepDetailCard 等组件复用。
 */
import type { Component } from 'vue'
import type { StepType } from '@/composables/useWorkflowModel'
import {
  Zap,
  Wrench,
  Bot,
  GitBranch,
  Repeat,
  GitMerge,
  Workflow,
  Circle,
  Clock,
  ShieldCheck,
} from 'lucide-vue-next'

/** 步骤类型元数据 */
export interface StepTypeMeta {
  /** 图标组件（lucide-vue-next） */
  icon: Component
  /** 中文名称 */
  label: string
  /** 中文描述 */
  description: string
}

/** 10 种步骤类型的元数据映射 */
export const STEP_TYPE_META: Record<StepType, StepTypeMeta> = {
  'skill':        { icon: Zap,         label: 'Skill 步骤', description: '调用已注册的 Skill' },
  'tool':         { icon: Wrench,      label: '工具步骤',   description: '调用已注册的 Tool' },
  'llm':          { icon: Bot,         label: 'LLM 步骤',  description: '调用 LLM 生成内容' },
  'condition':    { icon: GitBranch,   label: '条件分支',   description: '根据条件选择分支' },
  'loop':         { icon: Repeat,      label: '循环步骤',   description: '遍历集合执行步骤' },
  'parallel':     { icon: GitMerge,    label: '并行步骤',   description: '并发执行多个分支' },
  'sub-workflow': { icon: Workflow,    label: '子工作流',   description: '调用另一个工作流' },
  'noop':         { icon: Circle,      label: '空操作',     description: '占位，不执行操作' },
  'wait':         { icon: Clock,       label: '等待步骤',   description: '等待指定时长' },
  'approval':     { icon: ShieldCheck, label: '审批步骤',   description: '暂停等待人工审批' },
}
