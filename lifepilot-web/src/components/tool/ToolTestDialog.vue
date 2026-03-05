<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useToolStore } from '@/stores/tool'
import type { ToolDetail, ToolTestResponse } from '@/types'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Checkbox } from '@/components/ui/checkbox'

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

// 根据 inputSchema 生成表单字段
const formFields = computed(() => {
  if (!props.tool?.inputSchema) return []
  
  const schema = props.tool.inputSchema
  const properties = schema.properties || {}
  const required = schema.required || []
  
  return Object.keys(properties).map(key => {
    const prop = properties[key]
    return {
      key,
      label: prop.title || key,
      type: prop.type || 'string',
      description: prop.description || '',
      required: required.includes(key),
      default: prop.default,
      enum: prop.enum,
      format: prop.format
    }
  })
})

// 初始化表单数据
watch(() => props.tool, (tool) => {
  if (tool) {
    testArguments.value = {}
    testResult.value = null
    errors.value = {}
    
    // 设置默认值
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
  }
}, { immediate: true })

// 表单验证
function validate(): boolean {
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

// 运行测试
async function runTest() {
  if (!props.tool) return
  
  if (!validate()) return
  
  testLoading.value = true
  testResult.value = null
  errors.value = {}
  
  try {
    testResult.value = await toolStore.testTool({
      toolId: props.tool.id,
      input: testArguments.value
    })
  } catch (e: any) {
    // 错误已在 store 中处理
    testResult.value = {
      success: false,
      output: {},
      error: e.message || '测试失败',
      meta: {
        durationMs: 0,
        toolId: props.tool.id,
        action: 'test'
      }
    }
  } finally {
    testLoading.value = false
  }
}

function closeDialog() {
  emit('close')
}
</script>

<template>
  <div class="fixed inset-0 bg-black/50 flex items-center justify-center z-[60]" @click.self="closeDialog">
    <div class="bg-card border border-border rounded-lg p-6 w-full max-w-3xl shadow-lg max-h-[90vh] overflow-y-auto">
      <div class="flex items-center justify-between mb-4">
        <h3 class="text-lg font-semibold text-foreground">
          测试 Tool: {{ tool?.name || tool?.id }}
        </h3>
        <button
          class="text-muted-foreground hover:text-foreground text-2xl leading-none"
          type="button"
          aria-label="关闭测试对话框"
          @click="closeDialog"
        >×</button>
      </div>

      <div v-if="tool" class="space-y-4">
        <!-- Tool 基本信息 -->
        <div class="text-sm text-muted-foreground">
          <p v-if="tool.description">{{ tool.description }}</p>
          <p class="mt-1">ID: <span class="font-mono">{{ tool.id }}</span></p>
        </div>

        <!-- 参数表单 -->
        <div v-if="formFields.length > 0" class="space-y-4">
          <h4 class="text-sm font-medium text-foreground">输入参数</h4>
          
          <div v-for="field in formFields" :key="field.key" class="space-y-1">
            <label class="block text-sm font-medium text-foreground">
              {{ field.label }}
              <span v-if="field.required" class="text-destructive">*</span>
            </label>
            
            <p v-if="field.description" class="text-xs text-muted-foreground mb-1">
              {{ field.description }}
            </p>

            <!-- 字符串输入 -->
            <Input
              v-if="field.type === 'string' && !field.enum && field.format !== 'textarea'"
              v-model="testArguments[field.key]"
              type="text"
              :class="{ 'border-destructive': errors[field.key] }"
              :placeholder="field.description || `输入 ${field.label}`"
            />

            <!-- 多行文本 -->
            <Textarea
              v-else-if="field.type === 'string' && field.format === 'textarea'"
              v-model="testArguments[field.key]"
              rows="4"
              class="font-mono text-sm"
              :class="{ 'border-destructive': errors[field.key] }"
              :placeholder="field.description || `输入 ${field.label}`"
            />

            <!-- 枚举选择 -->
            <select
              v-else-if="field.enum && field.enum.length > 0"
              v-model="testArguments[field.key]"
              class="w-full px-3 py-2 border border-input rounded-md bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              :class="{ 'border-destructive': errors[field.key] }"
            >
              <option value="">请选择...</option>
              <option v-for="option in field.enum" :key="option" :value="option">
                {{ option }}
              </option>
            </select>

            <!-- 数字输入 -->
            <Input
              v-else-if="field.type === 'number' || field.type === 'integer'"
              :model-value="String(testArguments[field.key])"
              @update:model-value="testArguments[field.key] = Number($event)"
              type="number"
              :class="{ 'border-destructive': errors[field.key] }"
              :placeholder="field.description || `输入 ${field.label}`"
            />

            <!-- 布尔值 -->
            <div v-else-if="field.type === 'boolean'" class="flex items-center gap-2">
              <Checkbox
                :checked="testArguments[field.key]"
                @update:checked="testArguments[field.key] = $event"
              />
              <span class="text-sm text-muted-foreground">{{ field.description || '启用' }}</span>
            </div>

            <!-- JSON 对象/数组 -->
            <Textarea
              v-else-if="field.type === 'object' || field.type === 'array'"
              :model-value="typeof testArguments[field.key] === 'object' ? JSON.stringify(testArguments[field.key], null, 2) : testArguments[field.key]"
              rows="6"
              class="font-mono text-sm"
              :class="{ 'border-destructive': errors[field.key] }"
              placeholder='例如: {"key": "value"} 或 ["item1", "item2"]'
              @input="(e: Event) => {
                const value = (e.target as HTMLTextAreaElement).value
                try {
                  if (value.trim()) {
                    testArguments[field.key] = JSON.parse(value)
                    delete errors[field.key]
                  } else {
                    testArguments[field.key] = field.type === 'array' ? [] : {}
                  }
                } catch (err) {
                  errors[field.key] = '无效的 JSON 格式'
                }
              }"
            />

            <p v-if="errors[field.key]" class="text-xs text-destructive mt-1">
              {{ errors[field.key] }}
            </p>
          </div>
        </div>

        <!-- 无参数提示 -->
        <div v-else class="text-sm text-muted-foreground py-4 text-center">
          此 Tool 无需输入参数
        </div>

        <!-- 操作按钮 -->
        <div class="flex justify-end gap-2 pt-4 border-t border-border">
          <button
            class="h-9 px-4 rounded-md text-sm border border-input hover:bg-accent transition-colors"
            type="button"
            @click="closeDialog"
          >取消</button>
          <button
            class="h-9 px-4 rounded-md text-sm bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
            type="button"
            :disabled="testLoading"
            @click="runTest"
          >{{ testLoading ? '测试中...' : '运行测试' }}</button>
        </div>

        <!-- 测试结果 -->
        <div v-if="testResult" class="mt-4 border-t border-border pt-4">
          <h4 class="text-sm font-medium text-foreground mb-2">测试结果</h4>
          <div class="space-y-2">
            <!-- 状态 -->
            <div class="flex items-center gap-2">
              <span class="text-sm text-muted-foreground">状态：</span>
              <span
                class="text-sm px-2 py-0.5 rounded-full"
                :class="testResult.success ? 'bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200' : 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200'"
              >{{ testResult.success ? '成功' : '失败' }}</span>
            </div>

            <!-- 错误信息 -->
            <div v-if="testResult.error" class="text-sm text-destructive">
              <span class="font-medium">错误：</span>
              <p class="mt-1 whitespace-pre-wrap break-words">{{ testResult.error }}</p>
            </div>

            <!-- 输出结果 -->
            <div v-if="testResult.output" class="text-sm">
              <span class="font-medium text-foreground">输出：</span>
              <pre class="mt-1 p-3 rounded-md bg-muted text-xs whitespace-pre-wrap break-words overflow-x-auto">{{ JSON.stringify(testResult.output, null, 2) }}</pre>
            </div>

            <!-- 元数据 -->
            <div v-if="testResult.meta" class="text-xs text-muted-foreground space-y-1">
              <div>执行时间：{{ testResult.meta.durationMs }}ms</div>
              <div>Tool ID：<span class="font-mono">{{ testResult.meta.toolId }}</span></div>
              <div v-if="testResult.meta.action">操作：{{ testResult.meta.action }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
