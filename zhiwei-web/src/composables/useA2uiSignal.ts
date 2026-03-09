import { ref } from 'vue'
import { chatApi } from '@/api/client'
import type { A2uiSignal } from '@/types'

/**
 * A2UI 信号 composable，封装信号发送逻辑。
 * 调用 POST /api/chat/signals 将用户交互信号回传给 Agent。
 */
export function useA2uiSignal() {
  const sending = ref(false)
  const error = ref<string | null>(null)

  /** 发送 A2UI 信号 */
  async function emitSignal(signal: A2uiSignal, sessionId: string) {
    sending.value = true
    error.value = null
    try {
      await chatApi.sendSignal(signal.name, signal.payload, sessionId)
    } catch (e: unknown) {
      error.value = e instanceof Error ? e.message : '信号发送失败'
    } finally {
      sending.value = false
    }
  }

  return { emitSignal, sending, error }
}
