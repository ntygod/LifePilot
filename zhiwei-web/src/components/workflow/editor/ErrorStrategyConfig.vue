<!--
  错误策略配置组件。
  下拉选择错误策略类型（无/重试/跳过/失败/补偿），
  根据选中类型动态展示对应参数表单。
-->
<script setup lang="ts">
import { computed } from 'vue'
import type { ErrorStrategyModel } from '@/composables/useWorkflowModel'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

const props = defineProps<{
  modelValue: ErrorStrategyModel | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: ErrorStrategyModel | null]
}>()

/** 策略类型选项 */
type StrategyOption = 'none' | 'retry' | 'skip' | 'fail' | 'compensate'

/** 当前选中的策略类型，null 映射为 'none' */
const selectedType = computed<StrategyOption>(() => {
  return props.modelValue?.type ?? 'none'
})

/** 策略类型变更时，发射带默认值的新模型 */
function onTypeChange(value: unknown) {
  const type = String(value ?? 'none') as StrategyOption
  if (type === 'none') {
    emit('update:modelValue', null)
    return
  }
  switch (type) {
    case 'retry':
      emit('update:modelValue', {
        type: 'retry',
        maxAttempts: 3,
        initialDelayMs: 500,
        maxDelayMs: 5000,
      })
      break
    case 'skip':
      emit('update:modelValue', { type: 'skip', reason: '' })
      break
    case 'fail':
      emit('update:modelValue', { type: 'fail' })
      break
    case 'compensate':
      emit('update:modelValue', { type: 'compensate' })
      break
  }
}

/** 更新当前策略的某个字段 */
function updateField(field: string, value: unknown) {
  if (!props.modelValue) return
  emit('update:modelValue', { ...props.modelValue, [field]: value })
}

/** 将输入值解析为数字，无效时返回默认值 */
function parseNumber(val: string | number, fallback: number): number {
  const num = typeof val === 'string' ? parseInt(val, 10) : val
  return Number.isNaN(num) ? fallback : num
}
</script>

<template>
  <div class="space-y-3">
    <!-- 策略类型下拉选择 -->
    <div class="space-y-1.5">
      <Label class="text-xs">错误策略</Label>
      <Select :model-value="selectedType" @update:model-value="onTypeChange">
        <SelectTrigger class="h-8 text-sm">
          <SelectValue placeholder="选择错误策略" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="none">无</SelectItem>
          <SelectItem value="retry">重试</SelectItem>
          <SelectItem value="skip">跳过</SelectItem>
          <SelectItem value="fail">失败</SelectItem>
          <SelectItem value="compensate">补偿</SelectItem>
        </SelectContent>
      </Select>
    </div>

    <!-- Retry 参数 -->
    <template v-if="modelValue?.type === 'retry'">
      <div class="space-y-1.5">
        <Label class="text-xs">最大重试次数</Label>
        <Input
          type="number"
          :model-value="modelValue.maxAttempts ?? 3"
          min="1"
          class="h-8 text-sm"
          @update:model-value="updateField('maxAttempts', parseNumber($event, 3))"
        />
      </div>
      <div class="space-y-1.5">
        <Label class="text-xs">初始延迟 (ms)</Label>
        <Input
          type="number"
          :model-value="modelValue.initialDelayMs ?? 500"
          min="0"
          class="h-8 text-sm"
          @update:model-value="updateField('initialDelayMs', parseNumber($event, 500))"
        />
      </div>
      <div class="space-y-1.5">
        <Label class="text-xs">最大延迟 (ms)</Label>
        <Input
          type="number"
          :model-value="modelValue.maxDelayMs ?? 5000"
          min="0"
          class="h-8 text-sm"
          @update:model-value="updateField('maxDelayMs', parseNumber($event, 5000))"
        />
      </div>
    </template>

    <!-- Skip 参数 -->
    <template v-if="modelValue?.type === 'skip'">
      <div class="space-y-1.5">
        <Label class="text-xs">跳过原因</Label>
        <Input
          :model-value="modelValue.reason ?? ''"
          placeholder="输入跳过原因"
          class="h-8 text-sm"
          @update:model-value="updateField('reason', $event)"
        />
      </div>
    </template>

    <!-- Fail：无额外参数 -->

    <!-- Compensate 简化提示 -->
    <template v-if="modelValue?.type === 'compensate'">
      <div class="rounded-md border border-dashed p-3 text-xs text-muted-foreground">
        补偿步骤配置（简化版）
      </div>
    </template>
  </div>
</template>
