<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Database, SlidersHorizontal, Sparkles, X } from 'lucide-vue-next'
import type { SessionConfig, KnowledgeBase } from '@/types'
import type { LlmProvider } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Slider } from '@/components/ui/slider'
import { Input } from '@/components/ui/input'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'

const props = defineProps<{
  preferredProviderId?: string
  temperature?: number
  maxTokens?: number
  knowledgeBaseIds?: string[]
  providers: LlmProvider[]
  knowledgeBases: KnowledgeBase[]
}>()

const emit = defineEmits<{
  (e: 'update', config: SessionConfig): void
  (e: 'close'): void
}>()

const localPreferredProviderId = ref(props.preferredProviderId ?? '')
const localTemperature = ref(props.temperature ?? 0.7)
const localMaxTokens = ref(props.maxTokens ?? 2000)
const localKbIds = ref<string[]>(props.knowledgeBaseIds ?? [])

const selectedProviderLabel = computed(() => {
  if (!localPreferredProviderId.value) return '跟随场景默认'
  const provider = props.providers.find(item => item.id === localPreferredProviderId.value)
  return provider?.displayName || provider?.modelName || provider?.id || '已选择 Provider'
})

const selectedKnowledgeBases = computed(() =>
  props.knowledgeBases.filter(item => localKbIds.value.includes(item.id)),
)

const temperatureSummary = computed(() => {
  if (localTemperature.value <= 0.4) return '偏稳'
  if (localTemperature.value >= 1.2) return '偏发散'
  return '平衡'
})

const maxTokensSummary = computed(() => {
  if (localMaxTokens.value >= 6000) return '长回答'
  if (localMaxTokens.value <= 1200) return '短回答'
  return '常规长度'
})

watch(() => props.preferredProviderId, value => {
  localPreferredProviderId.value = value ?? ''
})
watch(() => props.temperature, value => {
  localTemperature.value = value ?? 0.7
})
watch(() => props.maxTokens, value => {
  localMaxTokens.value = value ?? 2000
})
watch(() => props.knowledgeBaseIds, value => {
  localKbIds.value = value ?? []
})

function emitUpdate() {
  const temperature = Math.min(2, Math.max(0, localTemperature.value))
  const maxTokens = Math.min(8000, Math.max(100, localMaxTokens.value))

  emit('update', {
    preferredProviderId: localPreferredProviderId.value || undefined,
    temperature,
    maxTokens,
    knowledgeBaseIds: localKbIds.value.length > 0 ? localKbIds.value : undefined,
  })
}

function onPreferredProviderChange(value: unknown) {
  const nextValue = String(value ?? '')
  localPreferredProviderId.value = nextValue === '__default__' ? '' : nextValue
  emitUpdate()
}

function onTemperatureChange(value: number[] | undefined) {
  if (value?.length) {
    localTemperature.value = value[0]
  }
  emitUpdate()
}

function onMaxTokensChange(value: string | number) {
  localMaxTokens.value = Number(value)
  emitUpdate()
}

function normalizeCheckboxValue(value: boolean | 'indeterminate') {
  return value === true
}

function toggleKb(id: string, checked: boolean) {
  if (checked) {
    if (!localKbIds.value.includes(id)) {
      localKbIds.value.push(id)
    }
  } else {
    const index = localKbIds.value.indexOf(id)
    if (index >= 0) {
      localKbIds.value.splice(index, 1)
    }
  }

  emitUpdate()
}
</script>

