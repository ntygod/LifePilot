<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'

const props = defineProps<{
  analyserNode: AnalyserNode | null
  isActive: boolean
}>()

const canvasRef = ref<HTMLCanvasElement | null>(null)
let animFrame: number | null = null
const BAR_COUNT = 32

function draw() {
  const canvas = canvasRef.value
  const analyser = props.analyserNode
  if (!canvas || !analyser || !props.isActive) return

  const ctx = canvas.getContext('2d')
  if (!ctx) return

  const dataArray = new Uint8Array(analyser.frequencyBinCount)
  analyser.getByteFrequencyData(dataArray)

  const { width, height } = canvas
  ctx.clearRect(0, 0, width, height)

  const barWidth = width / BAR_COUNT
  const gap = 2
  const effectiveBarWidth = barWidth - gap

  for (let i = 0; i < BAR_COUNT; i++) {
    // 从频域数据中均匀采样
    const dataIndex = Math.floor((i / BAR_COUNT) * dataArray.length)
    const value = dataArray[dataIndex] / 255
    const barHeight = Math.max(value * height, 2)

    const x = i * barWidth + gap / 2
    const y = height - barHeight

    // 渐变色：低频偏蓝，高频偏紫
    const hue = 220 + (i / BAR_COUNT) * 40
    ctx.fillStyle = `hsl(${hue}, 70%, 60%)`
    ctx.fillRect(x, y, effectiveBarWidth, barHeight)
  }

  animFrame = requestAnimationFrame(draw)
}

watch(
  () => [props.analyserNode, props.isActive] as const,
  ([analyser, active]) => {
    if (animFrame !== null) {
      cancelAnimationFrame(animFrame)
      animFrame = null
    }
    if (analyser && active) {
      draw()
    } else if (canvasRef.value) {
      // 清空画布
      const ctx = canvasRef.value.getContext('2d')
      ctx?.clearRect(0, 0, canvasRef.value.width, canvasRef.value.height)
    }
  },
  { immediate: true }
)

onUnmounted(() => {
  if (animFrame !== null) {
    cancelAnimationFrame(animFrame)
    animFrame = null
  }
})
</script>

<template>
  <canvas
    ref="canvasRef"
    class="audio-waveform"
    width="200"
    height="40"
    aria-label="录音波形"
  />
</template>

<style scoped>
.audio-waveform {
  display: block;
  border-radius: 4px;
}
</style>
