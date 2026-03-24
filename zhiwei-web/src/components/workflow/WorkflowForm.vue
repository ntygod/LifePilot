<script setup lang="ts">
import { ref, watch, onBeforeUnmount, onMounted } from 'vue'
import type { WorkflowDetail } from '@/types'
import type { ValidationResponse } from '@/types'
import { workflowApi } from '@/api/client'
import FormDialogShell from '@/components/common/FormDialogShell.vue'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import VisualEditor from '@/components/workflow/editor/VisualEditor.vue'
import { useWorkflowStore } from '@/stores/workflow'
import { useUiStore } from '@/stores/ui'
import { Maximize2, Minimize2 } from 'lucide-vue-next'

const props = defineProps<{
  workflow?: WorkflowDetail | null
  mode: 'create' | 'edit' | 'duplicate'
}>()

const emit = defineEmits<{
  close: []
  saved: [workflow: WorkflowDetail]
}>()

const store = useWorkflowStore()
const uiStore = useUiStore()

const formData = ref({
  yaml: '',
})

const loading = ref(false)
const errors = ref<Record<string, string>>({})
const validationResult = ref<ValidationResponse | null>(null)
const validating = ref(false)
const isFullscreen = ref(false)

type EditorTab = 'visual' | 'yaml'
const editorTab = ref<EditorTab>(props.mode === 'create' ? 'visual' : 'yaml')

watch(
  () => [props.workflow?.id, props.mode] as const,
  async () => {
    errors.value = {}
    editorTab.value = props.mode === 'create' ? 'visual' : 'yaml'

    if (props.mode === 'create') {
      formData.value.yaml = ''
      return
    }

    const workflow = props.workflow
    if (!workflow) {
      return
    }

    try {
      const response = await workflowApi.getYaml(workflow.id)
      const yaml = response.yamlContent ?? ''

      if (props.mode === 'duplicate') {
        const newId = `${workflow.id}-copy`
        formData.value.yaml = yaml.match(/^id:\s*.*$/m)
          ? yaml.replace(/^id:\s*.*$/m, `id: ${newId}`)
          : `id: ${newId}\n${yaml}`
      } else {
        formData.value.yaml = yaml
      }
    } catch {
      formData.value.yaml = `id: ${workflow.id}
name: ${workflow.name}
description: ${workflow.description || ''}
version: "${workflow.version || '1.0'}"

triggers:
  - type: manual

inputs: {}

steps:
  - id: step1
    name: 第一步
    type: tool
    toolId: my_tool
    params: {}
`
    }
  },
  { immediate: true },
)

function validateYaml() {
  errors.value = {}

  if (!formData.value.yaml.trim()) {
    errors.value.yaml = 'YAML 内容不能为空'
    return false
  }

  const yaml = formData.value.yaml
  const hasId = /^id:\s*\S+/m.test(yaml)
  const hasName = /^name:\s*.+/m.test(yaml)
  const hasSteps = /^steps:\s*$/m.test(yaml) || /^steps:\s*\[/m.test(yaml)

  if (!hasId || !hasName || !hasSteps) {
    errors.value.yaml = '至少需要包含 id、name 和 steps 字段'
  }

  return Object.keys(errors.value).length === 0
}

async function validateWorkflow() {
  validating.value = true
  try {
    validationResult.value = await workflowApi.validateYaml(formData.value.yaml)
    return validationResult.value
  } catch (e) {
    console.error('校验失败:', e)
    return null
  } finally {
    validating.value = false
  }
}

async function handleSubmit() {
  if (!validateYaml()) {
    uiStore.showToast('error', '请先修正 YAML 配置')
    return
  }

  // 保存前先进行 YAML 校验
  const result = await validateWorkflow()
  if (result && !result.valid) {
    // 有语法错误，阻止保存
    uiStore.showToast('error', `YAML 语法错误: ${result.errors.join(', ')}`)
    return
  }

  // 有警告但不阻止保存
  if (result && result.warnings.length > 0) {
    uiStore.showToast('info', `存在 ${result.warnings.length} 个警告，请留意`)
  }

  loading.value = true

  try {
    const workflow = props.mode === 'edit'
      ? await store.update(props.workflow!.id, { yaml: formData.value.yaml })
      : await store.create({ yaml: formData.value.yaml })

    emit('saved', workflow)
    emit('close')
  } catch (error) {
    uiStore.showToast('error', error instanceof Error ? error.message : '保存工作流失败')
  } finally {
    loading.value = false
  }
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
    event.preventDefault()
    void handleSubmit()
  }
}

onMounted(() => window.addEventListener('keydown', handleKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', handleKeydown))
</script>

<template>
  <FormDialogShell
    :title="mode === 'create' ? '新建工作流' : mode === 'edit' ? '编辑工作流' : '复制工作流'"
    description="可通过可视化方式或 YAML 方式编辑工作流。"
    :content-class="isFullscreen ? 'sm:max-w-none w-screen h-screen !rounded-none' : 'sm:max-w-[min(1400px,calc(100vw-2rem))]'"
    body-class="!overflow-hidden !px-0 !py-0"
    @close="emit('close')"
  >
    <form id="workflow-form" :class="isFullscreen ? 'flex h-screen flex-col' : 'flex h-[85vh] flex-col'" @submit.prevent="handleSubmit">
      <div class="flex items-center gap-2 border-b border-border/70 px-6 py-4">
        <Button
          type="button"
          size="sm"
          :variant="editorTab === 'visual' ? 'default' : 'outline'"
          @click="editorTab = 'visual'"
        >
          可视化编排
        </Button>
        <Button
          type="button"
          size="sm"
          :variant="editorTab === 'yaml' ? 'default' : 'outline'"
          @click="editorTab = 'yaml'"
        >
          YAML 高级编辑
        </Button>
        <div class="ml-auto flex items-center gap-2">
          <Button
            type="button"
            size="sm"
            variant="ghost"
            title="切换全屏"
            @click="isFullscreen = !isFullscreen"
          >
            <Minimize2 v-if="isFullscreen" class="h-4 w-4" />
            <Maximize2 v-else class="h-4 w-4" />
          </Button>
          <span class="text-xs text-muted-foreground">
            快捷键：Ctrl/Cmd + Enter 保存
          </span>
        </div>
      </div>

      <div v-show="editorTab === 'visual'" class="min-h-0 flex-1 overflow-hidden">
        <VisualEditor v-model="formData.yaml" />
      </div>

      <div v-show="editorTab === 'yaml'" class="min-h-0 flex-1 overflow-y-auto px-6 py-5">
        <div class="space-y-2">
          <label class="text-sm font-medium text-foreground">
            YAML 定义 <span class="text-destructive">*</span>
          </label>
          <Textarea
            v-model="formData.yaml"
            rows="22"
            spellcheck="false"
            class="min-h-[420px] font-mono text-sm leading-normal"
            :class="{ 'border-destructive': errors.yaml }"
            placeholder="示例：id/name/triggers/steps..."
          />
          <p v-if="errors.yaml" class="text-xs text-destructive">{{ errors.yaml }}</p>
          <p v-else class="text-xs text-muted-foreground">
            至少需要包含 id、name 和 steps 字段。
          </p>
        </div>
      </div>
    </form>

    <template #footer>
      <div class="flex justify-end gap-3">
        <Button type="button" variant="outline" @click="emit('close')">
          取消
        </Button>
        <Button form="workflow-form" type="submit" :disabled="loading">
          {{ loading ? '保存中...' : '保存工作流' }}
        </Button>
      </div>
    </template>
  </FormDialogShell>
</template>
