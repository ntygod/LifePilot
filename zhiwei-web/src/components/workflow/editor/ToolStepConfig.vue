<!--
  工具步骤配置组件。
  配置 toolId（通过搜索选择器从已注册原生工具中选择）和 params（基于 inputSchema 自动生成表单）。
-->
<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import type { ToolStepConfig } from '@/composables/useWorkflowModel'
import { Label } from '@/components/ui/label'
import { toolApi } from '@/api/client'
import ResourceCombobox from './ResourceCombobox.vue'
import SchemaParamsEditor from './SchemaParamsEditor.vue'
import type { ResourceOption } from './ResourceCombobox.vue'

const props = defineProps<{
  modelValue: ToolStepConfig
}>()

const emit = defineEmits<{
  'update:modelValue': [value: ToolStepConfig]
}>()

// 本地状态：仅加载原生工具，不污染全局 toolStore
const builtinTools = ref<ResourceOption[]>([])
const loading = ref(false)
const inputSchema = ref<Record<string, unknown> | null>(null)

onMounted(async () => {
  loading.value = true
  try {
    const tools = await toolApi.list({ source: 'builtin' })
    builtinTools.value = tools.map(t => ({
      id: t.id,
      name: t.displayName ?? t.name,
      description: t.description,
    }))
  } catch {
    // 加载失败时保持空列表
  } finally {
    loading.value = false
  }
  // 如果已有 toolId，加载其 schema
  if (props.modelValue.toolId) {
    await fetchToolSchema(props.modelValue.toolId)
  }
})

/** 选择工具后获取 inputSchema */
async function fetchToolSchema(toolId: string) {
  if (!toolId) {
    inputSchema.value = null
    return
  }
  try {
    const detail = await toolApi.get(toolId)
    inputSchema.value = detail.inputSchema ?? null
  } catch {
    inputSchema.value = null
  }
}

function updateToolId(val: string) {
  emit('update:modelValue', { ...props.modelValue, toolId: val, params: {} })
  void fetchToolSchema(val)
}

function updateParams(val: Record<string, unknown>) {
  emit('update:modelValue', { ...props.modelValue, params: val })
}
</script>

<template>
  <div class="space-y-3">
    <div class="space-y-1.5">
      <Label class="text-xs">工具 ID</Label>
      <ResourceCombobox
        :model-value="modelValue.toolId"
        :options="builtinTools"
        :loading="loading"
        placeholder="选择或输入工具 ID"
        search-placeholder="搜索工具..."
        empty-text="无匹配工具"
        @update:model-value="updateToolId"
      />
    </div>
    <SchemaParamsEditor
      :model-value="modelValue.params ?? {}"
      :schema="inputSchema"
      @update:model-value="updateParams"
    />
  </div>
</template>
