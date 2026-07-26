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
  <div class="empty-state">
    <div class="text-center">
      <div class="empty-state__mark">
        <ZhiweiMark class="size-7 text-primary" />
      </div>

      <h1 class="empty-state__title">
        {{ greeting }}
      </h1>

      <p class="empty-state__subtitle">知微</p>
    </div>
  </div>
</template>

<style scoped>
.empty-state {
  width: 100%;
  max-width: 540px;
  animation: fade-slide-in 420ms ease-out both;
}

.empty-state__mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  margin: 0 auto 18px;
  border-radius: 999px;
  color: var(--primary);
}

.empty-state__title {
  font-size: 28px;
  line-height: 1.25;
  font-weight: 650;
  color: var(--foreground);
  letter-spacing: 0;
  animation: fade-slide-in 420ms ease-out 80ms both;
}

.empty-state__subtitle {
  margin-top: 8px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--muted-foreground);
  animation: fade-slide-in 420ms ease-out 140ms both;
}

@keyframes fade-slide-in {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (prefers-reduced-motion: reduce) {
  .empty-state,
  .empty-state__title,
  .empty-state__subtitle {
    animation: none;
  }
}

@media (max-width: 640px) {
  .empty-state__title {
    font-size: 24px;
  }
}
</style>
