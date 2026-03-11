<script setup lang="ts">
import { ref, watch } from 'vue'
import type { ToolDetail } from '@/types'
import FormDialogShell from '@/components/common/FormDialogShell.vue'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
import { useToolStore } from '@/stores/tool'
import { useUiStore } from '@/stores/ui'

const props = defineProps<{
  tool?: ToolDetail | null
  mode: 'create' | 'edit'
}>()

const emit = defineEmits<{
  close: []
  saved: [tool: ToolDetail]
}>()

const store = useToolStore()
const uiStore = useUiStore()

const formData = ref({
  id: '',
  name: '',
  description: '',
  inputSchema: {} as Record<string, any>,
  outputSchema: {} as Record<string, any>,
  budget: {
    timeoutSeconds: 30,
    maxRetries: 2,
    maxCostCents: Number.MAX_SAFE_INTEGER,
  },
  riskLevel: 'MEDIUM',
  idempotent: false,
  tags: [] as string[],
})

const inputSchemaText = ref('')
const outputSchemaText = ref('')
const tagsText = ref('')
const loading = ref(false)
const errors = ref<Record<string, string>>({})

const riskLevels = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']

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
        maxCostCents: tool.budget?.maxCostCents || Number.MAX_SAFE_INTEGER,
      },
      riskLevel: tool.riskLevel || 'MEDIUM',
      idempotent: tool.idempotent || false,
      tags: [...(tool.tags || [])],
    }
    inputSchemaText.value = JSON.stringify(tool.inputSchema || {}, null, 2)
    outputSchemaText.value = JSON.stringify(tool.outputSchema || {}, null, 2)
    tagsText.value = tool.tags?.join(', ') || ''
    return
  }

  if (props.mode === 'create') {
    formData.value = {
      id: '',
      name: '',
      description: '',
      inputSchema: {
        type: 'object',
        properties: {},
        required: [],
      },
      outputSchema: {
        type: 'object',
        properties: {},
      },
      budget: {
        timeoutSeconds: 30,
        maxRetries: 2,
        maxCostCents: Number.MAX_SAFE_INTEGER,
      },
      riskLevel: 'MEDIUM',
      idempotent: false,
      tags: [],
    }
    inputSchemaText.value = JSON.stringify(formData.value.inputSchema, null, 2)
    outputSchemaText.value = JSON.stringify(formData.value.outputSchema, null, 2)
    tagsText.value = ''
  }
}, { immediate: true })

function validate() {
  errors.value = {}

  if (!formData.value.id.trim()) {
    errors.value.id = '工具 ID 不能为空'
  } else if (!/^[a-z0-9-_]+$/.test(formData.value.id)) {
    errors.value.id = '工具 ID 只能包含小写字母、数字、连字符和下划线'
  }

  if (!formData.value.name.trim()) {
    errors.value.name = '请输入工具名称'
  }

  try {
    formData.value.inputSchema = JSON.parse(inputSchemaText.value)
  } catch {
    errors.value.inputSchema = '输入 Schema 不是合法的 JSON'
  }

  try {
    formData.value.outputSchema = JSON.parse(outputSchemaText.value)
  } catch {
    errors.value.outputSchema = '输出 Schema 不是合法的 JSON'
  }

  formData.value.tags = tagsText.value
    .split(',')
    .map(tag => tag.trim())
    .filter(Boolean)

  return Object.keys(errors.value).length === 0
}

