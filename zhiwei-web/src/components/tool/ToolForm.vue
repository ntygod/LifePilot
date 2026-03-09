<script setup lang="ts">
import { ref, watch } from 'vue'
import { useToolStore } from '@/stores/tool'
import type { ToolDetail } from '@/types'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Checkbox } from '@/components/ui/checkbox'

const props = defineProps<{
  tool?: ToolDetail | null
  mode: 'create' | 'edit'
}>()

const emit = defineEmits<{
  close: []
  saved: [tool: ToolDetail]
}>()

const store = useToolStore()

const formData = ref({
  id: '',
  name: '',
  description: '',
  inputSchema: {} as Record<string, any>,
  outputSchema: {} as Record<string, any>,
  budget: {
    timeoutSeconds: 30,
    maxRetries: 2,
    maxCostCents: Number.MAX_SAFE_INTEGER
  },
  riskLevel: 'MEDIUM',
  idempotent: false,
  tags: [] as string[]
})

const inputSchemaText = ref('')
const outputSchemaText = ref('')
const tagsText = ref('')
const loading = ref(false)
const errors = ref<Record<string, string>>({})

const riskLevels = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']

// 初始化表单数据
watch(() => props.tool, (tool) => {
  if (tool) {
    formData.value = {
      id: tool.id,
      name: tool.name,
      description: tool.description || '',
      inputSchema: tool.inputSchema || {},
      outputSchema: tool.outputSchema || {},
      budget: {
        timeoutSeconds: tool.budget?.timeoutSeconds || 30,
        maxRetries: tool.budget?.maxRetries || 2,
        maxCostCents: tool.budget?.maxCostCents || Number.MAX_SAFE_INTEGER
      },
      riskLevel: tool.riskLevel || 'MEDIUM',
      idempotent: tool.idempotent || false,
      tags: [...(tool.tags || [])]
    }
    inputSchemaText.value = JSON.stringify(tool.inputSchema || {}, null, 2)
    outputSchemaText.value = JSON.stringify(tool.outputSchema || {}, null, 2)
    tagsText.value = tool.tags?.join(', ') || ''
  } else if (props.mode === 'create') {
    formData.value = {
      id: '',
      name: '',
      description: '',
      inputSchema: {
        type: 'object',
        properties: {},
        required: []
      },
      outputSchema: {
        type: 'object',
        properties: {}
      },
      budget: {
        timeoutSeconds: 30,
        maxRetries: 2,
        maxCostCents: Number.MAX_SAFE_INTEGER
      },
      riskLevel: 'MEDIUM',
      idempotent: false,
      tags: []
    }
    inputSchemaText.value = JSON.stringify(formData.value.inputSchema, null, 2)
    outputSchemaText.value = JSON.stringify(formData.value.outputSchema, null, 2)
    tagsText.value = ''
  }
}, { immediate: true })

// 表单验证
function validate(): boolean {
  errors.value = {}
  
  if (!formData.value.id.trim()) {
    errors.value.id = 'ID 不能为空'
  } else if (!/^[a-z0-9-_]+$/.test(formData.value.id)) {
    errors.value.id = 'ID 只能包含小写字母、数字、连字符和下划线'
  }
  
  if (!formData.value.name.trim()) {
    errors.value.name = '名称不能为空'
  }

  // 验证 JSON Schema
  try {
    formData.value.inputSchema = JSON.parse(inputSchemaText.value)
  } catch (e) {
    errors.value.inputSchema = '输入 Schema JSON 格式错误'
    return false
  }

  try {
    formData.value.outputSchema = JSON.parse(outputSchemaText.value)
  } catch (e) {
    errors.value.outputSchema = '输出 Schema JSON 格式错误'
    return false
  }

  // 解析标签
  formData.value.tags = tagsText.value.split(',').map(t => t.trim()).filter(t => t.length > 0)
  
  return Object.keys(errors.value).length === 0
}

