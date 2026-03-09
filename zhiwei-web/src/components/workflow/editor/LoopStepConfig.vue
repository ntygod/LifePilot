<!--
  循环步骤配置组件。
  配置 items 遍历集合表达式、loopVar 循环变量名、body 嵌套步骤列表。
-->
<script setup lang="ts">
import type { LoopStepConfig, StepModel } from '@/composables/useWorkflowModel'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Separator } from '@/components/ui/separator'
import NestedStepList from '@/components/workflow/editor/NestedStepList.vue'

const props = defineProps<{
  modelValue: LoopStepConfig
}>()

const emit = defineEmits<{
  'update:modelValue': [value: LoopStepConfig]
}>()

function updateItems(val: string | number) {
  emit('update:modelValue', { ...props.modelValue, items: String(val) })
}

function updateLoopVar(val: string | number) {
  emit('update:modelValue', { ...props.modelValue, loopVar: String(val) })
}

function updateBody(steps: StepModel[]) {
  emit('update:modelValue', { ...props.modelValue, body: steps })
}
</script>

<template>
  <div class="space-y-3">
    <!-- 遍历集合表达式 -->
    <div class="space-y-1.5">
      <Label class="text-xs">遍历集合表达式</Label>
      <Input
        :model-value="modelValue.items"
        placeholder='例如: ${inputs.urls}'
        class="h-8 text-sm font-mono"
        @update:model-value="updateItems"
      />
    </div>

    <!-- 循环变量名 -->
    <div class="space-y-1.5">
      <Label class="text-xs">循环变量名</Label>
      <Input
        :model-value="modelValue.loopVar"
        placeholder='例如: item'
        class="h-8 text-sm font-mono"
        @update:model-value="updateLoopVar"
      />
    </div>

    <Separator />

    <!-- 循环体步骤列表 -->
    <NestedStepList
      :model-value="modelValue.body"
      label="循环体步骤"
      @update:model-value="updateBody"
    />
  </div>
</template>
