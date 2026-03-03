<script setup lang="ts">
interface Props {
  icon?: string
  title?: string
  description?: string
  actionLabel?: string
  showAction?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  icon: '📭',
  title: '暂无数据',
  description: '',
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
    <div class="text-4xl mb-4" v-if="icon">{{ icon }}</div>
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
