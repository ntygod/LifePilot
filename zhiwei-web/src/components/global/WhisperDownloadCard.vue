<script setup lang="ts">
import { computed } from 'vue'
import { CheckCircle, Loader2, XCircle, X } from 'lucide-vue-next'
import { useWhisperDownload } from '@/composables/useWhisperDownload'

const {
  status,
  progress,
  stage,
  downloaded,
  total,
  speedBps,
  errorMessage,
  visible,
  triggerDownload,
  cancelDownload,
  dismiss,
  formatSpeed,
  formatSize,
} = useWhisperDownload()

const percentText = computed(() => {
  if (progress.value < 0) return ''
  return `${Math.round(progress.value * 100)}%`
})

const sizeText = computed(() => {
  if (total.value === 0) return ''
  return `${formatSize(downloaded.value)} / ${formatSize(total.value)}`
})
</script>

<template>
  <Transition name="card-slide">
    <div
      v-if="visible"
      class="mx-2 mb-2 overflow-hidden rounded-xl border border-border/50 bg-card/95 shadow-sm backdrop-blur-sm"
    >
      <!-- 下载中 -->
      <div v-if="status === 'downloading'" class="p-3">
        <div class="mb-2 flex items-center gap-2">
          <Loader2 class="size-4 shrink-0 animate-spin text-primary" />
          <span class="truncate text-xs font-medium text-foreground">{{ stage }}</span>
          <button
            class="ml-auto shrink-0 rounded-md p-0.5 text-muted-foreground/60 transition-colors hover:text-foreground"
            @click="cancelDownload"
          >
            <X class="size-3.5" />
          </button>
        </div>

        <!-- 进度条 -->
        <div class="mb-1.5 h-1.5 overflow-hidden rounded-full bg-muted/60">
          <div
            class="h-full rounded-full bg-primary transition-all duration-300"
            :style="{ width: progress >= 0 ? `${Math.round(progress * 100)}%` : '100%' }"
            :class="{ 'animate-pulse': progress < 0 }"
          />
        </div>

        <!-- 详情 -->
        <div class="flex items-center justify-between text-[10px] text-muted-foreground">
          <span>{{ sizeText }}</span>
          <span class="flex items-center gap-1.5">
            <span v-if="speedBps > 0">{{ formatSpeed(speedBps) }}</span>
            <span v-if="percentText">{{ percentText }}</span>
          </span>
        </div>
      </div>

      <!-- 完成 -->
      <div v-else-if="status === 'complete'" class="flex items-center gap-2 p-3">
        <CheckCircle class="size-4 shrink-0 text-emerald-500" />
        <span class="text-xs font-medium text-foreground">语音引擎已就绪</span>
      </div>

      <!-- 错误 -->
      <div v-else-if="status === 'error'" class="p-3">
        <div class="mb-2 flex items-center gap-2">
          <XCircle class="size-4 shrink-0 text-destructive" />
          <span class="truncate text-xs font-medium text-destructive">下载失败</span>
          <button
            class="ml-auto shrink-0 rounded-md p-0.5 text-muted-foreground/60 transition-colors hover:text-foreground"
            @click="dismiss"
          >
            <X class="size-3.5" />
          </button>
        </div>
        <p class="mb-2 text-[10px] leading-relaxed text-muted-foreground">{{ errorMessage }}</p>
        <button
          class="w-full rounded-lg bg-primary/10 px-2 py-1 text-xs font-medium text-primary transition-colors hover:bg-primary/20"
          @click="triggerDownload"
        >
          重试
        </button>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.card-slide-enter-active,
.card-slide-leave-active {
  transition: all 0.3s ease;
}

.card-slide-enter-from {
  opacity: 0;
  transform: translateY(-8px);
}

.card-slide-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>
