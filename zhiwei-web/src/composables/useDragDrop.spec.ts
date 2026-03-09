import { describe, it, expect, vi } from 'vitest'
import { ref } from 'vue'
import { useDragDrop } from './useDragDrop'
import type { StepType } from './useWorkflowModel'

/**
 * 创建模拟 DragEvent，包含 dataTransfer 对象。
 */
function createDragEvent(overrides: Partial<DragEvent> = {}): DragEvent {
  const store: Record<string, string> = {}
  const dataTransfer = {
    setData: (format: string, data: string) => { store[format] = data },
    getData: (format: string) => store[format] ?? '',
    effectAllowed: 'uninitialized' as string,
    dropEffect: 'none' as string,
  } as unknown as DataTransfer

  const event = {
    dataTransfer,
    preventDefault: vi.fn(),
    target: null as EventTarget | null,
    ...overrides,
  } as unknown as DragEvent

  return event
}

describe('useDragDrop', () => {
  const ALL_STEP_TYPES: StepType[] = [
    'skill', 'tool', 'llm', 'condition', 'loop',
    'parallel', 'sub-workflow', 'noop', 'wait', 'approval',
  ]

  it('onDragStart_设置dataTransfer类型和effectAllowed', () => {
    const canvasRef = ref<HTMLElement | null>(document.createElement('div'))
    const onDropCb = vi.fn()
    const { onDragStart } = useDragDrop({ canvasRef, onDrop: onDropCb })

    const e = createDragEvent()
    onDragStart(e, 'skill')

    expect(e.dataTransfer!.getData('text/plain')).toBe('skill')
    expect(e.dataTransfer!.effectAllowed).toBe('copy')
  })

  it('onDragOver_阻止默认行为并设置dropEffect', () => {
    const canvasRef = ref<HTMLElement | null>(document.createElement('div'))
    const onDropCb = vi.fn()
    const { onDragOver } = useDragDrop({ canvasRef, onDrop: onDropCb })

    const e = createDragEvent()
    onDragOver(e)

    expect(e.preventDefault).toHaveBeenCalled()
    expect(e.dataTransfer!.dropEffect).toBe('copy')
  })

  it('onDrop_画布内合法类型_调用回调', () => {
    const canvas = document.createElement('div')
    const child = document.createElement('span')
    canvas.appendChild(child)
    const canvasRef = ref<HTMLElement | null>(canvas)
    const onDropCb = vi.fn()
    const { onDragStart, onDrop } = useDragDrop({ canvasRef, onDrop: onDropCb })

    // 先模拟 dragStart 写入数据
    const startEvent = createDragEvent()
    onDragStart(startEvent, 'llm')

    // 构造 drop 事件，共享同一个 dataTransfer，target 在画布内
    const dropEvent = createDragEvent({
      dataTransfer: startEvent.dataTransfer,
      target: child,
    } as Partial<DragEvent>)

    onDrop(dropEvent)

    expect(dropEvent.preventDefault).toHaveBeenCalled()
    expect(onDropCb).toHaveBeenCalledWith('llm')
  })

  it('onDrop_画布外drop_不调用回调', () => {
    const canvas = document.createElement('div')
    const outside = document.createElement('div')
    const canvasRef = ref<HTMLElement | null>(canvas)
    const onDropCb = vi.fn()
    const { onDrop } = useDragDrop({ canvasRef, onDrop: onDropCb })

    const e = createDragEvent({ target: outside } as Partial<DragEvent>)
    e.dataTransfer!.setData('text/plain', 'skill')
    // 手动设置 getData 返回值（因为 target 不在 canvas 内，不应触发回调）

    onDrop(e)

    expect(onDropCb).not.toHaveBeenCalled()
  })

  it('onDrop_canvasRef为null_不调用回调', () => {
    const canvasRef = ref<HTMLElement | null>(null)
    const onDropCb = vi.fn()
    const { onDrop } = useDragDrop({ canvasRef, onDrop: onDropCb })

    const e = createDragEvent({ target: document.createElement('div') } as Partial<DragEvent>)
    e.dataTransfer!.setData('text/plain', 'skill')

    onDrop(e)

    expect(onDropCb).not.toHaveBeenCalled()
  })

  it('onDrop_非法步骤类型_不调用回调', () => {
    const canvas = document.createElement('div')
    const canvasRef = ref<HTMLElement | null>(canvas)
    const onDropCb = vi.fn()
    const { onDrop } = useDragDrop({ canvasRef, onDrop: onDropCb })

    const e = createDragEvent({ target: canvas } as Partial<DragEvent>)
    e.dataTransfer!.setData('text/plain', 'invalid-type')

    onDrop(e)

    expect(onDropCb).not.toHaveBeenCalled()
  })

  it.each(ALL_STEP_TYPES)('onDrop_合法类型 %s_正确传递', (type) => {
    const canvas = document.createElement('div')
    const canvasRef = ref<HTMLElement | null>(canvas)
    const onDropCb = vi.fn()
    const { onDrop } = useDragDrop({ canvasRef, onDrop: onDropCb })

    const e = createDragEvent({ target: canvas } as Partial<DragEvent>)
    e.dataTransfer!.setData('text/plain', type)

    onDrop(e)

    expect(onDropCb).toHaveBeenCalledWith(type)
  })

  it('onDragStart_dataTransfer为null_不抛异常', () => {
    const canvasRef = ref<HTMLElement | null>(document.createElement('div'))
    const onDropCb = vi.fn()
    const { onDragStart } = useDragDrop({ canvasRef, onDrop: onDropCb })

    const e = { dataTransfer: null, preventDefault: vi.fn() } as unknown as DragEvent
    expect(() => onDragStart(e, 'skill')).not.toThrow()
  })
})
