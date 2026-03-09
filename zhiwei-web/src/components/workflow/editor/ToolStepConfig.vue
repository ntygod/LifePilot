<!--
  工具步骤配置组件。
  配置 toolId 和 params（JSON 格式）。
-->
<script setup lang="ts">
import { computed } from 'vue'
import type { ToolStepConfig } from '@/composables/useWorkflowModel'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Label } from '@/components/ui/label'

const props = defineProps<{
  modelValue: ToolStepConfig
}>()

const emit = defineEmits<{
  'update:modelValue': [value: ToolStepConfig]
}>()

/** params 序列化为 JSON 文本展示 */
const paramsText = computed(() => {
  const entries = Object.entries(props.modelValue.params ?? {})
  return entries.length > 0 ? JSON.stringify(props.modelValue.params, null, 2) : ''
})

function updateToolId(val: string | number) {
  emit('update:modelValue', { ...props.modelValue, toolId: String(val) })
}

function updateParams(val: string | number) {
  const text = String(val)
  try {
    const parsed = text.trim() ? JSON.parse(text) : {}
    emit('update:modelValue', { ...props.modelValue, params: parsed })
  } catch {
    // JSON 解析失败时不更新
  }
}
</script>

<template>
  <div class="space-y-3">
    <div class="space-y-1.5">
      <Label class="text-xs">工具 ID</Label>
      <Input
        :model-value="modelValue.toolId"
        placeholder="输入工具标识符"
        class="h-8 text-sm"
        @update:model-value="updateToolId"
      />
    </div>
    <div class="space-y-1.5">
      <Label class="text-xs">参数（JSON）</Label>
      <Textarea
        :model-value="paramsText"
        placeholder='{"key": "value"}'
        rows="4"
        class="text-sm font-mono"
        @update:model-value="updateParams"
      />
    </div>
  </div>
</template>
