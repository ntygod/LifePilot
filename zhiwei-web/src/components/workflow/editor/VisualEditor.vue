<!--
  可视化编排器主组件。
  三栏布局：左侧 StepPalette + 中央 EditorCanvas + 右侧 StepConfigPanel，
  顶部 MetadataPanel。通过 useWorkflowModel 管理编辑状态，
  通过 useYamlSync 实现 YAML 双向同步。
  支持 v-model 绑定 YAML 文本。
-->
<script setup lang="ts">
import { computed, watch, onMounted } from 'vue'
import { useWorkflowModel } from '@/composables/useWorkflowModel'
import { useYamlSync } from '@/composables/useYamlSync'
import type { StepType, StepModel } from '@/composables/useWorkflowModel'
import MetadataPanel from '@/components/workflow/editor/MetadataPanel.vue'
import StepPalette from '@/components/workflow/editor/StepPalette.vue'
import EditorCanvas from '@/components/workflow/editor/EditorCanvas.vue'
import StepConfigPanel from '@/components/workflow/editor/StepConfigPanel.vue'

const props = defineProps<{
  modelValue: string
}>()

const emit = defineEmits<{
  'update:modelValue': [yaml: string]
}>()

const {
  model,
  addStep,
  removeStep,
  updateStep,
  selectStep,
  findStep,
  addDependency,
  removeDependency,
  removeAllDependencies,
  loadFromSteps,
} = useWorkflowModel()

const { serialize, deserialize } = useYamlSync()

// 当前选中的步骤（支持嵌套步骤查找）
const selectedStep = computed<StepModel | null>(() => {
  if (!model.value.selectedStepId) return null
  return findStep(model.value.selectedStepId)
})

// ========== YAML 双向同步 ==========

// 标记：内部更新时跳过外部 prop 变更的 watch
let internalUpdate = false

// 初始化：挂载时从 modelValue 反序列化
onMounted(() => {
  if (props.modelValue) {
    const result = deserialize(props.modelValue)
    if (result.ok) {
      loadFromSteps(result.model)
    }
  }
})

// 外部 modelValue 变更时重新反序列化（如从 YAML Tab 切换回来）
watch(() => props.modelValue, (newYaml) => {
  if (internalUpdate) {
    internalUpdate = false
    return
  }
  if (newYaml) {
    const result = deserialize(newYaml)
    if (result.ok) {
      loadFromSteps(result.model)
    }
  }
})

// 模型变更时序列化为 YAML 并 emit
watch(model, () => {
  internalUpdate = true
  const yaml = serialize(model.value)
  emit('update:modelValue', yaml)
}, { deep: true })

// ========== 事件处理 ==========

/** 画布拖放步骤 */
function onDropStep(type: StepType) {
  addStep(type)
}

/** 删除步骤 */
function onDeleteStep(stepId: string) {
  removeStep(stepId)
}

/** 画布连线 */
function onConnect(fromId: string, toId: string, branch?: 'then' | 'else') {
  // 如果有 branch 參數，則添加到對應的分支中
  if (branch) {
    // 根據 branch 參數找到目標節點應該添加到哪個分支
    // 這裡的邏輯需要根據實際的步驟結構來處理
    console.log(`Connecting from ${fromId} to ${toId} in branch ${branch}`)
    // TODO: 實現分支內的連接邏輯
  } else {
    addDependency(fromId, toId)
  }
}

/** 画布断开连线 */
function onDisconnect(fromId: string, toId: string) {
  if (toId === '') {
    removeAllDependencies(fromId)
  } else {
    removeDependency(fromId, toId)
  }
}

/** 步骤配置面板更新 */
function onUpdateStep(updates: Partial<StepModel>) {
  if (model.value.selectedStepId) {
    updateStep(model.value.selectedStepId, updates)
  }
}
</script>

<template>
  <div class="flex h-full min-h-0 flex-col">
    <!-- 元数据面板 -->
    <MetadataPanel
      :id="model.id"
      :name="model.name"
      :description="model.description"
      :version="model.version"
      :triggers="model.triggers"
      :inputs="model.inputs"
      @update:id="model.id = $event"
      @update:name="model.name = $event"
      @update:description="model.description = $event"
      @update:version="model.version = $event"
      @update:triggers="model.triggers = $event"
      @update:inputs="model.inputs = $event"
    />

    <!-- 三栏编辑区域 -->
    <div class="flex min-h-0 flex-1 overflow-hidden">
      <!-- 左侧：步骤面板 -->
      <StepPalette />

      <!-- 中央：编排画布 -->
      <EditorCanvas
        :steps="model.steps"
        :selected-step-id="model.selectedStepId"
        :validation-errors="model.validationErrors"
        @select-step="selectStep"
        @drop-step="onDropStep"
        @connect="onConnect"
        @disconnect="onDisconnect"
        @delete-step="onDeleteStep"
      />

      <!-- 右侧：步骤配置面板 -->
      <StepConfigPanel
        :step="selectedStep"
        @update:step="onUpdateStep"
      />
    </div>
  </div>
</template>
