<!--
  编排画布组件。
  基于绝对定位 + SVG 连线层的交互式 DAG 画布。
  支持节点自由拖拽、连接锚点拖拽连线、键盘删除、右键上下文菜单。
  支持条件分支、循环、并行等嵌套步骤的可视化渲染。
-->
<script setup lang="ts">
import { computed, ref, triggerRef, watch, onMounted, onUnmounted } from 'vue'
import type { StepModel, StepType } from '@/composables/useWorkflowModel'
import { useDagLayout } from '@/composables/useDagLayout'
import { flattenNestedSteps, getConditionExits, type CanvasNode, type BranchType, type ConditionExits } from '@/composables/useNestedSteps'
import { STEP_TYPE_META } from '@/components/workflow/editor/stepTypeMeta'
import { PackagePlus, Trash2, Unlink } from 'lucide-vue-next'

const props = defineProps<{
  steps: StepModel[]
  selectedStepId: string | null
  validationErrors: Map<string, string[]>
}>()

const emit = defineEmits<{
  'select-step': [stepId: string]
  'drop-step': [type: StepType]
  'connect': [fromId: string, toId: string, branch?: 'then' | 'else']
  'disconnect': [fromId: string, toId: string]
  'delete-step': [stepId: string]
}>()

const { computeCanvasLayers } = useDagLayout()
const containerRef = ref<HTMLElement | null>(null)

// ── 画布平移状态 ──
const isPanning = ref(false)
const panStart = ref({ x: 0, y: 0 })
const panOffset = ref({ x: 0, y: 0 })
const panOffsetStart = ref({ x: 0, y: 0 })

// ── 扁平化的画布節點計算 ──
const canvasNodes = computed<CanvasNode[]>(() => flattenNestedSteps(props.steps))
const conditionExits = computed<Map<string, ConditionExits>>(() => getConditionExits(canvasNodes.value))

// ── 节点位置状态（使用普通对象，确保 Vue 响应式追踪可靠） ──
const positions = ref<Record<string, { x: number; y: number }>>({})

/** 节点尺寸常量 */
const NODE_WIDTH = 180
const NODE_HEIGHT = 72
const LAYER_GAP_Y = 80
const NODE_GAP_X = 40
const PADDING_TOP = 40
const PADDING_LEFT = 40

/** 获取步骤位置的辅助函数 */
function getPos(stepId: string): { x: number; y: number } {
  return positions.value[stepId] ?? { x: 0, y: 0 }
}

/** 设置步骤位置（直接修改 + 手动触发响应式更新，避免拖拽时频繁创建新对象） */
function setPos(stepId: string, pos: { x: number; y: number }) {
  positions.value[stepId] = pos
  triggerRef(positions)
}

/**
 * 根据拓扑分层计算初始位置。
 * 仅为没有位置的新节点分配位置，已有位置的节点保持不变。
 * 使用扁平化的画布节点进行分层计算。
 */
function autoLayoutNewNodes() {
  const layers = computeCanvasLayers(canvasNodes.value)
  const newPositions = { ...positions.value }
  let changed = false

  for (let layerIdx = 0; layerIdx < layers.length; layerIdx++) {
    const layer = layers[layerIdx]
    const layerWidth = layer.length * NODE_WIDTH + (layer.length - 1) * NODE_GAP_X
    const startX = PADDING_LEFT + Math.max(0, (400 - layerWidth) / 2)
    for (let nodeIdx = 0; nodeIdx < layer.length; nodeIdx++) {
      const canvasId = layer[nodeIdx]
      if (!(canvasId in newPositions)) {
        newPositions[canvasId] = {
          x: startX + nodeIdx * (NODE_WIDTH + NODE_GAP_X),
          y: PADDING_TOP + layerIdx * (NODE_HEIGHT + LAYER_GAP_Y),
        }
        changed = true
      }
    }
  }

  // 清理已删除步骤的位置
  const canvasIds = new Set(canvasNodes.value.map(n => n.canvasId))
  for (const id of Object.keys(newPositions)) {
    if (!canvasIds.has(id)) {
      delete newPositions[id]
      changed = true
    }
  }

  if (changed) {
    positions.value = newPositions
  }
}


