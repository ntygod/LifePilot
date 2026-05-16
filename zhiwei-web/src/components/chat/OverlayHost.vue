<script setup lang="ts">
/**
 * 对话页右侧浮窗容器。
 *
 * <p>浮窗模式：不拦截主区交互（无 scrim），用户可以直接点对话区；点击浮窗外部
 * 任意位置会自动关闭。右上角仍有一个悬浮关闭按钮。键盘 Esc 也可关闭。</p>
 *
 * <p>面板本身是圆角卡片，带投影，视觉上明确"浮起"在主区上方。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { X } from 'lucide-vue-next'

const props = defineProps<{
  open: boolean
  /** 是否显示右上角悬浮关闭按钮（默认 true） */
  showClose?: boolean
}>()

const emit = defineEmits<{
  close: []
}>()

const panelRef = ref<HTMLElement | null>(null)

/** 监听 document mousedown：目标不在浮窗内 + 不在带 data-overlay-toggle 的元素内则关闭。 */
function handleDocumentMouseDown(event: MouseEvent) {
  if (!props.open) return
  const target = event.target as HTMLElement | null
  if (!target) {
    emit('close')
    return
  }
  if (panelRef.value && panelRef.value.contains(target)) {
    return
  }
  // 头部"文档 / 任务 / ..."菜单等 overlay 触发入口允许自己走切换逻辑，不被 click-outside 误关
  if (target.closest('[data-overlay-toggle]')) {
    return
  }
  emit('close')
}

watch(
  () => props.open,
  async open => {
    if (open) {
      // 先等浮窗挂载，再注册 mousedown —— 避免触发它的那次点击立刻被误判为"点击外部"
      await nextTick()
      // setTimeout(0) 把注册推到同一宏任务之后，彻底让触发点击事件传播完毕
      setTimeout(() => {
        document.addEventListener('mousedown', handleDocumentMouseDown, true)
      }, 0)
    } else {
      document.removeEventListener('mousedown', handleDocumentMouseDown, true)
    }
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  document.removeEventListener('mousedown', handleDocumentMouseDown, true)
})
</script>

<template>
  <Transition name="overlay-panel" :duration="280">
    <aside
      v-if="props.open"
      ref="panelRef"
      class="overlay-panel"
      role="dialog"
      aria-modal="false"
    >
      <button
        v-if="props.showClose !== false"
        type="button"
        class="overlay-panel__close"
        title="关闭（Esc）"
        @click="emit('close')"
      >
        <X class="size-4" />
      </button>
      <slot />
    </aside>
  </Transition>
</template>

<style scoped>
.overlay-panel {
  position: fixed;
  top: 14px;
  right: 14px;
  bottom: 14px;
  width: var(--chat-overlay-width, 420px);
  max-width: calc(100vw - 28px);
  z-index: var(--chat-overlay-z, 40);
  background: var(--background);
  border: 1px solid hsl(from var(--border) h s l / 0.6);
  border-radius: 14px;
  box-shadow:
    0 1px 2px hsl(var(--shadow-color) / 0.04),
    -12px 18px 48px -18px hsl(var(--shadow-color) / 0.22);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transition: transform 280ms cubic-bezier(0.32, 0.72, 0, 1),
              opacity 280ms cubic-bezier(0.32, 0.72, 0, 1);
  will-change: transform, opacity;
}

.overlay-panel__close {
  position: absolute;
  top: 10px;
  right: 10px;
  z-index: 5;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 999px;
  color: var(--muted-foreground);
  background: hsl(from var(--background) h s l / 0.85);
  backdrop-filter: blur(4px);
  border: 1px solid hsl(from var(--border) h s l / 0.4);
  cursor: pointer;
  transition: color 120ms ease, background 120ms ease;
}

.overlay-panel__close:hover {
  color: var(--foreground);
  background: hsl(from var(--muted) h s l / 0.8);
}

/* 从右侧轻盈滑入（不像全屏 drawer 那样占满整条），更像桌面应用的浮窗 */
.overlay-panel-enter-from,
.overlay-panel-leave-to {
  transform: translateX(24px);
  opacity: 0;
}
</style>
