<!--
  子工作流步骤配置组件。
  配置 workflowId（通过搜索选择器从已注册工作流中选择）和 params（JSON 格式）。
-->
<script setup lang="ts">
import { computed, onMounted } from 'vue'
import type { SubWorkflowStepConfig } from '@/composables/useWorkflowModel'
import { Textarea } from '@/components/ui/textarea'
import { Label } from '@/components/ui/label'
import { useWorkflowStore } from '@/stores/workflow'
import ResourceCombobox from './ResourceCombobox.vue'
import type { ResourceOption } from './ResourceCombobox.vue'

const props = defineProps<{
  modelValue: SubWorkflowStepConfig
}>()

const emit = defineEmits<{
  'update:modelValue': [value: SubWorkflowStepConfig]
}>()

const workflowStore = useWorkflowStore()

onMounted(() => {
  if (workflowStore.list.length === 0) workflowStore.fetchList()
})

/** 将 WorkflowItem 转为 ResourceOption */
const workflowOptions = computed<ResourceOption[]>(() =>
  workflowStore.list.map(w => ({
    id: w.id,
    name: w.name,
    description: w.description,
  }))
)

/** params 序列化为 JSON 文本展示 */
const paramsText = computed(() => {
  const entries = Object.entries(props.modelValue.params ?? {})
  return entries.length > 0 ? JSON.stringify(props.modelValue.params, null, 2) : ''
})

function updateWorkflowId(val: string) {
  emit('update:modelValue', { ...props.modelValue, workflowId: val })
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
      <Label class="text-xs">目标工作流 ID</Label>
      <ResourceCombobox
        :model-value="modelValue.workflowId"
        :options="workflowOptions"
        :loading="workflowStore.loading"
        placeholder="选择或输入工作流 ID"
        search-placeholder="搜索工作流..."
        empty-text="无匹配工作流"
        @update:model-value="updateWorkflowId"
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
