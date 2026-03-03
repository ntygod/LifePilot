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
}, { immediate: true })

// 表单验证
function validate(): boolean {
  errors.value = {}
  
  if (!formData.value.id.trim()) {
    errors.value.id = 'ID 不能为空'
  } else if (!/^[a-z0-9-_]+$/.test(formData.value.id)) {
    errors.value.id = 'ID 只能包含小写字母、数字、连字符和下划线'
  }
  
  if (!formData.value.name.trim()) {
    errors.value.name = '名称不能为空'
  }
  
  if (!formData.value.systemPrompt.trim()) {
    errors.value.systemPrompt = '系统提示不能为空'
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

function addTool() {
  const tool = prompt('请输入工具名称:')
  if (tool && !formData.value.allowedTools.includes(tool)) {
    formData.value.allowedTools.push(tool)
  }
}

function removeTool(tool: string) {
  formData.value.allowedTools = formData.value.allowedTools.filter(t => t !== tool)
}
</script>

<template>
  <div class="fixed inset-0 bg-black/50 flex items-center justify-center z-50" @click.self="emit('close')">
    <div class="bg-card border border-border rounded-lg shadow-lg w-full max-w-[672px] max-h-[90vh] overflow-y-auto m-4">
      <div class="p-6">
        <div class="flex items-center justify-between mb-6">
          <h2 class="text-xl font-semibold text-foreground">
            {{ mode === 'create' ? '新建 Skill' : mode === 'edit' ? '编辑 Skill' : '复制 Skill' }}
          </h2>
          <button
            class="text-muted-foreground hover:text-foreground transition-colors"
            @click="emit('close')"
          >×</button>
        </div>

        <form @submit.prevent="handleSubmit" class="space-y-4">
          <!-- ID -->
          <div>
            <label class="block text-sm font-medium text-foreground mb-1">
              ID <span class="text-destructive">*</span>
            </label>
            <input
              v-model="formData.id"
              type="text"
              :disabled="mode === 'edit'"
              class="w-full px-3 py-2 border border-input rounded-md bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-50"
              :class="{ 'border-destructive': errors.id }"
              placeholder="例如: my-custom-skill"
            />
            <p v-if="errors.id" class="text-xs text-destructive mt-1">{{ errors.id }}</p>
            <p v-else class="text-xs text-muted-foreground mt-1">只能包含小写字母、数字、连字符和下划线</p>
          </div>

          <!-- 名称 -->
          <div>
            <label class="block text-sm font-medium text-foreground mb-1">
              名称 <span class="text-destructive">*</span>
            </label>
            <input
              v-model="formData.name"
              type="text"
              class="w-full px-3 py-2 border border-input rounded-md bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              :class="{ 'border-destructive': errors.name }"
              placeholder="例如: 我的自定义技能"
            />
            <p v-if="errors.name" class="text-xs text-destructive mt-1">{{ errors.name }}</p>
          </div>

          <!-- 描述 -->
          <div>
            <label class="block text-sm font-medium text-foreground mb-1">描述</label>
            <textarea
              v-model="formData.description"
              rows="2"
              class="w-full px-3 py-2 border border-input rounded-md bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              placeholder="Skill 的简要描述"
            />
          </div>

          <!-- 版本 -->
          <div>
            <label class="block text-sm font-medium text-foreground mb-1">版本</label>
            <input
              v-model="formData.version"
              type="text"
              class="w-full px-3 py-2 border border-input rounded-md bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              placeholder="例如: 1.0.0"
            />
          </div>

          <!-- 系统提示 -->
          <div>
            <label class="block text-sm font-medium text-foreground mb-1">
              系统提示 <span class="text-destructive">*</span>
            </label>
            <textarea
              v-model="formData.systemPrompt"
              rows="6"
              class="w-full px-3 py-2 border border-input rounded-md bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring font-mono text-sm"
              :class="{ 'border-destructive': errors.systemPrompt }"
              placeholder="定义 Skill 的行为和角色..."
            />
            <p v-if="errors.systemPrompt" class="text-xs text-destructive mt-1">{{ errors.systemPrompt }}</p>
          </div>

          <!-- 允许的工具 -->
          <div>
            <label class="block text-sm font-medium text-foreground mb-1">允许的工具</label>
            <div class="flex flex-wrap gap-2 mb-2">
              <span
                v-for="tool in formData.allowedTools"
                :key="tool"
                class="inline-flex items-center gap-1 px-2 py-1 bg-accent text-accent-foreground rounded-md text-sm"
              >
                {{ tool }}
                <button
                  type="button"
                  class="hover:text-destructive"
                  @click="removeTool(tool)"
                >×</button>
              </span>
            </div>
            <button
              type="button"
              class="text-sm px-3 py-1 border border-input rounded-md hover:bg-accent transition-colors"
              @click="addTool"
            >+ 添加工具</button>
          </div>

          <!-- 首选 Provider -->
          <div>
            <label class="block text-sm font-medium text-foreground mb-1">首选 Provider ID</label>
            <input
              v-model="formData.preferredProviderId"
              type="text"
              class="w-full px-3 py-2 border border-input rounded-md bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              placeholder="例如: openai-gpt-4"
            />
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
