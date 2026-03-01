<script setup lang="ts">
interface Props {
  title?: string
  message: string
  showRetry?: boolean
  retryLabel?: string
}

const props = withDefaults(defineProps<Props>(), {
  title: '发生错误',
  showRetry: false,
  retryLabel: '重试'
})

const emit = defineEmits<{
  retry: []
}>()

function handleRetry() {
  emit('retry')
}
</script>

<template>
  <div class="rounded-md border border-destructive bg-destructive/10 p-4">
    <div class="flex items-start gap-3">
      <div class="flex-shrink-0 text-destructive text-xl">⚠️</div>
      <div class="flex-1 min-w-0">
        <h3 class="text-sm font-semibold text-destructive mb-1">{{ title }}</h3>
        <p class="text-sm text-destructive/90 break-words">{{ message }}</p>
        <button
          v-if="showRetry"
          class="mt-3 h-8 px-3 rounded-md text-xs border border-destructive text-destructive hover:bg-destructive/20 transition-colors"
          @click="handleRetry"
        >
          {{ retryLabel }}
        </button>
      </div>
    </div>
  </div>
</template>
