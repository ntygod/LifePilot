<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useSkillStore } from '@/stores/skill'
import type { SkillDetail } from '@/types'

const props = defineProps<{
  skill?: SkillDetail | null
  mode: 'create' | 'edit' | 'duplicate'
}>()

const emit = defineEmits<{
  close: []
  saved: [skill: SkillDetail]
}>()

const store = useSkillStore()

const formData = ref({
  id: '',
  name: '',
  description: '',
  version: '1.0.0',
  systemPrompt: '',
  allowedTools: [] as string[],
  preferredProviderId: '',
  metadata: {} as Record<string, string>
})

const showAdvanced = ref(false)
const idTouched = ref(false)

const availableTools = ref<string[]>([])
const loading = ref(false)
const errors = ref<Record<string, string>>({})

// 初始化表单数据
watch(() => props.skill, (skill) => {
  if (skill) {
    formData.value = {
      id: props.mode === 'duplicate' ? '' : skill.id,
      name: props.mode === 'duplicate' ? `${skill.name} (副本)` : skill.name,
      description: skill.description || '',
      version: skill.version || '1.0.0',
      systemPrompt: skill.systemPrompt || '',
      allowedTools: [...(skill.allowedTools || [])],
      preferredProviderId: skill.preferredProviderId || '',
      metadata: { ...(skill.metadata || {}) }
    }
  } else if (props.mode === 'create') {
    formData.value = {
      id: '',
      name: '',
      description: '',
      version: '1.0.0',
      systemPrompt: '',
      allowedTools: [],
      preferredProviderId: '',
      metadata: {}
    }
  }
  idTouched.value = props.mode === 'edit'
}, { immediate: true })

function slugifyName(name: string): string {
  return name
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9\s-_]/g, '')
    .replace(/[\s-_]+/g, '-')
}

// 根据名称自动生成 ID（仅创建或复制时，且用户未手动修改过 ID）
watch(() => formData.value.name, (name) => {
  if (!name || props.mode === 'edit' || idTouched.value) return
  formData.value.id = slugifyName(name)
})

// 表单验证
function validate(): boolean {
  errors.value = {}
  
  if (!formData.value.id.trim()) {
    errors.value.id = '请为能力设置一个唯一 ID，方便系统识别'
  } else if (!/^[a-z0-9-_]+$/.test(formData.value.id)) {
    errors.value.id = 'ID 只能包含小写字母、数字、连字符和下划线'
  }
  
  if (!formData.value.name.trim()) {
    errors.value.name = '给这个能力起个名字吧，方便你在列表中识别它'
  }
  
  if (!formData.value.systemPrompt.trim()) {
    errors.value.systemPrompt = '建议为能力补充一段系统提示，帮助它稳定理解自己的角色和行为'
  }
  
  return Object.keys(errors.value).length === 0
}

async function handleSubmit() {
  if (!validate()) return
  
  loading.value = true
  try {
    let skill: SkillDetail
    
    if (props.mode === 'create') {
      skill = await store.createSkill({
        id: formData.value.id,
        name: formData.value.name,
        description: formData.value.description,
        version: formData.value.version,
        systemPrompt: formData.value.systemPrompt,
        allowedTools: formData.value.allowedTools,
        preferredProviderId: formData.value.preferredProviderId || undefined,
        metadata: formData.value.metadata
      })
    } else if (props.mode === 'duplicate') {
      // 复制时先创建新 Skill
      skill = await store.createSkill({
        id: formData.value.id,
        name: formData.value.name,
        description: formData.value.description,
        version: formData.value.version,
        systemPrompt: formData.value.systemPrompt,
        allowedTools: formData.value.allowedTools,
        preferredProviderId: formData.value.preferredProviderId || undefined,
        metadata: formData.value.metadata
      })
    } else {
      // 编辑
      skill = await store.updateSkill(props.skill!.id, {
        name: formData.value.name,
        description: formData.value.description,
        version: formData.value.version,
        systemPrompt: formData.value.systemPrompt,
        allowedTools: formData.value.allowedTools,
        preferredProviderId: formData.value.preferredProviderId || undefined,
        metadata: formData.value.metadata
      })
    }
    
    emit('saved', skill)
    emit('close')
  } catch (e) {
    // 错误已在 store 中处理
  } finally {
    loading.value = false
  }
}

const newToolName = ref('')

function addTool() {
  const tool = newToolName.value.trim()
  if (tool && !formData.value.allowedTools.includes(tool)) {
    formData.value.allowedTools.push(tool)
    newToolName.value = ''
  }
}

function removeTool(tool: string) {
  formData.value.allowedTools = formData.value.allowedTools.filter(t => t !== tool)
}
</script>

