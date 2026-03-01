<script setup lang="ts">
import { ref, watch } from 'vue'
import { useWorkflowStore } from '@/stores/workflow'
import type { WorkflowDetail } from '@/types'

const props = defineProps<{
  workflow?: WorkflowDetail | null
  mode: 'create' | 'edit' | 'duplicate'
}>()

const emit = defineEmits<{
  close: []
  saved: [workflow: WorkflowDetail]
}>()

const store = useWorkflowStore()

const formData = ref({
  yaml: ''
})

const loading = ref(false)
const errors = ref<Record<string, string>>({})

watch(() => props.workflow, async (workflow) => {
  if (workflow) {
    // 对于编辑和复制，我们需要获取完整的 YAML
    // 这里简化处理，使用 JSON 转 YAML（实际应该从后端获取原始 YAML）
    try {
      const yaml = convertToYaml(workflow)
      formData.value.yaml = props.mode === 'duplicate' ? yaml.replace(/^id:\s*.*$/m, `id: ${workflow.id}-copy`) : yaml
    } catch (e) {
      formData.value.yaml = `id: ${workflow.id}\nname: ${workflow.name}\ndescription: ${workflow.description || ''}\n`
    }
  } else if (props.mode === 'create') {
    formData.value.yaml = `id: my-workflow\nname: 我的工作流\ndescription: 工作流描述\ntriggerType: manual\nenabled: true\nsteps:\n  - id: step1\n    type: tool\n    tool: my_tool\n`
  }
}, { immediate: true })

function convertToYaml(workflow: WorkflowDetail): string {
  // 简化的转换，实际应该从后端获取原始 YAML
  return `id: ${workflow.id}\nname: ${workflow.name}\ndescription: ${workflow.description || ''}\nversion: ${workflow.version || '1.0.0'}\nenabled: ${workflow.enabled}\n`
}

function validate(): boolean {
  errors.value = {}
  
  if (!formData.value.yaml.trim()) {
    errors.value.yaml = 'YAML 内容不能为空'
  }
  
  return Object.keys(errors.value).length === 0
}

async function handleSubmit() {
  if (!validate()) return
  
  loading.value = true
  try {
    let workflow: WorkflowDetail
    
    if (props.mode === 'duplicate') {
      // 复制时先创建新 Workflow
      workflow = await store.create({ yaml: formData.value.yaml })
    } else if (props.mode === 'create') {
      workflow = await store.create({ yaml: formData.value.yaml })
    } else {
      workflow = await store.update(props.workflow!.id, { yaml: formData.value.yaml })
    }
    
    emit('saved', workflow)
    emit('close')
  } catch (e) {
    // 错误已在 store 中处理
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="emit('close')">
    <div class="bg-card border border-border rounded-lg shadow-lg w-full max-w-4xl max-h-[90vh] overflow-y-auto m-4">
      <div class="p-6">
        <div class="flex items-center justify-between mb-6">
          <h2 class="text-xl font-semibold text-foreground">
            {{ mode === 'create' ? '新建 Workflow' : mode === 'edit' ? '编辑 Workflow' : '复制 Workflow' }}
          </h2>
          <button
            class="text-muted-foreground hover:text-foreground transition-colors"
            @click="emit('close')"
          >×</button>
        </div>

        <form @submit.prevent="handleSubmit" class="space-y-4">
          <!-- YAML 编辑器 -->
          <div>
            <label class="block text-sm font-medium text-foreground mb-1">
              YAML 定义 <span class="text-destructive">*</span>
            </label>
            <textarea
              v-model="formData.yaml"
              rows="20"
              class="w-full px-3 py-2 border border-input rounded-md bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring font-mono text-sm"
              :class="{ 'border-destructive': errors.yaml }"
              placeholder="输入 Workflow 的 YAML 定义..."
            />
            <p v-if="errors.yaml" class="text-xs text-destructive mt-1">{{ errors.yaml }}</p>
            <p v-else class="text-xs text-muted-foreground mt-1">使用 YAML 格式定义工作流</p>
          </div>

          <!-- 操作按钮 -->
          <div class="flex justify-end gap-2 pt-4 border-t border-border">
            <button
              type="button"
              class="px-4 py-2 text-sm border border-input rounded-md hover:bg-accent transition-colors"
              @click="emit('close')"
            >取消</button>
            <button
              type="submit"
              :disabled="loading"
              class="px-4 py-2 text-sm bg-primary text-primary-foreground rounded-md hover:bg-primary/90 transition-colors disabled:opacity-50"
            >{{ loading ? '保存中...' : '保存' }}</button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>