// ── 画布内容区域尺寸（确保 SVG 和内容区域足够大） ──
const canvasSize = computed(() => {
  let maxX = 800
  let maxY = 600
  for (const pos of Object.values(positions.value)) {
    maxX = Math.max(maxX, pos.x + NODE_WIDTH + 60)
    maxY = Math.max(maxY, pos.y + NODE_HEIGHT + 60)
  }
  return { width: maxX, height: maxY }
})

// ── SVG 连线计算 ──
/** 基于位置状态计算连线坐标（不依赖 DOM） */
/** 连线类型：普通連線、分支Then連線、分支Else連線 */
type LineType = 'normal' | 'then' | 'else'

interface LineData {
  x1: number; y1: number; x2: number; y2: number
  fromId: string; toId: string
  lineType: LineType
}

const lines = computed<LineData[]>(() => {
  const result: LineData[] = []
  
  // 遍歷所有畫布節點，計算連線
  for (const node of canvasNodes.value) {
    for (const depId of node.dependsOn) {
      const fromPos = positions.value[depId]
      const toPos = positions.value[node.canvasId]
      if (!fromPos || !toPos) continue
      
      // 判斷這條連線的類型
      let lineType: LineType = 'normal'
      
      // 如果目標節點是分支內的節點，且依賴於父節點，則標記為分支連線
      if (node.branch !== 'root' && depId === node.parentStepId) {
        lineType = node.branch === 'then' ? 'then' : 'else'
      }
      
      result.push({
        x1: fromPos.x + NODE_WIDTH / 2,
        y1: fromPos.y + NODE_HEIGHT,
        x2: toPos.x + NODE_WIDTH / 2,
        y2: toPos.y,
        fromId: depId,
        toId: node.canvasId,
        lineType,
      })
    }
  }
  
  return result
})

/** 獲取連線顏色 */
function getLineColor(line: LineData): string {
  if (line.lineType === 'then') return '#22c55e' // 綠色 - Then
  if (line.lineType === 'else') return '#ef4444' // 紅色 - Else
  return isLineHighlighted(line) ? 'hsl(var(--primary))' : '#9ca3af'
}

/** 獲取連線寬度 */
function getLineWidth(line: LineData): number {
  return isLineHighlighted(line) ? 2.5 : 1.5
}

/** 獲取連線樣式 */
function getLineDash(line: LineData): string {
  if (line.lineType !== 'normal') return 'none' // 分支連線用實線
  return isLineHighlighted(line) ? 'none' : '6 3'
}


// ── 悬停高亮状态 ──
const hoveredStepId = ref<string | null>(null)

// ── 右键上下文菜单状态 ──
interface ContextMenuState {
  x: number
  y: number
  type: 'step' | 'line'
  stepId?: string
  canvasId?: string
  fromId?: string
  toId?: string
}
const contextMenu = ref<ContextMenuState | null>(null)

// ── 连接拖拽状态 ──
/** 正在拖拽连线的源步骤 ID */
const connectingFrom = ref<string | null>(null)
/** 拖拽过程中鼠标位置（相对于画布内容区域） */
const mousePos = ref({ x: 0, y: 0 })

// ── 节点拖拽状态 ──
/** 正在拖拽的节点 ID */
const draggingNodeId = ref<string | null>(null)
/** 拖拽起始时鼠标与节点左上角的偏移 */
const dragOffset = ref({ x: 0, y: 0 })

/** 临时连线：从源锚点到鼠标位置 */
const tempLine = computed(() => {
  if (!connectingFrom.value) return null
  
  // 解析 connectingFrom，可能是普通節點ID或條件節點的 Then/Else 出口
  const parts = connectingFrom.value.split('-')
  const isConditionBranch = parts.length >= 2 && (parts[parts.length - 1] === 'then' || parts[parts.length - 1] === 'else')
  
  let sourceId: string
  let sourcePos: { x: number; y: number } | undefined
  
  if (isConditionBranch) {
    // 條件節點的 Then/Else 出口
    sourceId = parts.slice(0, -1).join('-') // 移除最後的 then/else
    sourcePos = positions.value[sourceId]
    if (!sourcePos) return null
    
    // Then 出口在左側，Else 出口在右側
    const branch = parts[parts.length - 1] as 'then' | 'else'
    const offsetX = branch === 'then' ? -NODE_WIDTH / 4 : NODE_WIDTH / 4
    
    return {
      x1: sourcePos.x + NODE_WIDTH / 2 + offsetX,
      y1: sourcePos.y + NODE_HEIGHT,
      x2: mousePos.value.x,
      y2: mousePos.value.y,
    }
  } else {
    // 普通節點
    sourcePos = positions.value[connectingFrom.value]
    if (!sourcePos) return null
    return {
      x1: sourcePos.x + NODE_WIDTH / 2,
      y1: sourcePos.y + NODE_HEIGHT,
      x2: mousePos.value.x,
      y2: mousePos.value.y,
    }
  }
})

