<script setup lang="ts">
import { computed } from 'vue'
import { Input } from '@/components/ui/input'

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
  <Input
    v-model="inputValue"
    :type="type || 'text'"
    :placeholder="placeholder"
    :disabled="disabled"
    :min="min"
    :max="max"
    :step="step"
  />
</template>
