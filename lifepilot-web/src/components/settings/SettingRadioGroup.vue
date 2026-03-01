<script setup lang="ts">
const props = defineProps<{
  modelValue: string | number
  options: { value: string | number; label: string }[]
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string | number): void
}>()
</script>

<template>
  <div class="flex flex-wrap gap-3">
    <label
      v-for="option in options"
      :key="option.value"
      class="flex items-center gap-2 cursor-pointer"
      :class="{ 'opacity-50 cursor-not-allowed': disabled }"
    >
      <input
        :checked="modelValue === option.value"
        type="radio"
        :value="option.value"
        :disabled="disabled"
        class="h-4 w-4 accent-primary cursor-pointer disabled:cursor-not-allowed"
        @change="emit('update:modelValue', option.value)"
      />
      <span class="text-sm">{{ option.label }}</span>
    </label>
  </div>
</template>
