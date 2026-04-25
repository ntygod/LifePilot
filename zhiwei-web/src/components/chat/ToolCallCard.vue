<script setup lang="ts">
import { computed } from 'vue'
import type { ToolCallSummary } from '@/types'
import { CheckCircle2, XCircle } from 'lucide-vue-next'
import { Card } from '@/components/ui/card'
import {
  Accordion,
  AccordionItem,
  AccordionTrigger,
  AccordionContent,
} from '@/components/ui/accordion'
import BrowserToolCallCard from './BrowserToolCallCard.vue'

const props = defineProps<{
  tool: ToolCallSummary
}>()

/** 浏览器工具走特化卡片，展示 URL / 截图 / 可交互元素列表 */
const isBrowserTool = computed(() =>
  props.tool.toolId === 'browser' || props.tool.toolId.startsWith('browser.')
)

const hasDetails = computed(() => !!(props.tool.inputSummary || props.tool.outputSummary))
const stateLabel = computed(() => (props.tool.success === false ? '失败' : '已完成'))
const latencyLabel = computed(() => (
  props.tool.latencyMs > 0 ? `${props.tool.latencyMs}ms` : '即时'
))
</script>

<template>
  <BrowserToolCallCard v-if="isBrowserTool" :tool="tool" />
  <Card
    v-else
    :class="[
      'tool-call-card gap-0 py-0 text-xs shadow-none',
      tool.success === false
        ? 'tool-call-card-failure'
        : 'tool-call-card-success',
    ]"
  >
    <Accordion
      v-if="hasDetails"
      type="single"
      collapsible
      class="w-full"
    >
      <AccordionItem value="details" class="border-b-0">
        <AccordionTrigger class="tool-call-trigger px-3 py-2.5 text-left hover:no-underline">
          <div class="flex min-w-0 items-center gap-3">
            <span
              class="tool-call-icon inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-2xl"
              :class="tool.success === false ? 'tool-call-icon-failure' : 'tool-call-icon-success'"
            >
              <component
                :is="tool.success === false ? XCircle : CheckCircle2"
                :size="14"
                class="shrink-0"
              />
            </span>
            <div class="min-w-0 space-y-1">
              <div class="flex min-w-0 items-center gap-2.5">
                <span class="truncate font-medium text-foreground/92">{{ tool.toolId }}</span>
                <span v-if="tool.action" class="tool-call-chip truncate">{{ tool.action }}</span>
                <span
                  class="tool-call-chip shrink-0"
                  :class="tool.success === false ? 'tool-call-chip-failure' : 'tool-call-chip-success'"
                >
                  {{ stateLabel }}
                </span>
              </div>
              <p class="truncate text-[11px] text-muted-foreground/82">
                {{ tool.outputSummary || tool.inputSummary || '查看这次调用的输入和输出。' }}
              </p>
            </div>
          </div>
          <template #icon>
            <span class="tool-call-latency ml-auto shrink-0 text-[10px]">{{ latencyLabel }}</span>
          </template>
        </AccordionTrigger>
        <AccordionContent class="px-3 pb-3">
          <div class="space-y-2 border-t border-border/50 pt-2.5">
            <div v-if="tool.inputSummary" class="tool-call-detail-block">
              <div class="tool-call-detail-label">输入</div>
              <p class="text-[11px] leading-5 text-foreground/84">{{ tool.inputSummary }}</p>
            </div>
            <div v-if="tool.outputSummary" class="tool-call-detail-block">
              <div class="tool-call-detail-label">输出</div>
              <p class="text-[11px] leading-5 text-foreground/84">{{ tool.outputSummary }}</p>
            </div>
          </div>
        </AccordionContent>
      </AccordionItem>
    </Accordion>

    <!-- 无详情时仅展示头部信息 -->
    <div
      v-else
      class="tool-call-trigger flex items-center gap-3 px-3 py-2.5"
    >
      <span
        class="tool-call-icon inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-2xl"
        :class="tool.success === false ? 'tool-call-icon-failure' : 'tool-call-icon-success'"
      >
        <component
          :is="tool.success === false ? XCircle : CheckCircle2"
          :size="14"
          class="shrink-0"
        />
      </span>
      <div class="min-w-0 space-y-1">
        <div class="flex min-w-0 items-center gap-2.5">
          <span class="truncate font-medium text-foreground/92">{{ tool.toolId }}</span>
          <span v-if="tool.action" class="tool-call-chip truncate">{{ tool.action }}</span>
          <span
            class="tool-call-chip shrink-0"
            :class="tool.success === false ? 'tool-call-chip-failure' : 'tool-call-chip-success'"
          >
            {{ stateLabel }}
          </span>
        </div>
        <p class="text-[11px] text-muted-foreground/78">这次调用没有更多摘要。</p>
      </div>
      <span class="tool-call-latency ml-auto shrink-0 text-[10px]">{{ latencyLabel }}</span>
    </div>
  </Card>
