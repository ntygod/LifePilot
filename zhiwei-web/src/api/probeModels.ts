/**
 * 模型探测 REST 封装 —— LLM 适配器层重构 Phase 8。
 *
 * <p>沿用项目现有的 API client 模式（见 {@code src/api/providerProfile.ts}）：
 * 通过 {@code getApiOrigin() + '/api'} 作为基地址，自动解包后端
 * {@code ApiResponse<T>} 响应结构（{code, message, data}），
 * 失败时抛出结构化错误对象。</p>
 *
 * <p>本文件提供 {@link probeModels}：按 ProviderProfile 探测某 provider
 * 实际可用的模型清单，供模型服务编辑页"拉取可用模型"按钮消费。</p>
 *
 * @author zsg
 * @since 2026-04-27
 */
import { getApiOrigin } from '@/api/config'

const getBase = () => getApiOrigin() + '/api'

/**
 * 探测模型清单请求 —— 对应后端 {@code ProbeModelsRequest}。
 *
 * @property profileId 内置 ProviderProfile id（如 "deepseek-official" / "ollama-local"）
 * @property baseUrl   provider 基础地址（不含 /v1/models 之类的路径）
 * @property apiKey    provider 鉴权 key（Ollama 等无鉴权 provider 可传 null / 空串）
 */
export interface ProbeModelsRequest {
  profileId: string
  baseUrl: string
  apiKey?: string
}

/**
 * 单个模型条目 —— 对应后端 {@code ProbeModelsResponse.ModelInfo}。
 *
 * @property id   模型唯一标识（用于实际 API 调用）
 * @property name 展示名称（当前与 id 一致，预留 displayName 扩展点）
 */
export interface ModelInfo {
  id: string
  name: string
}

/**
 * 探测响应 —— 对应后端 {@code ProbeModelsResponse}。
 *
 * @property models 模型清单（空列表代表 provider 未返回任何模型）
 */
export interface ProbeModelsResponse {
  models: ModelInfo[]
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
 * 按 ProviderProfile 探测 provider 实际可用模型清单。
 *
 * <p>对应后端 {@code POST /api/model-services/probe-models}。后端按
 * {@code ProviderProfile.modelDiscovery()} 配置发起 GET 请求，从响应体
 * 提取 model 列表后返回；失败时后端抛 RuntimeException → 转 ApiResponse 错误。</p>
 *
 * @returns 模型清单数组（空数组代表 provider 未返回任何模型）
 */
export async function probeModels(req: ProbeModelsRequest): Promise<ModelInfo[]> {
  const response = await request<ProbeModelsResponse>('/model-services/probe-models', {
    method: 'POST',
    body: JSON.stringify(req),
  })
  return response.models ?? []
}
