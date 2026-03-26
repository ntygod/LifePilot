import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { A2uiComponent, A2uiSignalContext, A2uiSignalRuntime } from '@/types'
import { normalizeA2uiComponents } from '@/utils/a2ui'

export const useA2uiStore = defineStore('a2ui', () => {
  // 当前 A2UI 组件列表（邻接表扁平数组）
  const components = ref<A2uiComponent[]>([])
  const currentTraceId = ref<string | null>(null)
  const signalStates = ref<Record<string, A2uiSignalRuntime>>({})

  /** 更新组件树 */
  function updateComponents(newComponents: A2uiComponent[], context?: { traceId?: string | null }) {
    components.value = normalizeA2uiComponents(newComponents)
    if (context && 'traceId' in context) {
      currentTraceId.value = context.traceId ?? null
    }
  }

  function setCurrentTraceId(traceId: string | null) {
    currentTraceId.value = traceId
  }

  /** 清空组件树 */
  function clearComponents() {
    components.value = []
    currentTraceId.value = null
    signalStates.value = {}
  }

  function buildSignalKey(context: A2uiSignalContext) {
    return [
      context.entryId ?? 'transient',
      context.componentId ?? 'component',
      context.signalName ?? 'signal',
    ].join(':')
  }

  function getSignalState(context: A2uiSignalContext) {
    return signalStates.value[buildSignalKey(context)]
  }

  function setSignalState(context: A2uiSignalContext, state: Omit<A2uiSignalRuntime, 'updatedAt'>) {
    const key = buildSignalKey(context)
    signalStates.value = {
      ...signalStates.value,
      [key]: {
        ...state,
        updatedAt: Date.now(),
      },
    }

    return key
  }

  function clearSignalState(contextOrKey: A2uiSignalContext | string) {
    const key = typeof contextOrKey === 'string' ? contextOrKey : buildSignalKey(contextOrKey)
    if (!(key in signalStates.value)) return

    const nextStates = { ...signalStates.value }
    delete nextStates[key]
    signalStates.value = nextStates
  }

  function clearSignalStatesForEntry(entryId: string) {
    const nextStates = Object.fromEntries(
      Object.entries(signalStates.value).filter(([key]) => !key.startsWith(`${entryId}:`)),
    )
    signalStates.value = nextStates
  }

  return {
    components,
    currentTraceId,
    signalStates,
    updateComponents,
    setCurrentTraceId,
    clearComponents,
    buildSignalKey,
    getSignalState,
    setSignalState,
    clearSignalState,
    clearSignalStatesForEntry,
  }
})
