<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  modelValue: number
  min?: number
  max?: number
  step?: number
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: number): void
}>()

const sliderValue = computed({
  get: () => props.modelValue,
  set: (val: number) => emit('update:modelValue', val),
})

const percentage = computed(() => {
  const min = props.min ?? 0
  const max = props.max ?? 100
  const range = max - min
  if (range === 0) return 0
  const value = Math.max(min, Math.min(max, props.modelValue))
  return ((value - min) / range) * 100
})

const backgroundStyle = computed(() => {
  const pct = percentage.value
  return {
    background: `linear-gradient(to right, hsl(var(--primary)) 0%, hsl(var(--primary)) ${pct}%, hsl(var(--input)) ${pct}%, hsl(var(--input)) 100%)`
  }
})
</script>

<template>
  <div class="flex items-center gap-3 w-full">
    <input
      v-model.number="sliderValue"
      type="range"
      :min="min ?? 0"
      :max="max ?? 100"
      :step="step ?? 1"
      :disabled="disabled"
      class="flex-1 h-2 w-full cursor-pointer appearance-none rounded-lg bg-input accent-primary disabled:cursor-not-allowed disabled:opacity-50"
      :style="backgroundStyle"
    />
    <span class="text-sm text-muted-foreground min-w-[3rem] text-right">
      {{ modelValue }}
    </span>
  </div>
</template>
