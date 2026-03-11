<script setup lang="ts">
import { reactive, watch } from 'vue'
import type { WorkflowInputParam } from '@/types'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'

const props = defineProps<{
  open: boolean
  inputs: Record<string, WorkflowInputParam>
  loading: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  'confirm': [values: Record<string, unknown>]
}>()

const formValues = reactive<Record<string, unknown>>({})
const errors = reactive<Record<string, string>>({})

/** 当对话框打开时，用默认值初始化表单 */
watch(() => props.open, (open) => {
  if (!open) return
  Object.keys(formValues).forEach(k => delete formValues[k])
  Object.keys(errors).forEach(k => delete errors[k])

  for (const [key, param] of Object.entries(props.inputs)) {
    if (param.defaultValue !== undefined && param.defaultValue !== null) {
      formValues[key] = param.defaultValue
    } else if (param.type === 'boolean') {
      formValues[key] = false
    } else {
      formValues[key] = ''
    }
  }
})

function validate(): boolean {
  Object.keys(errors).forEach(k => delete errors[k])
  let valid = true

  for (const [key, param] of Object.entries(props.inputs)) {
    if (!param.required) continue
    const val = formValues[key]
    if (val === undefined || val === null || val === '') {
      errors[key] = `${param.name || key} 为必填项`
      valid = false
    }
  }
  return valid
}

function handleConfirm() {
  if (!validate()) return

  const values: Record<string, unknown> = {}
  for (const [key, param] of Object.entries(props.inputs)) {
    const raw = formValues[key]
    if (param.type === 'number' && raw !== '' && raw !== undefined) {
      values[key] = Number(raw)
    } else {
      values[key] = raw
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
    <DialogContent class="sm:max-w-lg">
      <DialogHeader>
        <DialogTitle>输入参数</DialogTitle>
        <DialogDescription>请填写工作流所需的输入参数后触发执行。</DialogDescription>
      </DialogHeader>

      <div class="space-y-4 py-2">
        <div v-for="(param, key) in inputs" :key="key" class="space-y-2">
          <Label :for="`wf-input-${key}`" class="flex items-center gap-1">
            {{ param.name || key }}
            <span v-if="param.required" class="text-destructive">*</span>
          </Label>

          <!-- boolean → Switch -->
          <div v-if="param.type === 'boolean'" class="flex items-center gap-2">
            <Switch
              :id="`wf-input-${key}`"
              :checked="!!formValues[key]"
              @update:checked="formValues[key] = $event"
            />
            <span class="text-sm text-muted-foreground">{{ formValues[key] ? '是' : '否' }}</span>
          </div>

          <!-- number → Input[type=number] -->
          <Input
            v-else-if="param.type === 'number'"
            :id="`wf-input-${key}`"
            type="number"
            :model-value="String(formValues[key] ?? '')"
            :placeholder="param.description || `请输入 ${param.name || key}`"
            @update:model-value="formValues[key] = $event"
          />

          <!-- string / 其他 → Input -->
          <Input
            v-else
            :id="`wf-input-${key}`"
            :model-value="String(formValues[key] ?? '')"
            :placeholder="param.description || `请输入 ${param.name || key}`"
            @update:model-value="formValues[key] = $event"
          />

          <p v-if="param.description" class="text-xs text-muted-foreground">
            {{ param.description }}
          </p>
          <p v-if="errors[key]" class="text-xs text-destructive">
            {{ errors[key] }}
          </p>
        </div>
      </div>

      <DialogFooter>
        <Button variant="outline" :disabled="loading" @click="handleCancel">取消</Button>
        <Button :disabled="loading" @click="handleConfirm">
          {{ loading ? '触发中...' : '确认触发' }}
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
