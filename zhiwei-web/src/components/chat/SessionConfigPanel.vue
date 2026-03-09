<script setup lang="ts">
import { ref, watch } from 'vue'
import type { SessionConfig, KnowledgeBase } from '@/types'
import type { LlmProvider } from '@/api/client'
import { X } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { Select, SelectTrigger, SelectValue, SelectContent, SelectItem } from '@/components/ui/select'
import { Slider } from '@/components/ui/slider'
import { Input } from '@/components/ui/input'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'

const props = defineProps<{
  modelId?: string
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

const localModelId = ref(props.modelId ?? '')
const localTemperature = ref(props.temperature ?? 0.7)
const localMaxTokens = ref(props.maxTokens ?? 2000)
const localKbIds = ref<string[]>(props.knowledgeBaseIds ?? [])

// 同步外部 prop 变化
watch(() => props.modelId, v => { localModelId.value = v ?? '' })
watch(() => props.temperature, v => { localTemperature.value = v ?? 0.7 })
watch(() => props.maxTokens, v => { localMaxTokens.value = v ?? 2000 })
watch(() => props.knowledgeBaseIds, v => { localKbIds.value = v ?? [] })

function emitUpdate() {
  // 约束范围
  const temp = Math.min(2, Math.max(0, localTemperature.value))
  const tokens = Math.min(8000, Math.max(100, localMaxTokens.value))
  emit('update', {
    modelId: localModelId.value || undefined,
    temperature: temp,
    maxTokens: tokens,
    knowledgeBaseIds: localKbIds.value.length > 0 ? localKbIds.value : undefined,
  })
}

function onModelChange(value: unknown) {
  const v = String(value ?? '')
  localModelId.value = v === '__default__' ? '' : v
  emitUpdate()
}

function onTemperatureChange(value: number[] | undefined) {
  if (value) {
    localTemperature.value = value[0]
  }
  emitUpdate()
}

function onMaxTokensChange(value: string | number) {
  localMaxTokens.value = Number(value)
  emitUpdate()
}

function toggleKb(id: string, checked: boolean) {
  if (checked) {
    if (!localKbIds.value.includes(id)) {
      localKbIds.value.push(id)
    }
  } else {
    const idx = localKbIds.value.indexOf(id)
    if (idx >= 0) {
      localKbIds.value.splice(idx, 1)
    }
  }
  emitUpdate()
}
</script>

<template>
  <div class="rounded-lg border border-border bg-card p-4 space-y-4 text-sm">
    <div class="flex items-center justify-between">
      <span class="font-medium text-foreground">会话配置</span>
      <Button
        variant="ghost"
        size="icon-sm"
        @click="emit('close')"
      >
        <X :size="16" />
      </Button>
    </div>

    <!-- 模型选择 -->
    <div class="space-y-1.5">
      <Label class="text-xs text-muted-foreground">模型</Label>
      <Select :model-value="localModelId || '__default__'" @update:model-value="onModelChange">
        <SelectTrigger class="w-full">
          <SelectValue placeholder="默认模型" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="__default__">默认模型</SelectItem>
          <SelectItem v-for="p in providers" :key="p.id" :value="p.id">
            {{ p.displayName || p.modelName || p.id }}
          </SelectItem>
        </SelectContent>
      </Select>
    </div>

    <!-- 温度 -->
    <div class="space-y-1.5">
      <Label class="text-xs text-muted-foreground">
        温度：{{ localTemperature.toFixed(1) }}
      </Label>
      <Slider
        :model-value="[localTemperature]"
        :min="0"
        :max="2"
        :step="0.1"
        @update:model-value="onTemperatureChange"
      />
    </div>

    <!-- 最大 Token -->
    <div class="space-y-1.5">
      <Label class="text-xs text-muted-foreground">最大 Token</Label>
      <Input
        type="number"
        :model-value="localMaxTokens"
        :min="100"
        :max="8000"
        :step="100"
        @update:model-value="onMaxTokensChange"
      />
    </div>

    <!-- 知识库多选 -->
    <div v-if="knowledgeBases.length > 0" class="space-y-1.5">
      <Label class="text-xs text-muted-foreground">关联知识库</Label>
      <div class="space-y-1 max-h-32 overflow-y-auto">
        <label
          v-for="kb in knowledgeBases"
          :key="kb.id"
          class="flex items-center gap-2 cursor-pointer hover:bg-muted/50 rounded px-1 py-0.5"
        >
          <Checkbox
            :model-value="localKbIds.includes(kb.id)"
            @update:model-value="(checked: boolean) => toggleKb(kb.id, checked)"
          />
          <span class="text-sm text-foreground">{{ kb.name }}</span>
        </label>
      </div>
    </div>
  </div>
</template>
