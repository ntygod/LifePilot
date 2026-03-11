<!--
  Skill 步骤配置组件。
  配置 skillId（通过搜索选择器从已注册 Skill 中选择）和 params。
  Skill 无 inputSchema，SchemaParamsEditor 自动回退到 JSON 编辑模式。
-->
<script setup lang="ts">
import { computed, onMounted } from 'vue'
import type { SkillStepConfig } from '@/composables/useWorkflowModel'
import { Label } from '@/components/ui/label'
import { useSkillStore } from '@/stores/skill'
import ResourceCombobox from './ResourceCombobox.vue'
import SchemaParamsEditor from './SchemaParamsEditor.vue'
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

function updateSkillId(val: string) {
  emit('update:modelValue', { ...props.modelValue, skillId: val, params: {} })
}

function updateParams(val: Record<string, unknown>) {
  emit('update:modelValue', { ...props.modelValue, params: val })
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
    <SchemaParamsEditor
      :model-value="modelValue.params ?? {}"
      :schema="null"
      @update:model-value="updateParams"
    />
  </div>
</template>