async function handleSubmit() {
  if (!validate()) {
    uiStore.showToast('error', '请先修正表单中的错误项')
    return
  }

  loading.value = true

  try {
    const payload = {
      name: formData.value.name,
      description: formData.value.description,
      inputSchema: formData.value.inputSchema,
      outputSchema: formData.value.outputSchema,
      budget: formData.value.budget,
      riskLevel: formData.value.riskLevel,
      idempotent: formData.value.idempotent,
      tags: formData.value.tags,
    }

    const tool = props.mode === 'create'
      ? await store.createTool({
        id: formData.value.id,
        ...payload,
      })
      : await store.updateTool(props.tool!.id, payload)

    emit('saved', tool)
    emit('close')
  } catch (error) {
    uiStore.showToast('error', error instanceof Error ? error.message : '保存工具失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <FormDialogShell
    :title="mode === 'create' ? '新建工具' : '编辑工具'"
    description="填写工具名称、参数说明和运行限制。"
    content-class="sm:max-w-[960px]"
    body-class="space-y-6"
    @close="emit('close')"
  >
    <form id="tool-form" class="space-y-6" @submit.prevent="handleSubmit">
      <section class="grid gap-4 md:grid-cols-2">
        <div v-if="mode === 'create'" class="space-y-2">
          <label class="text-sm font-medium text-foreground">
            工具 ID <span class="text-destructive">*</span>
          </label>
          <Input
            v-model="formData.id"
            :class="{ 'border-destructive': errors.id }"
            placeholder="例如：my-tool"
          />
          <p v-if="errors.id" class="text-xs text-destructive">{{ errors.id }}</p>
        </div>

        <div class="space-y-2">
          <label class="text-sm font-medium text-foreground">
            名称 <span class="text-destructive">*</span>
          </label>
          <Input
            v-model="formData.name"
            :class="{ 'border-destructive': errors.name }"
            placeholder="给这个工具起一个清晰的名字"
          />
          <p v-if="errors.name" class="text-xs text-destructive">{{ errors.name }}</p>
        </div>

        <div class="space-y-2 md:col-span-2">
          <label class="text-sm font-medium text-foreground">描述</label>
          <Textarea
            v-model="formData.description"
            rows="3"
            placeholder="描述这个工具的用途、边界和使用场景"
          />
        </div>
      </section>

      <section class="grid gap-4 lg:grid-cols-2">
        <div class="space-y-2">
          <label class="text-sm font-medium text-foreground">
            输入 Schema <span class="text-destructive">*</span>
          </label>
          <Textarea
            v-model="inputSchemaText"
            rows="12"
            class="font-mono text-xs"
            :class="{ 'border-destructive': errors.inputSchema }"
            placeholder='{"type":"object","properties":{},"required":[]}'
          />
          <p v-if="errors.inputSchema" class="text-xs text-destructive">{{ errors.inputSchema }}</p>
        </div>

        <div class="space-y-2">
          <label class="text-sm font-medium text-foreground">输出 Schema</label>
          <Textarea
            v-model="outputSchemaText"
            rows="12"
            class="font-mono text-xs"
            :class="{ 'border-destructive': errors.outputSchema }"
            placeholder='{"type":"object","properties":{}}'
          />
          <p v-if="errors.outputSchema" class="text-xs text-destructive">{{ errors.outputSchema }}</p>
        </div>
      </section>

      <section class="grid gap-4 md:grid-cols-3">
        <div class="space-y-2">
          <label class="text-sm font-medium text-foreground">超时时间（秒）</label>
          <Input
            :model-value="String(formData.budget.timeoutSeconds)"
            type="number"
            min="1"
            @update:model-value="formData.budget.timeoutSeconds = Number($event)"
          />
        </div>

        <div class="space-y-2">
          <label class="text-sm font-medium text-foreground">最大重试次数</label>
          <Input
            :model-value="String(formData.budget.maxRetries)"
            type="number"
            min="0"
            @update:model-value="formData.budget.maxRetries = Number($event)"
          />
        </div>

        <div class="space-y-2">
          <label class="text-sm font-medium text-foreground">最大成本（分）</label>
          <Input
            :model-value="String(formData.budget.maxCostCents)"
            type="number"
            min="0"
            @update:model-value="formData.budget.maxCostCents = Number($event)"
          />
        </div>

        <div class="space-y-2">
          <label class="text-sm font-medium text-foreground">风险等级</label>
          <Select :model-value="formData.riskLevel" @update:model-value="(value) => formData.riskLevel = String(value)">
            <SelectTrigger>
              <SelectValue placeholder="选择风险等级" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem v-for="level in riskLevels" :key="level" :value="level">
                {{ level }}
              </SelectItem>
            </SelectContent>
          </Select>
        </div>

        <div class="space-y-2 md:col-span-2">
          <label class="text-sm font-medium text-foreground">标签</label>
          <Input
            v-model="tagsText"
            placeholder="例如：api, http, external"
          />
        </div>
      </section>

      <section class="rounded-2xl border border-border/70 bg-muted/25 p-4">
        <label class="flex items-center gap-3">
          <Checkbox
            :model-value="formData.idempotent"
            @update:model-value="formData.idempotent = Boolean($event)"
          />
          <div class="space-y-1">
            <div class="text-sm font-medium text-foreground">幂等操作</div>
            <p class="text-xs leading-5 text-muted-foreground">
              开启后表示重复调用不会产生额外影响，适合允许重试的场景。
            </p>
          </div>
        </label>
      </section>
    </form>

    <template #footer>
      <div class="flex justify-end gap-3">
        <Button variant="outline" @click="emit('close')">
          取消
        </Button>
        <Button form="tool-form" type="submit" :disabled="loading">
          {{ loading ? '保存中...' : '保存工具' }}
        </Button>
      </div>
    </template>
  </FormDialogShell>
</template>
