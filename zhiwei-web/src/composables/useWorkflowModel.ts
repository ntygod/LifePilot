import { ref } from 'vue'
import type { Ref } from 'vue'
import { useDagLayout } from './useDagLayout'

// ========== 类型定义 ==========

/** 步骤类型 */
export type StepType =
  | 'skill'
  | 'tool'
  | 'llm'
  | 'condition'
  | 'loop'
  | 'parallel'
  | 'sub-workflow'
  | 'noop'
  | 'wait'
  | 'approval'
  | 'notify'

/** Skill 步骤配置 */
export interface SkillStepConfig {
  skillId: string
  params: Record<string, unknown>
}

/** 工具步骤配置 */
export interface ToolStepConfig {
  toolId: string
  params: Record<string, unknown>
}

/** LLM 步骤配置 */
export interface LlmMediaConfig {
  source: string
  mimeType?: string
  fileName?: string
}

export interface LlmStepConfig {
  scene: string
  capability: 'CHAT' | 'EMBEDDING' | 'STRUCTURED_OUTPUT' | 'FUNCTION_CALLING' | 'STREAMING' | 'VISION' | 'TTS' | 'STT'
  promptTemplate: string
  outputSchema?: string
  modelName?: string
  preferredProviderId?: string
  media: LlmMediaConfig[]
}

/** 条件分支步骤配置 */
export interface ConditionStepConfig {
  condition: string
  thenSteps: StepModel[]
  elseSteps: StepModel[]
}

/** 循环步骤配置 */
export interface LoopStepConfig {
  items: string
  loopVar: string
  body: StepModel[]
}

/** 并行步骤配置 */
export interface ParallelStepConfig {
  branches: StepModel[][]
}

/** 子工作流步骤配置 */
export interface SubWorkflowStepConfig {
  workflowId: string
  params: Record<string, unknown>
}

/** 等待步骤配置 */
export interface WaitStepConfig {
  durationSeconds: number
}

/** 审批步骤配置 */
export interface ApprovalStepConfig {
  message: string
  approvers: string[]
  timeoutSeconds: number
  autoApproveOnTimeout: boolean
}

/** 通知步骤配置 */
export interface NotifyStepConfig {
  targetUserId: string
  content: string
  contentType: 'TEXT' | 'MARKDOWN' | 'HTML'
  urgency: 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'
}

/** 空操作步骤配置 */
export interface NoopStepConfig {}

/** 步骤配置联合类型 */
export type StepConfig =
  | SkillStepConfig
  | ToolStepConfig
  | LlmStepConfig
  | ConditionStepConfig
  | LoopStepConfig
  | ParallelStepConfig
  | SubWorkflowStepConfig
  | WaitStepConfig
  | ApprovalStepConfig
  | NotifyStepConfig
  | NoopStepConfig

/** 错误策略模型 */
export interface ErrorStrategyModel {
  type: 'retry' | 'skip' | 'fail' | 'compensate'
  maxAttempts?: number
  initialDelayMs?: number
  maxDelayMs?: number
  reason?: string
  compensationStep?: StepModel
}

/** 触发器模型 */
export interface TriggerModel {
  type: 'cron' | 'event' | 'manual'
  cron?: string
  eventType?: string
}

/** 输入参数模型 */
export interface InputParamModel {
  name: string
  type: string
  required: boolean
  defaultValue?: string
  description?: string
}

/** 步骤模型 */
export interface StepModel {
  id: string
  name: string
  type: StepType
  config: StepConfig
  dependsOn: string[]
  errorStrategy: ErrorStrategyModel | null
}

/** 工作流模型 */
export interface WorkflowModel {
  id: string
  name: string
  description: string
  version: string
  triggers: TriggerModel[]
  inputs: InputParamModel[]
  steps: StepModel[]
  selectedStepId: string | null
  validationErrors: Map<string, string[]>
}

// ========== 步骤类型中文标签映射 ==========

const STEP_TYPE_LABELS: Record<StepType, string> = {
  'skill': 'Skill 步骤',
  'tool': '工具步骤',
  'llm': 'LLM 步骤',
  'condition': '条件分支',
  'loop': '循环步骤',
  'parallel': '并行步骤',
  'sub-workflow': '子工作流',
  'noop': '空操作',
  'wait': '等待步骤',
  'approval': '审批步骤',
  'notify': '通知步骤',
}

// ========== 默认配置工厂 ==========

