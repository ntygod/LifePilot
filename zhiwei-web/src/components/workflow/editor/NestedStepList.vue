<!--
  嵌套步骤列表编辑器。
  用于 ConditionStepConfig / LoopStepConfig / ParallelStepConfig 中的子步骤列表编辑。
  提供添加、删除子步骤，以及修改子步骤 id、name、type 的内联表单。
-->
<script setup lang="ts">
import type { StepModel, StepType, StepConfig } from '@/composables/useWorkflowModel'
import { STEP_TYPE_META } from '@/components/workflow/editor/stepTypeMeta'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Plus, X } from 'lucide-vue-next'

const props = defineProps<{
  modelValue: StepModel[]
  label: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: StepModel[]]
}>()

/** 所有步骤类型列表 */
const ALL_STEP_TYPES: StepType[] = [
  'skill', 'tool', 'llm', 'condition', 'loop',
  'parallel', 'sub-workflow', 'noop', 'wait', 'approval', 'notify',
]

/** 步骤计数器，用于生成唯一 ID */
let counter = 0

/** 根据步骤类型创建默认配置 */
function createDefaultConfig(type: StepType): StepConfig {
  switch (type) {
    case 'skill': return { skillId: '', params: {} }
    case 'tool': return { toolId: '', params: {} }
    case 'llm': return { scene: '', capability: 'CHAT', promptTemplate: '', outputSchema: undefined, modelName: undefined, preferredProviderId: undefined, media: [] }
    case 'condition': return { condition: '', thenSteps: [], elseSteps: [] }
    case 'loop': return { items: '', loopVar: 'item', body: [] }
    case 'parallel': return { branches: [[]] }
    case 'sub-workflow': return { workflowId: '', params: {} }
    case 'noop': return {}
    case 'wait': return { durationSeconds: 60 }
    case 'approval': return { message: '', approvers: [], timeoutSeconds: 3600, autoApproveOnTimeout: false }
    case 'notify': return { targetUserId: '', content: '', contentType: 'TEXT' }
  }
}

/** 添加一个默认的 noop 子步骤 */
function addStep() {
  counter++
  const newStep: StepModel = {
    id: `nested-${counter}`,
    name: '新步骤',
    type: 'noop',
    config: {},
    dependsOn: [],
    errorStrategy: null,
  }
  emit('update:modelValue', [...props.modelValue, newStep])
}

/** 删除指定索引的子步骤 */
function removeStep(index: number) {
  const updated = [...props.modelValue]
  updated.splice(index, 1)
  emit('update:modelValue', updated)
}

/** 更新子步骤的某个字段 */
function updateStepField(index: number, field: keyof StepModel, value: unknown) {
  const updated = [...props.modelValue]
  const step = { ...updated[index] }

  if (field === 'type') {
    // 类型变更时重置配置
    const newType = value as StepType
    step.type = newType
    step.config = createDefaultConfig(newType)
  } else {
    ;(step as Record<string, unknown>)[field] = value
  }

  updated[index] = step
  emit('update:modelValue', updated)
}
</script>

<template>
  <div class="space-y-2">
    <!-- 标签 + 添加按钮 -->
    <div class="flex items-center justify-between">
      <span class="text-xs font-medium text-muted-foreground">{{ label }}</span>
      <Button type="button" variant="ghost" size="sm" class="h-6 px-2 text-xs" @click="addStep">
        <Plus class="mr-1 h-3 w-3" />
        添加步骤
      </Button>
    </div>

    <!-- 空列表提示 -->
    <div
      v-if="modelValue.length === 0"
      class="rounded-md border border-dashed p-2 text-center text-xs text-muted-foreground"
    >
      暂无步骤，点击"添加步骤"开始
    </div>

    <!-- 子步骤列表 -->
    <div
      v-for="(step, index) in modelValue"
      :key="index"
      class="group relative rounded-md border bg-muted/30 p-2 space-y-1.5"
    >
      <!-- 删除按钮 -->
      <button
        type="button"
        class="absolute right-1 top-1 rounded p-0.5 text-muted-foreground opacity-0 transition-opacity hover:bg-destructive/10 hover:text-destructive group-hover:opacity-100"
        title="删除步骤"
        @click="removeStep(index)"
      >
        <X class="h-3 w-3" />
      </button>

      <!-- 步骤类型选择 + 类型标签 -->
      <div class="flex items-center gap-2 pr-5">
        <Select
          :model-value="step.type"
          @update:model-value="updateStepField(index, 'type', $event)"
        >
          <SelectTrigger class="h-7 w-full text-xs">
            <SelectValue placeholder="类型" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem
              v-for="t in ALL_STEP_TYPES"
              :key="t"
              :value="t"
            >
              <div class="flex items-center gap-1.5">
                <component :is="STEP_TYPE_META[t].icon" class="h-3 w-3" />
                <span>{{ STEP_TYPE_META[t].label }}</span>
              </div>
            </SelectItem>
          </SelectContent>
        </Select>
        <Badge variant="secondary" class="shrink-0 text-[10px]">
          {{ STEP_TYPE_META[step.type].label }}
        </Badge>
      </div>

      <!-- ID 和名称内联编辑 -->
      <div class="grid grid-cols-2 gap-1.5">
        <Input
          :model-value="step.id"
          placeholder="步骤 ID"
          class="h-7 text-xs"
          @update:model-value="updateStepField(index, 'id', $event)"
        />
        <Input
          :model-value="step.name"
          placeholder="步骤名称"
          class="h-7 text-xs"
          @update:model-value="updateStepField(index, 'name', $event)"
        />
      </div>
    </div>
  </div>
</template>
