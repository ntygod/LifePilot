<!--
  子工作流步骤配置组件。
  配置 workflowId（通过搜索选择器从已注册工作流中选择）和 params。
  选择工作流后自动获取其 inputs 定义，转换为 JSON Schema 供 SchemaParamsEditor 渲染表单。
-->
<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import type { SubWorkflowStepConfig } from '@/composables/useWorkflowModel'
import { Label } from '@/components/ui/label'
import { useWorkflowStore } from '@/stores/workflow'
import { workflowApi } from '@/api/client'
import ResourceCombobox from './ResourceCombobox.vue'
import SchemaParamsEditor from './SchemaParamsEditor.vue'
import type { ResourceOption } from './ResourceCombobox.vue'

const props = defineProps<{
  modelValue: SubWorkflowStepConfig
}>()

const emit = defineEmits<{
  'update:modelValue': [value: SubWorkflowStepConfig]
}>()

const workflowStore = useWorkflowStore()
const inputSchema = ref<Record<string, unknown> | null>(null)

onMounted(async () => {
  if (workflowStore.list.length === 0) workflowStore.fetchList()
  // 如果已有 workflowId，加载其 inputs schema
  if (props.modelValue.workflowId) {
    await fetchWorkflowInputs(props.modelValue.workflowId)
  }
})

/** 将 WorkflowDetail.inputs 转换为 JSON Schema 格式 */
function convertInputsToSchema(inputs: Record<string, unknown>): Record<string, unknown> | null {
  const entries = Object.entries(inputs)
  if (entries.length === 0) return null

  // 类型映射：WorkflowInputParam.type → JSON Schema type
  const typeMap: Record<string, string> = {
    string: 'string',
    number: 'number',
    boolean: 'boolean',
    list: 'array',
    map: 'object',
  }

  const properties: Record<string, unknown> = {}
  const required: string[] = []

  for (const [key, value] of entries) {
    const param = value as {
      name?: string
      type?: string
      required?: boolean
      defaultValue?: unknown
      description?: string
    }
    properties[key] = {
      type: typeMap[param.type ?? 'string'] ?? 'string',
      ...(param.description ? { description: param.description } : {}),
      ...(param.defaultValue !== undefined && param.defaultValue !== null
        ? { default: param.defaultValue }
        : {}),
    }
    if (param.required) required.push(key)
  }

  return { type: 'object', properties, required }
}

/** 获取工作流详情并转换 inputs 为 schema */
async function fetchWorkflowInputs(workflowId: string) {
  if (!workflowId) {
    inputSchema.value = null
    return
  }
  try {
    const detail = await workflowApi.get(workflowId)
    inputSchema.value = convertInputsToSchema(detail.inputs ?? {})
  } catch {
    inputSchema.value = null
  }
}

/** 将 WorkflowItem 转为 ResourceOption */
const workflowOptions = computed<ResourceOption[]>(() =>
  workflowStore.list.map(w => ({
    id: w.id,
    name: w.name,
    description: w.description,
  }))
)

function updateWorkflowId(val: string) {
  emit('update:modelValue', { ...props.modelValue, workflowId: val, params: {} })
  void fetchWorkflowInputs(val)
}

function updateParams(val: Record<string, unknown>) {
  emit('update:modelValue', { ...props.modelValue, params: val })
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
    <SchemaParamsEditor
      :model-value="modelValue.params ?? {}"
      :schema="inputSchema"
      @update:model-value="updateParams"
    />
  </div>
</template>
