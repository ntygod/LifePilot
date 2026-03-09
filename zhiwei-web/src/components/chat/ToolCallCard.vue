<script setup lang="ts">
import type { ToolCallSummary } from '@/types'
import { CheckCircle2, XCircle } from 'lucide-vue-next'
import { Card } from '@/components/ui/card'
import {
  Accordion,
  AccordionItem,
  AccordionTrigger,
  AccordionContent,
} from '@/components/ui/accordion'

defineProps<{
  tool: ToolCallSummary
}>()
</script>

<template>
  <Card
    :class="[
      'gap-0 py-0 text-xs shadow-none',
      tool.success === false
        ? 'border-destructive/40 bg-destructive/5'
        : 'border-border bg-muted/30',
    ]"
  >
    <Accordion
      v-if="tool.inputSummary || tool.outputSummary"
      type="single"
      collapsible
    >
      <AccordionItem value="details" class="border-b-0">
        <AccordionTrigger class="px-3 py-2 hover:no-underline hover:bg-muted/50 transition-colors">
          <div class="flex items-center gap-2 min-w-0">
            <component
              :is="tool.success === false ? XCircle : CheckCircle2"
              :size="14"
              class="shrink-0"
              :class="tool.success === false ? 'text-destructive' : 'text-emerald-500'"
            />
            <span class="font-medium text-foreground/90 truncate">{{ tool.toolId }}</span>
            <span v-if="tool.action" class="text-muted-foreground truncate">· {{ tool.action }}</span>
          </div>
          <template #icon>
            <span class="ml-auto shrink-0 text-muted-foreground text-xs">{{ tool.latencyMs }}ms</span>
          </template>
        </AccordionTrigger>
        <AccordionContent class="px-3 pb-2">
          <div class="space-y-1.5 border-t border-border/50 pt-1.5">
            <div v-if="tool.inputSummary">
              <span class="text-muted-foreground">输入：</span>
              <span class="text-foreground/80">{{ tool.inputSummary }}</span>
            </div>
            <div v-if="tool.outputSummary">
              <span class="text-muted-foreground">输出：</span>
              <span class="text-foreground/80">{{ tool.outputSummary }}</span>
            </div>
          </div>
        </AccordionContent>
      </AccordionItem>
    </Accordion>

    <!-- 无详情时仅展示头部信息 -->
    <div
      v-else
      class="flex items-center gap-2 px-3 py-2"
    >
      <component
        :is="tool.success === false ? XCircle : CheckCircle2"
        :size="14"
        class="shrink-0"
        :class="tool.success === false ? 'text-destructive' : 'text-emerald-500'"
      />
      <span class="font-medium text-foreground/90 truncate">{{ tool.toolId }}</span>
      <span v-if="tool.action" class="text-muted-foreground truncate">· {{ tool.action }}</span>
      <span class="ml-auto shrink-0 text-muted-foreground">{{ tool.latencyMs }}ms</span>
    </div>
  </Card>
</template>
