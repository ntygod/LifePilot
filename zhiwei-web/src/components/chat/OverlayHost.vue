<script setup lang="ts">
/**
 * 对话页右侧 Overlay 容器。
 *
 * <p>取代旧版 ChatRightPanel 的"流式即占位"挤压式布局。仅当用户显式触发
 * （查看执行轨迹 / 打开文档 / 打开会话设置 / 打开会话概览）时，才以半透明遮罩
 * + 右侧滑入的方式展开，关闭后主列保持全宽。</p>
 *
 * <p>组件自身只负责 z-index + 遮罩点击关闭 + 滑入动效；具体面板内容由 slot 插入。</p>
 *
 * @author zsg
 * @since 2026-05-08
 */
import { Transition } from 'vue'

const props = defineProps<{
  open: boolean
  /** 面板标题；用于 aria-labelledby */
  title?: string
  /** 是否允许点击遮罩关闭（默认 true） */
  dismissOnScrim?: boolean
}>()

const emit = defineEmits<{
  close: []
}>()

function handleScrimClick() {
  if (props.dismissOnScrim !== false) {
    emit('close')
  }
}
</script>

<template>
  <Transition name="overlay-fade">
    <div v-if="props.open" class="overlay-host" role="dialog" aria-modal="true">
      <div class="overlay-host__scrim" @click="handleScrimClick" />
      <Transition name="overlay-slide">
        <aside v-if="props.open" class="overlay-host__panel">
          <slot />
        </aside>
      </Transition>
    </div>
  </Transition>
</template>

<style scoped>
.overlay-host {
  position: absolute;
  inset: 0;
  z-index: var(--chat-overlay-z, 40);
  pointer-events: none;
}

.overlay-host__scrim {
  position: absolute;
  inset: 0;
  background: var(--chat-overlay-scrim);
  backdrop-filter: blur(2px);
  pointer-events: auto;
}

.overlay-host__panel {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: var(--chat-overlay-width, 420px);
  max-width: 100vw;
  background: var(--background);
  border-left: 1px solid hsl(from var(--border) h s l / 0.55);
  box-shadow: -12px 0 24px -12px hsl(var(--shadow-color) / 0.15);
  pointer-events: auto;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.overlay-fade-enter-active,
.overlay-fade-leave-active {
  transition: opacity 180ms ease;
}

.overlay-fade-enter-from,
.overlay-fade-leave-to {
  opacity: 0;
}

.overlay-slide-enter-active,
.overlay-slide-leave-active {
  transition: transform 220ms cubic-bezier(0.22, 1, 0.36, 1), opacity 220ms ease;
}

.overlay-slide-enter-from,
.overlay-slide-leave-to {
  transform: translateX(16px);
  opacity: 0;
}
</style>