/** 根据步骤类型创建默认配置 */
function createDefaultConfig(type: StepType): StepConfig {
  switch (type) {
    case 'skill':
      return { skillId: '', params: {} }
    case 'tool':
      return { toolId: '', params: {} }
    case 'llm':
      return {
        scene: '',
        capability: 'CHAT',
        promptTemplate: '',
        outputSchema: undefined,
        modelName: undefined,
        preferredProviderId: undefined,
        media: [],
      }
    case 'condition':
      return { condition: '', thenSteps: [], elseSteps: [] }
    case 'loop':
      return { items: '', loopVar: 'item', body: [] }
    case 'parallel':
      return { branches: [[]] }
    case 'sub-workflow':
      return { workflowId: '', params: {} }
    case 'noop':
      return {}
    case 'wait':
      return { durationSeconds: 60 }
    case 'approval':
      return { message: '', approvers: [], timeoutSeconds: 3600, autoApproveOnTimeout: false }
    case 'notify':
      return { targetUserId: '', content: '', contentType: 'TEXT', urgency: 'NORMAL' }
  }
}

/** 创建空的工作流模型 */
function createEmptyModel(): WorkflowModel {
  return {
    id: '',
    name: '',
    description: '',
    version: '1.0',
    triggers: [],
    inputs: [],
    steps: [],
    selectedStepId: null,
    validationErrors: new Map(),
  }
}

// ========== 验证逻辑 ==========

/** 验证单个步骤，返回错误消息列表 */
function validateStep(step: StepModel): string[] {
  const errors: string[] = []
  if (!step.id.trim()) errors.push('步骤 ID 不能为空')
  if (!step.name.trim()) errors.push('步骤名称不能为空')

  const config = step.config
  switch (step.type) {
    case 'skill': {
      const c = config as SkillStepConfig
      if (!c.skillId.trim()) errors.push('skillId 不能为空')
      break
    }
    case 'tool': {
      const c = config as ToolStepConfig
      if (!c.toolId.trim()) errors.push('toolId 不能为空')
      break
    }
    case 'llm': {
      const c = config as LlmStepConfig
      if (c.scene.trim().toLowerCase() === 'workflow') errors.push('scene 不能再使用 workflow，请填写真实任务意图')
      if (!c.capability.trim()) errors.push('capability 不能为空')
      if (!c.scene.trim()) errors.push('scene 不能为空')
      if (!c.promptTemplate.trim()) errors.push('promptTemplate 不能为空')
      break
    }
    case 'condition': {
      const c = config as ConditionStepConfig
      if (!c.condition.trim()) errors.push('condition 不能为空')
      break
    }
    case 'loop': {
      const c = config as LoopStepConfig
      if (!c.items.trim()) errors.push('items 不能为空')
      if (!c.loopVar.trim()) errors.push('loopVar 不能为空')
      break
    }
    case 'sub-workflow': {
      const c = config as SubWorkflowStepConfig
      if (!c.workflowId.trim()) errors.push('workflowId 不能为空')
      break
    }
    case 'wait': {
      const c = config as WaitStepConfig
      if (c.durationSeconds <= 0) errors.push('durationSeconds 必须大于 0')
      break
    }
    case 'approval': {
      const c = config as ApprovalStepConfig
      if (!c.message.trim()) errors.push('message 不能为空')
      break
    }
    case 'parallel':
    case 'noop':
      break
  }
  if (step.type === 'llm') {
    const c = config as LlmStepConfig
    if (c.media.some(item => !item.source.trim())) errors.push('media 中存在未填写 source 的条目')
    if (c.capability === 'VISION' && c.media.length === 0) errors.push('VISION 节点至少需要一个 media 输入')
    if (c.media.length > 0 && c.capability !== 'VISION') errors.push('配置了 media 的节点 capability 必须为 VISION')
  }
  return errors
}

// ========== Composable ==========

/**
 * 工作流编排器状态管理 composable。
 *
 * 维护工作流的完整编辑状态，提供步骤增删改查、依赖管理、验证等操作。
 */
