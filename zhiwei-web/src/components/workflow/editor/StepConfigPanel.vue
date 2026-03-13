<!--
  步骤配置面板组件。
  右侧边栏，展示当前选中步骤的配置表单。
  通用字段（id/name/errorStrategy）+ 按步骤类型动态渲染子组件。
-->
<script setup lang="ts">
import type {
  StepModel,
  SkillStepConfig as SkillStepConfigType,
  ToolStepConfig as ToolStepConfigType,
  LlmStepConfig as LlmStepConfigType,
  ConditionStepConfig as ConditionStepConfigType,
  LoopStepConfig as LoopStepConfigType,
  ParallelStepConfig as ParallelStepConfigType,
  SubWorkflowStepConfig as SubWorkflowStepConfigType,
  WaitStepConfig as WaitStepConfigType,
  ApprovalStepConfig as ApprovalStepConfigType,
  NoopStepConfig as NoopStepConfigType,
  NotifyStepConfig as NotifyStepConfigType,
} from '@/composables/useWorkflowModel'
import { STEP_TYPE_META } from '@/components/workflow/editor/stepTypeMeta'
import { ScrollArea } from '@/components/ui/scroll-area'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Separator } from '@/components/ui/separator'
import { MousePointerClick } from 'lucide-vue-next'
import ErrorStrategyConfig from '@/components/workflow/editor/ErrorStrategyConfig.vue'
import SkillStepConfig from '@/components/workflow/editor/SkillStepConfig.vue'
import ToolStepConfig from '@/components/workflow/editor/ToolStepConfig.vue'
import LlmStepConfig from '@/components/workflow/editor/LlmStepConfig.vue'
import SubWorkflowStepConfig from '@/components/workflow/editor/SubWorkflowStepConfig.vue'
import WaitStepConfig from '@/components/workflow/editor/WaitStepConfig.vue'
import ApprovalStepConfig from '@/components/workflow/editor/ApprovalStepConfig.vue'
import NoopStepConfig from '@/components/workflow/editor/NoopStepConfig.vue'
import NotifyStepConfig from '@/components/workflow/editor/NotifyStepConfig.vue'
import ConditionStepConfig from '@/components/workflow/editor/ConditionStepConfig.vue'
import LoopStepConfig from '@/components/workflow/editor/LoopStepConfig.vue'
import ParallelStepConfig from '@/components/workflow/editor/ParallelStepConfig.vue'

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

          <!-- 类型专属配置区域 -->
          <div>
            <p class="mb-2 text-xs font-medium text-muted-foreground">类型配置</p>

            <!-- 基础步骤类型配置组件 -->
            <SkillStepConfig
              v-if="step.type === 'skill'"
              :model-value="(step.config as SkillStepConfigType)"
              @update:model-value="updateField('config', $event)"
            />
            <ToolStepConfig
              v-else-if="step.type === 'tool'"
              :model-value="(step.config as ToolStepConfigType)"
              @update:model-value="updateField('config', $event)"
            />
            <LlmStepConfig
              v-else-if="step.type === 'llm'"
              :model-value="(step.config as LlmStepConfigType)"
              @update:model-value="updateField('config', $event)"
            />
            <SubWorkflowStepConfig
              v-else-if="step.type === 'sub-workflow'"
              :model-value="(step.config as SubWorkflowStepConfigType)"
              @update:model-value="updateField('config', $event)"
            />
            <WaitStepConfig
              v-else-if="step.type === 'wait'"
              :model-value="(step.config as WaitStepConfigType)"
              @update:model-value="updateField('config', $event)"
            />
            <ApprovalStepConfig
              v-else-if="step.type === 'approval'"
              :model-value="(step.config as ApprovalStepConfigType)"
              @update:model-value="updateField('config', $event)"
            />
            <NotifyStepConfig
              v-else-if="step.type === 'notify'"
              :model-value="(step.config as NotifyStepConfigType)"
              @update:model-value="updateField('config', $event)"
            />
            <NoopStepConfig
              v-else-if="step.type === 'noop'"
              :model-value="(step.config as NoopStepConfigType)"
              @update:model-value="updateField('config', $event)"
            />
            <ConditionStepConfig
              v-else-if="step.type === 'condition'"
              :model-value="(step.config as ConditionStepConfigType)"
              @update:model-value="updateField('config', $event)"
            />
            <LoopStepConfig
              v-else-if="step.type === 'loop'"
              :model-value="(step.config as LoopStepConfigType)"
              @update:model-value="updateField('config', $event)"
            />
            <ParallelStepConfig
              v-else-if="step.type === 'parallel'"
              :model-value="(step.config as ParallelStepConfigType)"
              @update:model-value="updateField('config', $event)"
            />
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
