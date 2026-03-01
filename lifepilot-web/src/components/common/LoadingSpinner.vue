<script setup lang="ts">
interface Props {
  size?: 'sm' | 'md' | 'lg'
  text?: string
  fullScreen?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  size: 'md',
  text: '',
  fullScreen: false
})

const sizeClasses = {
  sm: 'w-4 h-4',
  md: 'w-8 h-8',
  lg: 'w-12 h-12'
}

const borderClasses = {
  sm: 'border-2',
  md: 'border-4',
  lg: 'border-4'
}
</script>

<template>
  <div :class="['flex flex-col items-center justify-center gap-2', fullScreen ? 'fixed inset-0 bg-background/80 z-50' : 'py-8']">
    <div
      :class="[
        'border-t-primary border-r-transparent border-b-transparent border-l-transparent rounded-full animate-spin',
        sizeClasses[size],
        borderClasses[size]
      ]"
      role="status"
      aria-label="加载中"
    />
    <p v-if="text" class="text-sm text-muted-foreground">{{ text }}</p>
  </div>
</template>
