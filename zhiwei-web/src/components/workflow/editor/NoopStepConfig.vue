<!--
  空操作步骤配置组件。
  NoopStep 无需额外配置，仅展示提示文本。
-->
<script setup lang="ts">
import type { NoopStepConfig } from '@/composables/useWorkflowModel'

defineProps<{
  modelValue: NoopStepConfig
}>()

defineEmits<{
  'update:modelValue': [value: NoopStepConfig]
}>()
</script>

<template>
  <div class="rounded-md border border-dashed p-3 text-xs text-muted-foreground">
    空操作步骤，无需额外配置
  </div>
</template>
