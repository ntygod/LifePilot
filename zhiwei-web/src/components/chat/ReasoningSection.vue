<script setup lang="ts">
/**
 * 推理流展示区
 *
 * <p>展示推理模型（DeepSeek V4 / Qwen3 / O1 等）的 reasoning_content 流。
 * 流式时自动展开显示「思考中...」+ 实时增量；DONE 后折叠为
 * 「已思考 X 秒」按钮，可点击展开/折叠。
 *
 * @author zsg
 * @since 2026-04-27
 */
import { ref, watch } from 'vue'
import { Brain, ChevronDown } from 'lucide-vue-next'

const props = defineProps<{
  /** 推理过程文本 */
  reasoning: string
  /** 是否处于推理流活跃中（流式时为 true，DONE 后为 false） */
  active: boolean
  /** 推理过程持续时间（毫秒），完成后用于显示「已思考 X 秒」 */
  durationMs?: number
}>()

// 流式期间默认展开；完成后默认折叠
const expanded = ref(props.active)

// 一旦从 active 转为 inactive，自动折叠；从 inactive 转回 active 自动展开
watch(() => props.active, (newVal) => {
  expanded.value = newVal
})

/** 把毫秒数格式化为「Xms / X.Y 秒」可读文本 */
function formatDuration(ms: number = 0): string {
  if (ms <= 0) return '不到 1 秒'
  if (ms < 1000) return `${ms} 毫秒`
  const seconds = ms / 1000
  if (seconds < 60) return `${seconds.toFixed(1)} 秒`
  const minutes = Math.floor(seconds / 60)
  const remain = Math.round(seconds % 60)
  return `${minutes} 分 ${remain} 秒`
}
</script>

<template>
  <div
    v-if="reasoning || active"
    class="reasoning-section my-sm rounded-md border border-border/60 bg-muted/40 px-md py-sm text-sm"
  >
    <button
      type="button"
      class="flex w-full items-center justify-between gap-sm text-muted-foreground transition-colors hover:text-foreground"
      @click="expanded = !expanded"
    >
      <span class="flex items-center gap-xs">
        <Brain :size="14" :class="active ? 'animate-pulse text-foreground/80' : ''" />
        <span>{{ active ? '思考中...' : `已思考 ${formatDuration(durationMs)}` }}</span>
      </span>
      <ChevronDown
        :size="14"
        class="transition-transform duration-200"
        :class="(expanded || active) ? 'rotate-180' : ''"
      />
    </button>
    <div
      v-if="(expanded || active) && reasoning"
      class="mt-sm whitespace-pre-wrap text-muted-foreground/90"
    >
      {{ reasoning }}
    </div>
  </div>
</template>

<style scoped>
.reasoning-section {
  /* 与 assistant 气泡的内联样式协调 */
  font-family: var(--font-sans);
}
</style>
