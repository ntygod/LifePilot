<script setup lang="ts">
import { logger } from '@/utils/logger'

const props = defineProps<{
  type?: string
}>()

if (props.type) {
  logger.warn(`[A2UI] 未注册的组件类型: ${props.type}`)
}
</script>

<template>
  <div class="rounded-[calc(var(--radius)+4px)] border border-dashed border-border/70 bg-background/55 px-3 py-2 text-xs text-muted-foreground">
    未知组件: {{ type ?? 'unknown' }}
  </div>
</template>
