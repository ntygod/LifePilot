<script setup lang="ts">
/**
 * Markdown 编辑器组件。
 *
 * 左右分栏布局：左侧 Monaco Editor（Markdown 模式 + YAML Frontmatter 高亮），
 * 右侧 marked 渲染 HTML 预览。支持 YAML Frontmatter 实时校验、未保存标记、
 * 保存/重置按钮、只读模式。
 */
import { ref, computed, watch, onMounted, shallowRef } from 'vue'
import { VueMonacoEditor, useMonaco } from '@guolao/vue-monaco-editor'
import { Marked } from 'marked'
import { markedHighlight } from 'marked-highlight'
import hljs from 'highlight.js'
import * as yaml from 'yaml'
import { useUiStore } from '@/stores/ui'

// ========== Props & Emits ==========

const props = withDefaults(defineProps<{
  modelValue: string
  readonly?: boolean
  title?: string
  onSave?: (content: string) => Promise<void>
}>(), {
  readonly: false,
  title: 'Markdown 编辑器',
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

// 保存按钮是否可用
const canSave = computed(() => dirty.value && !hasYamlErrors.value && !saving.value)

// ========== Marked 实例（带 highlight.js 代码高亮） ==========

const markedInstance = new Marked(
  markedHighlight({
    langPrefix: 'hljs language-',
    highlight(code: string, lang: string) {
      if (lang && hljs.getLanguage(lang)) {
        return hljs.highlight(code, { language: lang }).value
      }
      return hljs.highlightAuto(code).value
    },
  }),
)

// 预览 HTML
const previewHtml = computed(() => {
  if (!props.modelValue) return ''
  try {
    return markedInstance.parse(props.modelValue) as string
  } catch {
    return '<p class="text-destructive">Markdown 解析失败</p>'
  }
})

// ========== YAML Frontmatter 校验 ==========

/** 提取 frontmatter 内容和行范围 */
function extractFrontmatter(content: string): { yaml: string; startLine: number; endLine: number } | null {
  const lines = content.split('\n')
  if (lines.length < 2 || lines[0].trim() !== '---') return null

  for (let i = 1; i < lines.length; i++) {
    if (lines[i].trim() === '---') {
      return {
        yaml: lines.slice(1, i).join('\n'),
        startLine: 1, // 第一个 --- 之后
        endLine: i - 1,
      }
    }
  }
  return null
}

/** 校验 YAML frontmatter 并设置 Monaco markers */
function validateFrontmatter(content: string) {
  const fm = extractFrontmatter(content)
  if (!fm) {
    // 无 frontmatter，清除错误
    hasYamlErrors.value = false
    yamlErrorMessage.value = ''
    clearMarkers()
    return
  }

  try {
    yaml.parse(fm.yaml)
    hasYamlErrors.value = false
    yamlErrorMessage.value = ''
    clearMarkers()
  } catch (e: any) {
    hasYamlErrors.value = true
    const pos = e.linePos?.[0]
    // 错误行号需要加上 frontmatter 起始偏移（第 0 行是 ---）
    const errorLine = pos ? fm.startLine + pos.line : fm.startLine + 1
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
    monaco.editor.setModelMarkers(model, 'yaml-frontmatter', [])
  }
}

function setMarkers(line: number, col: number, message: string) {
  const editor = editorRef.value
  if (!editor) return
  const model = editor.getModel()
  if (!model) return
  const monaco = monacoRef.value
  if (!monaco) return

  monaco.editor.setModelMarkers(model, 'yaml-frontmatter', [
    {
      severity: monaco.MarkerSeverity.Error,
      startLineNumber: line + 1, // Monaco 行号从 1 开始
      startColumn: col,
      endLineNumber: line + 1,
      endColumn: col + 1,
      message,
    },
  ])
}

// ========== 编辑器事件 ==========

function onEditorMount(editor: any) {
  editorRef.value = editor
  // 初始校验
  validateFrontmatter(props.modelValue)
}

function onContentChange(value: string | undefined) {
  const v = value ?? ''
  emit('update:modelValue', v)
  validateFrontmatter(v)
}

// 外部 modelValue 变化时同步校验
watch(() => props.modelValue, (val) => {
  validateFrontmatter(val)
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
      Frontmatter YAML 错误：{{ yamlErrorMessage }}
    </div>

    <!-- 编辑器 + 预览分栏 -->
    <div class="flex" style="height: 480px;">
      <!-- 左侧：Monaco Editor -->
      <div class="flex-1 min-w-0 border-r border-border">
        <VueMonacoEditor
          :value="modelValue"
          language="markdown"
          :options="{
            readOnly: readonly,
            minimap: { enabled: false },
            lineNumbers: 'on',
            wordWrap: 'on',
            scrollBeyondLastLine: false,
            fontSize: 13,
            tabSize: 2,
            renderWhitespace: 'none',
            overviewRulerLanes: 0,
            hideCursorInOverviewRuler: true,
            automaticLayout: true,
          }"
          @mount="onEditorMount"
          @change="onContentChange"
        />
      </div>

      <!-- 右侧：Markdown 预览 -->
      <div class="flex-1 min-w-0 overflow-y-auto p-4 bg-background">
        <div class="prose prose-sm max-w-none dark:prose-invert" v-html="previewHtml" />
      </div>
    </div>
  </div>
</template>
