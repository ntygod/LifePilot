<script setup lang="ts">
import { ref, watch } from 'vue'
import { X } from 'lucide-vue-next'
import type { SessionConfig, KnowledgeBase } from '@/types'
import type { ModelService } from '@/api/client'
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
  maxSteps?: number
  maxDurationSeconds?: number
  knowledgeBaseIds?: string[]
  providers: ModelService[]
  knowledgeBases: KnowledgeBase[]
}>()

const emit = defineEmits<{
  (e: 'update', config: SessionConfig): void
  (e: 'close'): void
}>()

const DEFAULT_MAX_TOKENS = 131072
const DEFAULT_MAX_STEPS = 60
const DEFAULT_MAX_DURATION_SECONDS = 300

const localPreferredProviderId = ref(props.preferredProviderId ?? '')
const localTemperature = ref(props.temperature ?? 0.7)
const localMaxTokens = ref(props.maxTokens ?? DEFAULT_MAX_TOKENS)
const localMaxSteps = ref(props.maxSteps ?? DEFAULT_MAX_STEPS)
const localMaxDurationSeconds = ref(props.maxDurationSeconds ?? DEFAULT_MAX_DURATION_SECONDS)
const localKbIds = ref<string[]>(props.knowledgeBaseIds ?? [])

watch(() => props.preferredProviderId, v => { localPreferredProviderId.value = v ?? '' })
watch(() => props.temperature, v => { localTemperature.value = v ?? 0.7 })
watch(() => props.maxTokens, v => { localMaxTokens.value = v ?? DEFAULT_MAX_TOKENS })
watch(() => props.maxSteps, v => { localMaxSteps.value = v ?? DEFAULT_MAX_STEPS })
watch(() => props.maxDurationSeconds, v => { localMaxDurationSeconds.value = v ?? DEFAULT_MAX_DURATION_SECONDS })
watch(() => props.knowledgeBaseIds, v => { localKbIds.value = v ?? [] })

function clampInteger(value: unknown, fallback: number, min: number, max: number) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) return fallback
  return Math.min(max, Math.max(min, Math.round(parsed)))
}

function emitUpdate() {
  emit('update', {
    preferredProviderId: localPreferredProviderId.value || undefined,
    temperature: Math.min(2, Math.max(0, localTemperature.value)),
    maxTokens: clampInteger(localMaxTokens.value, DEFAULT_MAX_TOKENS, 256, 1000000),
    maxSteps: clampInteger(localMaxSteps.value, DEFAULT_MAX_STEPS, 1, 500),
    maxDurationSeconds: clampInteger(localMaxDurationSeconds.value, DEFAULT_MAX_DURATION_SECONDS, 5, 86400),
    knowledgeBaseIds: localKbIds.value.length > 0 ? localKbIds.value : undefined,
  })
}

function onProviderChange(value: unknown) {
  const v = String(value ?? '')
  localPreferredProviderId.value = v === '__default__' ? '' : v
  emitUpdate()
}

function onTemperatureChange(value: number[] | undefined) {
  if (value?.length) localTemperature.value = value[0]
  emitUpdate()
}

function onMaxTokensChange(value: string | number) {
  localMaxTokens.value = clampInteger(value, DEFAULT_MAX_TOKENS, 256, 1000000)
  emitUpdate()
}

function onMaxStepsChange(value: string | number) {
  localMaxSteps.value = clampInteger(value, DEFAULT_MAX_STEPS, 1, 500)
  emitUpdate()
}

function onMaxDurationChange(value: string | number) {
  localMaxDurationSeconds.value = clampInteger(value, DEFAULT_MAX_DURATION_SECONDS, 5, 86400)
  emitUpdate()
}

function toggleKb(id: string, checked: boolean | 'indeterminate') {
  if (checked === true) {
    if (!localKbIds.value.includes(id)) localKbIds.value.push(id)
  } else {
    const i = localKbIds.value.indexOf(id)
    if (i >= 0) localKbIds.value.splice(i, 1)
  }
  emitUpdate()
}
</script>

<template>
  <div class="detail-card space-y-4 p-4 text-sm">
    <!-- 标题栏 -->
    <div class="flex items-center justify-between">
      <span class="text-sm font-medium text-foreground">会话配置</span>
      <Button variant="ghost" size="icon-sm" @click="emit('close')">
        <X :size="14" />
      </Button>
    </div>

    <!-- Provider -->
    <section class="space-y-1.5">
      <Label class="text-xs text-muted-foreground">Provider</Label>
      <Select
        :model-value="localPreferredProviderId || '__default__'"
        @update:model-value="onProviderChange"
      >
        <SelectTrigger class="w-full bg-background/80">
          <SelectValue placeholder="场景默认" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="__default__">场景默认</SelectItem>
          <SelectItem v-for="p in providers" :key="p.id" :value="p.id">
            {{ p.displayName || p.modelName || p.id }}
          </SelectItem>
        </SelectContent>
      </Select>
    </section>

    <!-- 温度 -->
    <section class="space-y-1.5">
      <div class="flex items-center justify-between">
        <Label class="text-xs text-muted-foreground">温度</Label>
        <span class="text-xs tabular-nums text-muted-foreground">{{ localTemperature.toFixed(1) }}</span>
      </div>
      <Slider
        :model-value="[localTemperature]"
        :min="0" :max="2" :step="0.1"
        @update:model-value="onTemperatureChange"
      />
      <div class="flex justify-between text-[10px] text-muted-foreground/60">
        <span>精确</span><span>平衡</span><span>发散</span>
      </div>
    </section>

    <!-- Token 预算 -->
    <section class="space-y-1.5">
      <Label class="text-xs text-muted-foreground">总 Token 预算</Label>
      <Input
        type="number"
        class="bg-background/80"
        :model-value="localMaxTokens"
        :min="256" :max="1000000" :step="256"
        @update:model-value="onMaxTokensChange"
      />
    </section>

    <section class="space-y-1.5">
      <Label class="text-xs text-muted-foreground">最大迭代步数</Label>
      <Input
        type="number"
        class="bg-background/80"
        :model-value="localMaxSteps"
        :min="1" :max="500" :step="1"
        @update:model-value="onMaxStepsChange"
      />
    </section>

    <section class="space-y-1.5">
      <Label class="text-xs text-muted-foreground">最大执行时长（秒）</Label>
      <Input
        type="number"
        class="bg-background/80"
        :model-value="localMaxDurationSeconds"
        :min="5" :max="86400" :step="5"
        @update:model-value="onMaxDurationChange"
      />
    </section>

    <!-- 知识库 -->
    <section v-if="knowledgeBases.length > 0" class="space-y-2">
      <div class="flex items-center justify-between">
        <Label class="text-xs text-muted-foreground">知识库</Label>
        <span class="text-[10px] text-muted-foreground">{{ localKbIds.length }} / {{ knowledgeBases.length }}</span>
      </div>
      <div class="max-h-40 space-y-1 overflow-y-auto pr-1 scrollbar-thin">
        <label
          v-for="kb in knowledgeBases" :key="kb.id"
          class="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 transition-colors hover:bg-accent/50"
        >
          <Checkbox
            :model-value="localKbIds.includes(kb.id)"
            @update:model-value="checked => toggleKb(kb.id, checked)"
          />
          <span class="truncate text-xs text-foreground">{{ kb.name }}</span>
        </label>
      </div>
    </section>
  </div>
</template>
