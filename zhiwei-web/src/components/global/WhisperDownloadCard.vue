<script setup lang="ts">
import { computed } from 'vue'
import { CheckCircle, Download, RefreshCw, X, XCircle } from 'lucide-vue-next'
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

const sizeText = computed(() => {
  if (total.value === 0) return ''
  return `${formatSize(downloaded.value)} / ${formatSize(total.value)}`
})

const speedText = computed(() => {
  if (speedBps.value === 0) return ''
  return formatSpeed(speedBps.value)
})
</script>

<template>
  <Transition name="card-slide">
    <div
      v-if="visible"
      class="mx-sm mb-sm rounded-lg border border-border/60 bg-card/90 px-md py-sm shadow-sm"
    >
      <!-- 下载中 -->
      <template v-if="status === 'downloading'">
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-xs text-xs font-medium text-foreground">
            <Download class="size-3.5 text-primary" />
            <span>{{ stage || '正在下载语音引擎...' }}</span>
          </div>
          <button
            class="flex size-5 items-center justify-center rounded text-muted-foreground/60 transition-colors hover:text-foreground"
            title="取消下载"
            @click="cancelDownload"
          >
            <X class="size-3" />
          </button>
        </div>

        <!-- 进度条 -->
        <div class="mt-xs h-1.5 w-full overflow-hidden rounded-full bg-muted">
          <div
            class="h-full rounded-full bg-primary transition-all duration-300"
            :style="{ width: `${progress}%` }"
          />
        </div>

        <!-- 详情行 -->
        <div class="mt-xs flex items-center justify-between text-[10px] text-muted-foreground/70">
          <span>{{ sizeText }}</span>
          <span class="flex items-center gap-xs">
            <span v-if="speedText">{{ speedText }}</span>
            <span>{{ progress }}%</span>
          </span>
        </div>
      </template>

      <!-- 完成 -->
      <template v-else-if="status === 'complete'">
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-xs text-xs font-medium text-foreground">
            <CheckCircle class="size-3.5 text-emerald-500" />
            <span>语音引擎已就绪</span>
          </div>
          <button
            class="flex size-5 items-center justify-center rounded text-muted-foreground/60 transition-colors hover:text-foreground"
            @click="dismiss"
          >
            <X class="size-3" />
          </button>
        </div>
        <p class="mt-xs text-[10px] text-muted-foreground/70">
          点击麦克风按钮即可使用语音输入
        </p>
      </template>

      <!-- 错误 -->
      <template v-else-if="status === 'error'">
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-xs text-xs font-medium text-destructive">
            <XCircle class="size-3.5" />
            <span>下载失败</span>
          </div>
          <button
            class="flex size-5 items-center justify-center rounded text-muted-foreground/60 transition-colors hover:text-foreground"
            @click="dismiss"
          >
            <X class="size-3" />
          </button>
        </div>
        <p class="mt-xs text-[10px] text-muted-foreground/70">
          {{ errorMessage || '未知错误' }}
        </p>
        <button
          class="mt-xs flex items-center gap-xs text-[10px] font-medium text-primary transition-colors hover:text-primary/80"
          @click="triggerDownload"
        >
          <RefreshCw class="size-3" />
          重试
        </button>
      </template>
    </div>
  </Transition>
</template>

<style scoped>
.card-slide-enter-active,
.card-slide-leave-active {
  transition: all 0.25s ease;
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
