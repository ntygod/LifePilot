<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import type { WorkflowInputParam } from '@/types'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import { Textarea } from '@/components/ui/textarea'

const props = defineProps<{
  open: boolean
  inputs: Record<string, WorkflowInputParam>
  loading: boolean
  fieldErrors?: Record<string, string>
  formError?: string | null
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  'confirm': [values: Record<string, unknown>]
  'change': [key: string, value: unknown]
}>()

const formValues = reactive<Record<string, unknown>>({})
const localErrors = reactive<Record<string, string>>({})

const inputEntries = computed(() => (
  Object.entries(props.inputs).sort(([leftKey, leftParam], [rightKey, rightParam]) => {
    if (leftParam.required !== rightParam.required) {
      return Number(rightParam.required) - Number(leftParam.required)
    }

    return leftKey.localeCompare(rightKey)
  })
))

watch(() => props.open, (open) => {
  if (!open) return

  Object.keys(formValues).forEach(key => delete formValues[key])
  Object.keys(localErrors).forEach(key => delete localErrors[key])

  for (const [key, param] of Object.entries(props.inputs)) {
    if (param.defaultValue !== undefined && param.defaultValue !== null) {
      if (param.type === 'list' || param.type === 'map') {
        formValues[key] = JSON.stringify(param.defaultValue, null, 2)
      } else {
        formValues[key] = param.defaultValue
      }
    } else if (param.type === 'boolean') {
      formValues[key] = false
    } else {
      formValues[key] = ''
    }
  }
})

function getParamLabel(key: string, param: WorkflowInputParam) {
  return param.name || key
}

function getParamTypeLabel(type: WorkflowInputParam['type']) {
  switch (type) {
    case 'number':
      return '数字'
    case 'boolean':
      return '开关'
    case 'list':
      return '列表'
    case 'map':
      return '对象'
    default:
      return '文本'
  }
}

