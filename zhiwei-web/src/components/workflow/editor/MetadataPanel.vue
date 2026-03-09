<!--
  工作流元数据配置面板。
  位于编排器顶部，提供工作流基本信息（id/name/description/version）、
  触发器配置和输入参数定义。默认紧凑展示基本字段，
  触发器和输入参数区域可折叠展开。
-->
<script setup lang="ts">
import { ref } from 'vue'
import type { TriggerModel, InputParamModel } from '@/composables/useWorkflowModel'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Label } from '@/components/ui/label'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Separator } from '@/components/ui/separator'
import { Plus, X, ChevronDown, ChevronUp } from 'lucide-vue-next'

const props = defineProps<{
  id: string
  name: string
  description: string
  version: string
  triggers: TriggerModel[]
  inputs: InputParamModel[]
}>()

const emit = defineEmits<{
  'update:id': [value: string]
  'update:name': [value: string]
  'update:description': [value: string]
  'update:version': [value: string]
  'update:triggers': [value: TriggerModel[]]
  'update:inputs': [value: InputParamModel[]]
}>()

/** 折叠状态：触发器和输入参数区域 */
const expanded = ref(false)

// ========== 触发器操作 ==========

/** 添加一个默认的手动触发器 */
function addTrigger() {
  emit('update:triggers', [...props.triggers, { type: 'manual' }])
}

/** 删除指定索引的触发器 */
function removeTrigger(index: number) {
  const updated = props.triggers.filter((_, i) => i !== index)
  emit('update:triggers', updated)
}

/** 更新指定索引触发器的类型，重置类型专属字段 */
function updateTriggerType(index: number, type: string) {
  const updated = [...props.triggers]
  const triggerType = type as TriggerModel['type']
  const newTrigger: TriggerModel = { type: triggerType }
  if (triggerType === 'cron') newTrigger.cron = ''
  if (triggerType === 'event') newTrigger.eventType = ''
  updated[index] = newTrigger
  emit('update:triggers', updated)
}

/** 更新指定索引触发器的某个字段 */
function updateTriggerField(index: number, field: string, value: string) {
  const updated = [...props.triggers]
  updated[index] = { ...updated[index], [field]: value }
  emit('update:triggers', updated)
}

// ========== 输入参数操作 ==========

/** 添加一个默认输入参数 */
function addInput() {
  emit('update:inputs', [
    ...props.inputs,
    { name: '', type: 'string', required: false, defaultValue: '', description: '' },
  ])
}

/** 删除指定索引的输入参数 */
function removeInput(index: number) {
  const updated = props.inputs.filter((_, i) => i !== index)
  emit('update:inputs', updated)
}

/** 更新指定索引输入参数的某个字段 */
function updateInputField(index: number, field: string, value: unknown) {
  const updated = [...props.inputs]
  updated[index] = { ...updated[index], [field]: value }
  emit('update:inputs', updated)
}
</script>

