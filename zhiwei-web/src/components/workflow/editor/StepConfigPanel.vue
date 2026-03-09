<!--
  步骤配置面板组件。
  右侧边栏，展示当前选中步骤的配置表单。
  通用字段（id/name/errorStrategy）+ 按步骤类型动态渲染子组件。
-->
<script setup lang="ts">
import type { Component } from 'vue'
import type { StepModel, StepType } from '@/composables/useWorkflowModel'
import { STEP_TYPE_META } from '@/components/workflow/editor/stepTypeMeta'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Separator } from '@/components/ui/separator'
import { MousePointerClick } from 'lucide-vue-next'
import ErrorStrategyConfig from '@/components/workflow/editor/ErrorStrategyConfig.vue'

const props = defineProps<{
  step: StepModel | null
}>()

const emit = defineEmits<{
  'update:step': [updates: Partial<StepModel>]
}>()

/** 更新步骤通用字段 */
function updateField(field: keyof StepModel, value: unknown) {
  emit('update:step', { [field]: value })
}

/**
 * 按步骤类型获取占位文本。
 * 后续任务 4.3/4.4 会替换为实际子组件。
 */
const TYPE_CONFIG_PLACEHOLDER: Record<StepType, string> = {
  'skill': 'Skill 配置区域（skillId、params）',
  'tool': '工具配置区域（toolId、params）',
  'llm': 'LLM 配置区域（scene、prompt、outputSchema）',
  'condition': '条件分支配置区域（condition、thenSteps、elseSteps）',
  'loop': '循环配置区域（items、loopVar、body）',
  'parallel': '并行配置区域（branches）',
  'sub-workflow': '子工作流配置区域（workflowId、params）',
  'noop': '空操作，无额外配置',
  'wait': '等待配置区域（durationSeconds）',
  'approval': '审批配置区域（message、approvers、timeout）',
}
</script>

<template>
  <div class="flex h-full w-[320px] shrink-0 flex-col border-l bg-background">
    <!-- 未选中步骤时的占位提示 -->
    <div
      v-if="!step"
      class="flex h-full flex-col items-center justify-center gap-3 px-4 text-muted-foreground"
    >
      <MousePointerClick class="h-8 w-8 opacity-40" />
      <p class="text-center text-sm">点击画布中的步骤进行配置</p>
    </div>

    <!-- 选中步骤时的配置表单 -->
    <template v-else>
      <!-- 面板标题：步骤类型图标 + 名称 -->
      <div class="flex items-center gap-2 px-4 py-3">
        <component
          :is="STEP_TYPE_META[step.type].icon"
          class="h-4 w-4 shrink-0 text-primary"
        />
        <span class="text-sm font-medium">{{ STEP_TYPE_META[step.type].label }}</span>
      </div>

      <Separator />

      <ScrollArea class="flex-1">
        <div class="space-y-4 p-4">
          <!-- 通用字段：步骤 ID -->
          <div class="space-y-1.5">
            <Label for="step-id" class="text-xs">步骤 ID</Label>
            <Input
              id="step-id"
              :model-value="step.id"
              placeholder="唯一标识符"
              class="h-8 text-sm"
              @update:model-value="updateField('id', $event)"
            />
          </div>

          <!-- 通用字段：步骤名称 -->
          <div class="space-y-1.5">
            <Label for="step-name" class="text-xs">步骤名称</Label>
            <Input
              id="step-name"
              :model-value="step.name"
              placeholder="步骤显示名称"
              class="h-8 text-sm"
              @update:model-value="updateField('name', $event)"
            />
          </div>

          <Separator />

          <!-- 类型专属配置区域（占位，后续任务 4.3/4.4 替换为实际子组件） -->
          <div>
            <p class="mb-2 text-xs font-medium text-muted-foreground">类型配置</p>
            <div class="rounded-md border border-dashed p-3 text-xs text-muted-foreground">
              {{ TYPE_CONFIG_PLACEHOLDER[step.type] }}
            </div>
          </div>

          <Separator />

          <!-- 错误策略配置 -->
          <div>
            <p class="mb-2 text-xs font-medium text-muted-foreground">错误策略</p>
            <ErrorStrategyConfig
              :model-value="step.errorStrategy"
              @update:model-value="updateField('errorStrategy', $event)"
            />
          </div>
        </div>
      </ScrollArea>
    </template>
  </div>
</template>
