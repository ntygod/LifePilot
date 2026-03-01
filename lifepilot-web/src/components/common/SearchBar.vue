<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  modelValue: string
  placeholder?: string
  size?: 'sm' | 'md'
  inputType?: string
  ariaLabel?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'search', value: string): void
}>()

const innerValue = computed({
  get: () => props.modelValue,
  set: (val: string) => emit('update:modelValue', val),
})

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter') {
    emit('search', innerValue.value.trim())
  }
}
</script>

<template>
  <div class="relative w-full">
    <span class="pointer-events-none absolute inset-y-0 left-2 flex items-center text-xs text-muted-foreground">
      🔍
    </span>
    <input
      v-model="innerValue"
      :type="inputType || 'search'"
      :placeholder="placeholder"
      :aria-label="ariaLabel || placeholder || '搜索'"
      class="flex h-9 w-full rounded-md border border-input bg-transparent pl-7 pr-3 py-1 text-sm shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
      :class="size === 'sm' ? 'h-8 text-xs' : 'h-9 text-sm'"
      @keydown="handleKeydown"
    />
  </div>
</template>

