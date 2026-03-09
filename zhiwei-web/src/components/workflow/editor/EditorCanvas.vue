<!--
  编排画布组件。
  基于 CSS Flex 分层布局 + SVG 连线层的交互式 DAG 画布。
  任务 3.1：基础布局 + SVG 连线 + 空画布引导 + 拖放区域。
  任务 3.2：步骤节点渲染（类型图标、名称、ID、类型标签、选中高亮、验证错误红色边框、悬停效果）。
  任务 3.3：连接锚点（底部出口 + 顶部入口）+ 拖拽连线交互。
  任务 3.4：键盘删除、右键上下文菜单、悬停高亮连线。
-->
<script setup lang="ts">
import { computed, ref, watch, nextTick, onMounted, onUnmounted } from 'vue'
import type { StepModel, StepType } from '@/composables/useWorkflowModel'
import { useDagLayout } from '@/composables/useDagLayout'
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
  'connect': [fromId: string, toId: string]
  'disconnect': [fromId: string, toId: string]
  'delete-step': [stepId: string]
}>()

const { computeLayers } = useDagLayout()
const containerRef = ref<HTMLElement | null>(null)

// 拓扑分层
const layers = computed(() => computeLayers(props.steps))

// 步骤 ID → 步骤对象映射
const stepMap = computed(() => {
  const map = new Map<string, StepModel>()
  for (const s of props.steps) map.set(s.id, s)
  return map
})

// SVG 连线状态（含 fromId/toId 用于悬停高亮）
const lines = ref<Array<{ x1: number; y1: number; x2: number; y2: number; fromId: string; toId: string }>>([])

// ── 悬停高亮状态 ──
/** 当前悬停的步骤 ID，用于高亮关联连线 */
const hoveredStepId = ref<string | null>(null)

// ── 右键上下文菜单状态 ──
interface ContextMenuState {
  x: number
  y: number
  type: 'step' | 'line'
  stepId?: string
  fromId?: string
  toId?: string
}
const contextMenu = ref<ContextMenuState | null>(null)

// ── 连接拖拽状态 ──
/** 正在拖拽连线的源步骤 ID，null 表示未在拖拽 */
const connectingFrom = ref<string | null>(null)
/** 拖拽过程中鼠标位置（相对于容器） */
const mousePos = ref({ x: 0, y: 0 })

/** 临时连线：从源锚点到鼠标位置 */
const tempLine = computed(() => {
  if (!connectingFrom.value || !containerRef.value) return null
  const container = containerRef.value
  const containerRect = container.getBoundingClientRect()
  const fromEl = container.querySelector(`[data-step-id="${connectingFrom.value}"]`) as HTMLElement | null
  if (!fromEl) return null
  const fromRect = fromEl.getBoundingClientRect()
  return {
    x1: fromRect.left + fromRect.width / 2 - containerRect.left,
    y1: fromRect.bottom - containerRect.top,
    x2: mousePos.value.x,
    y2: mousePos.value.y,
  }
})

/**
 * 根据 DOM 元素位置计算 SVG 连线坐标。
 * 从源节点底部中心 → 目标节点顶部中心。
 */
function updateLines() {
  if (!containerRef.value) return
  const container = containerRef.value
  const containerRect = container.getBoundingClientRect()
  const newLines: typeof lines.value = []

  for (const step of props.steps) {
    for (const dep of step.dependsOn) {
      const fromEl = container.querySelector(`[data-step-id="${dep}"]`) as HTMLElement | null
      const toEl = container.querySelector(`[data-step-id="${step.id}"]`) as HTMLElement | null
      if (!fromEl || !toEl) continue

      const fromRect = fromEl.getBoundingClientRect()
      const toRect = toEl.getBoundingClientRect()

      newLines.push({
        x1: fromRect.left + fromRect.width / 2 - containerRect.left,
        y1: fromRect.bottom - containerRect.top,
        x2: toRect.left + toRect.width / 2 - containerRect.left,
        y2: toRect.top - containerRect.top,
        fromId: dep,
        toId: step.id,
      })
    }
  }
  lines.value = newLines
}

/**
 * 生成 SVG 贝塞尔曲线路径。
 * 从 (x1,y1) 到 (x2,y2)，控制点在垂直方向偏移以形成平滑曲线。
 */
function bezierPath(l: { x1: number; y1: number; x2: number; y2: number }): string {
  const dy = Math.abs(l.y2 - l.y1)
  const offset = Math.max(dy * 0.4, 20)
  return `M ${l.x1} ${l.y1} C ${l.x1} ${l.y1 + offset}, ${l.x2} ${l.y2 - offset}, ${l.x2} ${l.y2}`
}

// ── 连接锚点拖拽交互 ──

/** 从底部出口锚点开始拖拽连线 */
function onAnchorMouseDown(e: MouseEvent, stepId: string) {
  e.stopPropagation()
  e.preventDefault()
  connectingFrom.value = stepId
  updateMousePos(e)
  document.addEventListener('mousemove', onDocumentMouseMove)
  document.addEventListener('mouseup', onDocumentMouseUp)
}

