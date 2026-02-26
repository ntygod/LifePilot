<script setup lang="ts">
const props = defineProps<{
  type?: string
}>()

if (props.type) {
  console.warn(`[A2UI] 未注册的组件类型: ${props.type}`)
}
</script>

<template>
  <div class="rounded border border-dashed border-muted-foreground/30 p-2 text-xs text-muted-foreground">
    未知组件: {{ type ?? 'unknown' }}
  </div>
</template>
