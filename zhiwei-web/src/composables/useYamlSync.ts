import * as yaml from 'yaml'
import type {
  WorkflowModel,
  StepModel,
  StepType,
  StepConfig,
  ErrorStrategyModel,
  TriggerModel,
  InputParamModel,
  SkillStepConfig,
  ToolStepConfig,
  LlmStepConfig,
  ConditionStepConfig,
  LoopStepConfig,
  ParallelStepConfig,
  SubWorkflowStepConfig,
  WaitStepConfig,
  ApprovalStepConfig,
} from './useWorkflowModel'

// ========== 类型定义 ==========

/** 反序列化结果：成功或失败 */
export type DeserializeResult =
  | { ok: true; model: WorkflowModel }
  | { ok: false; error: string }

// ========== 序列化：WorkflowModel → YAML 纯对象 ==========

/** 将错误策略模型转换为 YAML 纯对象 */
function serializeErrorStrategy(strategy: ErrorStrategyModel): Record<string, unknown> {
  const obj: Record<string, unknown> = { type: strategy.type }
  switch (strategy.type) {
    case 'retry':
      if (strategy.maxAttempts !== undefined) obj.maxAttempts = strategy.maxAttempts
      if (strategy.initialDelayMs !== undefined) obj.initialDelayMs = strategy.initialDelayMs
      if (strategy.maxDelayMs !== undefined) obj.maxDelayMs = strategy.maxDelayMs
      break
    case 'skip':
      if (strategy.reason !== undefined) obj.reason = strategy.reason
      break
    case 'compensate':
      if (strategy.compensationStep) {
        obj.compensationStep = serializeStep(strategy.compensationStep)
      }
      break
    // fail 无额外字段
  }
  return obj
}

/**
 * 将 StepModel 序列化为 YAML 纯对象。
 * 关键映射：promptTemplate → prompt, thenSteps → then, elseSteps → else
 * 配置字段平铺到步骤对象上（不嵌套在 config 下）。
 */
function serializeStep(step: StepModel): Record<string, unknown> {
  const obj: Record<string, unknown> = {
    id: step.id,
    name: step.name,
    type: step.type,
  }

  // dependsOn 非空时才输出
  if (step.dependsOn.length > 0) {
    obj.dependsOn = [...step.dependsOn]
  }

  // 错误策略非空时才输出
  if (step.errorStrategy) {
    obj.errorStrategy = serializeErrorStrategy(step.errorStrategy)
  }

  // 按步骤类型平铺配置字段
  const config = step.config
  switch (step.type) {
    case 'skill': {
      const c = config as SkillStepConfig
      obj.skillId = c.skillId
      if (Object.keys(c.params).length > 0) obj.params = { ...c.params }
      break
    }
    case 'tool': {
      const c = config as ToolStepConfig
      obj.toolId = c.toolId
      if (Object.keys(c.params).length > 0) obj.params = { ...c.params }
      break
    }
    case 'llm': {
      const c = config as LlmStepConfig
      obj.scene = c.scene
      // 关键映射：前端 promptTemplate → YAML prompt
      obj.prompt = c.promptTemplate
      if (c.outputSchema) obj.outputSchema = c.outputSchema
      break
    }
    case 'condition': {
      const c = config as ConditionStepConfig
      obj.condition = c.condition
      // 关键映射：前端 thenSteps → YAML then, elseSteps → else
      obj.then = c.thenSteps.map(serializeStep)
      obj.else = c.elseSteps.map(serializeStep)
      break
    }
    case 'loop': {
      const c = config as LoopStepConfig
      obj.items = c.items
      obj.loopVar = c.loopVar
      obj.body = c.body.map(serializeStep)
      break
    }
    case 'parallel': {
      const c = config as ParallelStepConfig
      obj.branches = c.branches.map(branch => branch.map(serializeStep))
      break
    }
    case 'sub-workflow': {
      const c = config as SubWorkflowStepConfig
      obj.workflowId = c.workflowId
      if (Object.keys(c.params).length > 0) obj.params = { ...c.params }
      break
    }
    case 'wait': {
      const c = config as WaitStepConfig
      obj.durationSeconds = c.durationSeconds
      break
    }
    case 'approval': {
      const c = config as ApprovalStepConfig
      obj.message = c.message
      if (c.approvers.length > 0) obj.approvers = [...c.approvers]
      obj.timeoutSeconds = c.timeoutSeconds
      obj.autoApproveOnTimeout = c.autoApproveOnTimeout
      break
    }
    case 'noop':
      // 无额外字段
      break
  }

  return obj
}

/**
 * 将触发器模型序列化为 YAML 纯对象。
 */
function serializeTrigger(trigger: TriggerModel): Record<string, unknown> {
  const obj: Record<string, unknown> = { type: trigger.type }
  if (trigger.type === 'cron' && trigger.cron) {
    obj.cron = trigger.cron
  }
  if (trigger.type === 'event' && trigger.eventType) {
    obj.eventType = trigger.eventType
  }
  return obj
}

