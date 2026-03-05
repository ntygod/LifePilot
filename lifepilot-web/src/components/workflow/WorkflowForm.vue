<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount, onMounted } from 'vue'
import { useWorkflowStore } from '@/stores/workflow'
import { useToolStore } from '@/stores/tool'
import { workflowApi } from '@/api/client'
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
const toolStore = useToolStore()

const formData = ref({
  yaml: ''
})

const loading = ref(false)
const errors = ref<Record<string, string>>({})

type EditorTab = 'guided' | 'yaml'
const editorTab = ref<EditorTab>(props.mode === 'create' ? 'guided' : 'yaml')

const guided = ref({
  id: 'my-workflow',
  name: '我的工作流',
  description: '工作流描述',
  version: '1.0',
  triggerType: 'manual' as 'manual',
  stepId: 'step1',
  stepName: '第一步',
  toolId: 'my_tool',
  paramsJson: '{}',
  author: 'me'
})

const templates = [
  {
    key: 'manual_tool',
    title: '手动触发 + 工具步骤',
    description: '最常用的起步模板：点击「手动触发」后执行一个 Tool。',
    preset: {
      id: 'my-workflow',
      name: '我的工作流',
      description: '工作流描述',
      version: '1.0',
      triggerType: 'manual' as const,
      stepId: 'step1',
      stepName: '第一步',
      toolId: 'my_tool',
      paramsJson: '{}',
      author: 'me'
    }
  },
  {
    key: 'manual_tool_with_input',
    title: '手动触发 + 带输入参数',
    description: '适合需要输入参数的工具：先定义 inputs，再在 params 里引用。',
    preset: {
      id: 'my-workflow',
      name: '带输入的工作流',
      description: '从 inputs 读取参数并传给工具。',
      version: '1.0',
      triggerType: 'manual' as const,
      stepId: 'step1',
      stepName: '调用工具',
      toolId: 'my_tool',
      paramsJson: '{\n  "query": "${inputs.query}"\n}',
      author: 'me'
    }
  }
] as const

function normalizeId(input: string): string {
  return input
    .trim()
    .replace(/\s+/g, '-')
    .replace(/[^a-zA-Z0-9_-]/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-+|-+$/g, '')
}

function buildYamlFromGuided(): string {
  // 注意：这里用字符串拼装 YAML（避免引入额外依赖），只覆盖最常用字段。
  const id = normalizeId(guided.value.id || 'my-workflow') || 'my-workflow'
  const name = guided.value.name?.trim() || '我的工作流'
  const description = guided.value.description?.trim() || ''
  const version = guided.value.version?.trim() || '1.0'
  const stepId = normalizeId(guided.value.stepId || 'step1') || 'step1'
  const stepName = guided.value.stepName?.trim() || '第一步'
  const toolId = guided.value.toolId?.trim() || 'my_tool'
  const author = guided.value.author?.trim() || ''

  let params: unknown = {}
  try {
    params = guided.value.paramsJson?.trim() ? JSON.parse(guided.value.paramsJson) : {}
  } catch {
    // 交给 validate() 处理错误提示，这里兜底为空对象
    params = {}
  }

  const paramsBlock = JSON.stringify(params, null, 2)
    .split('\n')
    .map(line => `      ${line}`)
    .join('\n')

  // inputs 的轻量支持：当 paramsJson 使用了 ${inputs.xxx} 时，生成一个示例 inputs.query
  const needsInputs = guided.value.paramsJson.includes('${inputs.')
  const inputsBlock = needsInputs ? 'inputs:\n  query: ""\n' : 'inputs: {}\n'

  const metadataBlock = author ? `\nmetadata:\n  author: ${author}\n` : ''

  return `id: ${id}
name: ${name}
description: ${description}
version: "${version}"

triggers:
  - type: ${guided.value.triggerType}

${inputsBlock}
steps:
  - id: ${stepId}
    name: ${stepName}
    type: tool
    toolId: ${toolId}
    params:
${paramsBlock}
${metadataBlock}`
}

