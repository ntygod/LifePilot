<script setup lang="ts">
import { ref, watch } from 'vue'
import type { SkillDetail } from '@/types'
import FormDialogShell from '@/components/common/FormDialogShell.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { useSkillStore } from '@/stores/skill'
import { useUiStore } from '@/stores/ui'

const props = defineProps<{
  skill?: SkillDetail | null
  mode: 'create' | 'edit' | 'duplicate'
}>()

const emit = defineEmits<{
  close: []
  saved: [skill: SkillDetail]
}>()

const store = useSkillStore()
const uiStore = useUiStore()

const formData = ref({
  id: '',
  name: '',
  description: '',
  version: '1.0.0',
  instructions: '',
  suggestedTools: [] as string[],
  metadata: {} as Record<string, string>,
})

const showAdvanced = ref(false)
const idTouched = ref(false)
const loading = ref(false)
const errors = ref<Record<string, string>>({})
const newToolName = ref('')

watch(() => props.skill, (skill) => {
  if (skill) {
    formData.value = {
      id: props.mode === 'duplicate' ? '' : skill.id,
      name: props.mode === 'duplicate' ? `${skill.name} 副本` : skill.name,
      description: skill.description || '',
      version: skill.version || '1.0.0',
      instructions: skill.instructions || '',
      suggestedTools: [...(skill.suggestedTools || [])],
      metadata: { ...(skill.metadata || {}) },
    }
  } else {
    formData.value = {
      id: '',
      name: '',
      description: '',
      version: '1.0.0',
      instructions: '',
      suggestedTools: [],
      metadata: {},
    }
  }

  showAdvanced.value = props.mode !== 'create'
  idTouched.value = props.mode === 'edit'
}, { immediate: true })

function slugifyName(name: string) {
  return name
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9\s_-]/g, '')
    .replace(/[\s_-]+/g, '-')
}

watch(() => formData.value.name, (name) => {
  if (!name || props.mode === 'edit' || idTouched.value) {
    return
  }

  formData.value.id = slugifyName(name)
})

function validate() {
  errors.value = {}

  if (!formData.value.id.trim()) {
    errors.value.id = '技能 ID 不能为空'
  } else if (!/^[a-z0-9-_]+$/.test(formData.value.id)) {
    errors.value.id = '技能 ID 只能包含小写字母、数字、连字符和下划线'
  }

  if (!formData.value.name.trim()) {
    errors.value.name = '请填写技能名称'
  }

  if (!formData.value.instructions.trim()) {
    errors.value.instructions = '请补充技能指令，便于系统理解这个能力的角色'
  }

  return Object.keys(errors.value).length === 0
}

async function handleSubmit() {
  if (!validate()) {
    uiStore.showToast('error', '请先修正表单中的错误项')
    return
  }

  loading.value = true

  try {
    const payload = {
      id: formData.value.id,
      name: formData.value.name,
      description: formData.value.description,
      version: formData.value.version,
      instructions: formData.value.instructions,
      suggestedTools: formData.value.suggestedTools,
      metadata: formData.value.metadata,
    }

    const skill = props.mode === 'edit'
      ? await store.updateSkill(props.skill!.id, {
        name: payload.name,
        description: payload.description,
        version: payload.version,
        instructions: payload.instructions,
        suggestedTools: payload.suggestedTools,
        metadata: payload.metadata,
      })
      : await store.createSkill(payload)

    emit('saved', skill)
    emit('close')
  } catch (error) {
    uiStore.showToast('error', error instanceof Error ? error.message : '保存技能失败')
  } finally {
    loading.value = false
  }
}

function addTool() {
  const tool = newToolName.value.trim()

  if (!tool || formData.value.suggestedTools.includes(tool)) {
    return
  }

  formData.value.suggestedTools.push(tool)
  newToolName.value = ''
}

function removeTool(tool: string) {
  formData.value.suggestedTools = formData.value.suggestedTools.filter(item => item !== tool)
}
</script>

