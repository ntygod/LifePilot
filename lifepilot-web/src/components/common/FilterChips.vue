<script setup lang="ts">
const props = defineProps<{
  modelValue: string | number
  options: { value: string | number; label: string }[]
  size?: 'sm' | 'md'
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string | number): void
}>()

function handleClick(value: string | number) {
  if (value === props.modelValue) return
  emit('update:modelValue', value)
}
</script>

<template>
  <div class="inline-flex flex-wrap items-center gap-2">
    <button
      v-for="option in options"
      :key="option.value"
      type="button"
      class="rounded-full border text-xs transition-colors px-2 py-1"
      :class="modelValue === option.value
        ? 'bg-primary text-primary-foreground border-primary'
        : 'border-border text-muted-foreground hover:text-foreground'"
      @click="handleClick(option.value)"
    >
      {{ option.label }}
    </button>
  </div>
</template>

