<script setup lang="ts">
/**
 * 流式中的"停止生成"悬浮按钮。
 *
 * <p>仅当 isStreaming=true 时由父组件挂载。位置：对话区右下角悬浮，与"回到底部"按钮
 * 竖排一组。点击驱动后端 CancellationToken.cancel 真实中断 Agent 循环。</p>
 *
 * <p>不再承载 statusText —— MessageBubble 内的 ThinkingIndicator 已经在气泡顶部
 * 展示"正在思考回答… >"且可展开，重复显示反而打断视觉。</p>
 *
 * @author zsg
 * @since 2026-05-08
 */
import { Square } from 'lucide-vue-next'

const emit = defineEmits<{
  abort: []
}>()
</script>

<template>
  <button
    type="button"
    class="stop-btn"
    title="停止生成"
    data-testid="composer-stop-pill"
    @click="emit('abort')"
  >
    <Square class="size-3.5" fill="currentColor" />
  </button>
</template>

<style scoped>
.stop-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 999px;
  border: 1px solid hsl(from var(--border) h s l / 0.5);
  background: var(--background);
  color: var(--foreground);
  cursor: pointer;
  box-shadow: 0 4px 12px -6px hsl(var(--shadow-color) / 0.18);
  transition: background 120ms ease, box-shadow 120ms ease, color 120ms ease;
}

.stop-btn:hover {
  background: var(--destructive);
  color: var(--destructive-foreground);
  box-shadow: 0 6px 14px -6px hsl(var(--destructive) / 0.35);
}
</style>
