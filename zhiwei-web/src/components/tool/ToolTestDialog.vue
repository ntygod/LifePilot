<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ToolDetail, ToolTestHistoryItem, ToolTestResponse } from '@/types'
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

const props = defineProps<{
  tool: ToolDetail | null
}>()

const emit = defineEmits<{
  close: []
}>()

const toolStore = useToolStore()

const testArguments = ref<Record<string, any>>({})
const testLoading = ref(false)
const testResult = ref<ToolTestResponse | null>(null)
const errors = ref<Record<string, string>>({})
const resultTab = ref<'formatted' | 'json'>('formatted')
const testHistory = ref<ToolTestHistoryItem[]>([])
const lastRequest = ref<Record<string, any> | null>(null)

const formFields = computed(() => {
  if (!props.tool?.inputSchema) {
    return []
  }

  const schema = props.tool.inputSchema
  const properties = schema.properties || {}
  const required = schema.required || []

  return Object.keys(properties).map(key => {
    const field = properties[key]
    return {
      key,
      label: field.title || key,
      type: field.type || 'string',
      description: field.description || '',
      required: required.includes(key),
      default: field.default,
      enum: field.enum,
      format: field.format,
    }
  })
})

watch(() => props.tool, () => {
  if (!props.tool) {
    return
  }

  testArguments.value = {}
  testResult.value = null
  errors.value = {}
  resultTab.value = 'formatted'
  lastRequest.value = null

  formFields.value.forEach(field => {
    if (field.default !== undefined) {
      testArguments.value[field.key] = field.default
    } else if (field.type === 'object') {
      testArguments.value[field.key] = {}
    } else if (field.type === 'array') {
      testArguments.value[field.key] = []
    } else if (field.type === 'boolean') {
      testArguments.value[field.key] = false
    } else if (field.type === 'number' || field.type === 'integer') {
      testArguments.value[field.key] = 0
    } else {
      testArguments.value[field.key] = ''
    }
  })
}, { immediate: true })

function validate() {
  errors.value = {}
  let valid = true

  formFields.value.forEach(field => {
    if (field.required && (testArguments.value[field.key] === undefined || testArguments.value[field.key] === '')) {
      errors.value[field.key] = `${field.label} 是必填项`
      valid = false
    }
  })

  return valid
}

function addHistoryItem(input: Record<string, any>, result: ToolTestResponse) {
  const item: ToolTestHistoryItem = {
    id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    timestamp: Date.now(),
    input: { ...input },
    result,
  }

  testHistory.value.push(item)

  if (testHistory.value.length > 5) {
    testHistory.value.shift()
  }
}

async function runTest() {
  if (!props.tool || !validate()) {
    return
  }

  testLoading.value = true
  testResult.value = null
  errors.value = {}

  const inputSnapshot = { ...testArguments.value }
  lastRequest.value = {
    toolId: props.tool.id,
    input: inputSnapshot,
  }

  try {
    const result = await toolStore.testTool({
      toolId: props.tool.id,
      input: testArguments.value,
    })
    testResult.value = result
    addHistoryItem(inputSnapshot, result)
  } catch (error: any) {
    const errorResult: ToolTestResponse = {
      success: false,
      output: {},
      error: error.message || '测试失败',
      meta: {
        durationMs: 0,
        toolId: props.tool.id,
        action: 'test',
      },
    }
    testResult.value = errorResult
    addHistoryItem(inputSnapshot, errorResult)
  } finally {
    testLoading.value = false
  }
}

function closeDialog() {
  testHistory.value = []
  lastRequest.value = null
  resultTab.value = 'formatted'
  emit('close')
}
</script>

