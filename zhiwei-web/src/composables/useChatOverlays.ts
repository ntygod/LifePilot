import { onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 对话页 overlay 状态集中管理。
 *
 * <p>包含 trace / document / settings / info 四种可切换 overlay，加 Esc 键全局关闭。
 * 任意时刻最多打开一个 overlay（互斥语义）；打开新的会自动关闭旧的。</p>
 *
 * @author zsg
 * @since 2026-05-08
 */
export type OverlayId = 'trace' | 'document' | 'settings' | 'info' | 'tasks' | null

export function useChatOverlays() {
  const activeOverlay = ref<OverlayId>(null)
  /** 当 activeOverlay === 'trace' 时，记录要看的 messageId（'streaming' 表示当前流式中） */
  const traceTargetId = ref<string | null>(null)

  function openTrace(messageIdOrStreaming: string) {
    traceTargetId.value = messageIdOrStreaming
    activeOverlay.value = 'trace'
  }

  function openDocument() {
    activeOverlay.value = 'document'
  }

  function openSettings() {
    activeOverlay.value = 'settings'
  }

  function openInfo() {
    activeOverlay.value = 'info'
  }

  function openTasks() {
    activeOverlay.value = 'tasks'
  }

  function close() {
    activeOverlay.value = null
    traceTargetId.value = null
  }

  function handleKeyDown(event: KeyboardEvent) {
    if (event.key === 'Escape' && activeOverlay.value !== null) {
      close()
    }
  }

  onMounted(() => {
    window.addEventListener('keydown', handleKeyDown)
  })

  onBeforeUnmount(() => {
    window.removeEventListener('keydown', handleKeyDown)
  })

  return {
    activeOverlay,
    traceTargetId,
    openTrace,
    openDocument,
    openSettings,
    openInfo,
    openTasks,
    close,
  }
}
