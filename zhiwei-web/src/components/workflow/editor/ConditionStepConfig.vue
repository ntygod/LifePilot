<!--
  条件分支步骤配置组件。
  配置 condition 表达式、thenSteps 和 elseSteps 嵌套步骤列表。
-->
<script setup lang="ts">
import type { ConditionStepConfig, StepModel } from '@/composables/useWorkflowModel'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Separator } from '@/components/ui/separator'
import NestedStepList from '@/components/workflow/editor/NestedStepList.vue'

const props = defineProps<{
  modelValue: ConditionStepConfig
}>()

const emit = defineEmits<{
  'update:modelValue': [value: ConditionStepConfig]
}>()

function updateCondition(val: string | number) {
  emit('update:modelValue', { ...props.modelValue, condition: String(val) })
}

function updateThenSteps(steps: StepModel[]) {
  emit('update:modelValue', { ...props.modelValue, thenSteps: steps })
}

function updateElseSteps(steps: StepModel[]) {
  emit('update:modelValue', { ...props.modelValue, elseSteps: steps })
}
</script>

<template>
  <div class="space-y-3">
    <!-- 条件表达式 -->
    <div class="space-y-1.5">
      <Label class="text-xs">条件表达式</Label>
      <Input
        :model-value="modelValue.condition"
        placeholder='例如: ${result.score > 0.8}'
        class="h-8 text-sm font-mono"
        @update:model-value="updateCondition"
      />
    </div>

    <Separator />

    <!-- Then 分支步骤列表 -->
    <NestedStepList
      :model-value="modelValue.thenSteps"
      label="Then 分支（条件为真）"
      @update:model-value="updateThenSteps"
    />

    <Separator />

    <!-- Else 分支步骤列表 -->
    <NestedStepList
      :model-value="modelValue.elseSteps"
      label="Else 分支（条件为假）"
      @update:model-value="updateElseSteps"
    />
  </div>
</template>
