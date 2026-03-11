<!--
  基于 JSON Schema 的参数表单编辑器。
  从 schema.properties 自动生成逐字段输入表单，支持 string/number/boolean/object/array 类型。
  当 schema 为空时回退到 JSON 文本编辑模式。
-->
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { Switch } from '@/components/ui/switch'
import { Button } from '@/components/ui/button'
import { Code, List } from 'lucide-vue-next'

interface SchemaProperty {
  type?: string
  description?: string
  default?: unknown
  enum?: unknown[]
}

const props = defineProps<{
  /** 当前参数值 */
  modelValue: Record<string, unknown>
  /** JSON Schema（标准格式，含 properties / required） */
  schema?: Record<string, unknown> | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: Record<string, unknown>]
}>()

/** 是否使用 JSON 原始编辑模式 */
const rawMode = ref(false)
const rawText = ref('')

/** 解析 schema properties */
const schemaProperties = computed<Array<{
  key: string
  type: string
  description: string
  required: boolean
  defaultValue: unknown
  enumValues: unknown[]
}>>(() => {
  if (!props.schema || !props.schema.properties) return []
  const properties = props.schema.properties as Record<string, SchemaProperty>
  const required = (props.schema.required as string[]) ?? []

  return Object.entries(properties).map(([key, prop]) => ({
    key,
    type: prop.type ?? 'string',
    description: prop.description ?? '',
    required: required.includes(key),
    defaultValue: prop.default,
    enumValues: prop.enum ?? [],
  }))
})

/** 是否有可用的 schema 字段 */
const hasSchema = computed(() => schemaProperties.value.length > 0)

/** 同步 rawText */
watch(() => props.modelValue, (val) => {
  if (rawMode.value) return
  const entries = Object.entries(val ?? {})
  rawText.value = entries.length > 0 ? JSON.stringify(val, null, 2) : ''
}, { immediate: true })

/** 更新单个字段值 */
function updateField(key: string, value: unknown) {
  const updated = { ...props.modelValue }
  if (value === '' || value === undefined || value === null) {
    delete updated[key]
  } else {
    updated[key] = value
  }
  emit('update:modelValue', updated)
}

/** 获取字段当前值 */
function getFieldValue(key: string, type: string): unknown {
  const val = props.modelValue?.[key]
  if (val !== undefined) return val
  // 返回类型默认值用于展示
  return undefined
}

/** 处理数字输入 */
function handleNumberInput(key: string, val: string) {
  if (val === '') {
    updateField(key, undefined)
    return
  }
  const num = Number(val)
  if (!isNaN(num)) updateField(key, num)
}

/** 切换到 JSON 模式 */
function switchToRaw() {
  rawMode.value = true
  const entries = Object.entries(props.modelValue ?? {})
  rawText.value = entries.length > 0 ? JSON.stringify(props.modelValue, null, 2) : ''
}

/** 切换到表单模式 */
function switchToForm() {
  // 先尝试解析当前 rawText
  if (rawText.value.trim()) {
    try {
      const parsed = JSON.parse(rawText.value)
      emit('update:modelValue', parsed)
    } catch {
      // 解析失败不切换
      return
    }
  }
  rawMode.value = false
}

/** 更新 raw JSON */
function updateRawText(val: string | number) {
  const text = String(val)
  rawText.value = text
  try {
    const parsed = text.trim() ? JSON.parse(text) : {}
    emit('update:modelValue', parsed)
  } catch {
    // JSON 解析失败时不更新
  }
}
</script>

<template>
  <div class="space-y-3">
    <!-- 有 schema 时显示模式切换 -->
    <div v-if="hasSchema" class="flex items-center justify-between">
      <span class="text-xs text-muted-foreground">参数</span>
      <Button
        type="button"
        variant="ghost"
        size="sm"
        class="h-6 gap-1 px-1.5 text-[10px]"
        @click="rawMode ? switchToForm() : switchToRaw()"
      >
        <component :is="rawMode ? List : Code" class="h-3 w-3" />
        {{ rawMode ? '表单模式' : 'JSON 模式' }}
      </Button>
    </div>

    <!-- 表单模式 -->
    <template v-if="hasSchema && !rawMode">
      <div
        v-for="field in schemaProperties"
        :key="field.key"
        class="space-y-1"
      >
        <Label class="text-xs">
          {{ field.key }}
          <span v-if="field.required" class="text-destructive">*</span>
        </Label>
        <p v-if="field.description" class="text-[10px] text-muted-foreground leading-tight">
          {{ field.description }}
        </p>

        <!-- 布尔类型 -->
        <div v-if="field.type === 'boolean'" class="flex items-center gap-2 pt-0.5">
          <Switch
            :checked="!!getFieldValue(field.key, field.type)"
            @update:checked="updateField(field.key, $event)"
          />
          <span class="text-xs text-muted-foreground">
            {{ getFieldValue(field.key, field.type) ? '是' : '否' }}
          </span>
        </div>

        <!-- 枚举类型 -->
        <select
          v-else-if="field.enumValues.length > 0"
          :value="(getFieldValue(field.key, field.type) as string) ?? ''"
          class="flex h-8 w-full rounded-md border border-input bg-background px-3 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          @change="updateField(field.key, ($event.target as HTMLSelectElement).value)"
        >
          <option value="">请选择...</option>
          <option v-for="opt in field.enumValues" :key="String(opt)" :value="String(opt)">
            {{ opt }}
          </option>
        </select>

        <!-- 数字类型 -->
        <Input
          v-else-if="field.type === 'number' || field.type === 'integer'"
          type="number"
          :model-value="String(getFieldValue(field.key, field.type) ?? '')"
          :placeholder="field.defaultValue !== undefined ? `默认: ${field.defaultValue}` : ''"
          class="h-8 text-sm"
          @update:model-value="handleNumberInput(field.key, String($event))"
        />

        <!-- 对象/数组类型 → 小 JSON 编辑框 -->
        <Textarea
          v-else-if="field.type === 'object' || field.type === 'array'"
          :model-value="getFieldValue(field.key, field.type) !== undefined
            ? JSON.stringify(getFieldValue(field.key, field.type), null, 2)
            : ''"
          :placeholder="field.type === 'object' ? '{...}' : '[...]'"
          rows="2"
          class="text-xs font-mono"
          @update:model-value="(val: string | number) => {
            const text = String(val)
            try { updateField(field.key, text.trim() ? JSON.parse(text) : undefined) } catch {}
          }"
        />

        <!-- 字符串类型（默认） -->
        <Input
          v-else
          :model-value="String(getFieldValue(field.key, field.type) ?? '')"
          :placeholder="field.defaultValue !== undefined ? `默认: ${field.defaultValue}` : ''"
          class="h-8 text-sm"
          @update:model-value="updateField(field.key, $event)"
        />
      </div>

      <!-- 无字段时的提示 -->
      <p v-if="schemaProperties.length === 0" class="text-xs text-muted-foreground">
        该资源无需参数
      </p>
    </template>

    <!-- JSON 原始编辑模式（无 schema 或用户切换） -->
    <template v-else>
      <Label v-if="!hasSchema" class="text-xs">参数（JSON）</Label>
      <Textarea
        :model-value="rawText"
        placeholder='{"key": "value"}'
        rows="4"
        class="text-sm font-mono"
        @update:model-value="updateRawText"
      />
    </template>
  </div>
</template>
