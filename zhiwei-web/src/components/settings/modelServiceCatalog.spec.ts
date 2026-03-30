import { describe, expect, it } from 'vitest'
import type { ModelServiceTemplate } from '@/api/client'
import {
  buildSuggestedServiceId,
  defaultModelForKind,
  inferVendorKey,
  modelOptionsForKind,
} from './modelServiceCatalog'

const templates: ModelServiceTemplate[] = [
  {
    vendorKey: 'openai',
    displayName: 'OpenAI',
    providerType: 'OPENAI_COMPATIBLE',
    description: '官方模板',
    defaultApiUrl: 'https://api.openai.com/v1',
    supportedKinds: ['GENERATION', 'EMBEDDING'],
    defaultTimeoutSeconds: 60,
    defaultCapabilities: ['CHAT', 'STRUCTURED_OUTPUT', 'FUNCTION_CALLING', 'STREAMING'],
    defaultScenes: ['chat', 'agent_react'],
    defaultSupportsStreaming: true,
    defaultMaxContextWindow: 400000,
    modelOptions: [
      {
        kind: 'GENERATION',
        value: 'gpt-5.4',
        label: 'GPT-5.4',
        recommended: true,
        capabilities: ['CHAT', 'STRUCTURED_OUTPUT', 'FUNCTION_CALLING', 'STREAMING'],
        scenes: ['chat', 'agent_react'],
        supportsStreaming: true,
        maxContextWindow: 400000,
      },
      {
        kind: 'EMBEDDING',
        value: 'text-embedding-3-large',
        label: 'text-embedding-3-large',
        recommended: true,
        capabilities: [],
        scenes: [],
        supportsStreaming: false,
        embeddingDimension: 3072,
      },
    ],
  },
  {
    vendorKey: 'deepseek',
    displayName: 'DeepSeek',
    providerType: 'OPENAI_COMPATIBLE',
    description: 'DeepSeek 模板',
    defaultApiUrl: 'https://api.deepseek.com/v1',
    supportedKinds: ['GENERATION'],
    defaultTimeoutSeconds: 60,
    defaultCapabilities: ['CHAT', 'STRUCTURED_OUTPUT', 'FUNCTION_CALLING', 'STREAMING'],
    defaultScenes: ['chat', 'agent_react'],
    defaultSupportsStreaming: true,
    defaultMaxContextWindow: 128000,
    modelOptions: [
      {
        kind: 'GENERATION',
        value: 'deepseek-chat',
        label: 'DeepSeek Chat',
        recommended: true,
        capabilities: ['CHAT', 'STRUCTURED_OUTPUT', 'FUNCTION_CALLING', 'STREAMING'],
        scenes: ['chat', 'agent_react'],
        supportsStreaming: true,
        maxContextWindow: 128000,
      },
      {
        kind: 'GENERATION',
        value: 'deepseek-reasoner',
        label: 'DeepSeek Reasoner',
        recommended: false,
        capabilities: ['CHAT', 'STRUCTURED_OUTPUT', 'FUNCTION_CALLING', 'STREAMING'],
        scenes: ['chat', 'agent_react'],
        supportsStreaming: true,
        maxContextWindow: 128000,
      },
    ],
  },
]

describe('modelServiceCatalog', () => {
  it('根据后端模板返回推荐模型', () => {
    expect(defaultModelForKind(templates[0], 'GENERATION')?.value).toBe('gpt-5.4')
    expect(defaultModelForKind(templates[0], 'EMBEDDING')?.value).toBe('text-embedding-3-large')
  })

  it('优先使用服务返回的vendorKey', () => {
    expect(inferVendorKey({
      vendorKey: 'openai',
      type: 'OPENAI_COMPATIBLE',
      apiUrl: 'https://api.deepseek.com/v1',
      modelName: 'deepseek-chat',
    })).toBe('openai')
  })

  it('能在缺少vendorKey时回退推断厂商', () => {
    expect(inferVendorKey({
      type: 'OPENAI_COMPATIBLE',
      apiUrl: 'https://api.deepseek.com/v1',
      modelName: 'deepseek-chat',
    })).toBe('deepseek')
  })

  it('自动生成更干净的服务ID', () => {
    expect(buildSuggestedServiceId('GENERATION', 'openai', 'gpt-5.4')).toBe('openai-generation-gpt-5-4')
  })

  it('返回模板对应种类的模型列表', () => {
    expect(modelOptionsForKind(templates[1], 'GENERATION').map(item => item.value)).toEqual([
      'deepseek-chat',
      'deepseek-reasoner',
    ])
  })
})