<template>
  <div class="detail-card p-4 text-sm sm:p-5">
    <div class="flex flex-col gap-3 border-b border-border/60 pb-4 sm:flex-row sm:items-start sm:justify-between">
      <div class="space-y-2">
        <div class="surface-label">会话配置</div>
        <div class="text-sm font-semibold text-foreground sm:text-base">
          决定这个会话优先使用哪个 Provider、回答风格和资料范围
        </div>
        <p class="text-sm leading-6 text-muted-foreground">
          这里的调整会立即作用在当前会话里，适合在开聊前先把模型偏好、回答长度和知识范围定好。
        </p>
      </div>

      <Button
        variant="ghost"
        size="icon-sm"
        class="self-end sm:self-start"
        @click="emit('close')"
      >
        <X :size="16" />
      </Button>
    </div>

    <div class="mt-4 flex flex-wrap gap-2 text-xs">
      <span class="surface-chip">Provider {{ selectedProviderLabel }}</span>
      <span class="surface-chip">回答倾向 {{ temperatureSummary }}</span>
      <span class="surface-chip">知识库 {{ selectedKnowledgeBases.length }} 个</span>
    </div>

    <div class="mt-4 grid gap-4 xl:grid-cols-[minmax(0,1fr)_220px]">
      <div class="space-y-4">
        <section class="space-y-2">
          <Label class="text-xs text-muted-foreground">优先 Provider</Label>
          <Select
            :model-value="localPreferredProviderId || '__default__'"
            @update:model-value="onPreferredProviderChange"
          >
            <SelectTrigger class="w-full bg-background/80">
              <SelectValue placeholder="默认 Provider" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="__default__">跟随场景默认</SelectItem>
              <SelectItem v-for="provider in providers" :key="provider.id" :value="provider.id">
                {{ provider.displayName || provider.modelName || provider.id }}
              </SelectItem>
            </SelectContent>
          </Select>
          <p class="text-xs leading-5 text-muted-foreground">
            如果不指定，这个会话会先看场景默认，再回退到全局默认和自动路由。
          </p>
        </section>

        <section class="space-y-3">
          <div class="flex items-center justify-between gap-3">
            <Label class="text-xs text-muted-foreground">回答温度</Label>
            <span class="surface-chip">{{ localTemperature.toFixed(1) }}</span>
          </div>
          <Slider
            :model-value="[localTemperature]"
            :min="0"
            :max="2"
            :step="0.1"
            @update:model-value="onTemperatureChange"
          />
          <p class="text-xs leading-5 text-muted-foreground">
            现在是 {{ temperatureSummary }} 模式，越低越稳，越高越容易发散联想。
          </p>
        </section>

        <section class="space-y-2">
          <div class="flex items-center justify-between gap-3">
            <Label class="text-xs text-muted-foreground">最大回答长度</Label>
            <span class="surface-chip">{{ maxTokensSummary }}</span>
          </div>
          <Input
            type="number"
            class="bg-background/80"
            :model-value="localMaxTokens"
            :min="100"
            :max="8000"
            :step="100"
            @update:model-value="onMaxTokensChange"
          />
          <p class="text-xs leading-5 text-muted-foreground">
            控制单轮回复上限，适合避免回答过短或展开过多。
          </p>
        </section>

        <section v-if="knowledgeBases.length > 0" class="space-y-3">
          <div class="flex items-center justify-between gap-3">
            <Label class="text-xs text-muted-foreground">接入知识库</Label>
            <span class="surface-chip">{{ selectedKnowledgeBases.length }} / {{ knowledgeBases.length }}</span>
          </div>

          <div class="grid max-h-56 gap-2 overflow-y-auto pr-1 scrollbar-thin">
            <label
              v-for="kb in knowledgeBases"
              :key="kb.id"
              class="list-card flex cursor-pointer items-start gap-3 px-3 py-3"
            >
              <Checkbox
                :model-value="localKbIds.includes(kb.id)"
                class="mt-0.5"
                @update:model-value="checked => toggleKb(kb.id, normalizeCheckboxValue(checked))"
              />
              <div class="min-w-0 space-y-1">
                <div class="text-sm font-medium text-foreground">{{ kb.name }}</div>
                <p class="text-xs leading-5 text-muted-foreground">
                  选中后，这个会话里的提问可以直接检索这些资料。
                </p>
              </div>
            </label>
          </div>
        </section>
      </div>

      <aside class="space-y-3">
        <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/58 px-4 py-3">
          <div class="mb-2 flex items-center gap-2 text-sm font-medium text-foreground">
            <Sparkles class="size-4 text-primary" />
            当前回答策略
          </div>
          <div class="space-y-1 text-sm text-muted-foreground">
            <p>Provider：{{ selectedProviderLabel }}</p>
            <p>温度：{{ temperatureSummary }}</p>
            <p>长度：{{ maxTokensSummary }}</p>
          </div>
        </div>

        <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/58 px-4 py-3">
          <div class="mb-2 flex items-center gap-2 text-sm font-medium text-foreground">
            <Database class="size-4 text-primary" />
            资料接入
          </div>
          <p class="text-sm text-muted-foreground">
            {{
              selectedKnowledgeBases.length > 0
                ? `本轮会接入 ${selectedKnowledgeBases.length} 个知识库。`
                : '当前没有挂载知识库，会只基于对话上下文回答。'
            }}
          </p>
        </div>

        <div class="rounded-[calc(var(--radius)+6px)] border border-dashed border-border/60 bg-background/58 px-4 py-3">
          <div class="mb-2 flex items-center gap-2 text-sm font-medium text-foreground">
            <SlidersHorizontal class="size-4 text-primary" />
            调整建议
          </div>
          <p class="text-sm text-muted-foreground">
            草稿和发散阶段可以把温度调高；整理纪要、排障和结构化输出更适合保持在当前或更低。
          </p>
        </div>
      </aside>
    </div>
  </div>
</template>
