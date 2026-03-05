<!-- FilterChips — 基于 shadcn-vue ToggleGroup 的适配层，保持原有 props/events 接口 -->
<script setup lang="ts">
import { computed } from 'vue'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'

const props = defineProps<{
  modelValue: string | number
  options: { value: string | number; label: string }[]
  size?: 'sm' | 'md'
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string | number): void
}>()

/** 将原始 value 转为 ToggleGroup 使用的 string */
function toStringValue(v: string | number): string {
  return String(v)
}

/** ToggleGroup 回传值，需还原为原始类型 */
function handleUpdate(val: unknown) {
  const strVal = Array.isArray(val) ? String(val[0]) : String(val)
  if (strVal === 'null' || strVal === 'undefined' || strVal === '') return
  // 在 options 中查找原始 value，保持类型一致
  const matched = props.options.find((o) => String(o.value) === strVal)
  if (matched) {
    emit('update:modelValue', matched.value)
  }
}

/** size 映射：sm → sm，md（默认）→ default */
const toggleSize = computed(() => (props.size === 'sm' ? 'sm' : 'default'))
</script>

<template>
  <ToggleGroup
    type="single"
    :model-value="toStringValue(modelValue)"
    :size="toggleSize"
    variant="outline"
    @update:model-value="handleUpdate"
  >
    <ToggleGroupItem
      v-for="opt in options"
      :key="opt.value"
      :value="toStringValue(opt.value)"
    >
      {{ opt.label }}
    </ToggleGroupItem>
  </ToggleGroup>
</template>