/**
 * 生成 SVG 贝塞尔曲线路径。
 */
function bezierPath(l: { x1: number; y1: number; x2: number; y2: number }): string {
  const dy = Math.abs(l.y2 - l.y1)
  const offset = Math.max(dy * 0.4, 20)
  return `M ${l.x1} ${l.y1} C ${l.x1} ${l.y1 + offset}, ${l.x2} ${l.y2 - offset}, ${l.x2} ${l.y2}`
}

/**
 * 计算连线终点处的箭头三角形顶点。
 */
function arrowPoints(l: { x1: number; y1: number; x2: number; y2: number }): string {
  const size = 5
  const x = l.x2
  const y = l.y2
  return `${x},${y} ${x - size},${y - size * 1.5} ${x + size},${y - size * 1.5}`
}

// ── 节点拖拽交互 ──

/** 节点 mousedown → 开始拖拽节点 */
function onNodeMouseDown(e: MouseEvent, stepId: string) {
  // 忽略右键和锚点上的事件
  if (e.button !== 0) return
  const target = e.target as HTMLElement
  if (target.closest('[data-anchor-input]') || target.closest('[data-anchor-output]')) return

  e.preventDefault()
  const pos = positions.value[stepId]
  if (!pos || !containerRef.value) return

  draggingNodeId.value = stepId
  const containerRect = containerRef.value.getBoundingClientRect()
  dragOffset.value = {
    x: e.clientX - containerRect.left + containerRef.value.scrollLeft - panOffset.value.x - pos.x,
    y: e.clientY - containerRect.top + containerRef.value.scrollTop - panOffset.value.y - pos.y,
  }

  document.addEventListener('mousemove', onNodeDragMove)
  document.addEventListener('mouseup', onNodeDragEnd)
}

/** 节点拖拽中 → 更新位置（考虑平移偏移） */
function onNodeDragMove(e: MouseEvent) {
  if (!draggingNodeId.value || !containerRef.value) return
  const containerRect = containerRef.value.getBoundingClientRect()
  const newX = e.clientX - containerRect.left + containerRef.value.scrollLeft - panOffset.value.x - dragOffset.value.x
  const newY = e.clientY - containerRect.top + containerRef.value.scrollTop - panOffset.value.y - dragOffset.value.y
  setPos(draggingNodeId.value, {
    x: Math.max(0, newX),
    y: Math.max(0, newY),
  })
}

/** 节点拖拽结束 */
function onNodeDragEnd() {
  draggingNodeId.value = null
  document.removeEventListener('mousemove', onNodeDragMove)
  document.removeEventListener('mouseup', onNodeDragEnd)
}

// ── 连接锚点拖拽交互 ──

/** 从底部出口锚点开始拖拽连线 */
function onAnchorMouseDown(e: MouseEvent, stepId: string) {
  e.stopPropagation()
  e.preventDefault()
  connectingFrom.value = stepId
  updateMousePos(e)
  document.addEventListener('mousemove', onConnectMouseMove)
  document.addEventListener('mouseup', onConnectMouseUp)
}

/** 條件節點的 Then/Else 出口錨點拖拽開始 */
function onConditionAnchorMouseDown(e: MouseEvent, stepId: string, branch: 'then' | 'else') {
  e.stopPropagation()
  e.preventDefault()
  // 使用特殊的連接ID：stepId + '-' + branch
  connectingFrom.value = `${stepId}-${branch}`
  updateMousePos(e)
  document.addEventListener('mousemove', onConnectMouseMove)
  document.addEventListener('mouseup', onConditionAnchorMouseUp)
}

