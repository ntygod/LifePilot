<script setup lang="ts">
/**
 * 图片 Lightbox 预览器 —— 支持缩放、拖拽平移、滚轮缩放、双击还原。
 *
 * @author zsg
 * @since 2026-05-18
 */
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { X, ZoomIn, ZoomOut, RotateCcw, Download } from 'lucide-vue-next'

const props = defineProps<{
  src: string
  alt?: string
  open: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
}>()

const scale = ref(1)
const translateX = ref(0)
const translateY = ref(0)
const isDragging = ref(false)
const dragStart = ref({ x: 0, y: 0 })
const lastTranslate = ref({ x: 0, y: 0 })

const MIN_SCALE = 0.5
const MAX_SCALE = 5
const ZOOM_STEP = 0.25

function reset() {
  scale.value = 1
  translateX.value = 0
  translateY.value = 0
}

function zoomIn() {
  scale.value = Math.min(MAX_SCALE, scale.value + ZOOM_STEP)
}

function zoomOut() {
  scale.value = Math.max(MIN_SCALE, scale.value - ZOOM_STEP)
}

function handleWheel(e: WheelEvent) {
  e.preventDefault()
  const delta = e.deltaY > 0 ? -ZOOM_STEP : ZOOM_STEP
  scale.value = Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale.value + delta))
}

function handleDoubleClick() {
  if (scale.value !== 1) {
    reset()
  } else {
    scale.value = 2
  }
}

function handleMouseDown(e: MouseEvent) {
  if (e.button !== 0) return
  isDragging.value = true
  dragStart.value = { x: e.clientX, y: e.clientY }
  lastTranslate.value = { x: translateX.value, y: translateY.value }
  e.preventDefault()
}

function handleMouseMove(e: MouseEvent) {
  if (!isDragging.value) return
  translateX.value = lastTranslate.value.x + (e.clientX - dragStart.value.x)
  translateY.value = lastTranslate.value.y + (e.clientY - dragStart.value.y)
}

function handleMouseUp() {
  isDragging.value = false
}

function handleKeydown(e: KeyboardEvent) {
  if (!props.open) return
  if (e.key === 'Escape') emit('close')
  if (e.key === '+' || e.key === '=') zoomIn()
  if (e.key === '-') zoomOut()
  if (e.key === '0') reset()
}

function handleBackdropClick(e: MouseEvent) {
  if (e.target === e.currentTarget) {
    emit('close')
  }
}

watch(() => props.open, (open) => {
  if (open) {
    reset()
    document.body.style.overflow = 'hidden'
  } else {
    document.body.style.overflow = ''
  }
})

onMounted(() => {
  document.addEventListener('keydown', handleKeydown)
  document.addEventListener('mousemove', handleMouseMove)
  document.addEventListener('mouseup', handleMouseUp)
})

onUnmounted(() => {
  document.removeEventListener('keydown', handleKeydown)
  document.removeEventListener('mousemove', handleMouseMove)
  document.removeEventListener('mouseup', handleMouseUp)
  document.body.style.overflow = ''
})
</script>

<template>
  <Teleport to="body">
    <Transition name="lightbox">
      <div
        v-if="open"
        class="fixed inset-0 z-[9999] flex items-center justify-center bg-black/80 backdrop-blur-sm"
        @click="handleBackdropClick"
        @wheel.prevent="handleWheel"
      >
        <!-- 工具栏 -->
        <div class="absolute top-4 right-4 z-10 flex items-center gap-1 rounded-lg bg-black/60 px-2 py-1.5 shadow-lg">
          <button
            type="button"
            class="lightbox-btn"
            title="放大 (+)"
            @click="zoomIn"
          >
            <ZoomIn class="size-4" />
          </button>
          <button
            type="button"
            class="lightbox-btn"
            title="缩小 (-)"
            @click="zoomOut"
          >
            <ZoomOut class="size-4" />
          </button>
          <button
            type="button"
            class="lightbox-btn"
            title="还原 (0)"
            @click="reset"
          >
            <RotateCcw class="size-4" />
          </button>
          <a
            :href="src"
            :download="alt ?? 'image'"
            class="lightbox-btn"
            title="下载"
          >
            <Download class="size-4" />
          </a>
          <div class="mx-1 h-4 w-px bg-white/20" />
          <button
            type="button"
            class="lightbox-btn"
            title="关闭 (Esc)"
            @click="emit('close')"
          >
            <X class="size-4" />
          </button>
        </div>

        <!-- 缩放比例指示 -->
        <div class="absolute bottom-4 left-1/2 -translate-x-1/2 rounded-md bg-black/60 px-3 py-1 text-xs text-white/80">
          {{ Math.round(scale * 100) }}%
        </div>

        <!-- 图片 -->
        <img
          :src="src"
          :alt="alt ?? ''"
          class="max-h-[90vh] max-w-[90vw] select-none"
          :class="isDragging ? 'cursor-grabbing' : 'cursor-grab'"
          :style="{
            transform: `translate(${translateX}px, ${translateY}px) scale(${scale})`,
            transition: isDragging ? 'none' : 'transform 200ms ease',
          }"
          draggable="false"
          @mousedown="handleMouseDown"
          @dblclick="handleDoubleClick"
        />
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.lightbox-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 2rem;
  height: 2rem;
  border-radius: 0.375rem;
  color: white;
  transition: background 120ms ease;
}

.lightbox-btn:hover {
  background: rgba(255, 255, 255, 0.15);
}

.lightbox-enter-active,
.lightbox-leave-active {
  transition: opacity 200ms ease;
}

.lightbox-enter-from,
.lightbox-leave-to {
  opacity: 0;
}
</style>