<template>
  <div class="border-b bg-muted/30 px-4 py-3">
    <!-- 基本元数据字段：紧凑网格布局 -->
    <div class="grid grid-cols-4 gap-3">
      <div class="space-y-1">
        <Label class="text-xs">工作流 ID</Label>
        <Input
          :model-value="id"
          placeholder="workflow-id"
          class="h-8 text-sm"
          @update:model-value="emit('update:id', $event as string)"
        />
      </div>
      <div class="space-y-1">
        <Label class="text-xs">工作流名称</Label>
        <Input
          :model-value="name"
          placeholder="输入名称"
          class="h-8 text-sm"
          @update:model-value="emit('update:name', $event as string)"
        />
      </div>
      <div class="space-y-1">
        <Label class="text-xs">版本号</Label>
        <Input
          :model-value="version"
          placeholder="1.0.0"
          class="h-8 text-sm"
          @update:model-value="emit('update:version', $event as string)"
        />
      </div>
      <div class="space-y-1">
        <Label class="text-xs">描述</Label>
        <Textarea
          :model-value="description"
          placeholder="工作流描述"
          class="h-8 min-h-8 resize-none text-sm"
          rows="1"
          @update:model-value="emit('update:description', $event as string)"
        />
      </div>
    </div>

    <!-- 展开/折叠按钮 -->
    <button
      class="mt-2 flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
      @click="expanded = !expanded"
    >
      <component :is="expanded ? ChevronUp : ChevronDown" class="h-3.5 w-3.5" />
      {{ expanded ? '收起' : '触发器与输入参数' }}
      <span v-if="!expanded" class="text-muted-foreground/60">
        ({{ triggers.length }} 个触发器, {{ inputs.length }} 个参数)
      </span>
    </button>

    <!-- 可折叠区域：触发器 + 输入参数 -->
    <div v-if="expanded" class="mt-3 space-y-4">
      <Separator />

      <!-- 触发器配置区域 -->
      <div>
        <div class="mb-2 flex items-center justify-between">
          <span class="text-xs font-medium">触发器</span>
          <Button variant="ghost" size="sm" class="h-6 px-2 text-xs" @click="addTrigger">
            <Plus class="mr-1 h-3 w-3" />
            添加触发器
          </Button>
        </div>

        <div v-if="triggers.length === 0" class="text-xs text-muted-foreground">
          暂无触发器
        </div>

        <div v-for="(trigger, idx) in triggers" :key="idx" class="mb-2 flex items-start gap-2">
          <!-- 触发器类型选择 -->
          <div class="w-28 shrink-0 space-y-1">
            <Select
              :model-value="trigger.type"
              @update:model-value="updateTriggerType(idx, $event)"
            >
              <SelectTrigger class="h-8 text-sm">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="cron">定时</SelectItem>
                <SelectItem value="event">事件</SelectItem>
                <SelectItem value="manual">手动</SelectItem>
              </SelectContent>
            </Select>
          </div>

          <!-- 类型专属字段 -->
          <div class="flex-1">
            <Input
              v-if="trigger.type === 'cron'"
              :model-value="trigger.cron ?? ''"
              placeholder="Cron 表达式，如 0 0 * * *"
              class="h-8 text-sm"
              @update:model-value="updateTriggerField(idx, 'cron', $event as string)"
            />
            <Input
              v-else-if="trigger.type === 'event'"
              :model-value="trigger.eventType ?? ''"
              placeholder="事件类型"
              class="h-8 text-sm"
              @update:model-value="updateTriggerField(idx, 'eventType', $event as string)"
            />
            <span v-else class="inline-block pt-1.5 text-xs text-muted-foreground">
              手动触发，无需额外配置
            </span>
          </div>

          <!-- 删除按钮 -->
          <Button
            variant="ghost"
            size="icon"
            class="h-8 w-8 shrink-0 text-muted-foreground hover:text-destructive"
            @click="removeTrigger(idx)"
          >
            <X class="h-3.5 w-3.5" />
          </Button>
        </div>
      </div>

      <Separator />

      <!-- 输入参数定义区域 -->
      <div>
        <div class="mb-2 flex items-center justify-between">
          <span class="text-xs font-medium">输入参数</span>
          <Button variant="ghost" size="sm" class="h-6 px-2 text-xs" @click="addInput">
            <Plus class="mr-1 h-3 w-3" />
            添加参数
          </Button>
        </div>

        <div v-if="inputs.length === 0" class="text-xs text-muted-foreground">
          暂无输入参数
        </div>

        <div v-for="(input, idx) in inputs" :key="idx" class="mb-3 rounded-md border p-2">
          <div class="mb-2 flex items-center justify-between">
            <span class="text-xs text-muted-foreground">参数 {{ idx + 1 }}</span>
            <Button
              variant="ghost"
              size="icon"
              class="h-6 w-6 text-muted-foreground hover:text-destructive"
              @click="removeInput(idx)"
            >
              <X class="h-3 w-3" />
            </Button>
          </div>

          <div class="grid grid-cols-3 gap-2">
            <!-- 参数名称 -->
            <div class="space-y-1">
              <Label class="text-xs">名称</Label>
              <Input
                :model-value="input.name"
                placeholder="paramName"
                class="h-7 text-xs"
                @update:model-value="updateInputField(idx, 'name', $event)"
              />
            </div>
            <!-- 参数类型 -->
            <div class="space-y-1">
              <Label class="text-xs">类型</Label>
              <Input
                :model-value="input.type"
                placeholder="string"
                class="h-7 text-xs"
                @update:model-value="updateInputField(idx, 'type', $event)"
              />
            </div>
            <!-- 默认值 -->
            <div class="space-y-1">
              <Label class="text-xs">默认值</Label>
              <Input
                :model-value="input.defaultValue ?? ''"
                placeholder="可选"
                class="h-7 text-xs"
                @update:model-value="updateInputField(idx, 'defaultValue', $event)"
              />
            </div>
          </div>

          <div class="mt-2 grid grid-cols-3 gap-2">
            <!-- 描述 -->
            <div class="col-span-2 space-y-1">
              <Label class="text-xs">描述</Label>
              <Input
                :model-value="input.description ?? ''"
                placeholder="参数说明"
                class="h-7 text-xs"
                @update:model-value="updateInputField(idx, 'description', $event)"
              />
            </div>
            <!-- 必填 -->
            <div class="flex items-end gap-1.5 pb-0.5">
              <Checkbox
                :checked="input.required"
                @update:checked="updateInputField(idx, 'required', $event)"
              />
              <Label class="text-xs">必填</Label>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
