<!--
  编排画布组件。
  基于 CSS Flex 分层布局 + SVG 连线层的交互式 DAG 画布。
  任务 3.1：基础布局 + SVG 连线 + 空画布引导 + 拖放区域。
  节点渲染细节（图标、选中高亮等）在 3.2 实现，连接锚点在 3.3，交互在 3.4。
-->
<script setup lang="ts">
import { computed, ref, watch, nextTick, onMounted } from 'vue'
import type { StepModel, StepType } from '@/composables/useWorkflowModel'
import { useDagLayout } from '@/composables/useDagLayout'
import { STEP_TYPE_META } from '@/components/workflow/editor/stepTypeMeta'
import { PackagePlus } from 'lucide-vue-next'

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

// SVG 连线状态
const lines = ref<Array<{ x1: number; y1: number; x2: number; y2: number }>>([])

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

// 拖放处理
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

// 步骤变化时重新计算连线
watch(() => props.steps, () => nextTick(updateLines), { deep: true })
onMounted(() => nextTick(updateLines))
</script>

<template>
  <div
    ref="containerRef"
    class="relative flex-1 overflow-auto bg-muted/30"
    @dragover="onDragOver"
    @drop="onDrop"
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
        v-if="lines.length > 0"
        class="pointer-events-none absolute inset-0 h-full w-full"
        style="z-index: 0"
      >
        <path
          v-for="(line, i) in lines"
          :key="i"
          :d="bezierPath(line)"
          fill="none"
          stroke="#9ca3af"
          stroke-width="1.5"
          stroke-dasharray="6 3"
        />
      </svg>

      <!-- 分层节点网格 -->
      <div class="relative space-y-6 p-6" style="z-index: 1">
        <div
          v-for="(layer, layerIdx) in layers"
          :key="layerIdx"
          class="flex flex-wrap items-start justify-center gap-4"
        >
          <!-- 简单节点盒子（3.2 会增强渲染细节） -->
          <div
            v-for="stepId in layer"
            :key="stepId"
            :data-step-id="stepId"
            class="flex min-w-[140px] max-w-[200px] cursor-pointer items-center gap-2 rounded-lg border-2 bg-background px-3 py-2 transition-colors hover:border-primary/50"
            :class="{
              'border-primary ring-2 ring-primary/20': selectedStepId === stepId,
              'border-destructive': validationErrors.has(stepId),
              'border-border': selectedStepId !== stepId && !validationErrors.has(stepId),
            }"
            @click="emit('select-step', stepId)"
          >
            <component
              :is="STEP_TYPE_META[stepMap.get(stepId)!.type].icon"
              class="h-4 w-4 shrink-0 text-muted-foreground"
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
        </div>
      </div>
    </template>
  </div>
</template>
