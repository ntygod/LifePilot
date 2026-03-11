import { ref } from 'vue'
import { chatApi } from '@/api/client'
import type { A2uiSignal, A2uiSignalContext, ChatResponse } from '@/types'
import { useA2uiStore } from '@/stores/a2ui'
import { useChatStore } from '@/stores/chat'
import { extractA2uiComponents } from '@/utils/a2ui'

function hasAssistantText(response: ChatResponse) {
  return Boolean(response.content?.trim())
}

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
      if (components && (response.traceId || signalContext.traceId)) {
        a2uiStore.setCurrentTraceId(response.traceId ?? signalContext.traceId ?? null)
      }

      if (hasAssistantText(response) || (!signalContext.messageId && components?.length)) {
        chatStore.upsertMessage({
          id: response.messageId,
          role: 'assistant',
          content: response.content ?? '',
          a2uiComponents: components ?? undefined,
          timestamp: Date.now(),
          traceId: response.traceId,
          tokenUsage: response.tokenUsage,
          modelId: response.tokenUsage?.modelId,
        })
      } else if (components && signalContext.messageId) {
        chatStore.updateMessage(signalContext.messageId, {
          a2uiComponents: components,
        })
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
