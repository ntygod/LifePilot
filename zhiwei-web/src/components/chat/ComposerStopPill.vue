<script setup lang="ts">
/**
 * 流式中的"停止生成"浮窗 Pill。
 *
 * <p>仅当 isStreaming=true 时由父组件挂载；组件自身不做可见性判断。
 * 浮在 ComposerCard 顶部上方 40px 处（由 --chat-stop-pill-offset 控制），
 * 不参与主布局流。可选显示当前推理状态文本（思考中 / 调用工具 / ...）。</p>
 *
 * @author zsg
 * @since 2026-05-08
 */
import { Square } from 'lucide-vue-next'

defineProps<{
  statusText?: string | null
}>()

const emit = defineEmits<{
  abort: []
}>()
</script>

<template>
  <button
    type="button"
    class="stop-pill"
    data-testid="composer-stop-pill"
    @click="emit('abort')"
  >
    <span class="stop-pill__dot" aria-hidden="true" />
    <span class="stop-pill__label">
      {{ statusText || '正在生成…' }}
    </span>
    <span class="stop-pill__divider" aria-hidden="true" />
    <span class="stop-pill__action">
      <Square class="size-3" />
      停止
    </span>
  </button>
</template>

<style scoped>
.stop-pill {
  position: absolute;
  top: var(--chat-stop-pill-offset, -40px);
  left: 50%;
  transform: translateX(-50%);
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 14px;
  border-radius: 999px;
  border: 1px solid hsl(from var(--border) h s l / 0.7);
  background: hsl(from var(--card) h s l / 0.96);
  box-shadow: 0 6px 16px -8px hsl(var(--shadow-color) / 0.2);
  font-size: 12px;
  color: var(--foreground);
  cursor: pointer;
  white-space: nowrap;
  backdrop-filter: blur(6px);
  transition: box-shadow 160ms ease, background 160ms ease;
  z-index: 5;
}

.stop-pill:hover {
  background: hsl(from var(--card) h s l / 1);
  box-shadow: 0 8px 20px -8px hsl(var(--shadow-color) / 0.22);
}

.stop-pill__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: hsl(from var(--primary) h s l / 0.85);
  animation: pulse 1.4s ease-in-out infinite;
}

.stop-pill__divider {
  width: 1px;
  height: 12px;
  background: hsl(from var(--border) h s l / 0.8);
}

.stop-pill__label {
  color: var(--muted-foreground);
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stop-pill__action {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--foreground);
  font-weight: 500;
}

@keyframes pulse {
  0%, 100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.6;
    transform: scale(0.85);
  }
}
</style>
