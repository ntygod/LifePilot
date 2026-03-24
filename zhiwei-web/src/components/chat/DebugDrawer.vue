<script setup lang="ts">
import { computed, ref } from 'vue'
import { ChevronDown, ChevronRight, Cpu, Database, Hammer, Route } from 'lucide-vue-next'
import { RouterLink } from 'vue-router'
import type { TokenUsage, ReasoningEvent, ToolCallSummary, SourceSummary } from '@/types'
import InspectorRail from '@/components/layout/InspectorRail.vue'

const props = defineProps<{
  tokenUsage: TokenUsage | null
  modelId: string | null
  prompt: string | null
  reasoningEvents: ReasoningEvent[]
  toolsSummary: ToolCallSummary[]
  kbSources: SourceSummary[]
  traceId?: string
}>()

const emit = defineEmits<{
  (e: 'close'): void
}>()

const toolSuccessCount = computed(() => props.toolsSummary.filter(item => item.success !== false).length)
const modelSummary = computed(() => props.modelId || '发送消息后显示')
const tokenSummary = computed(() => (
  props.tokenUsage
    ? `总token ${props.tokenUsage.totalTokens}`
    : '发送后会补充token统计'
))
const toolSummary = computed(() => (
  props.toolsSummary.length > 0
    ? `${toolSuccessCount.value} / ${props.toolsSummary.length} 次成功`
    : '本轮没有工具调用'
))
const knowledgeSummary = computed(() => (
  props.kbSources.length > 0
    ? `命中 ${props.kbSources.length} 条知识来源`
    : '本轮没有知识库命中'
))

const sections = ref({
  token: true,
  prompt: false,
  reasoning: false,
  tools: false,
  kb: false,
})

function toggle(key: keyof typeof sections.value) {
  sections.value[key] = !sections.value[key]
}
</script>

