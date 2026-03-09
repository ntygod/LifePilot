<!--
  LLM 步骤配置组件。
  配置 scene（场景）、promptTemplate（提示词模板）和 outputSchema（可选输出 Schema）。
-->
<script setup lang="ts">
import type { LlmStepConfig } from '@/composables/useWorkflowModel'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Label } from '@/components/ui/label'

const props = defineProps<{
  modelValue: LlmStepConfig
}>()

const emit = defineEmits<{
  'update:modelValue': [value: LlmStepConfig]
}>()

function updateField(field: keyof LlmStepConfig, val: string | number) {
  emit('update:modelValue', { ...props.modelValue, [field]: String(val) })
}
</script>

<template>
  <div class="space-y-3">
    <div class="space-y-1.5">
      <Label class="text-xs">场景</Label>
      <Input
        :model-value="modelValue.scene"
        placeholder="输入场景标识"
        class="h-8 text-sm"
        @update:model-value="updateField('scene', $event)"
      />
    </div>
    <div class="space-y-1.5">
      <Label class="text-xs">提示词模板</Label>
      <Textarea
        :model-value="modelValue.promptTemplate"
        placeholder="输入提示词模板内容"
        rows="5"
        class="text-sm"
        @update:model-value="updateField('promptTemplate', $event)"
      />
    </div>
    <div class="space-y-1.5">
      <Label class="text-xs">输出 Schema（可选）</Label>
      <Textarea
        :model-value="modelValue.outputSchema ?? ''"
        placeholder="输入 JSON Schema（可选）"
        rows="3"
        class="text-sm font-mono"
        @update:model-value="updateField('outputSchema', $event)"
      />
    </div>
  </div>
</template>
