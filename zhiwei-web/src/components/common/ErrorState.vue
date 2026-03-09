<script setup lang="ts">
interface Props {
  title?: string
  description?: string
  actionLabel?: string
  showAction?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  title: '出错了',
  description: '发生了未知错误，请稍后重试',
  showAction: false
})

const emit = defineEmits<{
  action: []
}>()

function handleAction() {
  emit('action')
}
</script>

<template>
  <div class="flex flex-col items-center justify-center py-12 px-4 text-center">
    <div class="w-16 h-16 rounded-full bg-destructive/10 flex items-center justify-center mb-4">
      <svg
        xmlns="http://www.w3.org/2000/svg"
        width="24"
        height="24"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
        class="text-destructive"
      >
        <circle cx="12" cy="12" r="10" />
        <line x1="12" y1="8" x2="12" y2="12" />
        <line x1="12" y1="16" x2="12.01" y2="16" />
      </svg>
    </div>
    <h3 class="text-lg font-semibold text-foreground mb-2">{{ title }}</h3>
    <p v-if="description" class="text-sm text-muted-foreground mb-4 max-w-[448px]">{{ description }}</p>
    <button
      v-if="showAction && actionLabel"
      class="h-9 px-4 rounded-md text-sm bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
      @click="handleAction"
    >
      {{ actionLabel }}
    </button>
  </div>
</template>
