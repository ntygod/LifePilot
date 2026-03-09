<!--
  步骤面板组件。
  展示 10 种步骤类型的可拖拽卡片，支持 HTML5 Drag API 拖拽到画布。
-->
<script setup lang="ts">
import { STEP_TYPE_META } from '@/components/workflow/editor/stepTypeMeta'
import type { StepType } from '@/composables/useWorkflowModel'
import { ScrollArea } from '@/components/ui/scroll-area'

/** 所有步骤类型，保持与 STEP_TYPE_META 一致的顺序 */
const stepTypes = Object.keys(STEP_TYPE_META) as StepType[]

/** 拖拽开始时设置 dataTransfer 数据 */
function onDragStart(e: DragEvent, type: StepType) {
  if (!e.dataTransfer) return
  e.dataTransfer.setData('text/plain', type)
  e.dataTransfer.effectAllowed = 'copy'
}
</script>

<template>
  <div class="flex h-full w-[160px] flex-col border-r bg-background">
    <!-- 面板标题 -->
    <div class="border-b px-3 py-2">
      <h3 class="text-sm font-medium text-foreground">步骤类型</h3>
    </div>

    <!-- 可滚动的步骤卡片列表 -->
    <ScrollArea class="flex-1">
      <div class="flex flex-col gap-1.5 p-2">
        <div
          v-for="type in stepTypes"
          :key="type"
          draggable="true"
          class="flex cursor-grab items-start gap-2 rounded-md border border-border bg-card p-2 transition-colors hover:bg-accent active:cursor-grabbing"
          @dragstart="onDragStart($event, type)"
        >
          <!-- 图标 -->
          <component
            :is="STEP_TYPE_META[type].icon"
            class="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground"
          />
          <!-- 文字区域 -->
          <div class="min-w-0">
            <div class="text-xs font-medium leading-tight text-foreground">
              {{ STEP_TYPE_META[type].label }}
            </div>
            <div class="mt-0.5 text-[10px] leading-tight text-muted-foreground">
              {{ STEP_TYPE_META[type].description }}
            </div>
          </div>
        </div>
      </div>
    </ScrollArea>
  </div>
</template>
