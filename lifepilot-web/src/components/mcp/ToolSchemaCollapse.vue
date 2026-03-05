<script setup lang="ts">
import {
  Accordion, AccordionContent, AccordionItem, AccordionTrigger,
} from '@/components/ui/accordion'

// 工具参数 Schema 折叠组件
// 始终展示工具名称和描述，若 inputSchema 存在则展示可折叠 JSON Schema 区域

interface Props {
  name: string
  description: string
  inputSchema?: Record<string, any>
}

const props = defineProps<Props>()

function formatSchema(schema: Record<string, any>): string {
  return JSON.stringify(schema, null, 2)
}
</script>

<template>
  <!-- 有 schema 时使用 Accordion 折叠面板 -->
  <Accordion v-if="inputSchema" type="single" collapsible class="rounded-md border border-border bg-card/50">
    <AccordionItem :value="name" class="border-0">
      <AccordionTrigger class="px-sm py-xs hover:bg-accent/50 hover:no-underline">
        <div class="flex-1 min-w-0 text-left">
          <span class="font-mono text-xs text-foreground">{{ name }}</span>
          <p class="text-xs text-muted-foreground mt-0.5 leading-normal">{{ description }}</p>
        </div>
      </AccordionTrigger>
      <AccordionContent class="px-sm pb-xs">
        <pre class="text-xs text-muted-foreground font-mono whitespace-pre-wrap break-all leading-relaxed">{{ formatSchema(inputSchema) }}</pre>
      </AccordionContent>
    </AccordionItem>
  </Accordion>

  <!-- 无 schema 时仅展示名称和描述 -->
  <div v-else class="rounded-md border border-border bg-card/50 px-sm py-xs">
    <div class="flex-1 min-w-0">
      <span class="font-mono text-xs text-foreground">{{ name }}</span>
      <p class="text-xs text-muted-foreground mt-0.5 leading-normal">{{ description }}</p>
    </div>
  </div>
</template>