/** 條件節點出口錨點連接完成 */
function onConditionAnchorMouseUp(e: MouseEvent) {
  // 這裡需要處理特殊的連接邏輯
  // 當從 Then 出口連接時，目標節點應該被添加到 thenSteps
  // 當從 Else 出口連接時，目標節點應該被添加到 elseSteps
  // 目前的實現只是發送普通的 connect 事件，需要在父組件處理
  if (connectingFrom.value) {
    const el = document.elementFromPoint(e.clientX, e.clientY) as HTMLElement | null
    const anchorInput = el?.closest('[data-anchor-input]') as HTMLElement | null
    if (anchorInput) {
      const targetId = anchorInput.getAttribute('data-anchor-input')
      if (targetId) {
        // 解析連接的源和目標
        const parts = connectingFrom.value.split('-')
        const sourceStepId = parts.slice(0, -1).join('-') // 移除最後的 then/else
        const branch = parts[parts.length - 1] as 'then' | 'else'
        
        // 發送帶分支信息的連接事件
        emit('connect', sourceStepId, targetId, branch)
      }
    }
  }
  cancelConnection()
}

/** 鼠标释放在顶部入口锚点上 → 完成连线 */
function onAnchorMouseUp(e: MouseEvent, stepId: string) {
  e.stopPropagation()
  if (connectingFrom.value && connectingFrom.value !== stepId) {
    emit('connect', connectingFrom.value, stepId)
  }
  cancelConnection()
}

/** 连线拖拽中更新鼠标位置 */
function onConnectMouseMove(e: MouseEvent) {
  updateMousePos(e)
}

/** 连线拖拽结束 → 检查是否在入口锚点上 */
function onConnectMouseUp(e: MouseEvent) {
  if (connectingFrom.value) {
    const el = document.elementFromPoint(e.clientX, e.clientY) as HTMLElement | null
    const anchorInput = el?.closest('[data-anchor-input]') as HTMLElement | null
    if (anchorInput) {
      const targetId = anchorInput.getAttribute('data-anchor-input')
      if (targetId && targetId !== connectingFrom.value) {
        emit('connect', connectingFrom.value, targetId)
      }
    }
  }
  cancelConnection()
}

/** 更新鼠标位置（相对于画布内容区域，考虑平移偏移） */
function updateMousePos(e: MouseEvent) {
  if (!containerRef.value) return
  const rect = containerRef.value.getBoundingClientRect()
  mousePos.value = {
    x: e.clientX - rect.left + containerRef.value.scrollLeft - panOffset.value.x,
    y: e.clientY - rect.top + containerRef.value.scrollTop - panOffset.value.y,
  }
}

/** 取消连线拖拽 */
function cancelConnection() {
  connectingFrom.value = null
  document.removeEventListener('mousemove', onConnectMouseMove)
  document.removeEventListener('mouseup', onConnectMouseUp)
}


// ── 从 StepPalette 拖放处理 ──

function onDragOver(e: DragEvent) {
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'
}

function onDrop(e: DragEvent) {
  e.preventDefault()
  const type = e.dataTransfer?.getData('text/plain') as StepType
  if (type && STEP_TYPE_META[type]) {
    // 在 drop 位置放置新节点
    if (containerRef.value) {
      const rect = containerRef.value.getBoundingClientRect()
      const dropX = e.clientX - rect.left + containerRef.value.scrollLeft - panOffset.value.x - NODE_WIDTH / 2
      const dropY = e.clientY - rect.top + containerRef.value.scrollTop - panOffset.value.y - NODE_HEIGHT / 2
      // 先 emit 添加步骤，然后在 watch 中为新步骤设置 drop 位置
      pendingDropPos.value = { x: Math.max(0, dropX), y: Math.max(0, dropY) }
    }
    emit('drop-step', type)
  }
}

/** 待设置的 drop 位置（用于新步骤） */
const pendingDropPos = ref<{ x: number; y: number } | null>(null)

// ── 键盘快捷键 ──

