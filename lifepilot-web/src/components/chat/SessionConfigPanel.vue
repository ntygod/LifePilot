<script setup lang="ts">
import { ref, watch } from 'vue'
import type { SessionConfig, KnowledgeBase } from '@/types'
import type { LlmProvider } from '@/api/client'
import { X } from 'lucide-vue-next'

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

function toggleKb(id: string) {
  const idx = localKbIds.value.indexOf(id)
  if (idx >= 0) {
    localKbIds.value.splice(idx, 1)
  } else {
    localKbIds.value.push(id)
  }
  emitUpdate()
}
</script>

<template>
  <div class="rounded-lg border border-border bg-card p-4 space-y-4 text-sm">
    <div class="flex items-center justify-between">
      <span class="font-medium text-foreground">会话配置</span>
      <button
        type="button"
        class="text-muted-foreground hover:text-foreground transition-colors"
        @click="emit('close')"
      >
        <X :size="16" />
      </button>
    </div>

    <!-- 模型选择 -->
    <div>
      <label class="text-xs font-medium text-muted-foreground mb-1 block">模型</label>
      <select
        v-model="localModelId"
        class="w-full rounded-md border border-input bg-background px-3 py-1.5 text-sm
               focus:outline-none focus:ring-1 focus:ring-ring"
        @change="emitUpdate"
      >
        <option value="">默认模型</option>
        <option v-for="p in providers" :key="p.id" :value="p.id">
          {{ p.displayName || p.modelName || p.id }}
        </option>
      </select>
    </div>

    <!-- 温度 -->
    <div>
      <label class="text-xs font-medium text-muted-foreground mb-1 block">
        温度：{{ localTemperature.toFixed(1) }}
      </label>
      <input
        v-model.number="localTemperature"
        type="range"
        min="0"
        max="2"
        step="0.1"
        class="w-full"
        @change="emitUpdate"
      />
    </div>

    <!-- 最大 Token -->
    <div>
      <label class="text-xs font-medium text-muted-foreground mb-1 block">最大 Token</label>
      <input
        v-model.number="localMaxTokens"
        type="number"
        min="100"
        max="8000"
        step="100"
        class="w-full rounded-md border border-input bg-background px-3 py-1.5 text-sm
               focus:outline-none focus:ring-1 focus:ring-ring"
        @change="emitUpdate"
      />
    </div>

    <!-- 知识库多选 -->
    <div v-if="knowledgeBases.length > 0">
      <label class="text-xs font-medium text-muted-foreground mb-1 block">关联知识库</label>
      <div class="space-y-1 max-h-32 overflow-y-auto">
        <label
          v-for="kb in knowledgeBases"
          :key="kb.id"
          class="flex items-center gap-2 cursor-pointer hover:bg-muted/50 rounded px-1 py-0.5"
        >
          <input
            type="checkbox"
            :checked="localKbIds.includes(kb.id)"
            class="rounded border-input"
            @change="toggleKb(kb.id)"
          />
          <span class="text-sm text-foreground">{{ kb.name }}</span>
        </label>
      </div>
    </div>
  </div>
</template>
