import { ref } from 'vue'
import { chatApi } from '@/api/client'
import type { A2uiComponent, A2uiSignal, A2uiSignalContext } from '@/types'
import { useA2uiStore } from '@/stores/a2ui'
import { useChatStore } from '@/stores/chat'

function isA2uiComponentArray(value: unknown): value is A2uiComponent[] {
  return Array.isArray(value)
    && value.every(item =>
      item
      && typeof item === 'object'
      && typeof (item as A2uiComponent).id === 'string'
      && typeof (item as A2uiComponent).type === 'string'
      && Array.isArray((item as A2uiComponent).children),
    )
}

export function extractA2uiComponents(payload: unknown): A2uiComponent[] | null {
  if (!payload || typeof payload !== 'object') return null

  const record = payload as Record<string, unknown>
  const candidates: unknown[] = [
    record.components,
    (record.a2ui as { components?: unknown } | undefined)?.components,
    (record.ui as { components?: unknown } | undefined)?.components,
    (record.message as { a2uiComponents?: unknown } | undefined)?.a2uiComponents,
    ((record.message as { a2ui?: { components?: unknown } } | undefined)?.a2ui)?.components,
  ]

  for (const candidate of candidates) {
    if (isA2uiComponentArray(candidate)) {
      return candidate
    }
  }

  return null
}

/**
 * A2UI 信号 composable，封装信号发送逻辑。
 * 调用 POST /api/chat/signals 将用户交互信号回传给 Agent。
 */
export function useA2uiSignal() {
  const sending = ref(false)
  const error = ref<string | null>(null)
  const a2uiStore = useA2uiStore()
  const chatStore = useChatStore()

  function formatSignalError(event: unknown) {
    if (event instanceof Error) return event.message
    if (typeof event === 'object' && event && 'message' in event) {
      return String((event as { message: unknown }).message)
    }
    return '信号发送失败'
  }

  function buildSignalPayload(
    signal: A2uiSignal,
    context?: A2uiSignalContext & { payloadPatch?: Record<string, unknown> },
  ) {
    const basePayload = {
      ...signal.payload,
      ...(context?.payloadPatch ?? {}),
    }

    const metadata = Object.fromEntries(
      Object.entries({
        componentId: context?.componentId,
        messageId: context?.messageId,
        traceId: context?.traceId,
        emittedAt: Date.now(),
      }).filter(([, value]) => value !== undefined && value !== null),
    )

    if (Object.keys(metadata).length === 0) {
      return basePayload
    }

    return {
      ...basePayload,
      __a2ui: metadata,
    }
  }

  /** 发送 A2UI 信号 */
  async function emitSignal(
    signal: A2uiSignal,
    sessionId: string,
    context?: A2uiSignalContext & { payloadPatch?: Record<string, unknown> },
  ) {
    const signalContext: A2uiSignalContext = {
      componentId: context?.componentId,
      messageId: context?.messageId,
      traceId: context?.traceId,
      signalName: context?.signalName ?? signal.name,
    }

    sending.value = true
    error.value = null
    const signalKey = a2uiStore.setSignalState(signalContext, {
      status: 'sending',
      error: null,
    })

    try {
      const response = await chatApi.sendSignal(
        signal.name,
        buildSignalPayload(signal, context),
        sessionId,
      )

      const components = extractA2uiComponents(response)
      if (components) {
        if (signalContext.messageId) {
          chatStore.updateMessage(signalContext.messageId, {
            a2uiComponents: components,
          })
        } else {
          a2uiStore.updateComponents(components, {
            traceId: signalContext.traceId ?? a2uiStore.currentTraceId,
          })
        }
      }

      a2uiStore.setSignalState(signalContext, {
        status: 'success',
        error: null,
      })
      window.setTimeout(() => {
        a2uiStore.clearSignalState(signalKey)
      }, 1800)

      return response
    } catch (e: unknown) {
      const message = formatSignalError(e)
      error.value = message
      a2uiStore.setSignalState(signalContext, {
        status: 'error',
        error: message,
      })
      return undefined
    } finally {
      sending.value = false
    }
  }

  function getSignalState(context: A2uiSignalContext) {
    return a2uiStore.getSignalState(context)
  }

  return { emitSignal, sending, error, getSignalState }
}
