<script setup lang="ts">
import { computed } from 'vue'
import { Input } from '@/components/ui/input'
import { Search } from 'lucide-vue-next'

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
    <Search
      class="pointer-events-none absolute inset-y-0 left-2.5 my-auto size-4 text-muted-foreground"
    />
    <Input
      v-model="innerValue"
      :type="inputType || 'search'"
      :placeholder="placeholder"
      :aria-label="ariaLabel || placeholder || '搜索'"
      :class="size === 'sm' ? 'h-8 pl-8 text-xs' : 'h-9 pl-8 text-sm'"
      @keydown="handleKeydown"
    />
  </div>
</template>
