<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted } from 'vue'

interface TrendDataPoint {
  date: string
  score: number
  runId: string
}

const props = defineProps<{
  data: TrendDataPoint[]
}>()

// ── 响应式容器宽度 ──
const containerRef = ref<HTMLDivElement | null>(null)
const chartWidth = ref(600)

const CHART_HEIGHT = 200
const padding = { top: 20, right: 20, bottom: 30, left: 40 }

let resizeObserver: ResizeObserver | null = null

onMounted(() => {
  if (containerRef.value) {
    chartWidth.value = containerRef.value.clientWidth || 600
    if (typeof ResizeObserver !== 'undefined') {
      resizeObserver = new ResizeObserver((entries) => {
        for (const entry of entries) {
          chartWidth.value = entry.contentRect.width || 600
        }
      })
      resizeObserver.observe(containerRef.value)
    }
  }
})

onUnmounted(() => {
  resizeObserver?.disconnect()
})

// ── 绘图区域尺寸 ──
const plotW = computed(() => chartWidth.value - padding.left - padding.right)
const plotH = computed(() => CHART_HEIGHT - padding.top - padding.bottom)

// ── 数据点坐标映射 ──
const points = computed(() => {
  if (props.data.length === 0) return []
  return props.data.map((d, i) => ({
    x: padding.left + (i / Math.max(props.data.length - 1, 1)) * plotW.value,
    y: padding.top + (1 - d.score) * plotH.value,
    label: `${d.date}: ${(d.score * 100).toFixed(1)}%`,
    runId: d.runId
  }))
})

// ── 折线路径 ──
const polylinePoints = computed(() =>
  points.value.map(p => `${p.x},${p.y}`).join(' ')
)

// ── Y 轴标签（0% ~ 100%，每 20% 一格） ──
const yLabels = computed(() =>
  [0, 0.2, 0.4, 0.6, 0.8, 1.0].map(v => ({
    value: v,
    label: `${(v * 100).toFixed(0)}%`,
    y: padding.top + (1 - v) * plotH.value
  }))
)

// ── 0.6 阈值线 Y 坐标 ──
const thresholdY = computed(() => padding.top + (1 - 0.6) * plotH.value)

// ── X 轴标签（首尾日期） ──
const xLabels = computed(() => {
  if (props.data.length === 0) return []
  if (props.data.length === 1) {
    return [{ label: props.data[0].date, x: points.value[0]?.x ?? padding.left }]
  }
  return [
    { label: props.data[0].date, x: points.value[0].x },
    { label: props.data[props.data.length - 1].date, x: points.value[points.value.length - 1].x }
  ]
})
</script>

<template>
  <div ref="containerRef" class="w-full">
    <!-- 空数据时不渲染任何内容（父组件处理空状态） -->
    <svg
      v-if="data.length > 0"
      :width="chartWidth"
      :height="CHART_HEIGHT"
      :viewBox="`0 0 ${chartWidth} ${CHART_HEIGHT}`"
      class="w-full"
      role="img"
      aria-label="评分趋势图"
    >
      <!-- 水平网格线 -->
      <line
        v-for="yl in yLabels"
        :key="`grid-${yl.value}`"
        :x1="padding.left"
        :y1="yl.y"
        :x2="chartWidth - padding.right"
        :y2="yl.y"
        stroke="currentColor"
        class="text-border"
        stroke-width="0.5"
        stroke-opacity="0.4"
      />

      <!-- Y 轴标签 -->
      <text
        v-for="yl in yLabels"
        :key="`ylabel-${yl.value}`"
        :x="padding.left - 6"
        :y="yl.y + 3"
        text-anchor="end"
        class="fill-muted-foreground"
        font-size="10"
      >
        {{ yl.label }}
      </text>

      <!-- X 轴标签 -->
      <text
        v-for="(xl, idx) in xLabels"
        :key="`xlabel-${idx}`"
        :x="xl.x"
        :y="CHART_HEIGHT - 6"
        :text-anchor="idx === 0 ? 'start' : xLabels.length === 1 ? 'middle' : 'end'"
        class="fill-muted-foreground"
        font-size="10"
      >
        {{ xl.label }}
      </text>

      <!-- 0.6 阈值虚线 -->
      <line
        :x1="padding.left"
        :y1="thresholdY"
        :x2="chartWidth - padding.right"
        :y2="thresholdY"
        stroke="hsl(var(--destructive))"
        stroke-width="1"
        stroke-dasharray="4 3"
        stroke-opacity="0.7"
      />
      <text
        :x="chartWidth - padding.right + 2"
        :y="thresholdY + 3"
        class="fill-destructive"
        font-size="9"
        text-anchor="start"
      >
        60%
      </text>

      <!-- 折线（多数据点时） -->
      <polyline
        v-if="points.length > 1"
        :points="polylinePoints"
        fill="none"
        stroke="hsl(var(--primary))"
        stroke-width="2"
        stroke-linejoin="round"
        stroke-linecap="round"
      />

      <!-- 数据点圆点 + hover tooltip -->
      <circle
        v-for="(pt, idx) in points"
        :key="`dot-${idx}`"
        :cx="pt.x"
        :cy="pt.y"
        :r="points.length === 1 ? 5 : 3.5"
        fill="hsl(var(--primary))"
        stroke="hsl(var(--background))"
        stroke-width="1.5"
        class="cursor-pointer"
      >
        <title>{{ pt.label }}</title>
      </circle>
    </svg>
  </div>
</template>