function onKeyDown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    closeContextMenu()
    if (connectingFrom.value) cancelConnection()
    return
  }
  if ((e.key === 'Delete' || e.key === 'Backspace') && props.selectedStepId) {
    const target = e.target as HTMLElement
    const tagName = target.tagName.toLowerCase()
    if (tagName === 'input' || tagName === 'textarea' || target.isContentEditable) return
    e.preventDefault()
    emit('delete-step', props.selectedStepId)
  }
}

// ── 画布平移交互 ──

/** 画布 mousedown → 左键在空白区域开始平移，中键任意位置平移 */
function onCanvasMouseDown(e: MouseEvent) {
  // 中键拖动（任意位置）
  if (e.button === 1) {
    e.preventDefault()
    startPanning(e)
    return
  }
  // 左键：只在空白画布区域触发拖动（不在节点、锚点上）
  if (e.button === 0) {
    const target = e.target as HTMLElement
    // 如果点击的是节点、锚点、按钮等交互元素，不启动平移
    if (target.closest('[data-step-id]') || target.closest('[data-anchor-input]') || target.closest('[data-anchor-output]') || target.closest('button')) {
      return
    }
    e.preventDefault()
    startPanning(e)
  }
}

function startPanning(e: MouseEvent) {
  isPanning.value = true
  panStart.value = { x: e.clientX, y: e.clientY }
  panOffsetStart.value = { ...panOffset.value }
  document.addEventListener('mousemove', onPanMove)
  document.addEventListener('mouseup', onPanEnd)
}

function onPanMove(e: MouseEvent) {
  if (!isPanning.value) return
  const dx = e.clientX - panStart.value.x
  const dy = e.clientY - panStart.value.y
  panOffset.value = {
    x: panOffsetStart.value.x + dx,
    y: panOffsetStart.value.y + dy,
  }
}

function onPanEnd() {
  isPanning.value = false
  document.removeEventListener('mousemove', onPanMove)
  document.removeEventListener('mouseup', onPanEnd)
}

// ── 右键上下文菜单 ──

function onNodeContextMenu(e: MouseEvent, stepId: string) {
  e.preventDefault()
  e.stopPropagation()
  // stepId 这里实际是 canvasId，需要查找对应的原始 stepId
  const node = canvasNodes.value.find(n => n.canvasId === stepId)
  contextMenu.value = {
    x: e.clientX, y: e.clientY, type: 'step',
    stepId: node?.stepId ?? stepId,
    canvasId: stepId,
  }
}

function onLineContextMenu(e: MouseEvent, fromId: string, toId: string) {
  e.preventDefault()
  e.stopPropagation()
  contextMenu.value = { x: e.clientX, y: e.clientY, type: 'line', fromId, toId }
}

function closeContextMenu() {
  contextMenu.value = null
}

function ctxDeleteStep() {
  if (contextMenu.value?.stepId) emit('delete-step', contextMenu.value.stepId)
  closeContextMenu()
}

function ctxDisconnectAll() {
  if (contextMenu.value?.canvasId) emit('disconnect', contextMenu.value.canvasId, '')
  closeContextMenu()
}

function ctxDisconnectLine() {
  if (contextMenu.value?.fromId && contextMenu.value?.toId) {
    emit('disconnect', contextMenu.value.fromId, contextMenu.value.toId)
  }
  closeContextMenu()
}

function onContainerClick() {
  closeContextMenu()
}

// ── 悬停高亮 ──

function onNodeMouseEnter(stepId: string) {
  hoveredStepId.value = stepId
}

function onNodeMouseLeave() {
  hoveredStepId.value = null
}

function isLineHighlighted(line: { fromId: string; toId: string }): boolean {
  if (!hoveredStepId.value) return false
  return line.fromId === hoveredStepId.value || line.toId === hoveredStepId.value
}

// ── 辅助函数：獲取節點的顯示名稱 ──
function getNodeDisplayName(node: CanvasNode): string {
  if (node.branch === 'root') {
    return node.name || node.stepId
  }
  // 分支內的節點顯示分支類型前綴
  const branchLabel = node.branch === 'then' ? 'Then' : node.branch === 'else' ? 'Else' : node.branch === 'loop' ? 'Loop' : node.branch
  return `${branchLabel}: ${node.name || node.stepId}`
}

