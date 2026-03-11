<!--
  Skill 步骤配置组件。
  配置 skillId（通过搜索选择器从已注册 Skill 中选择）和 params（JSON 格式）。
-->
<script setup lang="ts">
import { computed, onMounted } from 'vue'
import type { SkillStepConfig } from '@/composables/useWorkflowModel'
import { Textarea } from '@/components/ui/textarea'
import { Label } from '@/components/ui/label'
import { useSkillStore } from '@/stores/skill'
import ResourceCombobox from './ResourceCombobox.vue'
import type { ResourceOption } from './ResourceCombobox.vue'

const props = defineProps<{
  modelValue: SkillStepConfig
}>()

const emit = defineEmits<{
  'update:modelValue': [value: SkillStepConfig]
}>()

const skillStore = useSkillStore()

onMounted(() => {
  if (skillStore.skills.length === 0) skillStore.fetchSkills()
})

/** 将 SkillSummary 转为 ResourceOption */
const skillOptions = computed<ResourceOption[]>(() =>
  skillStore.skills.map(s => ({
    id: s.id,
    name: s.name,
    description: s.description,
  }))
)

/** params 序列化为 JSON 文本展示 */
const paramsText = computed(() => {
  const entries = Object.entries(props.modelValue.params ?? {})
  return entries.length > 0 ? JSON.stringify(props.modelValue.params, null, 2) : ''
})

function updateSkillId(val: string) {
  emit('update:modelValue', { ...props.modelValue, skillId: val })
}

function updateParams(val: string | number) {
  const text = String(val)
  try {
    const parsed = text.trim() ? JSON.parse(text) : {}
    emit('update:modelValue', { ...props.modelValue, params: parsed })
  } catch {
    // JSON 解析失败时不更新，保留用户输入
  }
}
</script>

<template>
  <div class="space-y-3">
    <div class="space-y-1.5">
      <Label class="text-xs">Skill ID</Label>
      <ResourceCombobox
        :model-value="modelValue.skillId"
        :options="skillOptions"
        :loading="skillStore.loading"
        placeholder="选择或输入 Skill ID"
        search-placeholder="搜索 Skill..."
        empty-text="无匹配 Skill"
        @update:model-value="updateSkillId"
      />
    </div>
    <div class="space-y-1.5">
      <Label class="text-xs">参数（JSON）</Label>
      <Textarea
        :model-value="paramsText"
        placeholder='{"key": "value"}'
        rows="4"
        class="text-sm font-mono"
        @update:model-value="updateParams"
      />
    </div>
  </div>
</template>