const generatedYaml = computed(() => buildYamlFromGuided())

function applyTemplate(key: (typeof templates)[number]['key']) {
  const tpl = templates.find(t => t.key === key)
  if (!tpl) return
  guided.value = { ...guided.value, ...tpl.preset }
  editorTab.value = 'guided'
}

watch(
  () => [props.workflow?.id, props.mode] as const,
  async () => {
    errors.value = {}
    editorTab.value = props.mode === 'create' ? 'guided' : 'yaml'

    if (props.mode === 'create') {
      guided.value = {
        id: 'my-workflow',
        name: '我的工作流',
        description: '工作流描述',
        version: '1.0',
        triggerType: 'manual',
        stepId: 'step1',
        stepName: '第一步',
        toolId: 'my_tool',
        paramsJson: '{}',
        author: 'me'
      }
      formData.value.yaml = generatedYaml.value
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

watch(
  guided,
  () => {
    if (editorTab.value === 'guided') {
      formData.value.yaml = generatedYaml.value
    }
  },
  { deep: true }
)

watch(
  editorTab,
  tab => {
    errors.value = {}
    if (tab === 'guided') {
      formData.value.yaml = generatedYaml.value
    }
  },
  { immediate: false }
)

function validateGuided(): boolean {
  errors.value = {}

  const id = normalizeId(guided.value.id)
  if (!id) errors.value.guided_id = '请填写工作流 ID（建议用字母/数字/中划线）'
  if (!guided.value.name.trim()) errors.value.guided_name = '请填写工作流名称'
  if (!normalizeId(guided.value.stepId)) errors.value.guided_stepId = '请填写步骤 ID'
  if (!guided.value.toolId.trim()) errors.value.guided_toolId = '请填写 Tool ID'

  const raw = guided.value.paramsJson.trim()
  if (raw) {
    try {
      const parsed = JSON.parse(raw)
      if (parsed === null || Array.isArray(parsed) || typeof parsed !== 'object') {
        errors.value.guided_paramsJson = 'params 必须是 JSON 对象（例如：{}）'
      }
    } catch {
      errors.value.guided_paramsJson = 'params 不是合法 JSON（例如：{} 或 {"k":"v"}）'
    }
  }

  return Object.keys(errors.value).length === 0
}

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
    errors.value.yaml = '需要包含至少：id、name、steps（可先用上方模板快速生成）'
  }
  
  return Object.keys(errors.value).length === 0
}

function validate(): boolean {
  // create 默认引导；edit/duplicate 默认 YAML，但允许用户手动切到 guided（会覆盖 YAML）
  if (editorTab.value === 'guided') return validateGuided()
  return validateYaml()
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

onMounted(async () => {
  // 仅用于 guided 的轻量自动补全（不影响保存逻辑）
  try {
    if (!toolStore.tools.length) {
      await toolStore.fetchTools()
    }
  } catch {
    // 忽略：没有 tools 也不影响创建工作流
  }
})
</script>

<template>
  <div class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="emit('close')">
    <div class="bg-card border border-border rounded-2xl shadow-lg w-full max-w-[768px] max-h-[90vh] overflow-y-auto m-md">
      <div class="px-lg py-lg">
        <div class="flex items-center justify-between mb-md">
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

        <form @submit.prevent="handleSubmit" class="space-y-md">
          <!-- 创建方式（仅对 create / duplicate 更有意义；edit 默认 YAML） -->
          <div class="flex items-center gap-sm">
            <button
              type="button"
              class="rounded-lg px-md py-xs text-sm font-medium transition-all duration-200 active:scale-[0.98]"
              :class="editorTab === 'guided' ? 'bg-accent text-accent-foreground shadow-sm' : 'bg-muted text-muted-foreground hover:bg-accent hover:text-accent-foreground'"
              @click="editorTab = 'guided'"
            >
              快速创建
            </button>
            <button
              type="button"
              class="rounded-lg px-md py-xs text-sm font-medium transition-all duration-200 active:scale-[0.98]"
              :class="editorTab === 'yaml' ? 'bg-accent text-accent-foreground shadow-sm' : 'bg-muted text-muted-foreground hover:bg-accent hover:text-accent-foreground'"
              @click="editorTab = 'yaml'"
            >
              YAML 高级
            </button>
            <span v-if="mode !== 'create' && editorTab === 'guided'" class="text-xs text-muted-foreground ml-sm">
              提示：从 YAML 切到「快速创建」会用表单重新生成内容，可能覆盖你现有的 YAML。
            </span>
          </div>

          <!-- 快速创建（表单 + 模板） -->
          <div v-if="editorTab === 'guided'" class="space-y-md">
            <div class="border border-border rounded-2xl bg-card p-md space-y-sm">
              <div class="text-sm font-medium text-foreground">选择模板</div>
              <div class="grid grid-cols-1 md:grid-cols-2 gap-sm">
                <button
                  v-for="t in templates"
                  :key="t.key"
                  type="button"
                  class="text-left border border-input rounded-lg p-md hover:bg-accent transition-all duration-200 active:scale-[0.98]"
                  @click="applyTemplate(t.key)"
                >
                  <div class="text-sm font-medium text-foreground">{{ t.title }}</div>
                  <div class="text-xs text-muted-foreground mt-xs leading-normal">{{ t.description }}</div>
                </button>
              </div>
            </div>

            <div class="grid grid-cols-1 md:grid-cols-2 gap-md">
              <div>
                <label class="block text-sm font-medium text-foreground mb-1">工作流 ID <span class="text-destructive">*</span></label>
                <input
                  v-model="guided.id"
                  class="w-full px-md py-sm border border-input rounded-2xl bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 text-sm"
                  :class="{ 'border-destructive': errors.guided_id }"
                  placeholder="例如：daily-report"
                />
                <p v-if="errors.guided_id" class="text-xs text-destructive mt-1">{{ errors.guided_id }}</p>
                <p v-else class="text-xs text-muted-foreground mt-1">建议使用字母/数字/中划线（会自动规整）。</p>
              </div>

              <div>
                <label class="block text-sm font-medium text-foreground mb-1">名称 <span class="text-destructive">*</span></label>
                <input
                  v-model="guided.name"
                  class="w-full px-md py-sm border border-input rounded-2xl bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 text-sm"
                  :class="{ 'border-destructive': errors.guided_name }"
                  placeholder="例如：每日工作汇总"
                />
                <p v-if="errors.guided_name" class="text-xs text-destructive mt-1">{{ errors.guided_name }}</p>
              </div>

              <div class="md:col-span-2">
                <label class="block text-sm font-medium text-foreground mb-1">描述</label>
                <textarea
                  v-model="guided.description"
                  rows="2"
                  class="w-full px-md py-sm border border-input rounded-2xl bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 text-sm leading-normal"
                  placeholder="这条工作流会做什么？什么时候运行？"
                />
              </div>
            </div>

            <div class="border border-border rounded-2xl bg-card p-md space-y-sm">
              <div class="text-sm font-medium text-foreground">第一步（Tool）</div>
              <div class="grid grid-cols-1 md:grid-cols-2 gap-md">
                <div>
                  <label class="block text-sm font-medium text-foreground mb-1">步骤 ID <span class="text-destructive">*</span></label>
                  <input
                    v-model="guided.stepId"
                    class="w-full px-md py-sm border border-input rounded-2xl bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 text-sm"
                    :class="{ 'border-destructive': errors.guided_stepId }"
                    placeholder="例如：fetch_data"
                  />
                  <p v-if="errors.guided_stepId" class="text-xs text-destructive mt-1">{{ errors.guided_stepId }}</p>
                </div>

                <div>
                  <label class="block text-sm font-medium text-foreground mb-1">步骤名称</label>
                  <input
                    v-model="guided.stepName"
                    class="w-full px-md py-sm border border-input rounded-2xl bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 text-sm"
                    placeholder="例如：抓取数据"
                  />
                </div>

                <div class="md:col-span-2 space-y-xs">
                  <label class="block text-sm font-medium text-foreground mb-1">
                    Tool ID <span class="text-destructive">*</span>
                  </label>
                  <!-- 优先提供下拉选择已有 Tool，展示 name + id，减少记忆成本 -->
                  <div class="flex flex-col md:flex-row gap-sm">
                    <div class="flex-1">
                      <select
                        v-model="guided.toolId"
                        class="w-full px-md py-sm border border-input rounded-2xl bg-background text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
                        :class="{ 'border-destructive': errors.guided_toolId }"
                      >
                        <option disabled value="">请选择一个 Tool</option>
                        <option
                          v-for="t in toolStore.tools"
                          :key="t.id"
                          :value="t.id"
                        >
                          {{ t.name || t.id }}（{{ t.id }}）
                        </option>
                      </select>
                    </div>
                    <!-- 保留手动输入能力，方便先设计 Workflow 再补充 Tool 场景 -->
                    <div class="flex-1">
                      <input
                        v-model="guided.toolId"
                        class="w-full px-md py-sm border border-input rounded-2xl bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 text-sm"
                        :class="{ 'border-destructive': errors.guided_toolId }"
                        placeholder="也可以直接输入自定义 Tool ID，例如：weather.get"
                      />
                    </div>
                  </div>
                  <p v-if="errors.guided_toolId" class="text-xs text-destructive mt-1">
                    {{ errors.guided_toolId }}
                  </p>
                  <p v-else class="text-xs text-muted-foreground mt-1">
                    可以直接从下拉列表中选择已有 Tool（展示名称 + ID），也可以在右侧输入框里手动填入新的 Tool ID。
                  </p>
                </div>

                <div class="md:col-span-2">
                  <label class="block text-sm font-medium text-foreground mb-1">params（JSON 对象）</label>
                  <textarea
                    v-model="guided.paramsJson"
                    rows="5"
                    class="w-full px-md py-sm border border-input rounded-2xl bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 font-mono text-sm leading-normal"
                    :class="{ 'border-destructive': errors.guided_paramsJson }"
                    spellcheck="false"
                    placeholder="例如：{} 或 {&quot;query&quot;:&quot;xxx&quot;}"
                  />
                  <p v-if="errors.guided_paramsJson" class="text-xs text-destructive mt-1">{{ errors.guided_paramsJson }}</p>
                  <p v-else class="text-xs text-muted-foreground mt-1">不会写也没关系：先用 `{}` 起步，后面再在 YAML 高级里扩展。</p>
                </div>
              </div>
            </div>

            <div class="border border-border rounded-2xl bg-card p-md space-y-sm">
              <div class="flex items-center justify-between">
                <div class="text-sm font-medium text-foreground">生成预览（YAML）</div>
                <button
                  type="button"
                  class="rounded-lg px-md py-xs text-sm font-medium bg-muted text-muted-foreground hover:bg-accent hover:text-accent-foreground transition-all duration-200 active:scale-[0.98]"
                  @click="editorTab = 'yaml'"
                >
                  切到 YAML 高级
                </button>
              </div>
              <pre class="w-full overflow-auto rounded-2xl border border-input bg-background p-md text-sm font-mono leading-normal text-foreground whitespace-pre">{{ generatedYaml }}</pre>
              <p class="text-xs text-muted-foreground">
                保存时会提交上面生成的 YAML。你也可以切到「YAML 高级」做更复杂的 triggers/steps 配置。
              </p>
            </div>
          </div>

          <!-- YAML 编辑器 -->
          <div v-show="editorTab === 'yaml'">
            <label class="block text-sm font-medium text-foreground mb-1">
              YAML 定义 <span class="text-destructive">*</span>
            </label>
            <textarea
              v-model="formData.yaml"
              rows="20"
              spellcheck="false"
              class="w-full px-md py-sm border border-input rounded-2xl bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2 font-mono text-sm leading-normal min-h-[360px]"
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
          <div class="flex justify-end gap-sm pt-md border-t border-border">
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