// ── 辅助函数：獲取節點的分支標籤 ──
function getBranchLabel(branch: BranchType): string {
  switch (branch) {
    case 'root': return ''
    case 'then': return 'Then'
    case 'else': return 'Else'
    case 'loop': return 'Loop'
    default: return (branch as string).replace('branch-', 'Branch ')
  }
}

// ── 步骤变化监听 ──
/**
 * 确保每个步骤都有位置。
 * 使用 canvasNodes 的 canvasId 列表的 join 作为 watch 源，避免 deep watch 的同引用问题。
 */
watch(
  () => canvasNodes.value.map(n => n.canvasId).join(','),
  () => {
    let needAutoLayout = false
    const newPositions = { ...positions.value }

    // 为没有位置的新步骤分配位置
    for (const node of canvasNodes.value) {
      if (!(node.canvasId in newPositions)) {
        if (pendingDropPos.value) {
          newPositions[node.canvasId] = { ...pendingDropPos.value }
          pendingDropPos.value = null
        } else {
          needAutoLayout = true
        }
      }
    }

    // 清理已删除步骤的位置
    const currentIds = new Set(canvasNodes.value.map(n => n.canvasId))
    for (const id of Object.keys(newPositions)) {
      if (!currentIds.has(id)) {
        delete newPositions[id]
      }
    }

    positions.value = newPositions

    // 如果有需要自动布局的新步骤，调用 autoLayoutNewNodes
    if (needAutoLayout) {
      autoLayoutNewNodes()
    }
  },
  { immediate: true },
)

onMounted(() => {
  document.addEventListener('click', onDocumentClickForMenu)
})

onUnmounted(() => {
  cancelConnection()
  if (draggingNodeId.value) onNodeDragEnd()
  if (isPanning.value) onPanEnd()
  document.removeEventListener('click', onDocumentClickForMenu)
})

function onDocumentClickForMenu() {
  closeContextMenu()
}
</script>