<template>
  <div class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="emit('close')">
    <div class="bg-card border border-border rounded-lg shadow-lg w-full max-w-[672px] max-h-[90vh] overflow-y-auto m-md">
      <div class="px-lg py-md">
        <div class="flex items-center justify-between mb-md">
          <div>
          <h2 class="text-xl font-semibold text-foreground">
              {{ mode === 'create' ? '新建能力' : mode === 'edit' ? '编辑能力' : '复制能力' }}
          </h2>
            <p class="mt-xs text-sm text-muted-foreground">
              先填写基础信息，之后可以根据需要展开高级设置进行精细配置。
            </p>
          </div>
          <button
            class="text-muted-foreground hover:text-foreground transition-colors"
            @click="emit('close')"
          >
            ×
          </button>
        </div>

        <form @submit.prevent="handleSubmit" class="space-y-lg">
          <!-- 基础信息 -->
          <div class="space-y-md">
          <!-- 名称 -->
          <div>
              <label class="block text-sm font-medium text-foreground mb-xs">
              名称 <span class="text-destructive">*</span>
            </label>
            <input
              v-model="formData.name"
              type="text"
                class="w-full px-md py-sm border border-input rounded-2xl bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              :class="{ 'border-destructive': errors.name }"
                placeholder="例如：帮我规划一周目标"
            />
              <p v-if="errors.name" class="text-xs text-destructive mt-xs">{{ errors.name }}</p>
          </div>

          <!-- 描述 -->
          <div>
              <label class="block text-sm font-medium text-foreground mb-xs">用途描述</label>
            <textarea
              v-model="formData.description"
              rows="2"
                class="w-full px-md py-sm border border-input rounded-2xl bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                placeholder="用一两句话说明这个能力主要帮你做什么，方便你和系统理解它的用途"
              />
            </div>
          </div>

          <!-- 高级设置 -->
          <div class="border border-border rounded-2xl p-md bg-muted/40">
            <button
              type="button"
              class="w-full flex items-center justify-between text-left text-sm font-medium text-foreground"
              @click="showAdvanced = !showAdvanced"
            >
              <span>高级设置（仅在你了解含义时再修改）</span>
              <span class="text-xs text-muted-foreground">
                {{ showAdvanced ? '收起' : '展开' }}
              </span>
            </button>

            <div v-if="showAdvanced" class="mt-md space-y-md text-sm">
              <!-- ID -->
              <div>
                <label class="block text-xs font-medium text-foreground mb-xs">
                  能力 ID <span class="text-destructive">*</span>
                </label>
                <input
                  v-model="formData.id"
                  type="text"
                  :disabled="mode === 'edit'"
                  class="w-full px-md py-sm border border-input rounded-lg bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-50"
                  :class="{ 'border-destructive': errors.id }"
                  placeholder="例如：weekly-planner"
                  @input="idTouched.value = true"
                />
                <p v-if="errors.id" class="text-xs text-destructive mt-xs">{{ errors.id }}</p>
                <p v-else class="text-xs text-muted-foreground mt-xs">
                  仅系统内部使用，自动根据名称生成，可在创建前按需微调。
                </p>
          </div>

          <!-- 版本 -->
          <div>
                <label class="block text-xs font-medium text-foreground mb-xs">版本号</label>
            <input
              v-model="formData.version"
              type="text"
                  class="w-full px-md py-sm border border-input rounded-lg bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                  placeholder="例如：1.0.0"
            />
          </div>

          <!-- 系统提示 -->
          <div>
                <label class="block text-xs font-medium text-foreground mb-xs">
                  系统提示（System Prompt）<span class="text-destructive">*</span>
            </label>
            <textarea
              v-model="formData.systemPrompt"
              rows="6"
                  class="w-full px-md py-sm border border-input rounded-2xl bg-background text-foreground font-mono text-xs focus:outline-none focus:ring-2 focus:ring-ring"
              :class="{ 'border-destructive': errors.systemPrompt }"
                  placeholder="用系统视角描述这个能力的角色、目标和应遵循的规则，例如：你是一名擅长时间管理的助手，帮助用户将模糊愿望拆解成可执行计划..."
            />
                <p v-if="errors.systemPrompt" class="text-xs text-destructive mt-xs">
                  {{ errors.systemPrompt }}
                </p>
          </div>

          <!-- 允许的工具 -->
          <div>
                <label class="block text-xs font-medium text-foreground mb-xs">允许调用的内部工具</label>
                <div class="flex flex-wrap gap-xs mb-xs">
              <span
                v-for="tool in formData.allowedTools"
                :key="tool"
                    class="inline-flex items-center gap-xs px-sm py-xs bg-accent text-accent-foreground rounded-lg text-xs"
              >
                {{ tool }}
                <button
                  type="button"
                  class="hover:text-destructive"
                  @click="removeTool(tool)"
                    >
                      ×
                    </button>
              </span>
            </div>
                <div class="flex items-center gap-xs">
                  <input
                    v-model="newToolName"
                    type="text"
                    class="flex-1 px-md py-xs border border-input rounded-lg bg-background text-foreground text-xs focus:outline-none focus:ring-2 focus:ring-ring"
                    placeholder="输入工具 ID，例如：todo-manager 或 calendar"
                  />
            <button
              type="button"
                    class="px-md py-xs text-xs border border-input rounded-lg hover:bg-accent transition-colors"
              @click="addTool"
                  >
                    添加
                  </button>
                </div>
                <p class="text-xs text-muted-foreground mt-xs">
                  可选。仅当你希望此能力显式调用某些内部工具时再配置，例如待办、日程等。
                </p>
          </div>

          <!-- 首选 Provider -->
          <div>
                <label class="block text-xs font-medium text-foreground mb-xs">首选 Provider ID</label>
            <input
              v-model="formData.preferredProviderId"
              type="text"
                  class="w-full px-md py-sm border border-input rounded-lg bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring text-xs"
                  placeholder="例如：openai-gpt-4，通常保持默认即可"
            />
              </div>
            </div>
          </div>

          <!-- 操作按钮 -->
          <div class="flex justify-end gap-sm pt-md border-t border-border">
            <button
              type="button"
              class="px-md py-sm text-sm border border-input rounded-lg hover:bg-accent transition-colors"
              @click="emit('close')"
            >
              取消
            </button>
            <button
              type="submit"
              :disabled="loading"
              class="px-md py-sm text-sm bg-primary text-primary-foreground rounded-lg hover:bg-primary/90 transition-colors disabled:opacity-50"
            >
              {{ loading ? '保存中...' : '保存' }}
            </button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>
