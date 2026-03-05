<script setup lang="ts">
import { ref } from 'vue'
import type { ToolCallSummary } from '@/types'
import { ChevronDown, ChevronRight, CheckCircle2, XCircle } from 'lucide-vue-next'

defineProps<{
  tool: ToolCallSummary
}>()

const showDetails = ref(false)
</script>

<template>
  <div
    class="rounded-lg border text-xs"
    :class="tool.success === false ? 'border-destructive/40 bg-destructive/5' : 'border-border bg-muted/30'"
  >
    <!-- 头部：工具名称 + 状态 + 耗时 -->
    <button
      type="button"
      class="w-full flex items-center gap-2 px-3 py-2 text-left hover:bg-muted/50 transition-colors"
      @click="showDetails = !showDetails"
    >
      <component
        :is="tool.success === false ? XCircle : CheckCircle2"
        :size="14"
        :class="tool.success === false ? 'text-destructive' : 'text-emerald-500'"
      />
      <span class="font-medium text-foreground/90 truncate">{{ tool.toolId }}</span>
      <span v-if="tool.action" class="text-muted-foreground truncate">· {{ tool.action }}</span>
      <span class="ml-auto shrink-0 text-muted-foreground">{{ tool.latencyMs }}ms</span>
      <component
        :is="showDetails ? ChevronDown : ChevronRight"
        :size="12"
        class="shrink-0 text-muted-foreground"
      />
    </button>

    <!-- 可折叠详情：输入/输出摘要 -->
    <div v-if="showDetails && (tool.inputSummary || tool.outputSummary)" class="px-3 pb-2 space-y-1.5 border-t border-border/50">
      <div v-if="tool.inputSummary" class="pt-1.5">
        <span class="text-muted-foreground">输入：</span>
        <span class="text-foreground/80">{{ tool.inputSummary }}</span>
      </div>
      <div v-if="tool.outputSummary">
        <span class="text-muted-foreground">输出：</span>
        <span class="text-foreground/80">{{ tool.outputSummary }}</span>
      </div>
    </div>
  </div>
</template>
