<!--
  工具步骤配置组件。
  配置 toolId（通过搜索选择器从已注册工具中选择）和 params（JSON 格式）。
-->
<script setup lang="ts">
import { computed, onMounted } from 'vue'
import type { ToolStepConfig } from '@/composables/useWorkflowModel'
import { Textarea } from '@/components/ui/textarea'
import { Label } from '@/components/ui/label'
import { useToolStore } from '@/stores/tool'
import ResourceCombobox from './ResourceCombobox.vue'
import type { ResourceOption } from './ResourceCombobox.vue'

const props = defineProps<{
  modelValue: ToolStepConfig
}>()

const emit = defineEmits<{
  'update:modelValue': [value: ToolStepConfig]
}>()

const toolStore = useToolStore()

onMounted(() => {
  if (toolStore.tools.length === 0) toolStore.fetchTools()
})

/** 将 ToolSummary 转为 ResourceOption */
const toolOptions = computed<ResourceOption[]>(() =>
  toolStore.tools.map(t => ({
    id: t.id,
    name: t.displayName ?? t.name,
    description: t.description,
  }))
)

/** params 序列化为 JSON 文本展示 */
const paramsText = computed(() => {
  const entries = Object.entries(props.modelValue.params ?? {})
  return entries.length > 0 ? JSON.stringify(props.modelValue.params, null, 2) : ''
})

function updateToolId(val: string) {
  emit('update:modelValue', { ...props.modelValue, toolId: val })
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
      <ResourceCombobox
        :model-value="modelValue.toolId"
        :options="toolOptions"
        :loading="toolStore.loading"
        placeholder="选择或输入工具 ID"
        search-placeholder="搜索工具..."
        empty-text="无匹配工具"
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
