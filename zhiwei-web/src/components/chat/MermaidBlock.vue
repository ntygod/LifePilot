<script setup lang="ts">
/**
 * Mermaid 图表渲染块 — 支持图片/代码切换 + 缩放/重置/下载
 *
 * 流式阶段显示源码，流式结束后自动渲染为 SVG。
 * 渲染失败时 fallback 回源码显示并提示错误。
 *
 * @author zsg
 * @since 2026-05-14
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { Code2, Download, Image, RotateCcw, ZoomIn, ZoomOut } from 'lucide-vue-next'

const props = defineProps<{
  /** Mermaid 源码 */
  code: string
  /** 是否处于流式输出阶段 */
  streaming?: boolean
}>()

type ViewMode = 'diagram' | 'code'

const viewMode = ref<ViewMode>('diagram')
const svgContent = ref('')
const renderError = ref<string | null>(null)
const scale = ref(1)
const containerRef = ref<HTMLElement | null>(null)

const MIN_SCALE = 0.5
const MAX_SCALE = 2.5
const SCALE_STEP = 0.2

/** 唯一 ID，mermaid.render 需要 */
let idCounter = 0
function nextId() {
  return `mermaid-${Date.now()}-${idCounter++}`
}

const canRender = computed(() => !props.streaming && props.code.trim().length > 0)

async function renderDiagram() {
  if (!canRender.value) return

  renderError.value = null
  try {
    const { default: mermaid } = await import('mermaid')
    mermaid.initialize({
      startOnLoad: false,
      theme: document.documentElement.classList.contains('dark') ? 'dark' : 'default',
      securityLevel: 'strict',
      fontFamily: 'var(--font-sans)',
    })
    const { svg } = await mermaid.render(nextId(), props.code.trim())
    svgContent.value = svg
    viewMode.value = 'diagram'
  } catch (e) {
    renderError.value = e instanceof Error ? e.message : '图表渲染失败'
    svgContent.value = ''
    viewMode.value = 'code'
  }
}

function zoomIn() {
  scale.value = Math.min(MAX_SCALE, scale.value + SCALE_STEP)
}

function zoomOut() {
  scale.value = Math.max(MIN_SCALE, scale.value - SCALE_STEP)
}

function resetZoom() {
  scale.value = 1
}

