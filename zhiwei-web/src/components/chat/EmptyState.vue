<script setup lang="ts">
import { computed } from 'vue'
import ZhiweiMark from '@/components/brand/ZhiweiMark.vue'

const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了，还有什么需要帮忙的？'
  if (hour < 12) return '早上好，今天有什么需要帮忙的？'
  if (hour < 18) return '下午好，有什么需要帮忙的？'
  return '晚上好，有什么需要帮忙的？'
})
</script>

<template>
  <div class="w-full max-w-[540px] animate-in fade-in slide-in-from-bottom-4 duration-500">
    <div class="text-center">
      <!-- 呼吸光晕 -->
      <div class="glow-wrapper mb-lg inline-flex items-center justify-center">
        <div class="glow-ring" />
        <div class="glow-core">
          <ZhiweiMark class="size-8 text-primary" />
        </div>
      </div>

      <!-- 问候语 -->
      <h1 class="text-[28px] font-semibold tracking-tight text-foreground animate-in fade-in slide-in-from-bottom-2 duration-400 delay-150">
        {{ greeting }}
      </h1>

      <!-- 副标题 -->
      <p class="mt-sm text-[14px] text-muted-foreground animate-in fade-in duration-400 delay-250">
        知微 · 你的 AI 助手
      </p>
    </div>
  </div>
</template>

<style scoped>
.glow-wrapper {
  position: relative;
  width: 72px;
  height: 72px;
}

.glow-core {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 72px;
  height: 72px;
  border-radius: 50%;
  background: radial-gradient(circle, hsl(from var(--primary) h s l / 0.12) 0%, transparent 70%);
  animation: glow-breathe 3s ease-in-out infinite;
}

.glow-ring {
  position: absolute;
  inset: -8px;
  border-radius: 50%;
  background: radial-gradient(circle, hsl(from var(--primary) h s l / 0.06) 0%, transparent 70%);
  animation: glow-breathe-outer 3s ease-in-out infinite;
}

@keyframes glow-breathe {
  0%, 100% {
    transform: scale(1);
    opacity: 0.8;
  }

  50% {
    transform: scale(1.08);
    opacity: 1;
  }
}

@keyframes glow-breathe-outer {
  0%, 100% {
    transform: scale(1);
    opacity: 0.4;
  }

  50% {
    transform: scale(1.15);
    opacity: 0.7;
  }
}

@media (prefers-reduced-motion: reduce) {
  .glow-core,
  .glow-ring {
    animation: none;
  }
}
</style>
