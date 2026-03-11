<script setup lang="ts">
import { computed } from 'vue'
import { AnimatePresence, motion } from 'motion-v'
import { CheckCircle2, Info, X, XCircle } from 'lucide-vue-next'
import { useUiStore } from '@/stores/ui'

const MotionDiv = motion.div
const uiStore = useUiStore()

const reversedToasts = computed(() => [...uiStore.toasts].reverse())

function iconFor(type: 'success' | 'error' | 'info') {
  switch (type) {
    case 'success':
      return CheckCircle2
    case 'error':
      return XCircle
    case 'info':
      return Info
  }
}

function colorFor(type: 'success' | 'error' | 'info') {
  switch (type) {
    case 'success':
      return 'text-emerald-500'
    case 'error':
      return 'text-red-500'
    case 'info':
      return 'text-sky-500'
  }
}

function surfaceFor(type: 'success' | 'error' | 'info') {
  switch (type) {
    case 'success':
      return 'border-emerald-500/20 bg-[linear-gradient(135deg,rgba(16,185,129,0.12),rgba(255,255,255,0))]'
    case 'error':
      return 'border-red-500/20 bg-[linear-gradient(135deg,rgba(239,68,68,0.12),rgba(255,255,255,0))]'
    case 'info':
      return 'border-sky-500/20 bg-[linear-gradient(135deg,rgba(14,165,233,0.12),rgba(255,255,255,0))]'
  }
}
</script>

<template>
  <div aria-live="polite" class="fixed top-4 right-4 z-50 flex w-80 flex-col gap-2">
    <AnimatePresence>
      <MotionDiv
        v-for="toast in reversedToasts"
        :key="toast.id"
        :initial="{ x: '100%', opacity: 0 }"
        :animate="{ x: 0, opacity: 1 }"
        :exit="{ x: '100%', opacity: 0 }"
        :transition="{ type: 'spring', stiffness: 300, damping: 25 }"
        :exit-transition="{ duration: 0.2 }"
      >
        <div
          :class="surfaceFor(toast.type)"
          class="flex items-start gap-3 rounded-2xl border bg-background/95 px-4 py-3 shadow-[0_24px_55px_-28px_hsl(var(--shadow-color)/0.85)] backdrop-blur-xl"
        >
          <component :is="iconFor(toast.type)" :size="18" :class="colorFor(toast.type)" class="mt-0.5 shrink-0" />
          <span class="flex-1 break-words text-sm text-foreground">{{ toast.message }}</span>
          <button
            type="button"
            class="shrink-0 text-muted-foreground transition-colors hover:text-foreground"
            @click="uiStore.clearToast(toast.id)"
          >
            <X :size="14" />
          </button>
        </div>
      </MotionDiv>
    </AnimatePresence>
  </div>
</template>
