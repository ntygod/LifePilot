<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  modelValue: string | number
  type?: 'text' | 'password' | 'number' | 'email' | 'url'
  placeholder?: string
  disabled?: boolean
  min?: number
  max?: number
  step?: number
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string | number): void
}>()

const inputValue = computed({
  get: () => props.modelValue,
  set: (val: string | number) => emit('update:modelValue', val),
})
</script>

<template>
  <input
    v-model="inputValue"
    :type="type || 'text'"
    :placeholder="placeholder"
    :disabled="disabled"
    :min="min"
    :max="max"
    :step="step"
    class="flex h-9 w-full rounded-lg border border-input bg-transparent px-3 py-1 text-sm shadow-sm 
           transition-all duration-200
           placeholder:text-muted-foreground 
           hover:border-ring hover:shadow-md
           focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2
           disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:border-input disabled:hover:shadow-sm"
  />
</template>
