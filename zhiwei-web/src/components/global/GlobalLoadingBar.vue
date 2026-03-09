<script setup lang="ts">
import { useUiStore } from '@/stores/ui'
import { ref, watch } from 'vue'

const uiStore = useUiStore()

/** 动画阶段：idle=隐藏, loading=播放中, finishing=快速填满, hiding=淡出 */
const phase = ref<'idle' | 'loading' | 'finishing' | 'hiding'>('idle')

watch(() => uiStore.isGlobalLoading, (loading) => {
  if (loading) {
    phase.value = 'loading'
  } else if (phase.value === 'loading') {
    // 结束时先快速填满，再淡出
    phase.value = 'finishing'
    setTimeout(() => {
      phase.value = 'hiding'
      setTimeout(() => {
        phase.value = 'idle'
      }, 300)
    }, 200)
  }
})
</script>

<template>
  <!-- 全局加载进度条：固定视口顶部，2px 高度 -->
  <div
    v-if="phase !== 'idle'"
    class="fixed top-0 left-0 right-0 z-50 h-[2px] overflow-hidden"
    :class="{ 'opacity-0 transition-opacity duration-300': phase === 'hiding' }"
  >
    <div
      class="h-full bg-primary"
      :class="{
        'loading-bar-animate': phase === 'loading',
        'w-full transition-[width] duration-200': phase === 'finishing' || phase === 'hiding',
      }"
    />
  </div>
</template>

<style scoped>
.loading-bar-animate {
  width: 100%;
  animation: loading-bar 1.5s ease-in-out infinite;
}

@keyframes loading-bar {
  0% {
    transform: translateX(-100%);
  }
  50% {
    transform: translateX(0%);
  }
  100% {
    transform: translateX(100%);
  }
}
</style>
