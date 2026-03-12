<script setup lang="ts">
import type { LlmStepConfig } from '@/composables/useWorkflowModel'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Plus, Trash2 } from 'lucide-vue-next'

const props = defineProps<{
  modelValue: LlmStepConfig
}>()

const emit = defineEmits<{
  'update:modelValue': [value: LlmStepConfig]
}>()

const capabilityOptions: LlmStepConfig['capability'][] = [
  'CHAT',
  'EMBEDDING',
  'STRUCTURED_OUTPUT',
  'FUNCTION_CALLING',
  'STREAMING',
  'VISION',
  'TTS',
  'STT',
]

function updateField<K extends keyof LlmStepConfig>(field: K, value: LlmStepConfig[K]) {
  emit('update:modelValue', { ...props.modelValue, [field]: value })
}

function updateOptionalField(field: 'outputSchema' | 'modelName' | 'preferredProviderId', value: string | number) {
  const nextValue = String(value).trim()
  updateField(field, (nextValue.length > 0 ? nextValue : undefined) as LlmStepConfig[typeof field])
}

function updateRequiredField(field: 'scene' | 'promptTemplate', value: string | number) {
  updateField(field, String(value) as LlmStepConfig[typeof field])
}

function updateMediaField(index: number, field: 'source' | 'mimeType' | 'fileName', value: string | number) {
  const nextMedia = props.modelValue.media.map((item, itemIndex) => {
    if (itemIndex !== index) return item
    const nextValue = String(value)
    if (field === 'source') {
      return { ...item, source: nextValue }
    }
    const trimmed = nextValue.trim()
    return {
      ...item,
      [field]: trimmed.length > 0 ? trimmed : undefined,
    }
  })
  updateField('media', nextMedia)
}

function addMedia() {
  updateField('media', [
    ...props.modelValue.media,
    { source: '', mimeType: undefined, fileName: undefined },
  ])
}

function removeMedia(index: number) {
  updateField('media', props.modelValue.media.filter((_, itemIndex) => itemIndex !== index))
}
</script>

<template>
  <div class="space-y-4">
    <div class="grid gap-3 md:grid-cols-2">
      <div class="space-y-1.5">
        <Label class="text-xs">任务意图 Scene</Label>
        <Input
          :model-value="modelValue.scene"
          placeholder="例如：document_summary / code_generation"
          class="h-8 text-sm"
          @update:model-value="updateRequiredField('scene', $event)"
        />
        <p class="text-[11px] text-muted-foreground">这里描述节点要完成的任务，不再使用 `workflow` 这类容器名。</p>
      </div>

      <div class="space-y-1.5">
        <Label class="text-xs">模型能力 Capability</Label>
        <Select :model-value="modelValue.capability" @update:model-value="updateField('capability', $event as LlmStepConfig['capability'])">
          <SelectTrigger class="h-8 text-sm">
            <SelectValue placeholder="选择这个节点需要的模型能力" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem v-for="capability in capabilityOptions" :key="capability" :value="capability">
              {{ capability }}
            </SelectItem>
          </SelectContent>
        </Select>
      </div>
    </div>

    <div class="grid gap-3 md:grid-cols-2">
      <div class="space-y-1.5">
        <Label class="text-xs">指定模型（可选）</Label>
        <Input
          :model-value="modelValue.modelName ?? ''"
          placeholder="例如：qwen3.5-flash"
          class="h-8 text-sm"
          @update:model-value="updateOptionalField('modelName', $event)"
        />
      </div>

      <div class="space-y-1.5">
        <Label class="text-xs">优先 Provider（可选）</Label>
        <Input
          :model-value="modelValue.preferredProviderId ?? ''"
          placeholder="例如：aliyun-primary"
          class="h-8 text-sm"
          @update:model-value="updateOptionalField('preferredProviderId', $event)"
        />
      </div>
    </div>

    <div class="space-y-1.5">
      <Label class="text-xs">提示词模板</Label>
      <Textarea
        :model-value="modelValue.promptTemplate"
        placeholder="输入这个节点要发送给模型的提示词模板"
        rows="6"
        class="text-sm"
        @update:model-value="updateRequiredField('promptTemplate', $event)"
      />
    </div>

    <div class="space-y-1.5">
      <Label class="text-xs">输出 Schema（可选）</Label>
      <Textarea
        :model-value="modelValue.outputSchema ?? ''"
        placeholder="填写 JSON Schema 后，执行器会尝试将响应解析为结构化结果"
        rows="4"
        class="text-sm font-mono"
        @update:model-value="updateOptionalField('outputSchema', $event)"
      />
    </div>

    <div class="space-y-2 rounded-xl border border-border/60 bg-muted/30 p-3">
      <div class="flex items-center justify-between gap-3">
        <div>
          <Label class="text-xs">媒体输入</Label>
          <p class="text-[11px] text-muted-foreground">用于图片/视频理解。支持文件路径、data URL 和 Base64；配置媒体时 capability 应为 `VISION`。</p>
        </div>
        <Button type="button" variant="outline" size="sm" class="h-8 gap-1" @click="addMedia">
          <Plus class="h-3.5 w-3.5" />
          添加媒体
        </Button>
      </div>

      <div v-if="modelValue.media.length === 0" class="rounded-lg border border-dashed border-border/70 px-3 py-4 text-xs text-muted-foreground">
        这个节点当前没有媒体输入；如果只处理文本，可以保持为空。
      </div>

      <div v-for="(media, index) in modelValue.media" :key="index" class="space-y-2 rounded-lg border border-border/70 bg-background/80 p-3">
        <div class="flex items-center justify-between">
          <p class="text-xs font-medium text-foreground">媒体 {{ index + 1 }}</p>
          <Button type="button" variant="ghost" size="icon" class="h-7 w-7 text-muted-foreground" @click="removeMedia(index)">
            <Trash2 class="h-3.5 w-3.5" />
          </Button>
        </div>

        <div class="space-y-1.5">
          <Label class="text-xs">来源 Source</Label>
          <Input
            :model-value="media.source"
            placeholder="文件路径、data:image/... 或 Base64"
            class="h-8 text-sm"
            @update:model-value="updateMediaField(index, 'source', $event)"
          />
        </div>

        <div class="grid gap-3 md:grid-cols-2">
          <div class="space-y-1.5">
            <Label class="text-xs">MIME 类型（可选）</Label>
            <Input
              :model-value="media.mimeType ?? ''"
              placeholder="例如：image/png"
              class="h-8 text-sm"
              @update:model-value="updateMediaField(index, 'mimeType', $event)"
            />
          </div>

          <div class="space-y-1.5">
            <Label class="text-xs">文件名（可选）</Label>
            <Input
              :model-value="media.fileName ?? ''"
              placeholder="例如：review.png"
              class="h-8 text-sm"
              @update:model-value="updateMediaField(index, 'fileName', $event)"
            />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