</template>

<style scoped>
.tool-call-card {
  position: relative;
  overflow: hidden;
  border-radius: 1.1rem;
  border: 1px solid hsl(from var(--border) h s l / 0.48);
  background: hsl(from var(--card) h s l / 0.92);
  box-shadow:
    0 12px 22px -28px hsl(var(--shadow-color) / 0.12),
    inset 0 1px 0 hsl(from var(--card) h s l / 0.4);
  transition:
    transform 180ms var(--ease-fluid),
    border-color 180ms var(--ease-fluid),
    box-shadow 180ms var(--ease-fluid);
}

.tool-call-card:hover {
  transform: translateY(-1px);
  box-shadow:
    0 16px 24px -28px hsl(var(--shadow-color) / 0.14),
    inset 0 1px 0 hsl(from var(--card) h s l / 0.44);
}

.tool-call-card-success {
  border-color: hsl(from var(--border) h s l / 0.48);
}

.tool-call-card-failure {
  border-color: hsl(from var(--destructive) h s l / 0.22);
  background: hsl(from var(--destructive) h s l / 0.05);
}

.tool-call-trigger {
  transition: background-color 180ms var(--ease-fluid);
}

.tool-call-trigger:hover {
  background: hsl(from var(--accent) h s l / 0.3);
}

.tool-call-icon {
  border: 1px solid hsl(from var(--border) h s l / 0.44);
  background: hsl(from var(--background) h s l / 0.8);
  box-shadow: inset 0 1px 0 hsl(from var(--card) h s l / 0.28);
}

.tool-call-icon-success {
  color: hsl(160 66% 38%);
}

.tool-call-icon-failure {
  color: hsl(from var(--destructive) h s l / 0.92);
}

.tool-call-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  border: 1px solid hsl(from var(--border) h s l / 0.44);
  background: hsl(from var(--background) h s l / 0.72);
  padding: 0.18rem 0.5rem;
  font-size: 10px;
  line-height: 1.1;
  color: hsl(from var(--muted-foreground) h s l / 0.92);
}

.tool-call-chip-success {
  border-color: hsl(160 56% 78% / 0.9);
  background: hsl(160 56% 92% / 0.86);
  color: hsl(160 58% 30%);
}

.tool-call-chip-failure {
  border-color: hsl(from var(--destructive) h s l / 0.2);
  background: hsl(from var(--destructive) h s l / 0.08);
  color: hsl(from var(--destructive) h s l / 0.86);
}

.tool-call-latency {
  border-radius: 999px;
  border: 1px solid hsl(from var(--border) h s l / 0.42);
  background: hsl(from var(--background) h s l / 0.74);
  padding: 0.22rem 0.52rem;
  color: hsl(from var(--muted-foreground) h s l / 0.88);
}

.tool-call-detail-block {
  border-radius: 0.9rem;
  border: 1px solid hsl(from var(--border) h s l / 0.42);
  background: hsl(from var(--background) h s l / 0.62);
  padding: 0.75rem 0.8rem;
}

.tool-call-detail-label {
  margin-bottom: 0.32rem;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: hsl(from var(--muted-foreground) h s l / 0.84);
}
</style>
