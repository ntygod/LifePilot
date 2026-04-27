import type {
  CreateModelServiceRequest,
  ModelService,
  ModelServiceTemplate,
  ModelServiceTemplateModel,
} from '@/api/client'

export type ServiceKind = 'GENERATION' | 'EMBEDDING' | 'RERANK'

export const CUSTOM_MODEL_VALUE = '__custom__'

export const KIND_OPTIONS: Array<{ value: ServiceKind; label: string }> = [
  { value: 'GENERATION', label: '生成服务' },
  { value: 'EMBEDDING', label: '向量服务' },
  { value: 'RERANK', label: '精排服务' },
]

export const GENERATION_CAPABILITY_OPTIONS = [
  { value: 'CHAT', label: '对话' },
  { value: 'STRUCTURED_OUTPUT', label: '结构化输出' },
  { value: 'FUNCTION_CALLING', label: '函数调用' },
  { value: 'STREAMING', label: '流式输出' },
  { value: 'VISION', label: '视觉理解' },
  { value: 'NATIVE_AUDIO', label: '原生音频' },
  { value: 'NATIVE_VIDEO', label: '原生视频' },
]

/** 生成场景可选项。hint 非空时，UI 渲染 info 图标 + tooltip 提示用户适合的模型档位。 */
export interface GenerationSceneOption {
  value: string
  label: string
  /** 配置提示（一般是"建议用 X 级模型"之类），非空时 UI 显示感叹号 tooltip。 */
  hint?: string
}

export const GENERATION_SCENE_OPTIONS: GenerationSceneOption[] = [
  { value: 'chat', label: '通用对话' },
  { value: 'agent_react', label: 'Agent 推理' },
  {
    value: 'knowledge_extraction',
    label: '知识提取',
    hint: '输入较长、对结构化输出准确度要求高，建议用 Pro / Plus 级模型',
  },
  { value: 'background_analysis', label: '后台分析' },
  {
    value: 'memory_compression',
    label: '记忆压缩',
    hint: '高频后台任务，建议用 Flash / Turbo 级小模型，在成本与延迟之间取舍',
  },
  {
    value: 'skill_generation',
    label: '技能生成',
    hint: '对生成质量与结构化要求高，建议用 Pro / Plus 级模型',
  },
  {
    value: 'session-title',
    label: '会话标题',
    hint: '仅生成 ≤ 20 字中文标题，延迟敏感，建议用 Flash / Turbo 级小模型',
  },
  {
    value: 'conversation-summary',
    label: '对话摘要',
    hint: '后台派生任务，输出数百字总结，建议用 Flash / Turbo 级小模型',
  },
  {
    value: 'knowledge_rerank',
    label: 'LLM 重排打分',
    hint: '用 LLM 对检索结果做 pointwise / listwise 打分，不是专用 reranker 模型（专用请在精排服务页配置）。延迟敏感，建议用 Flash / Turbo 级小模型',
  },
  {
    value: 'retrieval_quality_eval',
    label: 'LLM 检索评估',
    hint: '用 LLM 评估检索结果相关度的轻量打分场景，不是专用 reranker。建议用 Flash / Turbo 级小模型',
  },
]

export function buildEmptyModelServiceRequest(): CreateModelServiceRequest {
  return {
    id: '',
    kind: 'GENERATION',
    type: 'OPENAI_COMPATIBLE',
    profileId: undefined,
    vendorKey: undefined,
    apiUrl: '',
    apiKey: '',
    modelName: '',
    timeoutSeconds: 60,
    priority: 0,
    scenes: ['chat', 'agent_react'],
    capabilities: ['CHAT', 'STRUCTURED_OUTPUT', 'FUNCTION_CALLING', 'STREAMING'],
    enabled: true,
    isReasoning: false,
    thinkingMode: 'auto',
    costPerInputToken: 0,
    costPerOutputToken: 0,
    maxContextWindow: 131072,
    embeddingDimension: undefined,
    supportsStreaming: true,
    displayName: '',
    description: '',
  }
}

export function findVendorTemplate(templates: ModelServiceTemplate[],
                                   vendorKey: string | undefined): ModelServiceTemplate | undefined {
  if (!vendorKey) return undefined
  return templates.find(template => template.vendorKey === vendorKey)
}

export function availableVendorsForKind(templates: ModelServiceTemplate[],
                                        kind: ServiceKind | string): ModelServiceTemplate[] {
  return templates.filter(template => template.supportedKinds.includes(kind as ServiceKind))
}

export function modelOptionsForKind(template: ModelServiceTemplate | undefined,
                                    kind: ServiceKind | string): ModelServiceTemplateModel[] {
  return template?.modelOptions.filter(option => option.kind === kind) ?? []
}

export function defaultModelForKind(template: ModelServiceTemplate | undefined,
                                    kind: ServiceKind | string): ModelServiceTemplateModel | undefined {
  const options = modelOptionsForKind(template, kind)
  return options.find(option => option.recommended) ?? options[0]
}

export function inferVendorKey(service: Pick<ModelService, 'vendorKey' | 'type' | 'apiUrl' | 'modelName'>): string {
  if (service.vendorKey?.trim()) return service.vendorKey

  const type = (service.type || '').toUpperCase()
  const apiUrl = (service.apiUrl || '').toLowerCase()
  const modelName = (service.modelName || '').toLowerCase()

  if (type === 'ANTHROPIC') return 'anthropic'
  if (type === 'OLLAMA') return 'ollama'
  if (type === 'TEI') return 'tei'
  if (type === 'OPENAI_COMPATIBLE') {
    if (apiUrl.includes('api.openai.com')) return 'openai'
    if (apiUrl.includes('api.deepseek.com') || modelName.startsWith('deepseek-')) return 'deepseek'
    if (
      apiUrl.includes('dashscope.aliyuncs.com') ||
      apiUrl.includes('dashscope-intl.aliyuncs.com') ||
      modelName.startsWith('qwen') ||
      modelName.startsWith('qwq')
    ) {
      return 'qwen'
    }
  }
  return 'custom-openai'
}

export function vendorLabel(service: Pick<ModelService, 'vendorKey' | 'type' | 'apiUrl' | 'modelName'>,
                            templates: ModelServiceTemplate[]): string {
  return findVendorTemplate(templates, inferVendorKey(service))?.displayName ?? service.type
}

export function buildSuggestedServiceId(kind: ServiceKind | string,
                                        vendorKey: string,
                                        modelName: string): string {
  const normalizedKind = String(kind || '').toLowerCase()
  const normalizedVendor = String(vendorKey || '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
  const normalizedModel = String(modelName || '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
  return [normalizedVendor, normalizedKind, normalizedModel]
    .filter(Boolean)
    .join('-')
    .slice(0, 80)
}