export function useWorkflowModel(): {
  model: Ref<WorkflowModel>
  addStep: (type: StepType) => StepModel
  removeStep: (stepId: string) => void
  updateStep: (stepId: string, updates: Partial<StepModel>) => void
  selectStep: (stepId: string | null) => void
  addDependency: (fromStepId: string, toStepId: string) => boolean
  removeDependency: (fromStepId: string, toStepId: string) => void
  removeAllDependencies: (stepId: string) => void
  validate: () => boolean
  reset: () => void
  loadFromSteps: (data: WorkflowModel) => void
} {
  const model = ref<WorkflowModel>(createEmptyModel())

  // 步骤 ID 计数器，确保唯一性
  let stepCounter = 0

  /** 添加步骤，返回新创建的步骤 */
  function addStep(type: StepType): StepModel {
    stepCounter++
    const step: StepModel = {
      id: `${type}-${stepCounter}`,
      name: STEP_TYPE_LABELS[type],
      type,
      config: createDefaultConfig(type),
      dependsOn: [],
      errorStrategy: null,
    }
    model.value.steps.push(step)
    return step
  }

  /** 删除步骤，同时清理所有引用该步骤的 dependsOn */
  function removeStep(stepId: string): void {
    model.value.steps = model.value.steps.filter(s => s.id !== stepId)
    for (const step of model.value.steps) {
      step.dependsOn = step.dependsOn.filter(id => id !== stepId)
    }
    if (model.value.selectedStepId === stepId) {
      model.value.selectedStepId = null
    }
    model.value.validationErrors.delete(stepId)
  }

  /** 更新步骤字段 */
  function updateStep(stepId: string, updates: Partial<StepModel>): void {
    const step = model.value.steps.find(s => s.id === stepId)
    if (step) {
      Object.assign(step, updates)
    }
  }

  /** 设置当前选中步骤 */
  function selectStep(stepId: string | null): void {
    model.value.selectedStepId = stepId
  }

  const { wouldCreateCycle } = useDagLayout()

  /**
   * 添加依赖关系：toStepId 依赖 fromStepId。
   * 将 fromStepId 添加到 toStepId 的 dependsOn 列表中。
   * 返回 true 表示成功，false 表示步骤不存在或会产生环路。
   * 已存在的依赖不会重复添加（幂等性）。
   */
  function addDependency(fromStepId: string, toStepId: string): boolean {
    const toStep = model.value.steps.find(s => s.id === toStepId)
    const fromStep = model.value.steps.find(s => s.id === fromStepId)
    if (!toStep || !fromStep) return false
    // 幂等：已存在则直接返回 true
    if (toStep.dependsOn.includes(fromStepId)) return true
    // 环路检测：添加后会产生环路则拒绝
    if (wouldCreateCycle(model.value.steps, fromStepId, toStepId)) return false
    toStep.dependsOn.push(fromStepId)
    return true
  }

  /** 移除指定依赖关系 */
  function removeDependency(fromStepId: string, toStepId: string): void {
    const toStep = model.value.steps.find(s => s.id === toStepId)
    if (toStep) {
      toStep.dependsOn = toStep.dependsOn.filter(id => id !== fromStepId)
    }
  }

  /** 移除与指定步骤相关的所有依赖（入边和出边） */
  function removeAllDependencies(stepId: string): void {
    for (const step of model.value.steps) {
      if (step.id === stepId) {
        step.dependsOn = []
      } else {
        step.dependsOn = step.dependsOn.filter(id => id !== stepId)
      }
    }
  }

  /** 验证所有步骤，填充 validationErrors，返回是否全部通过 */
  function validate(): boolean {
    model.value.validationErrors.clear()
    let allValid = true
    for (const step of model.value.steps) {
      const errors = validateStep(step)
      if (errors.length > 0) {
        model.value.validationErrors.set(step.id, errors)
        allValid = false
      }
    }
    return allValid
  }

  /** 重置为空状态 */
  function reset(): void {
    model.value = createEmptyModel()
    stepCounter = 0
  }

  /** 从外部数据加载工作流模型 */
  function loadFromSteps(data: WorkflowModel): void {
    model.value = { ...data, validationErrors: new Map(data.validationErrors) }
    // 更新计数器，避免与已有步骤 ID 冲突
    let maxCounter = 0
    for (const step of data.steps) {
      const match = step.id.match(/-(\d+)$/)
      if (match) {
        const num = parseInt(match[1], 10)
        if (num > maxCounter) maxCounter = num
      }
    }
    stepCounter = maxCounter
  }

  return {
    model,
    addStep,
    removeStep,
    updateStep,
    selectStep,
    addDependency,
    removeDependency,
    removeAllDependencies,
    validate,
    reset,
    loadFromSteps,
  }
}