/**
 * 将输入参数数组序列化为 YAML map 格式（参数名为 key）。
 */
function serializeInputs(inputs: InputParamModel[]): Record<string, Record<string, unknown>> {
  const result: Record<string, Record<string, unknown>> = {}
  for (const input of inputs) {
    const param: Record<string, unknown> = {
      type: input.type,
      required: input.required,
    }
    if (input.defaultValue !== undefined && input.defaultValue !== '') {
      param.defaultValue = input.defaultValue
    }
    if (input.description) {
      param.description = input.description
    }
    result[input.name] = param
  }
  return result
}


/**
 * 将 YAML 纯对象反序列化为 StepModel。
 * 关键映射：prompt → promptTemplate, then → thenSteps, else → elseSteps
 */
function deserializeStep(obj: Record<string, unknown>): StepModel {
  const id = String(obj.id ?? '')
  const name = String(obj.name ?? '')
  const type = String(obj.type ?? '') as StepType

  // 解析 dependsOn
  const dependsOn: string[] = []
  if (Array.isArray(obj.dependsOn)) {
    for (const dep of obj.dependsOn) {
      if (dep != null) dependsOn.push(String(dep))
    }
  } else if (obj.dependsOn != null) {
    dependsOn.push(String(obj.dependsOn))
  }

  // 解析错误策略
  const errorStrategy = deserializeErrorStrategy(obj.errorStrategy)

  // 按步骤类型构建配置
  const config = deserializeConfig(type, obj)

  return { id, name, type, config, dependsOn, errorStrategy }
}

/**
 * 根据步骤类型从 YAML 纯对象中提取配置。
 */
function deserializeConfig(type: StepType, obj: Record<string, unknown>): StepConfig {
  switch (type) {
    case 'skill':
      return {
        skillId: String(obj.skillId ?? ''),
        params: toStringRecord(obj.params),
      }
    case 'tool':
      return {
        toolId: String(obj.toolId ?? ''),
        params: toStringRecord(obj.params),
      }
    case 'llm':
      return {
        scene: String(obj.scene ?? ''),
        // 关键映射：YAML prompt → 前端 promptTemplate
        promptTemplate: String(obj.prompt ?? ''),
        outputSchema: obj.outputSchema != null ? String(obj.outputSchema) : undefined,
      }
    case 'condition':
      return {
        condition: String(obj.condition ?? ''),
        // 关键映射：YAML then → 前端 thenSteps, else → elseSteps
        thenSteps: deserializeStepList(obj.then),
        elseSteps: deserializeStepList(obj.else),
      }
    case 'loop':
      return {
        items: String(obj.items ?? ''),
        loopVar: String(obj.loopVar ?? ''),
        body: deserializeStepList(obj.body),
      }
    case 'parallel':
      return {
        branches: deserializeBranches(obj.branches),
      }
    case 'sub-workflow':
      return {
        workflowId: String(obj.workflowId ?? ''),
        params: toStringRecord(obj.params),
      }
    case 'wait':
      return {
        durationSeconds: toNumber(obj.durationSeconds, 0),
      }
    case 'approval':
      return {
        message: String(obj.message ?? ''),
        approvers: toStringArray(obj.approvers),
        timeoutSeconds: toNumber(obj.timeoutSeconds, 3600),
        autoApproveOnTimeout: Boolean(obj.autoApproveOnTimeout ?? false),
      }
    case 'noop':
    default:
      return {}
  }
}

/** 反序列化错误策略 */
function deserializeErrorStrategy(obj: unknown): ErrorStrategyModel | null {
  if (obj == null || typeof obj !== 'object') return null
  const map = obj as Record<string, unknown>
  const type = String(map.type ?? '')
  if (!['retry', 'skip', 'fail', 'compensate'].includes(type)) return null

  const strategy: ErrorStrategyModel = { type: type as ErrorStrategyModel['type'] }
  switch (type) {
    case 'retry':
      if (map.maxAttempts !== undefined) strategy.maxAttempts = toNumber(map.maxAttempts, 3)
      if (map.initialDelayMs !== undefined) strategy.initialDelayMs = toNumber(map.initialDelayMs, 500)
      if (map.maxDelayMs !== undefined) strategy.maxDelayMs = toNumber(map.maxDelayMs, 5000)
      break
    case 'skip':
      if (map.reason !== undefined) strategy.reason = String(map.reason)
      break
    case 'compensate':
      if (map.compensationStep != null && typeof map.compensationStep === 'object') {
        strategy.compensationStep = deserializeStep(map.compensationStep as Record<string, unknown>)
      }
      break
  }
  return strategy
}

/** 反序列化步骤列表 */
function deserializeStepList(obj: unknown): StepModel[] {
  if (!Array.isArray(obj)) return []
  return obj
    .filter((item): item is Record<string, unknown> => item != null && typeof item === 'object')
    .map(deserializeStep)
}

