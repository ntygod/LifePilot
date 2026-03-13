import type { Ref } from 'vue'
import type { StepType } from './useWorkflowModel'

/** 全部合法的步骤类型集合，用于 drop 时校验 */
const VALID_STEP_TYPES: ReadonlySet<string> = new Set<StepType>([
  'skill',
  'tool',
  'llm',
  'condition',
  'loop',
  'parallel',
  'sub-workflow',
  'noop',
  'wait',
  'approval',
  'notify',
])

/**
 * 拖拽交互 composable。
 * 处理从 StepPalette 拖拽步骤类型到 EditorCanvas 的 HTML5 Drag & Drop 交互。
 *
 * @param options.canvasRef 画布 DOM 元素引用，用于判断 drop 目标是否在画布内
 * @param options.onDrop    合法 drop 时的回调，传入步骤类型
 */
export function useDragDrop(options: {
  canvasRef: Ref<HTMLElement | null>
  onDrop: (type: StepType) => void
}): {
  onDragStart: (e: DragEvent, type: StepType) => void
  onDragOver: (e: DragEvent) => void
  onDrop: (e: DragEvent) => void
} {
  /**
   * 拖拽开始：在 StepPalette 卡片上触发，将步骤类型写入 dataTransfer。
   */
  function onDragStart(e: DragEvent, type: StepType): void {
    if (!e.dataTransfer) return
    e.dataTransfer.setData('text/plain', type)
    e.dataTransfer.effectAllowed = 'copy'
  }

  /**
   * 拖拽经过画布：阻止默认行为以允许 drop，设置 dropEffect 为 copy。
   */
  function onDragOver(e: DragEvent): void {
    e.preventDefault()
    if (e.dataTransfer) {
      e.dataTransfer.dropEffect = 'copy'
    }
  }

  /**
   * 放置到画布：校验 drop 目标在画布内且步骤类型合法后，调用 onDrop 回调。
   */
  function onDrop(e: DragEvent): void {
    e.preventDefault()

    // 仅处理画布内的 drop
    const canvas = options.canvasRef.value
    if (!canvas) return
    const target = e.target as Node | null
    if (!target || !canvas.contains(target)) return

    // 从 dataTransfer 读取步骤类型并校验
    const raw = e.dataTransfer?.getData('text/plain')
    if (!raw || !VALID_STEP_TYPES.has(raw)) return

    options.onDrop(raw as StepType)
  }

  return { onDragStart, onDragOver, onDrop }
}