<template>
  <div
    ref="containerRef"
    class="relative flex-1 overflow-auto bg-muted/30 outline-none cursor-grab"
    :class="{ '!cursor-grabbing': isPanning }"
    tabindex="0"
    @dragover="onDragOver"
    @drop="onDrop"
    @keydown="onKeyDown"
    @click="onContainerClick"
    @mousedown="onCanvasMouseDown"
  >
    <!-- 空画布引导提示 -->
    <div
      v-if="canvasNodes.length === 0"
      class="flex h-full items-center justify-center"
    >
      <div class="flex flex-col items-center gap-3 text-muted-foreground">
        <PackagePlus class="h-10 w-10 opacity-40" />
        <p class="text-sm">从左侧拖拽步骤到此处开始编排</p>
      </div>
    </div>

    <!-- 有步骤时：SVG 连线层 + 绝对定位节点 -->
    <template v-else>
      <!-- 可滚动内容区域（确保 SVG 和节点层都在同一个可滚动容器内） -->
      <div
        class="relative"
        :style="{
          minWidth: canvasSize.width + 'px',
          minHeight: canvasSize.height + 'px',
          transform: `translate(${panOffset.x}px, ${panOffset.y}px)`,
        }"
      >
      <!-- SVG 连线层 -->
      <svg
        class="absolute inset-0"
        :width="canvasSize.width"
        :height="canvasSize.height"
        style="z-index: 3; pointer-events: none"
      >
        <!-- 已有 DAG 连线 -->
        <template v-for="(line, i) in lines" :key="i">
          <!-- 透明宽路径：扩大鼠标交互区域 -->
          <path
            :d="bezierPath(line)"
            fill="none"
            stroke="transparent"
            stroke-width="12"
            class="cursor-pointer"
            style="pointer-events: stroke"
            @contextmenu="onLineContextMenu($event, line.fromId, line.toId)"
          />
          <!-- 可见连线 -->
          <path
            :d="bezierPath(line)"
            fill="none"
            :stroke="getLineColor(line)"
            :stroke-width="getLineWidth(line)"
            :stroke-dasharray="getLineDash(line)"
            class="pointer-events-none transition-all duration-150"
          />
          <!-- 箭头标记 -->
          <polygon
            :points="arrowPoints(line)"
            :fill="getLineColor(line)"
            class="pointer-events-none transition-all duration-150"
          />
        </template>
        <!-- 拖拽中的临时连线 -->
        <path
          v-if="tempLine"
          :d="bezierPath(tempLine)"
          fill="none"
          stroke="hsl(var(--primary))"
          stroke-width="2"
          stroke-dasharray="4 4"
          class="pointer-events-none"
        />
      </svg>

      <!-- 绝对定位节点层 -->
      <div
        class="relative"
        :style="{ width: canvasSize.width + 'px', height: canvasSize.height + 'px' }"
        :class="connectingFrom ? 'z-[4]' : 'z-[2]'"
      >
        <div
          v-for="node in canvasNodes"
          :key="node.canvasId"
          :data-step-id="node.canvasId"
          class="group/node absolute flex flex-col gap-1.5 rounded-lg border-2 bg-background px-3 py-2.5 shadow-sm transition-shadow select-none"
          :class="[
            draggingNodeId === node.canvasId ? 'cursor-grabbing shadow-lg' : 'cursor-grab hover:shadow-md',
            selectedStepId === node.stepId
              ? 'border-primary ring-2 ring-primary/20'
              : validationErrors.has(node.stepId)
                ? 'border-destructive ring-2 ring-destructive/20'
                : 'border-border hover:border-primary/50',
            // 分支節點的特殊樣式
            node.branch === 'then' ? 'bg-green-50/80 dark:bg-green-950/40 border-green-200 dark:border-green-800' :
            node.branch === 'else' ? 'bg-red-50/80 dark:bg-red-950/40 border-red-200 dark:border-red-800' :
            node.branch === 'loop' ? 'bg-blue-50/80 dark:bg-blue-950/40 border-blue-200 dark:border-blue-800' :
            ''
          ]"
          :style="{
            left: getPos(node.canvasId).x + 'px',
            top: getPos(node.canvasId).y + 'px',
            width: NODE_WIDTH + 'px',
          }"
          @mousedown="onNodeMouseDown($event, node.canvasId)"
          @click.stop="emit('select-step', node.stepId)"
          @contextmenu="onNodeContextMenu($event, node.canvasId)"
          @mouseenter="onNodeMouseEnter(node.canvasId)"
          @mouseleave="onNodeMouseLeave"
        >
          <!-- 顶部入口锚点：根節點顯示，分支節點隱藏（因為已經通過分支連線連接） -->
          <div
            v-if="node.branch === 'root'"
            :data-anchor-input="node.canvasId"
            class="absolute -top-2 left-1/2 z-10 h-4 w-4 -translate-x-1/2 cursor-crosshair rounded-full border-2 border-background transition-all hover:scale-125"
            :class="connectingFrom
              ? 'bg-primary/70 scale-110 animate-pulse'
              : 'bg-gray-400 opacity-50 group-hover/node:opacity-100 group-hover/node:bg-primary/60'"
            @mouseup.stop="onAnchorMouseUp($event, node.canvasId)"
          />

          <!-- 上部：图标 + 名称 + ID -->
          <div class="flex items-center gap-2">
            <component
              :is="STEP_TYPE_META[node.type].icon"
              class="h-4 w-4 shrink-0 transition-colors"
              :class="selectedStepId === node.stepId ? 'text-primary' : 'text-muted-foreground group-hover/node:text-primary/70'"
            />
            <div class="min-w-0">
              <div class="truncate text-xs font-medium">
                {{ getNodeDisplayName(node) }}
              </div>
              <div class="truncate text-[10px] text-muted-foreground">
                {{ node.stepId }}
              </div>
            </div>
          </div>
          
          <!-- 分支標籤 + 類型標籤 -->
          <div class="flex items-center gap-1 flex-wrap">
            <span v-if="getBranchLabel(node.branch)" 
              class="inline-flex items-center rounded-full px-1.5 py-0.5 text-[10px] font-medium"
              :class="node.branch === 'then' ? 'bg-green-100 text-green-700 dark:bg-green-900 dark:text-green-300' :
                     node.branch === 'else' ? 'bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-300' :
                     node.branch === 'loop' ? 'bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300' :
                     'bg-yellow-100 text-yellow-700 dark:bg-yellow-900 dark:text-yellow-300'"
            >
              {{ getBranchLabel(node.branch) }}
            </span>
            <span class="inline-flex w-fit items-center rounded-full bg-muted px-1.5 py-0.5 text-[10px] text-muted-foreground">
              {{ STEP_TYPE_META[node.type].label }}
            </span>
          </div>

          <!-- 底部出口锚点 -->
          <!-- 條件節點有兩個出口：Then 和 Else -->
          <template v-if="node.type === 'condition'">
            <!-- Then 出口錨點 -->
            <div
              :data-anchor-output="node.canvasId + '-then'"
              class="absolute -bottom-2 left-1/4 z-10 h-4 w-4 -translate-x-1/2 cursor-crosshair rounded-full border-2 border-background transition-all hover:scale-125"
              :class="connectingFrom === node.canvasId + '-then'
                ? 'bg-green-500 scale-125'
                : 'bg-green-400 opacity-50 group-hover/node:opacity-100 group-hover/node:bg-green-500'"
              @mousedown.stop="onConditionAnchorMouseDown($event, node.canvasId, 'then')"
            >
              <span class="absolute -bottom-4 left-1/2 -translate-x-1/2 text-[8px] font-medium text-green-600 dark:text-green-400 whitespace-nowrap">Then</span>
            </div>
            <!-- Else 出口錨點 -->
            <div
              :data-anchor-output="node.canvasId + '-else'"
              class="absolute -bottom-2 left-3/4 z-10 h-4 w-4 -translate-x-1/2 cursor-crosshair rounded-full border-2 border-background transition-all hover:scale-125"
              :class="connectingFrom === node.canvasId + '-else'
                ? 'bg-red-500 scale-125'
                : 'bg-red-400 opacity-50 group-hover/node:opacity-100 group-hover/node:bg-red-500'"
              @mousedown.stop="onConditionAnchorMouseDown($event, node.canvasId, 'else')"
            >
              <span class="absolute -bottom-4 left-1/2 -translate-x-1/2 text-[8px] font-medium text-red-600 dark:text-red-400 whitespace-nowrap">Else</span>
            </div>
          </template>
          <!-- 普通節點只有一個出口 -->
          <div
            v-else
            :data-anchor-output="node.canvasId"
            class="absolute -bottom-2 left-1/2 z-10 h-4 w-4 -translate-x-1/2 cursor-crosshair rounded-full border-2 border-background transition-all hover:scale-125"
            :class="connectingFrom === node.canvasId
              ? 'bg-primary scale-125'
              : 'bg-gray-400 opacity-50 group-hover/node:opacity-100 group-hover/node:bg-primary/60'"
            @mousedown.stop="onAnchorMouseDown($event, node.canvasId)"
          />
        </div>
      </div>
      </div>
    </template>

    <!-- 右键上下文菜单 -->
    <Teleport to="body">
      <div
        v-if="contextMenu"
        class="fixed z-50 min-w-[140px] rounded-md border bg-popover py-1 shadow-lg"
        :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
        @click.stop
      >
        <template v-if="contextMenu.type === 'step'">
          <button
            type="button"
            class="flex w-full items-center gap-2 px-3 py-1.5 text-sm text-popover-foreground hover:bg-accent"
            @click="ctxDeleteStep"
          >
            <Trash2 class="h-3.5 w-3.5" />
            删除步骤
          </button>
          <button
            type="button"
            class="flex w-full items-center gap-2 px-3 py-1.5 text-sm text-popover-foreground hover:bg-accent"
            @click="ctxDisconnectAll"
          >
            <Unlink class="h-3.5 w-3.5" />
            删除所有连线
          </button>
        </template>
        <template v-if="contextMenu.type === 'line'">
          <button
            type="button"
            class="flex w-full items-center gap-2 px-3 py-1.5 text-sm text-popover-foreground hover:bg-accent"
            @click="ctxDisconnectLine"
          >
            <Unlink class="h-3.5 w-3.5" />
            删除连线
          </button>
        </template>
      </div>
    </Teleport>
  </div>
</template>