function downloadSvg() {
  if (!svgContent.value) return
  const blob = new Blob([svgContent.value], { type: 'image/svg+xml;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'diagram.svg'
  a.click()
  URL.revokeObjectURL(url)
}

// 流式结束后自动渲染
watch(() => props.streaming, (streaming, prev) => {
  if (prev && !streaming) {
    nextTick(() => renderDiagram())
  }
})

// 非流式场景（历史消息）直接渲染
onMounted(() => {
  if (canRender.value) {
    renderDiagram()
  }
})

// 暗色模式切换时重新渲染
const observer = new MutationObserver(() => {
  if (svgContent.value) {
    renderDiagram()
  }
})

onMounted(() => {
  observer.observe(document.documentElement, {
    attributes: true,
    attributeFilter: ['class'],
  })
})

import { onUnmounted } from 'vue'
onUnmounted(() => {
  observer.disconnect()
})
</script>

<template>
  <div class="mermaid-block">
    <!-- 工具栏 -->
    <div class="mermaid-toolbar">
      <div class="mermaid-tabs">
        <button
          type="button"
          class="mermaid-tab"
          :class="{ 'mermaid-tab--active': viewMode === 'diagram' }"
          :disabled="!svgContent"
          @click="viewMode = 'diagram'"
        >
          <Image class="size-3.5" />
          图片
        </button>
        <button
          type="button"
          class="mermaid-tab"
          :class="{ 'mermaid-tab--active': viewMode === 'code' }"
          @click="viewMode = 'code'"
        >
          <Code2 class="size-3.5" />
          代码
        </button>
      </div>

      <div v-if="viewMode === 'diagram' && svgContent" class="mermaid-actions">
        <button type="button" class="mermaid-action" title="放大" @click="zoomIn">
          <ZoomIn class="size-3.5" />
        </button>
        <button type="button" class="mermaid-action" title="缩小" @click="zoomOut">
          <ZoomOut class="size-3.5" />
        </button>
        <button type="button" class="mermaid-action" title="重置" @click="resetZoom">
          <RotateCcw class="size-3.5" />
        </button>
        <button type="button" class="mermaid-action" title="下载 SVG" @click="downloadSvg">
          <Download class="size-3.5" />
        </button>
      </div>
    </div>

    <!-- 图表视图 -->
    <div
      v-if="viewMode === 'diagram' && svgContent"
      ref="containerRef"
      class="mermaid-diagram"
    >
      <div
        class="mermaid-svg-wrapper"
        :style="{ transform: `scale(${scale})` }"
        v-html="svgContent"
      />
    </div>

    <!-- 代码视图 -->
    <div v-if="viewMode === 'code' || !svgContent" class="mermaid-code">
      <pre class="mermaid-pre"><code>{{ code }}</code></pre>
    </div>

    <!-- 渲染错误提示 -->
    <div v-if="renderError" class="mermaid-error">
      渲染失败：{{ renderError }}
    </div>
  </div>
</template>

<style scoped>
.mermaid-block {
  position: relative;
  margin: 0.95rem 0;
  border: 1px solid hsl(from var(--border) h s l / 0.5);
  border-radius: 0.95rem;
  overflow: hidden;
  background: var(--card);
}

.mermaid-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  padding: 0.5rem 0.75rem;
  border-bottom: 1px solid hsl(from var(--border) h s l / 0.4);
  background: hsl(from var(--muted) h s l / 0.3);
}

.mermaid-tabs {
  display: flex;
  gap: 2px;
  padding: 2px;
  border-radius: 0.5rem;
  background: hsl(from var(--muted) h s l / 0.5);
}

.mermaid-tab {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  padding: 0.3rem 0.6rem;
  border-radius: 0.375rem;
  border: none;
  background: transparent;
  font-size: 12px;
  font-weight: 500;
  color: var(--muted-foreground);
  cursor: pointer;
  transition: all 140ms ease;
}

.mermaid-tab:hover:not(:disabled) {
  color: var(--foreground);
}

.mermaid-tab--active {
  background: var(--background);
  color: var(--foreground);
  box-shadow: 0 1px 2px hsl(var(--shadow-color) / 0.06);
}

.mermaid-tab:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.mermaid-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}

.mermaid-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.625rem;
  height: 1.625rem;
  border-radius: 0.375rem;
  border: none;
  background: transparent;
  color: var(--muted-foreground);
  cursor: pointer;
  transition: all 140ms ease;
}

.mermaid-action:hover {
  background: hsl(from var(--muted) h s l / 0.6);
  color: var(--foreground);
}

.mermaid-diagram {
  overflow: auto;
  padding: 1.5rem;
  min-height: 120px;
  display: flex;
  align-items: center;
  justify-content: center;
  scrollbar-width: thin;
}

.mermaid-svg-wrapper {
  transform-origin: center center;
  transition: transform 200ms cubic-bezier(0.22, 1, 0.36, 1);
}

.mermaid-svg-wrapper :deep(svg) {
  max-width: 100%;
  height: auto;
}

.mermaid-code {
  overflow: auto;
  scrollbar-width: thin;
}

.mermaid-pre {
  margin: 0;
  padding: 1rem;
  font-size: 0.82rem;
  line-height: 1.7;
  font-family: var(--font-mono);
  color: hsl(from var(--foreground) h s l / 0.82);
  background: hsl(from var(--muted) h s l / 0.2);
  white-space: pre-wrap;
  word-break: break-word;
}

.mermaid-error {
  padding: 0.5rem 0.75rem;
  font-size: 11px;
  color: hsl(from var(--destructive) h s l / 0.8);
  background: hsl(from var(--destructive) h s l / 0.05);
  border-top: 1px solid hsl(from var(--destructive) h s l / 0.12);
}
</style>
