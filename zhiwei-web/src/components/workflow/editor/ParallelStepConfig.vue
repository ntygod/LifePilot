<!--
  并行步骤配置组件。
  配置 branches 多分支嵌套步骤列表，支持添加和删除分支。
-->
<script setup lang="ts">
import type { ParallelStepConfig, StepModel } from '@/composables/useWorkflowModel'
import { Button } from '@/components/ui/button'
import { Separator } from '@/components/ui/separator'
import { Plus, Trash2 } from 'lucide-vue-next'
import NestedStepList from '@/components/workflow/editor/NestedStepList.vue'

const props = defineProps<{
  modelValue: ParallelStepConfig
}>()

const emit = defineEmits<{
  'update:modelValue': [value: ParallelStepConfig]
}>()

/** 添加一个空分支 */
function addBranch() {
  const updated = [...props.modelValue.branches, []]
  emit('update:modelValue', { branches: updated })
}

/** 删除指定索引的分支 */
function removeBranch(index: number) {
  const updated = [...props.modelValue.branches]
  updated.splice(index, 1)
  emit('update:modelValue', { branches: updated })
}

/** 更新指定分支的步骤列表 */
function updateBranch(index: number, steps: StepModel[]) {
  const updated = [...props.modelValue.branches]
  updated[index] = steps
  emit('update:modelValue', { branches: updated })
}
</script>

<template>
  <div class="space-y-3">
    <!-- 标题 + 添加分支按钮 -->
    <div class="flex items-center justify-between">
      <span class="text-xs font-medium text-muted-foreground">并行分支</span>
      <Button variant="ghost" size="sm" class="h-6 px-2 text-xs" @click="addBranch">
        <Plus class="mr-1 h-3 w-3" />
        添加分支
      </Button>
    </div>

    <!-- 空分支提示 -->
    <div
      v-if="modelValue.branches.length === 0"
      class="rounded-md border border-dashed p-3 text-center text-xs text-muted-foreground"
    >
      暂无并行分支，点击"添加分支"开始
    </div>

    <!-- 各分支列表 -->
    <div
      v-for="(branch, index) in modelValue.branches"
      :key="index"
      class="space-y-2"
    >
      <Separator v-if="index > 0" />

      <!-- 分支标题 + 删除按钮 -->
      <div class="flex items-center justify-between">
        <span class="text-xs font-medium">分支 {{ index + 1 }}</span>
        <Button
          variant="ghost"
          size="sm"
          class="h-6 px-2 text-xs text-destructive hover:text-destructive"
          @click="removeBranch(index)"
        >
          <Trash2 class="mr-1 h-3 w-3" />
          删除
        </Button>
      </div>

      <!-- 分支内的步骤列表 -->
      <NestedStepList
        :model-value="branch"
        :label="`分支 ${index + 1} 步骤`"
        @update:model-value="updateBranch(index, $event)"
      />
    </div>
  </div>
</template>
