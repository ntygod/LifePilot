<!--
  审批步骤配置组件。
  配置 message（审批消息）、approvers（审批人列表）、
  timeoutSeconds（超时时间）和 autoApproveOnTimeout（超时自动通过）。
-->
<script setup lang="ts">
import { computed } from 'vue'
import type { ApprovalStepConfig } from '@/composables/useWorkflowModel'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Label } from '@/components/ui/label'
import { Checkbox } from '@/components/ui/checkbox'

const props = defineProps<{
  modelValue: ApprovalStepConfig
}>()

const emit = defineEmits<{
  'update:modelValue': [value: ApprovalStepConfig]
}>()

/** approvers 数组转为逗号分隔字符串展示 */
const approversText = computed(() => {
  return (props.modelValue.approvers ?? []).join(', ')
})

/** 解析数字输入，无效时返回默认值 */
function parseNumber(val: string | number, fallback: number): number {
  const num = typeof val === 'string' ? parseInt(val, 10) : val
  return Number.isNaN(num) ? fallback : num
}

function updateMessage(val: string | number) {
  emit('update:modelValue', { ...props.modelValue, message: String(val) })
}

function updateApprovers(val: string | number) {
  const text = String(val)
  const list = text
    .split(',')
    .map(s => s.trim())
    .filter(s => s.length > 0)
  emit('update:modelValue', { ...props.modelValue, approvers: list })
}

function updateTimeout(val: string | number) {
  emit('update:modelValue', {
    ...props.modelValue,
    timeoutSeconds: parseNumber(val, 3600),
  })
}

function updateAutoApprove(checked: boolean) {
  emit('update:modelValue', {
    ...props.modelValue,
    autoApproveOnTimeout: checked,
  })
}
</script>

<template>
  <div class="space-y-3">
    <div class="space-y-1.5">
      <Label class="text-xs">审批消息</Label>
      <Textarea
        :model-value="modelValue.message"
        placeholder="输入审批消息内容"
        rows="3"
        class="text-sm"
        @update:model-value="updateMessage"
      />
    </div>
    <div class="space-y-1.5">
      <Label class="text-xs">审批人（逗号分隔）</Label>
      <Input
        :model-value="approversText"
        placeholder="user1, user2"
        class="h-8 text-sm"
        @update:model-value="updateApprovers"
      />
    </div>
    <div class="space-y-1.5">
      <Label class="text-xs">超时时间（秒）</Label>
      <Input
        type="number"
        :model-value="modelValue.timeoutSeconds"
        min="0"
        placeholder="3600"
        class="h-8 text-sm"
        @update:model-value="updateTimeout"
      />
    </div>
    <div class="flex items-center gap-2">
      <Checkbox
        :model-value="modelValue.autoApproveOnTimeout"
        @update:model-value="updateAutoApprove"
      />
      <Label class="text-xs cursor-pointer">超时自动通过</Label>
    </div>
  </div>
</template>