<template>
  <InspectorRail
    title="对话详情"
    @close="emit('close')"
  >
    <template #eyebrow>
      调试
    </template>

    <div class="space-y-4 text-sm">

      <section data-token-usage class="detail-card p-4">
        <button
          type="button"
          class="flex w-full items-center justify-between gap-3 text-left text-sm font-medium text-foreground"
          @click="toggle('token')"
        >
          <span class="flex items-center gap-2">
            <component :is="sections.token ? ChevronDown : ChevronRight" class="size-4 text-muted-foreground" />
            <Cpu class="size-4 text-primary" />
            <span>模型与token</span>
          </span>
          <span class="surface-chip">{{ tokenUsage ? '已记录' : '等待本轮消息' }}</span>
        </button>
        <div v-if="sections.token" class="mt-3 space-y-2 text-sm text-muted-foreground">
          <template v-if="tokenUsage">
            <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/55 px-3 py-3">
              <p>模型：{{ modelId || '未知模型' }}</p>
              <p>总token：{{ tokenUsage.totalTokens }}</p>
              <p>提示 {{ tokenUsage.promptTokens }} / 回答 {{ tokenUsage.completionTokens }}</p>
            </div>
          </template>
          <p v-else>暂无统计信息，发送消息后即可查看。</p>
        </div>
      </section>

      <section class="detail-card p-4">
        <button
          type="button"
          class="flex w-full items-center justify-between gap-3 text-left text-sm font-medium text-foreground"
          @click="toggle('prompt')"
        >
          <span class="flex items-center gap-2">
            <component :is="sections.prompt ? ChevronDown : ChevronRight" class="size-4 text-muted-foreground" />
            <Route class="size-4 text-primary" />
            <span>提示词摘要</span>
          </span>
          <span class="surface-chip">{{ prompt ? '已记录' : '暂无内容' }}</span>
        </button>
        <div v-if="sections.prompt" class="mt-3 text-sm leading-6 text-muted-foreground">
          <p v-if="prompt" class="whitespace-pre-wrap break-words">{{ prompt }}</p>
          <p v-else>暂未记录本轮提示词摘要。</p>
        </div>
      </section>

      <section class="detail-card p-4">
        <button
          type="button"
          class="flex w-full items-center justify-between gap-3 text-left text-sm font-medium text-foreground"
          @click="toggle('reasoning')"
        >
          <span class="flex items-center gap-2">
            <component :is="sections.reasoning ? ChevronDown : ChevronRight" class="size-4 text-muted-foreground" />
            <Route class="size-4 text-primary" />
            <span>推理过程</span>
          </span>
          <span class="surface-chip">{{ reasoningEvents.length }} 条事件</span>
        </button>
        <div v-if="sections.reasoning" class="mt-3 space-y-2">
          <div v-if="reasoningEvents.length > 0" class="space-y-2">
            <div
              v-for="event in reasoningEvents"
              :key="event.id"
              class="list-card px-3 py-3"
            >
              <div class="flex items-center gap-2 text-sm font-medium text-foreground">
                <span
                  class="inline-flex h-2 w-2 rounded-full"
                  :class="event.type === 'SUSPEND'
                    ? 'bg-destructive'
                    : event.type === 'TOOL_CALL'
                      ? 'bg-primary'
                      : 'bg-muted-foreground/60'"
                />
                <span>{{ event.title }}</span>
              </div>
              <p v-if="event.description" class="mt-1 text-xs leading-5 text-muted-foreground">
                {{ event.description }}
              </p>
              <p v-if="event.toolName" class="mt-1 text-xs text-primary">工具：{{ event.toolName }}</p>
            </div>
          </div>
          <p v-else class="text-sm text-muted-foreground">暂无推理事件。</p>
        </div>
      </section>

      <section class="detail-card p-4">
        <button
          type="button"
          class="flex w-full items-center justify-between gap-3 text-left text-sm font-medium text-foreground"
          @click="toggle('tools')"
        >
          <span class="flex items-center gap-2">
            <component :is="sections.tools ? ChevronDown : ChevronRight" class="size-4 text-muted-foreground" />
            <Hammer class="size-4 text-primary" />
            <span>工具调用</span>
          </span>
          <span class="surface-chip">{{ toolsSummary.length }} 次</span>
        </button>
        <div v-if="sections.tools" class="mt-3 space-y-2 text-sm text-muted-foreground">
          <div v-if="toolsSummary.length > 0" class="space-y-2">
            <div
              v-for="(tool, index) in toolsSummary.slice(0, 5)"
              :key="`${tool.toolId}:${index}`"
              class="list-card px-3 py-3"
            >
              <div class="flex items-center justify-between gap-3">
                <span class="truncate text-foreground">{{ tool.toolId }}</span>
                <span class="shrink-0 text-xs">
                  <span :class="tool.success === false ? 'text-destructive' : 'text-emerald-500'">
                    {{ tool.success === false ? '失败' : '成功' }}
                  </span>
                  <span class="mx-1 text-muted-foreground/60">•</span>
                  <span>{{ tool.latencyMs }}ms</span>
                </span>
              </div>
            </div>
          </div>
          <p v-else>暂无工具调用统计。</p>
        </div>
      </section>

      <section class="detail-card p-4">
        <button
          type="button"
          class="flex w-full items-center justify-between gap-3 text-left text-sm font-medium text-foreground"
          @click="toggle('kb')"
        >
          <span class="flex items-center gap-2">
            <component :is="sections.kb ? ChevronDown : ChevronRight" class="size-4 text-muted-foreground" />
            <Database class="size-4 text-primary" />
            <span>知识来源</span>
          </span>
          <span class="surface-chip">{{ kbSources.length }} 条</span>
        </button>
        <div v-if="sections.kb" class="mt-3 space-y-2 text-sm text-muted-foreground">
          <div v-if="kbSources.length > 0" class="space-y-2">
            <div
              v-for="kb in kbSources.slice(0, 4)"
              :key="kb.id"
              class="list-card px-3 py-3"
            >
              <div class="truncate text-foreground">{{ kb.name }}</div>
              <div class="mt-1 text-xs text-muted-foreground">ID: {{ kb.id }}</div>
            </div>
          </div>
          <p v-else>暂无知识库命中记录。</p>
        </div>
      </section>
    </div>

    <template #footer>
      <RouterLink
        :to="traceId ? { name: 'traces', query: { id: traceId } } : { name: 'traces' }"
        class="flex w-full items-center justify-center gap-2 rounded-lg border border-primary/20 bg-primary/8 px-4 py-2.5 text-sm font-medium text-primary transition-all hover:bg-primary/15 active:scale-[0.98]"
      >
        <Route class="size-4" />
        {{ traceId ? '查看轨迹详情' : '浏览轨迹列表' }}
        <ChevronRight class="size-4" />
      </RouterLink>
    </template>
  </InspectorRail>
</template>