<template>
  <FormDialogShell
    :title="`测试工具：${tool?.name || tool?.id || ''}`"
    description="填写测试参数后，可立即查看返回结果和最近一次记录。"
    content-class="sm:max-w-[960px]"
    body-class="space-y-6"
    @close="closeDialog"
  >
    <div v-if="tool" class="space-y-6">
      <section class="rounded-2xl border border-border/70 bg-muted/20 p-4 text-sm text-muted-foreground">
        <p v-if="tool.description">{{ tool.description }}</p>
        <p class="mt-1">
          工具 ID：<span class="font-mono text-foreground">{{ tool.id }}</span>
        </p>
      </section>

      <section v-if="testHistory.length > 0" class="space-y-3">
        <h4 class="text-sm font-medium text-foreground">最近测试记录</h4>
        <div class="space-y-2">
          <button
            v-for="item in testHistory"
            :key="item.id"
            type="button"
            class="flex w-full items-center justify-between rounded-2xl border border-border/70 bg-background/80 px-4 py-3 text-left text-xs transition-colors hover:bg-accent/60"
            @click="testArguments = { ...item.input }"
          >
            <span class="text-muted-foreground">{{ new Date(item.timestamp).toLocaleTimeString() }}</span>
            <span :class="item.result.success ? 'text-emerald-600' : 'text-destructive'">
              {{ item.result.success ? '成功' : '失败' }}
            </span>
          </button>
        </div>
      </section>

      <section v-if="formFields.length > 0" class="space-y-4">
        <h4 class="text-sm font-medium text-foreground">输入参数</h4>

        <div v-for="field in formFields" :key="field.key" class="space-y-2">
          <label class="text-sm font-medium text-foreground">
            {{ field.label }}
            <span v-if="field.required" class="text-destructive">*</span>
          </label>

          <p v-if="field.description" class="text-xs text-muted-foreground">
            {{ field.description }}
          </p>

          <Input
            v-if="field.type === 'string' && !field.enum && field.format !== 'textarea'"
            v-model="testArguments[field.key]"
            :class="{ 'border-destructive': errors[field.key] }"
            :placeholder="field.description || `输入 ${field.label}`"
          />

          <Textarea
            v-else-if="field.type === 'string' && field.format === 'textarea'"
            v-model="testArguments[field.key]"
            rows="4"
            class="font-mono text-sm"
            :class="{ 'border-destructive': errors[field.key] }"
            :placeholder="field.description || `输入 ${field.label}`"
          />

          <Select
            v-else-if="field.enum && field.enum.length > 0"
            :model-value="testArguments[field.key]"
            @update:model-value="(value) => testArguments[field.key] = value"
          >
            <SelectTrigger :class="{ 'border-destructive': errors[field.key] }">
              <SelectValue placeholder="请选择" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem v-for="option in field.enum" :key="String(option)" :value="option">
                {{ option }}
              </SelectItem>
            </SelectContent>
          </Select>

          <Input
            v-else-if="field.type === 'number' || field.type === 'integer'"
            :model-value="String(testArguments[field.key])"
            type="number"
            :class="{ 'border-destructive': errors[field.key] }"
            :placeholder="field.description || `输入 ${field.label}`"
            @update:model-value="testArguments[field.key] = Number($event)"
          />

          <label v-else-if="field.type === 'boolean'" class="flex items-center gap-3 rounded-2xl border border-border/70 bg-background/80 px-4 py-3">
            <Checkbox
              :model-value="testArguments[field.key]"
              @update:model-value="testArguments[field.key] = Boolean($event)"
            />
            <span class="text-sm text-muted-foreground">{{ field.description || '启用此项' }}</span>
          </label>

          <Textarea
            v-else-if="field.type === 'object' || field.type === 'array'"
            :model-value="typeof testArguments[field.key] === 'object' ? JSON.stringify(testArguments[field.key], null, 2) : testArguments[field.key]"
            rows="6"
            class="font-mono text-sm"
            :class="{ 'border-destructive': errors[field.key] }"
            placeholder='例如：{"key":"value"} 或 ["item1","item2"]'
            @input="(event: Event) => {
              const value = (event.target as HTMLTextAreaElement).value
              try {
                if (value.trim()) {
                  testArguments[field.key] = JSON.parse(value)
                  delete errors[field.key]
                } else {
                  testArguments[field.key] = field.type === 'array' ? [] : {}
                }
              } catch {
                errors[field.key] = 'JSON 格式不合法'
              }
            }"
          />

          <p v-if="errors[field.key]" class="text-xs text-destructive">{{ errors[field.key] }}</p>
        </div>
      </section>

      <section v-else class="rounded-2xl border border-dashed border-border/70 bg-muted/15 px-4 py-6 text-center text-sm text-muted-foreground">
        当前工具不需要输入参数
      </section>

      <section v-if="testResult" class="space-y-4 border-t border-border/70 pt-6">
        <div class="flex gap-2">
          <Button size="sm" :variant="resultTab === 'formatted' ? 'default' : 'outline'" @click="resultTab = 'formatted'">
            格式化视图
          </Button>
          <Button size="sm" :variant="resultTab === 'json' ? 'default' : 'outline'" @click="resultTab = 'json'">
            JSON 视图
          </Button>
        </div>

        <div v-if="resultTab === 'formatted'" class="space-y-3 text-sm">
          <div class="flex items-center gap-2">
            <span class="text-muted-foreground">状态：</span>
            <span
              class="rounded-full px-2 py-0.5 text-xs"
              :class="testResult.success ? 'bg-emerald-500/10 text-emerald-600' : 'bg-destructive/10 text-destructive'"
            >
              {{ testResult.success ? '成功' : '失败' }}
            </span>
          </div>

          <div v-if="testResult.error" class="rounded-2xl border border-destructive/20 bg-destructive/5 px-4 py-3 text-destructive">
            {{ testResult.error }}
          </div>

          <div v-if="testResult.output">
            <div class="mb-2 font-medium text-foreground">输出</div>
            <pre class="overflow-x-auto rounded-2xl bg-muted px-4 py-3 text-xs">{{ JSON.stringify(testResult.output, null, 2) }}</pre>
          </div>

          <div v-if="testResult.meta" class="space-y-1 text-xs text-muted-foreground">
            <div>执行耗时：{{ testResult.meta.durationMs }}ms</div>
            <div>工具 ID：<span class="font-mono">{{ testResult.meta.toolId }}</span></div>
            <div v-if="testResult.meta.action">动作：{{ testResult.meta.action }}</div>
          </div>
        </div>

        <div v-else class="grid gap-4 lg:grid-cols-2">
          <div>
            <h5 class="mb-2 text-xs font-medium text-muted-foreground">请求 JSON</h5>
            <pre class="overflow-x-auto rounded-2xl bg-muted px-4 py-3 text-xs">{{ JSON.stringify(lastRequest, null, 2) }}</pre>
          </div>
          <div>
            <h5 class="mb-2 text-xs font-medium text-muted-foreground">响应 JSON</h5>
            <pre class="overflow-x-auto rounded-2xl bg-muted px-4 py-3 text-xs">{{ JSON.stringify(testResult, null, 2) }}</pre>
          </div>
        </div>
      </section>
    </div>

    <template #footer>
      <div class="flex justify-end gap-3">
        <Button variant="outline" @click="closeDialog">
          关闭
        </Button>
        <Button :disabled="testLoading || !tool" @click="runTest">
          {{ testLoading ? '测试中...' : '运行测试' }}
        </Button>
      </div>
    </template>
  </FormDialogShell>
</template>