/** 反序列化并行分支（列表的列表） */
function deserializeBranches(obj: unknown): StepModel[][] {
  if (!Array.isArray(obj)) return []
  return obj.map(branch => deserializeStepList(branch))
}

/** 反序列化触发器列表 */
function deserializeTriggers(obj: unknown): TriggerModel[] {
  if (!Array.isArray(obj)) return []
  return obj
    .filter((item): item is Record<string, unknown> => item != null && typeof item === 'object')
    .map(item => {
      const trigger: TriggerModel = { type: String(item.type ?? 'manual') as TriggerModel['type'] }
      if (trigger.type === 'cron' && item.cron != null) {
        trigger.cron = String(item.cron)
      }
      if (trigger.type === 'event' && item.eventType != null) {
        trigger.eventType = String(item.eventType)
      }
      return trigger
    })
}

/** 反序列化输入参数（YAML map → 数组） */
function deserializeInputs(obj: unknown): InputParamModel[] {
  if (obj == null || typeof obj !== 'object' || Array.isArray(obj)) return []
  const map = obj as Record<string, unknown>
  return Object.entries(map).map(([name, value]) => {
    const param = (value != null && typeof value === 'object') ? value as Record<string, unknown> : {}
    return {
      name,
      type: String(param.type ?? 'string'),
      required: Boolean(param.required ?? false),
      defaultValue: param.defaultValue != null ? String(param.defaultValue) : undefined,
      description: param.description != null ? String(param.description) : undefined,
    }
  })
}

// ========== 工具函数 ==========

/** 将 unknown 转为 Record<string, string>，非对象返回空 map */
function toStringRecord(obj: unknown): Record<string, string> {
  if (obj == null || typeof obj !== 'object' || Array.isArray(obj)) return {}
  const result: Record<string, string> = {}
  for (const [k, v] of Object.entries(obj as Record<string, unknown>)) {
    result[k] = String(v ?? '')
  }
  return result
}

/** 将 unknown 转为 string[]，非数组返回空数组 */
function toStringArray(obj: unknown): string[] {
  if (!Array.isArray(obj)) return []
  return obj.filter(item => item != null).map(item => String(item))
}

/** 将 unknown 转为 number，非数字返回默认值 */
function toNumber(obj: unknown, defaultValue: number): number {
  if (typeof obj === 'number') return obj
  if (typeof obj === 'string') {
    const n = Number(obj)
    return isNaN(n) ? defaultValue : n
  }
  return defaultValue
}

// ========== Composable ==========

/**
 * YAML 双向同步 composable。
 *
 * 提供 WorkflowModel ↔ YAML 文本的双向转换，
 * 序列化输出严格匹配后端 WorkflowYamlParser 的解析格式。
 */
export function useYamlSync(): {
  serialize: (model: WorkflowModel) => string
  deserialize: (yamlText: string) => DeserializeResult
} {
  /**
   * 将 WorkflowModel 序列化为 YAML 文本。
   * 不包含编辑器状态字段（selectedStepId、validationErrors）。
   */
  function serialize(model: WorkflowModel): string {
    const doc: Record<string, unknown> = {
      id: model.id,
      name: model.name,
      description: model.description,
      // version 必须是字符串，避免被 YAML 解析为数字
      version: String(model.version),
      enabled: true,
    }

    // 触发器非空时才输出
    if (model.triggers.length > 0) {
      doc.triggers = model.triggers.map(serializeTrigger)
    }

    // 输入参数非空时才输出
    if (model.inputs.length > 0) {
      doc.inputs = serializeInputs(model.inputs)
    }

    // 步骤
    doc.steps = model.steps.map(serializeStep)

    // 使用 yaml 库序列化，确保 version 字段被引号包裹
    return yaml.stringify(doc, {
      lineWidth: 0,       // 禁止自动换行
      defaultStringType: 'PLAIN',
      defaultKeyType: 'PLAIN',
    })
  }

  /**
   * 将 YAML 文本反序列化为 WorkflowModel。
   * 失败时返回错误信息。
   */
  function deserialize(yamlText: string): DeserializeResult {
    try {
      const parsed = yaml.parse(yamlText)
      if (parsed == null || typeof parsed !== 'object' || Array.isArray(parsed)) {
        return { ok: false, error: 'YAML 根节点必须是 Map 类型' }
      }

      const root = parsed as Record<string, unknown>

      const model: WorkflowModel = {
        id: String(root.id ?? ''),
        name: String(root.name ?? ''),
        description: String(root.description ?? ''),
        version: String(root.version ?? ''),
        triggers: deserializeTriggers(root.triggers),
        inputs: deserializeInputs(root.inputs),
        steps: deserializeStepList(root.steps),
        // 编辑器状态字段：反序列化时重置
        selectedStepId: null,
        validationErrors: new Map(),
      }

      return { ok: true, model }
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e)
      return { ok: false, error: `YAML 解析失败: ${message}` }
    }
  }

  return { serialize, deserialize }
}
