<script setup lang="ts">
import { useUiStore } from '@/stores/ui'
import { CheckCircle2, XCircle, Info, X } from 'lucide-vue-next'
import { computed } from 'vue'
import { AnimatePresence, motion } from 'motion-v'

const MotionDiv = motion.div

const uiStore = useUiStore()

// 反转顺序，最新在最上方
const reversedToasts = computed(() => [...uiStore.toasts].reverse())

/** 根据 toast 类型返回对应图标组件 */
function iconFor(type: 'success' | 'error' | 'info') {
  switch (type) {
    case 'success': return CheckCircle2
    case 'error': return XCircle
    case 'info': return Info
  }
}

/** 根据 toast 类型返回对应颜色 class */
function colorFor(type: 'success' | 'error' | 'info') {
  switch (type) {
    case 'success': return 'text-green-500'
    case 'error': return 'text-red-500'
    case 'info': return 'text-blue-500'
  }
}
</script>

<template>
  <!-- Toast 通知容器：固定右上角，z-50 -->
  <div class="fixed top-4 right-4 z-50 flex flex-col gap-2 w-80">
    <AnimatePresence>
      <MotionDiv
        v-for="toast in reversedToasts"
        :key="toast.id"
        :initial="{ x: '100%' }"
        :animate="{ x: 0 }"
        :exit="{ x: '100%', opacity: 0 }"
        :transition="{ type: 'spring', stiffness: 300, damping: 25 }"
        :exit-transition="{ duration: 0.2 }"
      >
        <div
          class="flex items-start gap-3 rounded-lg border bg-background px-4 py-3 shadow-lg"
        >
          <!-- 类型图标 -->
          <component :is="iconFor(toast.type)" :size="18" :class="colorFor(toast.type)" class="shrink-0 mt-0.5" />

          <!-- 消息文本 -->
          <span class="flex-1 text-sm text-foreground break-words">{{ toast.message }}</span>

          <!-- 手动关闭按钮 -->
          <button
            type="button"
            class="shrink-0 text-muted-foreground hover:text-foreground transition-colors"
            @click="uiStore.clearToast(toast.id)"
          >
            <X :size="14" />
          </button>
        </div>
      </MotionDiv>
    </AnimatePresence>
  </div>
</template>
