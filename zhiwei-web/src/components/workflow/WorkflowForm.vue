<script setup lang="ts">
import { ref, watch, onBeforeUnmount, onMounted } from 'vue'
import { useWorkflowStore } from '@/stores/workflow'
import { workflowApi } from '@/api/client'
import type { WorkflowDetail } from '@/types'
import { Textarea } from '@/components/ui/textarea'
import VisualEditor from '@/components/workflow/editor/VisualEditor.vue'

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

    const wf = props.workflow
    if (!wf) return

    try {
      const res = await workflowApi.getYaml(wf.id)
      const yaml = res.yamlContent ?? ''

      if (props.mode === 'duplicate') {
        const newId = `${wf.id}-copy`
        formData.value.yaml = yaml.match(/^id:\s*.*$/m)
          ? yaml.replace(/^id:\s*.*$/m, `id: ${newId}`)
          : `id: ${newId}\n${yaml}`
      } else {
        formData.value.yaml = yaml
      }
    } catch (e) {
      // fallback：最小可编辑骨架（避免弹窗空白）
      formData.value.yaml = `id: ${wf.id}
name: ${wf.name}
description: ${wf.description || ''}
version: "${wf.version || '1.0'}"

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
  { immediate: true }
)

function validateYaml(): boolean {
  errors.value = {}
  
  if (!formData.value.yaml.trim()) {
    errors.value.yaml = 'YAML 内容不能为空'
    return false
  }

  // 轻量校验：避免完全无提示。更严格的校验仍由后端负责。
  const yaml = formData.value.yaml
  const hasId = /^id:\s*\S+/m.test(yaml)
  const hasName = /^name:\s*.+/m.test(yaml)
  const hasSteps = /^steps:\s*$/m.test(yaml) || /^steps:\s*\[/m.test(yaml)
  if (!hasId || !hasName || !hasSteps) {
    errors.value.yaml = '需要包含至少：id、name、steps'
  }
  
  return Object.keys(errors.value).length === 0
}

function validate(): boolean {
  // 可视化编排器和 YAML 编辑器都通过 YAML 文本提交，统一校验
  return validateYaml()
}

async function handleSubmit() {
  if (!validate()) return
  
  loading.value = true
  try {
    let workflow: WorkflowDetail
    
    if (props.mode === 'duplicate') {
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

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    emit('close')
    return
  }
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
    e.preventDefault()
    void handleSubmit()
  }
}

onMounted(() => window.addEventListener('keydown', handleKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', handleKeydown))
</script>

<template>
  <div class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="emit('close')">
    <div class="bg-card border border-border rounded-2xl shadow-lg w-full max-w-[1200px] h-[85vh] overflow-hidden m-md flex flex-col">
      <div class="px-lg py-lg flex flex-col h-full">
        <div class="flex items-center justify-between mb-md flex-shrink-0">
          <h2 class="text-xl font-semibold text-foreground">
            {{ mode === 'create' ? '新建 Workflow' : mode === 'edit' ? '编辑 Workflow' : '复制 Workflow' }}
          </h2>
          <button
            class="text-muted-foreground hover:text-foreground transition-colors"
            @click="emit('close')"
            aria-label="关闭"
          >
            ×
          </button>
        </div>

        <form @submit.prevent="handleSubmit" class="flex flex-col flex-1 overflow-hidden">
          <!-- Tab 切换 -->
          <div class="flex items-center gap-sm flex-shrink-0 mb-md">
            <button
              type="button"
              class="rounded-lg px-md py-xs text-sm font-medium transition-all duration-200 active:scale-[0.98]"
              :class="editorTab === 'visual' ? 'bg-accent text-accent-foreground shadow-sm' : 'bg-muted text-muted-foreground hover:bg-accent hover:text-accent-foreground'"
              @click="editorTab = 'visual'"
            >
              可视化编排
            </button>
            <button
              type="button"
              class="rounded-lg px-md py-xs text-sm font-medium transition-all duration-200 active:scale-[0.98]"
              :class="editorTab === 'yaml' ? 'bg-accent text-accent-foreground shadow-sm' : 'bg-muted text-muted-foreground hover:bg-accent hover:text-accent-foreground'"
              @click="editorTab = 'yaml'"
            >
              YAML 高级
            </button>
          </div>

          <!-- 可视化编排器 -->
          <div v-show="editorTab === 'visual'" class="flex-1 overflow-hidden">
            <VisualEditor v-model="formData.yaml" />
          </div>

          <!-- YAML 编辑器 -->
          <div v-show="editorTab === 'yaml'" class="flex-1 overflow-auto">
            <label class="block text-sm font-medium text-foreground mb-1">
              YAML 定义 <span class="text-destructive">*</span>
            </label>
            <Textarea
              v-model="formData.yaml"
              rows="20"
              spellcheck="false"
              class="font-mono text-sm leading-normal min-h-[360px]"
              :class="{ 'border-destructive': errors.yaml }"
              placeholder="示例：id/name/triggers/steps..."
            />
            <p v-if="errors.yaml" class="text-xs text-destructive mt-1">{{ errors.yaml }}</p>
            <p v-else class="text-xs text-muted-foreground mt-1">
              需要包含至少：<span class="font-medium">id</span>、<span class="font-medium">name</span>、<span class="font-medium">steps</span>。
              快捷键：<span class="font-medium">Ctrl/Cmd + Enter</span> 保存，<span class="font-medium">Esc</span> 关闭。
            </p>
          </div>

          <!-- 操作按钮 -->
          <div class="flex justify-end gap-sm pt-md border-t border-border flex-shrink-0">
            <button
              type="button"
              class="rounded-lg px-md py-sm text-sm font-medium border border-input bg-background hover:bg-accent transition-all duration-200 active:scale-[0.98]"
              @click="emit('close')"
            >取消</button>
            <button
              type="submit"
              :disabled="loading"
              class="rounded-lg px-md py-sm text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 hover:shadow-md transition-all duration-200 active:scale-[0.98] disabled:opacity-50 disabled:cursor-not-allowed"
            >{{ loading ? '保存中...' : '保存' }}</button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>