<template>
  <FormDialogShell
    :title="mode === 'create' ? '新建技能' : mode === 'edit' ? '编辑技能' : '复制技能'"
    description="先填写基础信息，再按需补充高级配置。"
    content-class="sm:max-w-[720px]"
    body-class="space-y-6"
    @close="emit('close')"
  >
    <form id="skill-form" class="space-y-6" @submit.prevent="handleSubmit">
      <section class="space-y-4">
        <div class="space-y-2">
          <label class="text-sm font-medium text-foreground">
            名称 <span class="text-destructive">*</span>
          </label>
          <Input
            v-model="formData.name"
            :class="{ 'border-destructive': errors.name }"
            placeholder="例如：每周规划助手"
          />
          <p v-if="errors.name" class="text-xs text-destructive">{{ errors.name }}</p>
        </div>

        <div class="space-y-2">
          <label class="text-sm font-medium text-foreground">描述</label>
          <Textarea
            v-model="formData.description"
            rows="3"
            placeholder="一句话说明这个技能擅长帮助用户完成什么任务"
          />
        </div>
      </section>

      <section class="rounded-2xl border border-border/70 bg-muted/25 p-4">
        <button
          type="button"
          class="flex w-full items-center justify-between text-left"
          @click="showAdvanced = !showAdvanced"
        >
          <div>
            <p class="text-sm font-medium text-foreground">高级配置</p>
            <p class="mt-1 text-xs text-muted-foreground">仅在你了解字段含义时再修改这些内容。</p>
          </div>
          <span class="text-xs text-muted-foreground">{{ showAdvanced ? '收起' : '展开' }}</span>
        </button>

        <div v-if="showAdvanced" class="mt-4 space-y-4">
          <div class="grid gap-4 md:grid-cols-2">
            <div class="space-y-2">
              <label class="text-sm font-medium text-foreground">
                技能 ID <span class="text-destructive">*</span>
              </label>
              <Input
                v-model="formData.id"
                :disabled="mode === 'edit'"
                :class="{ 'border-destructive': errors.id }"
                placeholder="例如：weekly-planner"
                @input="idTouched = true"
              />
              <p v-if="errors.id" class="text-xs text-destructive">{{ errors.id }}</p>
              <p v-else class="text-xs text-muted-foreground">
                创建时会根据名称自动生成，你也可以在提交前手动调整。
              </p>
            </div>

            <div class="space-y-2">
              <label class="text-sm font-medium text-foreground">版本号</label>
              <Input
                v-model="formData.version"
                placeholder="例如：1.0.0"
              />
            </div>
          </div>

          <div class="space-y-2">
            <label class="text-sm font-medium text-foreground">
              Instructions <span class="text-destructive">*</span>
            </label>
            <Textarea
              v-model="formData.instructions"
              rows="8"
              class="font-mono text-xs"
              :class="{ 'border-destructive': errors.instructions }"
            placeholder="用系统视角描述这个技能的角色、目标和应遵循的规则。"
            />
            <p v-if="errors.instructions" class="text-xs text-destructive">{{ errors.instructions }}</p>
          </div>

          <div class="space-y-3">
            <label class="text-sm font-medium text-foreground">建议工具</label>
            <div v-if="formData.suggestedTools.length > 0" class="flex flex-wrap gap-2">
              <span
                v-for="tool in formData.suggestedTools"
                :key="tool"
                class="inline-flex items-center gap-2 rounded-full border border-border/70 bg-background/80 px-3 py-1 text-xs text-foreground"
              >
                {{ tool }}
                <button type="button" class="text-muted-foreground hover:text-destructive" @click="removeTool(tool)">
                  ×
                </button>
              </span>
            </div>

            <div class="flex gap-2">
              <Input
                v-model="newToolName"
                class="flex-1"
                placeholder="输入工具 ID，例如：todo-manager"
              />
              <Button type="button" variant="outline" @click="addTool">
                添加
              </Button>
            </div>
          </div>
        </div>
      </section>
    </form>

    <template #footer>
      <div class="flex justify-end gap-3">
        <Button variant="outline" @click="emit('close')">
          取消
        </Button>
        <Button form="skill-form" type="submit" :disabled="loading">
          {{ loading ? '保存中...' : '保存技能' }}
        </Button>
      </div>
    </template>
  </FormDialogShell>
</template>