function getSerializedDefaultValue(value: unknown) {
  if (value === undefined || value === null || value === '') return null
  if (typeof value === 'string') return value

  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

function getDisplayedError(key: string) {
  return localErrors[key] || props.fieldErrors?.[key]
}

function getComplexPlaceholder(type: WorkflowInputParam['type']) {
  if (type === 'list') return '["item-1", "item-2"]'
  return '{\n  "key": "value"\n}'
}

function updateFieldValue(key: string, value: unknown) {
  formValues[key] = value
  delete localErrors[key]
  emit('change', key, value)
}

function validate() {
  Object.keys(localErrors).forEach(key => delete localErrors[key])
  let valid = true

  for (const [key, param] of Object.entries(props.inputs)) {
    const value = formValues[key]
    const isEmptyString = typeof value === 'string' && value.trim() === ''

    if (param.required && (value === undefined || value === null || isEmptyString)) {
      localErrors[key] = `${getParamLabel(key, param)}为必填项`
      valid = false
      continue
    }

    if (param.type === 'number') {
      const rawValue = typeof value === 'string' ? value.trim() : value
      if (rawValue === '' || rawValue === undefined || rawValue === null) continue

      if (Number.isNaN(Number(rawValue))) {
        localErrors[key] = `${getParamLabel(key, param)}需要填写数字`
        valid = false
      }
      continue
    }

    if (param.type === 'list' || param.type === 'map') {
      const rawValue = typeof value === 'string' ? value.trim() : value
      if (!rawValue) continue

      try {
        JSON.parse(String(rawValue))
      } catch {
        localErrors[key] = `${getParamLabel(key, param)}需要填写合法的 JSON`
        valid = false
      }
    }
  }

  return valid
}

function normalizeValue(param: WorkflowInputParam, rawValue: unknown) {
  if (param.type === 'number') {
    if (rawValue === '' || rawValue === undefined || rawValue === null) {
      return undefined
    }
    return Number(rawValue)
  }

  if (param.type === 'list' || param.type === 'map') {
    if (typeof rawValue === 'string') {
      const trimmed = rawValue.trim()
      if (!trimmed) return undefined
      return JSON.parse(trimmed)
    }

    return rawValue
  }

  if (typeof rawValue === 'string') {
    const trimmed = rawValue.trim()
    return trimmed === '' ? undefined : trimmed
  }

  return rawValue
}

function handleConfirm() {
  if (!validate()) return

  const values: Record<string, unknown> = {}
  for (const [key, param] of Object.entries(props.inputs)) {
    const normalized = normalizeValue(param, formValues[key])
    if (normalized !== undefined) {
      values[key] = normalized
    } else if (param.type === 'boolean') {
      values[key] = false
    }
  }

  emit('confirm', values)
}

function handleCancel() {
  emit('update:open', false)
}
</script>

<template>
  <Dialog :open="open" @update:open="emit('update:open', $event)">
    <DialogContent class="w-[min(96vw,72rem)] max-w-[72rem] gap-0 overflow-hidden p-0 sm:w-[min(92vw,72rem)] sm:max-w-[72rem]">
      <DialogHeader class="border-b border-border/60 px-5 py-5 sm:px-6">
        <DialogTitle>填写触发参数</DialogTitle>
        <DialogDescription>
          先补全这个工作流需要的输入参数，再开始执行。必填参数会在提交前校验。
        </DialogDescription>
      </DialogHeader>

      <div class="space-y-5 px-5 py-5 sm:px-6">
        <div class="flex flex-wrap items-center gap-2 rounded-2xl border border-border/60 bg-muted/30 px-4 py-3 text-xs text-muted-foreground">
          <Badge variant="outline">参数 {{ inputEntries.length }}</Badge>
          <Badge variant="outline">必填 {{ inputEntries.filter(([, param]) => param.required).length }}</Badge>
          <Badge variant="outline">可选 {{ inputEntries.filter(([, param]) => !param.required).length }}</Badge>
        </div>

        <div
          v-if="formError"
          class="rounded-2xl border border-amber-300/70 bg-amber-50/80 px-4 py-3 text-sm text-amber-950"
        >
          {{ formError }}
        </div>

        <div class="grid max-h-[min(68vh,40rem)] gap-4 overflow-y-auto pr-1 md:grid-cols-2">
          <section
            v-for="[key, param] in inputEntries"
            :key="key"
            :class="[
              'rounded-2xl border border-border/60 bg-card/70 p-4 shadow-sm',
              param.type === 'list' || param.type === 'map' ? 'md:col-span-2' : '',
            ]"
          >
            <div class="flex flex-wrap items-start justify-between gap-3">
              <div class="space-y-1">
                <Label :for="`wf-input-${key}`" class="flex items-center gap-1 text-sm font-medium">
                  {{ getParamLabel(key, param) }}
                  <span v-if="param.required" class="text-destructive">*</span>
                </Label>
                <p v-if="param.description" class="text-sm leading-6 text-muted-foreground">
                  {{ param.description }}
                </p>
              </div>

              <div class="flex flex-wrap gap-2">
                <Badge variant="outline">{{ getParamTypeLabel(param.type) }}</Badge>
                <Badge v-if="param.required" variant="secondary">必填</Badge>
                <Badge v-else variant="outline">可选</Badge>
                <Badge v-if="getSerializedDefaultValue(param.defaultValue)" variant="outline">
                  默认值：{{ getSerializedDefaultValue(param.defaultValue) }}
                </Badge>
              </div>
            </div>

            <div class="mt-4 space-y-2">
              <div
                v-if="param.type === 'boolean'"
                class="flex items-center gap-3 rounded-xl border border-border/60 bg-muted/25 px-3 py-3"
              >
                <Switch
                  :id="`wf-input-${key}`"
                  :checked="Boolean(formValues[key])"
                  @update:checked="updateFieldValue(key, $event)"
                />
                <span class="text-sm text-muted-foreground">{{ formValues[key] ? '开启' : '关闭' }}</span>
              </div>

              <Input
                v-else-if="param.type === 'number'"
                :id="`wf-input-${key}`"
                type="number"
                :model-value="String(formValues[key] ?? '')"
                :aria-invalid="Boolean(getDisplayedError(key))"
                :placeholder="param.description || `请输入${getParamLabel(key, param)}`"
                @update:model-value="updateFieldValue(key, $event)"
              />

              <Textarea
                v-else-if="param.type === 'list' || param.type === 'map'"
                :id="`wf-input-${key}`"
                :model-value="String(formValues[key] ?? '')"
                :aria-invalid="Boolean(getDisplayedError(key))"
                :placeholder="getComplexPlaceholder(param.type)"
                class="min-h-28"
                @update:model-value="updateFieldValue(key, $event)"
              />

              <Input
                v-else
                :id="`wf-input-${key}`"
                :model-value="String(formValues[key] ?? '')"
                :aria-invalid="Boolean(getDisplayedError(key))"
                :placeholder="param.description || `请输入${getParamLabel(key, param)}`"
                @update:model-value="updateFieldValue(key, $event)"
              />

              <p v-if="param.type === 'list' || param.type === 'map'" class="text-xs text-muted-foreground">
                复杂类型请填写 JSON，提交时会自动解析。
              </p>
              <p v-if="getDisplayedError(key)" class="text-xs text-destructive">
                {{ getDisplayedError(key) }}
              </p>
            </div>
          </section>
        </div>
      </div>

      <DialogFooter class="border-t border-border/60 px-5 py-4 sm:px-6">
        <Button variant="outline" :disabled="loading" @click="handleCancel">取消</Button>
        <Button :disabled="loading" @click="handleConfirm">
          {{ loading ? '触发中...' : '确认触发' }}
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