async function handleSubmit() {
  if (!validate()) return
  
  loading.value = true
  try {
    let tool: ToolDetail
    
    if (props.mode === 'create') {
      tool = await store.createTool({
        id: formData.value.id,
        name: formData.value.name,
        description: formData.value.description,
        inputSchema: formData.value.inputSchema,
        outputSchema: formData.value.outputSchema,
        budget: formData.value.budget,
        riskLevel: formData.value.riskLevel,
        idempotent: formData.value.idempotent,
        tags: formData.value.tags
      })
    } else {
      tool = await store.updateTool(props.tool!.id, {
        name: formData.value.name,
        description: formData.value.description,
        inputSchema: formData.value.inputSchema,
        outputSchema: formData.value.outputSchema,
        budget: formData.value.budget,
        riskLevel: formData.value.riskLevel,
        idempotent: formData.value.idempotent,
        tags: formData.value.tags
      })
    }
    
    emit('saved', tool)
    emit('close')
  } catch (e) {
    // 错误已在 store 中处理
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="emit('close')">
    <div class="bg-card border border-border rounded-lg shadow-lg w-full max-w-4xl max-h-[90vh] overflow-y-auto m-4">
      <div class="p-6">
        <div class="flex items-center justify-between mb-6">
          <h2 class="text-xl font-semibold text-foreground">
            {{ mode === 'create' ? '新建 Tool' : '编辑 Tool' }}
          </h2>
          <button
            class="text-muted-foreground hover:text-foreground transition-colors"
            @click="emit('close')"
          >×</button>
        </div>

        <form @submit.prevent="handleSubmit" class="space-y-4">
          <!-- ID（仅创建时显示） -->
          <div v-if="mode === 'create'">
            <label class="block text-sm font-medium text-foreground mb-1">
              Tool ID <span class="text-destructive">*</span>
            </label>
            <Input
              v-model="formData.id"
              type="text"
              placeholder="例如: my-tool"
            />
            <p v-if="errors.id" class="text-sm text-destructive mt-1">{{ errors.id }}</p>
          </div>

          <!-- 名称 -->
          <div>
            <label class="block text-sm font-medium text-foreground mb-1">
              名称 <span class="text-destructive">*</span>
            </label>
            <Input
              v-model="formData.name"
              type="text"
              placeholder="Tool 显示名称"
            />
            <p v-if="errors.name" class="text-sm text-destructive mt-1">{{ errors.name }}</p>
          </div>

          <!-- 描述 -->
          <div>
            <label class="block text-sm font-medium text-foreground mb-1">描述</label>
            <Textarea
              v-model="formData.description"
              rows="3"
              placeholder="Tool 功能描述"
            />
          </div>

          <!-- 输入 Schema -->
          <div>
            <label class="block text-sm font-medium text-foreground mb-1">
              输入 Schema (JSON) <span class="text-destructive">*</span>
            </label>
            <Textarea
              v-model="inputSchemaText"
              rows="8"
              class="font-mono text-sm"
              placeholder='{"type": "object", "properties": {}, "required": []}'
            />
            <p v-if="errors.inputSchema" class="text-sm text-destructive mt-1">{{ errors.inputSchema }}</p>
          </div>

          <!-- 输出 Schema -->
          <div>
            <label class="block text-sm font-medium text-foreground mb-1">
              输出 Schema (JSON)
            </label>
            <Textarea
              v-model="outputSchemaText"
              rows="6"
              class="font-mono text-sm"
              placeholder='{"type": "object", "properties": {}}'
            />
            <p v-if="errors.outputSchema" class="text-sm text-destructive mt-1">{{ errors.outputSchema }}</p>
          </div>

          <!-- 预算配置 -->
          <div class="grid grid-cols-3 gap-4">
            <div>
              <label class="block text-sm font-medium text-foreground mb-1">超时时间（秒）</label>
              <Input
                :model-value="String(formData.budget.timeoutSeconds)"
                @update:model-value="formData.budget.timeoutSeconds = Number($event)"
                type="number"
                min="1"
              />
            </div>
            <div>
              <label class="block text-sm font-medium text-foreground mb-1">最大重试次数</label>
              <Input
                :model-value="String(formData.budget.maxRetries)"
                @update:model-value="formData.budget.maxRetries = Number($event)"
                type="number"
                min="0"
              />
            </div>
            <div>
              <label class="block text-sm font-medium text-foreground mb-1">最大成本（分）</label>
              <Input
                :model-value="String(formData.budget.maxCostCents)"
                @update:model-value="formData.budget.maxCostCents = Number($event)"
                type="number"
                min="0"
              />
            </div>
          </div>

          <!-- 风险等级 -->
          <div>
            <label class="block text-sm font-medium text-foreground mb-1">风险等级</label>
            <select
              v-model="formData.riskLevel"
              class="w-full px-3 py-2 bg-background border border-border rounded-md text-foreground focus:outline-none focus:ring-2 focus:ring-primary"
            >
              <option v-for="level in riskLevels" :key="level" :value="level">{{ level }}</option>
            </select>
          </div>

          <!-- 幂等性 -->
          <div>
            <label class="flex items-center gap-2">
              <Checkbox
                :model-value="formData.idempotent"
                @update:model-value="formData.idempotent = $event"
              />
              <span class="text-sm font-medium text-foreground">幂等操作</span>
            </label>
          </div>

          <!-- 标签 -->
          <div>
            <label class="block text-sm font-medium text-foreground mb-1">标签（逗号分隔）</label>
            <Input
              v-model="tagsText"
              type="text"
              placeholder="例如: api, http, external"
            />
          </div>

          <!-- 操作按钮 -->
          <div class="flex justify-end gap-3 pt-4">
            <button
              type="button"
              class="px-4 py-2 text-sm font-medium text-foreground bg-background border border-border rounded-md hover:bg-muted transition-colors"
              @click="emit('close')"
            >
              取消
            </button>
            <button
              type="submit"
              :disabled="loading"
              class="px-4 py-2 text-sm font-medium text-white bg-primary rounded-md hover:bg-primary/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {{ loading ? '保存中...' : '保存' }}
            </button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>
