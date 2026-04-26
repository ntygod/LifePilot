/**
 * 捆绑 Python 运行时状态 composable。
 *
 * <p>封装运行时状态轮询 + 安装进度 SSE 订阅：
 * <ul>
 *   <li>{@link refresh} — 主动拉取一次最新状态</li>
 *   <li>{@link install} — 触发安装并自动订阅进度流</li>
 *   <li>{@link subscribeProgress} — 仅订阅进度流（用于刷新页面时恢复 UI）</li>
 * </ul>
 * 组件卸载时自动关闭 EventSource，避免泄漏。</p>
 *
 * @author zsg
 * @since 2026-04-26
 */
import { ref, onUnmounted } from 'vue'
import { runtimeApi, type RuntimeStatus } from '@/api/runtime'
import { logger } from '@/utils/logger'

export function useRuntimeStatus() {
  const status = ref<RuntimeStatus | null>(null)
  const error = ref<string | null>(null)
  let eventSource: EventSource | null = null

  /** 主动拉取一次状态；失败时把错误对象的 message（或字符串化结果）写入 error。 */
  async function refresh(): Promise<void> {
    try {
      status.value = await runtimeApi.status()
      error.value = null
    } catch (e) {
      error.value = extractErrorMessage(e)
    }
  }

  /**
   * 订阅安装进度 SSE：
   * <ul>
   *   <li>{@code progress} 事件 → 更新 {@link status}；{@code phase === 'done'}
   *       视为终态，refresh 后关闭流</li>
   *   <li>{@code failed} 事件 → 写入 {@link error} 并 refresh 拉一次终态</li>
   * </ul>
   * 重复调用会先关闭旧连接，避免重复订阅。
   */
  function subscribeProgress(): void {
    eventSource?.close()
    eventSource = new EventSource(runtimeApi.installProgressUrl())

    eventSource.addEventListener('progress', (e) => {
      try {
        const data = JSON.parse((e as MessageEvent).data) as RuntimeStatus
        status.value = data
        if (data.phase === 'done') {
          void refresh()
          eventSource?.close()
          eventSource = null
        }
      } catch (err) {
        logger.error('运行时安装进度解析失败:', err)
      }
    })

    eventSource.addEventListener('failed', (e) => {
      const raw = (e as MessageEvent).data as string
      // 后端 emitFailed 推送的是裸字符串 reason；尝试 JSON.parse 兼容未来可能的对象封装
      let reason: string
      try {
        const parsed = JSON.parse(raw)
        reason = typeof parsed === 'string' ? parsed : String(parsed)
      } catch {
        reason = raw
      }
      error.value = reason
      eventSource?.close()
      eventSource = null
      void refresh()
    })

    eventSource.onerror = () => {
      // 连接断开（网络抖动 / 后端重启）— 关闭流，让上层决定是否重连
      eventSource?.close()
      eventSource = null
    }
  }

  /** 触发安装并立即订阅进度流。 */
  async function install(): Promise<void> {
    await runtimeApi.install()
    subscribeProgress()
  }

  onUnmounted(() => {
    eventSource?.close()
    eventSource = null
  })

  return { status, error, refresh, install, subscribeProgress }
}

/** 从未知错误对象提取 message：优先用 ApiResponse error.message，回落到字符串化。 */
function extractErrorMessage(e: unknown): string {
  if (e && typeof e === 'object' && 'message' in e && typeof (e as { message: unknown }).message === 'string') {
    return (e as { message: string }).message
  }
  return String(e)
}
