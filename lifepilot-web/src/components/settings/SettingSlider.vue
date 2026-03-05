<script setup lang="ts">
import { computed } from 'vue'
import { Slider } from '@/components/ui/slider'

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
  get: () => [props.modelValue],
  set: (val: number[]) => emit('update:modelValue', val[0]),
})
</script>

<template>
  <div class="flex items-center gap-3 w-full">
    <Slider
      :model-value="sliderValue"
      :min="min ?? 0"
      :max="max ?? 100"
      :step="step ?? 1"
      :disabled="disabled"
      class="flex-1"
      @update:model-value="(v: number[] | undefined) => { if (v) emit('update:modelValue', v[0]) }"
    />
    <span class="text-sm text-muted-foreground min-w-[3rem] text-right">
      {{ modelValue }}
    </span>
  </div>
</template>
