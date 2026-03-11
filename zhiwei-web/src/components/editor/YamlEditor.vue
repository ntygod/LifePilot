<script setup lang="ts">
/**
 * YAML 编辑器组件。
 *
 * 基于 Monaco Editor 的纯 YAML 编辑器，支持语法高亮、自动缩进、
 * 括号匹配、实时 YAML 校验、未保存标记、保存/重置按钮、只读模式。
 *
 * @author zsg
 * @since 2026-03-01
 */
import { ref, computed, watch, onMounted, shallowRef } from 'vue'
import { VueMonacoEditor, useMonaco } from '@guolao/vue-monaco-editor'
import * as yaml from 'yaml'
import { useUiStore } from '@/stores/ui'
import '@/plugins/monaco'

// ========== Props & Emits ==========

const props = withDefaults(defineProps<{
  modelValue: string
  readonly?: boolean
  title?: string
  onSave?: (content: string) => Promise<void>
}>(), {
  readonly: false,
  title: 'YAML 编辑器',
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

// ========== 状态 ==========

const uiStore = useUiStore()
const { monacoRef } = useMonaco()
const editorRef = shallowRef<any>(null)
const lastSavedContent = ref(props.modelValue)
const saving = ref(false)
const hasYamlErrors = ref(false)
const yamlErrorMessage = ref('')

// 脏标记：当前内容与上次保存内容不同
const dirty = computed(() => props.modelValue !== lastSavedContent.value)

// 保存按钮是否可用：内容已修改、无 YAML 错误、未在保存中
const canSave = computed(() => dirty.value && !hasYamlErrors.value && !saving.value)

// ========== YAML 校验 ==========

/** 校验整个内容为 YAML 并设置 Monaco markers */
function validateYaml(content: string) {
  if (!content.trim()) {
    // 空内容视为合法
    hasYamlErrors.value = false
    yamlErrorMessage.value = ''
    clearMarkers()
    return
  }

  try {
    yaml.parse(content)
    hasYamlErrors.value = false
    yamlErrorMessage.value = ''
    clearMarkers()
  } catch (e: any) {
    hasYamlErrors.value = true
    const pos = e.linePos?.[0]
    const errorLine = pos?.line ?? 1
    const errorCol = pos?.col ?? 1
    yamlErrorMessage.value = e.message?.split('\n')[0] ?? 'YAML 语法错误'

    setMarkers(errorLine, errorCol, yamlErrorMessage.value)
  }
}

function clearMarkers() {
  const editor = editorRef.value
  if (!editor) return
  const model = editor.getModel()
  if (!model) return
  const monaco = monacoRef.value
  if (monaco) {
    monaco.editor.setModelMarkers(model, 'yaml-validation', [])
  }
}

function setMarkers(line: number, col: number, message: string) {
  const editor = editorRef.value
  if (!editor) return
  const model = editor.getModel()
  if (!model) return
  const monaco = monacoRef.value
  if (!monaco) return

  monaco.editor.setModelMarkers(model, 'yaml-validation', [
    {
      severity: monaco.MarkerSeverity.Error,
      startLineNumber: line,
      startColumn: col,
      endLineNumber: line,
      endColumn: col + 1,
      message,
    },
  ])
}

// ========== 编辑器事件 ==========

function onEditorMount(editor: any) {
  editorRef.value = editor
  // 初始校验
  validateYaml(props.modelValue)
}

function onContentChange(value: string | undefined) {
  const v = value ?? ''
  emit('update:modelValue', v)
  validateYaml(v)
}

// 外部 modelValue 变化时同步校验
watch(() => props.modelValue, (val) => {
  validateYaml(val)
})

// ========== 保存 & 重置 ==========

async function handleSave() {
  if (!canSave.value || !props.onSave) return
  saving.value = true
  try {
    await props.onSave(props.modelValue)
    lastSavedContent.value = props.modelValue
  } catch (e: any) {
    uiStore.showToast('error', e.message || '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

function handleReset() {
  emit('update:modelValue', lastSavedContent.value)
}

// 外部初始值变化时更新 lastSavedContent（首次加载场景）
onMounted(() => {
  lastSavedContent.value = props.modelValue
})
</script>

<template>
  <div class="border border-border rounded-lg overflow-hidden">
    <!-- 标题栏 -->
    <div class="flex items-center justify-between px-4 py-2 bg-muted/50 border-b border-border">
      <div class="flex items-center gap-2">
        <span class="text-sm font-medium text-foreground">{{ title }}</span>
        <span
          v-if="dirty && !readonly"
          class="text-xs px-1.5 py-0.5 rounded bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200"
        >未保存</span>
      </div>
      <div v-if="!readonly" class="flex items-center gap-2">
        <button
          type="button"
          class="h-7 px-3 text-xs rounded-md border border-input hover:bg-accent transition-colors disabled:opacity-50"
          :disabled="!dirty"
          @click="handleReset"
        >重置</button>
        <button
          type="button"
          class="h-7 px-3 text-xs rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
          :disabled="!canSave"
          @click="handleSave"
        >{{ saving ? '保存中...' : '保存' }}</button>
      </div>
    </div>

    <!-- YAML 错误提示 -->
    <div
      v-if="hasYamlErrors"
      class="px-4 py-1.5 bg-destructive/10 text-destructive text-xs border-b border-border"
    >
      YAML 语法错误：{{ yamlErrorMessage }}
    </div>

    <!-- Monaco Editor（单面板，无预览） -->
    <div style="height: 480px;">
      <VueMonacoEditor
        :value="modelValue"
        language="yaml"
        :options="{
          readOnly: readonly,
          minimap: { enabled: false },
          lineNumbers: 'on',
          wordWrap: 'on',
          scrollBeyondLastLine: false,
          fontSize: 13,
          tabSize: 2,
          autoIndent: 'full',
          formatOnPaste: true,
          bracketPairColorization: { enabled: true },
          renderWhitespace: 'none',
          overviewRulerLanes: 0,
          hideCursorInOverviewRuler: true,
          automaticLayout: true,
        }"
        @mount="onEditorMount"
        @change="onContentChange"
      />
    </div>
  </div>
</template>
