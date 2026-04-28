/**
 * Provider Profile REST 封装 —— LLM 适配器层重构 Phase 8。
 *
 * <p>沿用项目现有的 API client 模式（见 {@code src/api/project.ts}）：
 * 通过 {@code getApiOrigin() + '/api'} 作为基地址，自动解包后端
 * {@code ApiResponse<T>} 响应结构（{code, message, data}），
 * 失败时抛出结构化错误对象。</p>
 *
 * <p>本文件提供 {@link listProviderProfiles}：列出后端内置的 ProviderProfile，
 * 供模型服务编辑页让用户挑选 profile。</p>
 *
 * @author zsg
 * @since 2026-04-27
 */
import { getApiOrigin } from '@/api/config'

const getBase = () => getApiOrigin() + '/api'

/** 基础适配器类型 — 决定使用哪条 Spring AI ChatModel 路径，与后端 BaseAdapterType 枚举一一对应 */
export type BaseAdapterType = 'OPENAI_BASE' | 'ANTHROPIC_BASE' | 'OLLAMA' | 'TEI'

/** 思考协议标识 — 描述 provider 如何接收/返回 reasoning，与后端 ThinkingProtocolId 一一对应 */
export type ThinkingProtocolId =
  | 'DEEPSEEK'
  | 'QWEN'
  | 'OPENAI_REASONING_EFFORT'
  | 'ANTHROPIC'
  | 'NONE'

/**
 * Provider Profile 元数据 —— 对应后端 {@code ProviderProfileDto}。
 *
 * <p>用于模型服务编辑页让用户挑选 profile。{@link thinkingProtocol} 为
 * {@code 'NONE'} 表示该协议不支持思考链；前端据此决定是否显示推理相关 UI。</p>
 */
export interface ProviderProfileDto {
  id: string
  displayName: string
  baseAdapter: BaseAdapterType
  defaultBaseUrl: string
  thinkingProtocol: ThinkingProtocolId
  capabilities: string[]
}

/** 统一 fetch 封装：解包 ApiResponse<T> 包装，非 2xx 抛结构化错误对象。 */
async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${getBase()}${url}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  })
  if (!res.ok) {
    const text = await res.text()
    let error: { code: number; message: string; timestamp: string }
    try {
      error = text
        ? (JSON.parse(text) as { code: number; message: string; timestamp: string })
        : { code: res.status, message: res.statusText, timestamp: new Date().toISOString() }
    } catch {
      error = {
        code: res.status,
        message: text?.trim() || res.statusText || '请求失败',
        timestamp: new Date().toISOString(),
      }
    }
    throw error
  }
  if (res.status === 204) return undefined as T
  const text = await res.text()
  if (!text || text.trim() === '') return undefined as T
  const json = JSON.parse(text)
  if (
    json &&
    typeof json === 'object' &&
    'code' in json &&
    typeof json.code === 'number' &&
    'message' in json &&
    'data' in json
  ) {
    return json.data as T
  }
  return json as T
}

/**
 * 列出所有内置 Provider Profile。
 *
 * <p>对应后端 {@code GET /api/model-services/profiles}。</p>
 */
export function listProviderProfiles(): Promise<ProviderProfileDto[]> {
  return request<ProviderProfileDto[]>('/model-services/profiles')
}
