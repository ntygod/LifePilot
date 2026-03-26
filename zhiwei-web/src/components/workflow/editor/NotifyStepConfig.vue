<!--
  通知步骤配置组件。
  配置通知的目标用户和内容。
-->
<script setup lang="ts">
import type { NotifyStepConfig } from '@/composables/useWorkflowModel'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'

const props = defineProps<{
  modelValue: NotifyStepConfig
}>()

const emit = defineEmits<{
  'update:modelValue': [value: NotifyStepConfig]
}>()

function updateField<K extends keyof NotifyStepConfig>(field: K, value: NotifyStepConfig[K]) {
  emit('update:modelValue', { ...props.modelValue, [field]: value })
}
</script>

<template>
  <div class="space-y-4">
    <!-- 目标用户 -->
    <div class="space-y-1.5">
      <Label class="text-xs">目标用户</Label>
      <Select :model-value="modelValue.targetUserId" @update:model-value="updateField('targetUserId', $event as string)">
        <SelectTrigger class="h-8 text-sm">
          <SelectValue placeholder="选择目标用户" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="owner">所有者</SelectItem>
          <SelectItem value="session">会话用户</SelectItem>
          <SelectItem value="custom">自定义用户 ID</SelectItem>
        </SelectContent>
      </Select>
      <p class="text-xs text-muted-foreground">owner: 当前工作流所有者, session: 当前会话用户</p>
    </div>

    <!-- 通知内容 -->
    <div class="space-y-1.5">
      <Label class="text-xs">通知内容</Label>
      <Textarea
        :model-value="modelValue.content"
        placeholder="通知内容，支持 ${} 表达式"
        class="h-24 text-sm font-mono"
        @update:model-value="updateField('content', $event as string)"
      />
    </div>

    <!-- 内容格式 -->
    <div class="space-y-1.5">
      <Label class="text-xs">内容格式</Label>
      <Select :model-value="modelValue.contentType" @update:model-value="updateField('contentType', $event as 'TEXT' | 'MARKDOWN' | 'CARD')">
        <SelectTrigger class="h-8 text-sm">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="TEXT">纯文本</SelectItem>
          <SelectItem value="MARKDOWN">Markdown</SelectItem>
          <SelectItem value="CARD">卡片</SelectItem>
        </SelectContent>
      </Select>
    </div>
  </div>
</template>
