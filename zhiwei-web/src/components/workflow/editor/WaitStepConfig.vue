<!--
  等待步骤配置组件。
  配置等待时长（秒）。
-->
<script setup lang="ts">
import type { WaitStepConfig } from '@/composables/useWorkflowModel'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

const props = defineProps<{
  modelValue: WaitStepConfig
}>()

const emit = defineEmits<{
  'update:modelValue': [value: WaitStepConfig]
}>()

/** 解析数字输入，无效时返回默认值 */
function parseNumber(val: string | number, fallback: number): number {
  const num = typeof val === 'string' ? parseInt(val, 10) : val
  return Number.isNaN(num) ? fallback : num
}

function updateDuration(val: string | number) {
  emit('update:modelValue', {
    ...props.modelValue,
    durationSeconds: parseNumber(val, 60),
  })
}
</script>

<template>
  <div class="space-y-3">
    <div class="space-y-1.5">
      <Label class="text-xs">等待时长（秒）</Label>
      <Input
        type="number"
        :model-value="modelValue.durationSeconds"
        min="1"
        placeholder="60"
        class="h-8 text-sm"
        @update:model-value="updateDuration"
      />
    </div>
  </div>
</template>
