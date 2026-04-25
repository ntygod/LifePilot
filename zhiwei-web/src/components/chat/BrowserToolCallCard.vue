<script setup lang="ts">
import { computed } from 'vue'
import type { ToolCallSummary } from '@/types'
import { Card } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import {
  AlertCircle,
  Camera,
  Globe,
  MousePointer2,
  ScanLine,
  Type,
} from 'lucide-vue-next'

const props = defineProps<{ tool: ToolCallSummary }>()

/** 工具输出可能是 JSON 字符串或对象，统一解析为对象便于取字段 */
const parsedOutput = computed<Record<string, unknown>>(() => {
  const raw = props.tool.output as unknown
  if (!raw) return {}
  if (typeof raw === 'string') {
    const trimmed = raw.trim()
    if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return {}
    try {
      const parsed = JSON.parse(trimmed)
      return typeof parsed === 'object' && parsed !== null
        ? (parsed as Record<string, unknown>)
        : {}
    } catch {
      return {}
    }
  }
  if (typeof raw === 'object') return raw as Record<string, unknown>
  return {}
})

/** 根据 action 选择图标 */
const actionIcon = computed(() => {
  switch (props.tool.action) {
    case 'navigate':
      return Globe
    case 'screenshot':
      return Camera
    case 'snapshot':
      return ScanLine
    case 'click':
    case 'hover':
      return MousePointer2
    case 'input':
      return Type
    default:
      return Globe
  }
})

const url = computed<string>(() => (parsedOutput.value.url as string) ?? '')
const title = computed<string>(() => (parsedOutput.value.title as string) ?? '')

/** 兼容两种截图字段：裸 base64(`screenshot`) 或完整 data-uri(`screenshotDataUri`) */
const screenshotSrc = computed<string>(() => {
  const dataUri = parsedOutput.value.screenshotDataUri as string | undefined
  if (dataUri && typeof dataUri === 'string' && dataUri.length > 0) {
    return dataUri
  }
  const bare = parsedOutput.value.screenshot as string | undefined
  if (bare && typeof bare === 'string' && bare.length > 0) {
    return bare.startsWith('data:') ? bare : `data:image/png;base64,${bare}`
  }
  return ''
})

interface IndexedElement {
  index: number
  tag: string
  text?: string
  ariaLabel?: string
  name?: string
}

const elements = computed<IndexedElement[]>(() => {
  const raw = parsedOutput.value.elements
  return Array.isArray(raw) ? (raw as IndexedElement[]) : []
})

const stateLabel = computed(() => (props.tool.success === false ? '失败' : '已完成'))
const latencyLabel = computed(() => (
  props.tool.latencyMs > 0 ? `${props.tool.latencyMs}ms` : '即时'
))

/** 头部展示的操作名，action 优先，其次 toolId */
const actionLabel = computed(() => props.tool.action || props.tool.toolId)

const showError = computed(() => props.tool.success === false)
</script>

<template>
  <Card
    :class="[
      'browser-tool-card gap-sm p-sm text-xs shadow-none',
      showError ? 'browser-tool-card-failure' : 'browser-tool-card-success',
    ]"
  >
    <!-- 顶部：操作图标 + 名称 + URL + 状态徽章 -->
    <div class="flex min-w-0 items-center gap-sm">
      <component
        :is="actionIcon"
        :size="14"
        class="shrink-0 text-muted-foreground"
      />
      <span class="shrink-0 font-medium text-foreground/92">{{ actionLabel }}</span>
      <span
        v-if="url"
        class="truncate text-muted-foreground"
        :title="url"
      >{{ url }}</span>
      <span
        class="ml-auto shrink-0 rounded-full border border-border/50 px-sm py-xs text-[10px]"
        :class="showError ? 'bg-destructive/10 text-destructive' : 'bg-muted text-muted-foreground'"
      >
        {{ stateLabel }} · {{ latencyLabel }}
      </span>
    </div>

    <!-- 截图缩略图：点击放大 -->
    <Dialog v-if="screenshotSrc">
      <DialogTrigger as-child>
        <img
          :src="screenshotSrc"
          :alt="title || url || '浏览器截图'"
          class="w-full cursor-zoom-in rounded-lg border border-border/50"
          loading="lazy"
        />
      </DialogTrigger>
      <DialogContent class="max-w-2xl">
        <DialogTitle>{{ title || url || '浏览器截图' }}</DialogTitle>
        <img
          :src="screenshotSrc"
          :alt="title || url || '浏览器截图'"
          class="w-full rounded-md"
        />
      </DialogContent>
    </Dialog>

    <!-- snapshot 特有：可交互元素折叠列表 -->
    <details v-if="elements.length > 0" class="text-xs">
      <summary class="cursor-pointer text-muted-foreground">
        已识别 {{ elements.length }} 个可交互元素
      </summary>
      <ul class="mt-sm space-y-xs">
        <li
          v-for="el in elements.slice(0, 20)"
          :key="el.index"
          class="flex min-w-0 items-center gap-sm"
        >
          <span class="shrink-0 rounded bg-muted px-xs font-mono">#{{ el.index }}</span>
          <span class="shrink-0 text-muted-foreground">{{ el.tag }}</span>
          <span class="truncate">{{ el.text || el.ariaLabel || el.name || '' }}</span>
        </li>
        <li v-if="elements.length > 20" class="pl-sm text-muted-foreground">
          … 仅展示前 20 个
        </li>
      </ul>
    </details>

    <!-- 失败时显示错误摘要 -->
    <div v-if="showError" class="flex items-start gap-sm text-destructive">
      <AlertCircle :size="14" class="mt-xs shrink-0" />
      <span class="text-xs">{{ tool.outputSummary || '执行失败' }}</span>
    </div>
  </Card>
</template>

<style scoped>
.browser-tool-card {
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

.browser-tool-card:hover {
  transform: translateY(-1px);
  box-shadow:
    0 16px 24px -28px hsl(var(--shadow-color) / 0.14),
    inset 0 1px 0 hsl(from var(--card) h s l / 0.44);
}

.browser-tool-card-failure {
  border-color: hsl(from var(--destructive) h s l / 0.22);
  background: hsl(from var(--destructive) h s l / 0.05);
}
</style>
