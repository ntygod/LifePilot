<script setup lang="ts">
import { ref } from 'vue'
import { ChevronRight } from 'lucide-vue-next'

// 工具参数 Schema 折叠组件
// 始终展示工具名称和描述，若 inputSchema 存在则展示可折叠 JSON Schema 区域

interface Props {
  name: string
  description: string
  inputSchema?: Record<string, any>
}

const props = defineProps<Props>()
const expanded = ref(false)

function toggle() {
  if (props.inputSchema) {
    expanded.value = !expanded.value
  }
}

function formatSchema(schema: Record<string, any>): string {
  return JSON.stringify(schema, null, 2)
}
</script>

<template>
  <div class="rounded-md border border-border bg-card/50">
    <div
      class="flex items-start gap-sm px-sm py-xs"
      :class="inputSchema ? 'cursor-pointer hover:bg-accent/50 transition-colors' : ''"
      @click="toggle"
    >
      <!-- 折叠箭头（仅有 schema 时展示） -->
      <ChevronRight
        v-if="inputSchema"
        :size="14"
        class="mt-0.5 shrink-0 text-muted-foreground transition-transform duration-200"
        :class="expanded ? 'rotate-90' : ''"
      />
      <div class="flex-1 min-w-0">
        <span class="font-mono text-xs text-foreground">{{ name }}</span>
        <p class="text-xs text-muted-foreground mt-0.5 leading-normal">{{ description }}</p>
      </div>
    </div>

    <!-- 可折叠 JSON Schema 区域 -->
    <div
      v-if="inputSchema && expanded"
      class="border-t border-border px-sm py-xs"
    >
      <pre class="text-xs text-muted-foreground font-mono whitespace-pre-wrap break-all leading-relaxed">{{ formatSchema(inputSchema) }}</pre>
    </div>
  </div>
</template>