/** 鼠标释放在顶部入口锚点上 → 完成连线 */
function onAnchorMouseUp(e: MouseEvent, stepId: string) {
  e.stopPropagation()
  if (connectingFrom.value && connectingFrom.value !== stepId) {
    emit('connect', connectingFrom.value, stepId)
  }
  cancelConnection()
}

/** 拖拽过程中更新鼠标位置 */
function onDocumentMouseMove(e: MouseEvent) {
  updateMousePos(e)
}

/** 鼠标释放 → 检查是否在入口锚点上，否则取消 */
function onDocumentMouseUp(e: MouseEvent) {
  if (connectingFrom.value) {
    // 检查鼠标下方是否有入口锚点
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

/** 更新鼠标位置（相对于容器） */
function updateMousePos(e: MouseEvent) {
  if (!containerRef.value) return
  const rect = containerRef.value.getBoundingClientRect()
  mousePos.value = {
    x: e.clientX - rect.left + containerRef.value.scrollLeft,
    y: e.clientY - rect.top + containerRef.value.scrollTop,
  }
}

/** 取消连线拖拽，清理事件监听 */
function cancelConnection() {
  connectingFrom.value = null
  document.removeEventListener('mousemove', onDocumentMouseMove)
  document.removeEventListener('mouseup', onDocumentMouseUp)
}

// 拖放处理（从 StepPalette 拖入步骤）
function onDragOver(e: DragEvent) {
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'
}

function onDrop(e: DragEvent) {
  e.preventDefault()
  const type = e.dataTransfer?.getData('text/plain') as StepType
  if (type && STEP_TYPE_META[type]) {
    emit('drop-step', type)
  }
}

// ── 键盘快捷键 ──

/** Delete/Backspace 删除选中步骤，Escape 关闭上下文菜单 */
function onKeyDown(e: KeyboardEvent) {
  // Escape 关闭上下文菜单
  if (e.key === 'Escape') {
    closeContextMenu()
    return
  }
  // Delete/Backspace 删除选中步骤（仅在画布聚焦时，不在 input/textarea 中）
  if ((e.key === 'Delete' || e.key === 'Backspace') && props.selectedStepId) {
    const target = e.target as HTMLElement
    const tagName = target.tagName.toLowerCase()
    if (tagName === 'input' || tagName === 'textarea' || target.isContentEditable) return
    e.preventDefault()
    emit('delete-step', props.selectedStepId)
  }
}

// ── 右键上下文菜单 ──

/** 步骤节点右键菜单 */
function onNodeContextMenu(e: MouseEvent, stepId: string) {
  e.preventDefault()
  e.stopPropagation()
  contextMenu.value = { x: e.clientX, y: e.clientY, type: 'step', stepId }
}

/** 连线右键菜单 */
function onLineContextMenu(e: MouseEvent, fromId: string, toId: string) {
  e.preventDefault()
  e.stopPropagation()
  contextMenu.value = { x: e.clientX, y: e.clientY, type: 'line', fromId, toId }
}

/** 关闭上下文菜单 */
function closeContextMenu() {
  contextMenu.value = null
}

/** 上下文菜单 — 删除步骤 */
function ctxDeleteStep() {
  if (contextMenu.value?.stepId) {
    emit('delete-step', contextMenu.value.stepId)
  }
  closeContextMenu()
}

/** 上下文菜单 — 删除步骤的所有连线 */
function ctxDisconnectAll() {
  if (contextMenu.value?.stepId) {
    // 发出 disconnect 事件，toId 为空字符串表示删除所有连线
    emit('disconnect', contextMenu.value.stepId, '')
  }
  closeContextMenu()
}

/** 上下文菜单 — 删除单条连线 */
function ctxDisconnectLine() {
  if (contextMenu.value?.fromId && contextMenu.value?.toId) {
    emit('disconnect', contextMenu.value.fromId, contextMenu.value.toId)
  }
  closeContextMenu()
}

/** 点击画布空白区域关闭上下文菜单 */
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

/** 判断连线是否与悬停步骤关联 */
function isLineHighlighted(line: { fromId: string; toId: string }): boolean {
  if (!hoveredStepId.value) return false
  return line.fromId === hoveredStepId.value || line.toId === hoveredStepId.value
}

// 步骤变化时重新计算连线
watch(() => props.steps, () => nextTick(updateLines), { deep: true })

onMounted(() => {
  nextTick(updateLines)
  document.addEventListener('click', onDocumentClickForMenu)
})

onUnmounted(() => {
  cancelConnection()
  document.removeEventListener('click', onDocumentClickForMenu)
})

/** 点击文档任意位置关闭上下文菜单（菜单外部） */
function onDocumentClickForMenu() {
  closeContextMenu()
}
</script>

<template>
  <div
    ref="containerRef"
    class="relative flex-1 overflow-auto bg-muted/30 outline-none"
    tabindex="0"
    @dragover="onDragOver"
    @drop="onDrop"
    @keydown="onKeyDown"
    @click="onContainerClick"
  >
    <!-- 空画布引导提示 -->
    <div
      v-if="steps.length === 0"
      class="flex h-full items-center justify-center"
    >
      <div class="flex flex-col items-center gap-3 text-muted-foreground">
        <PackagePlus class="h-10 w-10 opacity-40" />
        <p class="text-sm">从左侧拖拽步骤到此处开始编排</p>
      </div>
    </div>

    <!-- 有步骤时：SVG 连线层 + 分层节点 -->
    <template v-else>
      <!-- SVG 连线层 -->
      <svg
        class="absolute inset-0 h-full w-full"
        style="z-index: 0"
      >
        <!-- 已有 DAG 连线（可见线 + 透明宽击中区域） -->
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
            :stroke="isLineHighlighted(line) ? 'hsl(var(--primary))' : '#9ca3af'"
            :stroke-width="isLineHighlighted(line) ? 2.5 : 1.5"
            :stroke-dasharray="isLineHighlighted(line) ? 'none' : '6 3'"
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
          class="pointer-events-none"
        />
      </svg>

      <!-- 分层节点网格 -->
      <div class="relative space-y-6 p-6" style="z-index: 1">
        <div
          v-for="(layer, layerIdx) in layers"
          :key="layerIdx"
          class="flex flex-wrap items-start justify-center gap-4"
        >
          <!-- 步骤节点（类型图标 + 名称 + ID + 类型标签 + 选中高亮 + 验证错误边框 + 连接锚点） -->
          <div
            v-for="stepId in layer"
            :key="stepId"
            :data-step-id="stepId"
            class="group/node relative flex min-w-[160px] max-w-[220px] cursor-pointer flex-col gap-1.5 rounded-lg border-2 bg-background px-3 py-2.5 shadow-sm transition-all hover:border-primary/50 hover:shadow-md"
            :class="{
              'border-primary ring-2 ring-primary/20': selectedStepId === stepId,
              'border-destructive ring-2 ring-destructive/20': validationErrors.has(stepId) && selectedStepId !== stepId,
              'border-border': selectedStepId !== stepId && !validationErrors.has(stepId),
            }"
            @click="emit('select-step', stepId)"
            @contextmenu="onNodeContextMenu($event, stepId)"
            @mouseenter="onNodeMouseEnter(stepId)"
            @mouseleave="onNodeMouseLeave"
          >
            <!-- 顶部入口锚点 -->
            <div
              :data-anchor-input="stepId"
              class="absolute -top-1.5 left-1/2 z-10 h-3 w-3 -translate-x-1/2 cursor-crosshair rounded-full border-2 border-background bg-gray-400/60 transition-all hover:scale-150 hover:bg-primary"
              :class="connectingFrom ? 'scale-125 animate-pulse bg-primary/70' : 'opacity-0 group-hover/node:opacity-100'"
              @mouseup.stop="onAnchorMouseUp($event, stepId)"
            />

            <!-- 上部：图标 + 名称 + ID -->
            <div class="flex items-center gap-2">
              <component
                :is="STEP_TYPE_META[stepMap.get(stepId)!.type].icon"
                class="h-4 w-4 shrink-0 transition-colors"
                :class="selectedStepId === stepId ? 'text-primary' : 'text-muted-foreground group-hover/node:text-primary/70'"
              />
              <div class="min-w-0">
                <div class="truncate text-xs font-medium">
                  {{ stepMap.get(stepId)?.name ?? stepId }}
                </div>
                <div class="truncate text-[10px] text-muted-foreground">
                  {{ stepId }}
                </div>
              </div>
            </div>
            <!-- 类型标签 -->
            <span class="inline-flex w-fit items-center rounded-full bg-muted px-1.5 py-0.5 text-[10px] text-muted-foreground">
              {{ STEP_TYPE_META[stepMap.get(stepId)!.type].label }}
            </span>

            <!-- 底部出口锚点 -->
            <div
              :data-anchor-output="stepId"
              class="absolute -bottom-1.5 left-1/2 z-10 h-3 w-3 -translate-x-1/2 cursor-crosshair rounded-full border-2 border-background bg-gray-400/60 transition-all hover:scale-150 hover:bg-primary"
              :class="connectingFrom === stepId ? 'scale-150 bg-primary' : 'opacity-0 group-hover/node:opacity-100'"
              @mousedown="onAnchorMouseDown($event, stepId)"
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
        <!-- 步骤节点菜单 -->
        <template v-if="contextMenu.type === 'step'">
          <button
            class="flex w-full items-center gap-2 px-3 py-1.5 text-sm text-popover-foreground hover:bg-accent"
            @click="ctxDeleteStep"
          >
            <Trash2 class="h-3.5 w-3.5" />
            删除步骤
          </button>
          <button
            class="flex w-full items-center gap-2 px-3 py-1.5 text-sm text-popover-foreground hover:bg-accent"
            @click="ctxDisconnectAll"
          >
            <Unlink class="h-3.5 w-3.5" />
            删除所有连线
          </button>
        </template>
        <!-- 连线菜单 -->
        <template v-if="contextMenu.type === 'line'">
          <button
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
