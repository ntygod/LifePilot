<script setup lang="ts">
/**
 * 项目设置抽屉 —— Plan 1 Task 22。
 *
 * 职责：
 * - 从右侧滑入，展示并可编辑项目的基本信息：名称 / 指示词
 * - 隔离模式仅作只读展示（创建后不可修改）—— 项目归属不可迁移
 * - 底部「删除项目」按钮触发二次确认，调用 {@link useProjectStore.deleteProject}
 *   并在成功后跳回首页（项目已不存在，不能继续停留）
 *
 * 设计说明：
 * - 复用 {@link FormSheetShell}，视觉与 Task 21 {@code ProjectResourcePanel} 对齐
 * - 可编辑字段：name / instructions；isolation 仅展示原值
 * - 打开抽屉时从 {@link props.project} 拷贝到本地 ref，关闭/重新打开会重置回最新值
 * - 删除用 {@link ConfirmDialog} 二次确认（destructive variant），而非 window.confirm
 *
 * @author zsg
 * @since 2026-04-24
 */
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Trash2 } from 'lucide-vue-next'
import FormSheetShell from '@/components/common/FormSheetShell.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { useProjectStore } from '@/stores/project'
import type { ProjectDto } from '@/api/project'

const props = defineProps<{
  open: boolean
  project: ProjectDto
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
}>()

const store = useProjectStore()
const router = useRouter()

/** 项目名上限，与后端 UpdateProjectRequest / CreateProjectRequest 校验保持一致 */
const NAME_MAX = 64
/** 指示词上限 */
const INSTRUCTIONS_MAX = 1000

const name = ref(props.project.name)
const instructions = ref(props.project.instructions)

const submitting = ref(false)
const deleting = ref(false)
const submitError = ref<string | null>(null)
const showDeleteConfirm = ref(false)

const instructionsCount = computed(() => instructions.value.length)
const trimmedName = computed(() => name.value.trim())
const nameValid = computed(
  () => trimmedName.value.length > 0 && trimmedName.value.length <= NAME_MAX,
)
const instructionsValid = computed(() => instructionsCount.value <= INSTRUCTIONS_MAX)
const canSave = computed(
  () => nameValid.value && instructionsValid.value && !submitting.value,
)

/** 当前隔离模式的中文标签 —— 仅用于只读展示 */
const isolationLabel = computed(() =>
  props.project.isolation === 'ISOLATED' ? '隔离' : '共享',
)

/** 用当前 project prop 重置本地表单 —— 进抽屉 / 外部切换项目时调用 */
function resetFormFromProject() {
  name.value = props.project.name
  instructions.value = props.project.instructions
  submitError.value = null
}

// 外部切换到不同项目时，同步表单
watch(() => props.project, () => {
  resetFormFromProject()
})

// open 由 false → true 时，重新拉取最新 prop.project
watch(() => props.open, isOpen => {
  if (isOpen) {
    resetFormFromProject()
  }
})

function handleOpenChange(value: boolean) {
  emit('update:open', value)
}

async function handleSave() {
  if (!canSave.value) return
  submitting.value = true
  submitError.value = null
  try {
    // 注：isolation 不传；UpdateProjectRequest 将其视为可选，缺省时后端保留原值。
    await store.updateProject(props.project.id, {
      name: trimmedName.value,
      instructions: instructions.value,
    })
    emit('update:open', false)
  } catch (err: any) {
    submitError.value = err?.message ?? '保存失败，请稍后再试'
  } finally {
    submitting.value = false
  }
}

function handleCancel() {
  emit('update:open', false)
}

/** 打开删除确认弹窗；真正的删除在 {@link confirmDelete} 里执行 */
function openDeleteConfirm() {
  showDeleteConfirm.value = true
}

async function confirmDelete() {
  if (deleting.value) return
  deleting.value = true
  submitError.value = null
  try {
    await store.deleteProject(props.project.id)
    showDeleteConfirm.value = false
    emit('update:open', false)
    // 项目已删除，继续停留在详情页毫无意义 —— 回首页
    await router.replace({ name: 'home' })
  } catch (err: any) {
    submitError.value = err?.message ?? '删除失败，请稍后再试'
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <FormSheetShell
    :open="props.open"
    title="项目设置"
    description="修改项目名称或指示词，或删除此项目；记忆模式在创建时确定，无法修改。"
    body-class="flex flex-col gap-lg"
    @update:open="handleOpenChange"
  >
    <!-- 项目名称 -->
    <div class="flex flex-col gap-xs">
      <Label for="project-settings-name">
        项目名称
        <span class="text-destructive">*</span>
      </Label>
      <Input
        id="project-settings-name"
        v-model="name"
        :maxlength="NAME_MAX"
        placeholder="例如：毕业论文-MT 评估"
        data-testid="project-settings-name"
      />
    </div>

    <!-- 指示词 -->
    <div class="flex flex-col gap-xs">
      <div class="flex items-center justify-between">
        <Label for="project-settings-instructions">指示词</Label>
        <span
          class="text-xs"
          :class="instructionsCount > INSTRUCTIONS_MAX ? 'text-destructive' : 'text-muted-foreground'"
        >
          {{ instructionsCount }} / {{ INSTRUCTIONS_MAX }}
        </span>
      </div>
      <Textarea
        id="project-settings-instructions"
        v-model="instructions"
        :maxlength="INSTRUCTIONS_MAX"
        rows="5"
        placeholder="AI 应该了解这个项目的哪些信息？（例如：具体规则、语气或格式）"
        data-testid="project-settings-instructions"
      />
    </div>

    <!-- 记忆隔离（只读：创建后不可修改） -->
    <div class="flex flex-col gap-xs">
      <Label>记忆</Label>
      <div
        class="flex flex-col gap-xs rounded-md border border-border/60 bg-muted/30 px-sm py-xs"
        data-testid="project-settings-isolation-readonly"
      >
        <span class="text-sm text-foreground">
          隔离模式：{{ isolationLabel }}
        </span>
        <span class="text-xs text-muted-foreground">
          创建后不可修改——项目的记忆归属在创建时确定。
        </span>
      </div>
    </div>

    <!-- 错误提示 -->
    <div
      v-if="submitError"
      class="rounded-md border border-destructive/40 bg-destructive/10 px-md py-xs text-sm text-destructive"
      data-testid="project-settings-error"
    >
      {{ submitError }}
    </div>

    <template #footer>
      <div class="flex items-center justify-between gap-sm">
        <Button
          type="button"
          variant="ghost"
          class="text-destructive hover:bg-destructive/10 hover:text-destructive"
          :disabled="deleting || submitting"
          data-testid="project-settings-delete"
          @click="openDeleteConfirm"
        >
          <Trash2 class="size-md" />
          删除项目
        </Button>

        <div class="flex items-center gap-sm">
          <Button
            type="button"
            variant="outline"
            :disabled="submitting"
            data-testid="project-settings-cancel"
            @click="handleCancel"
          >
            取消
          </Button>
          <Button
            type="button"
            :disabled="!canSave"
            data-testid="project-settings-save"
            @click="handleSave"
          >
            {{ submitting ? '保存中...' : '保存' }}
          </Button>
        </div>
      </div>
    </template>
  </FormSheetShell>

  <!-- 删除二次确认（Reka UI AlertDialog，使用 destructive 样式） -->
  <ConfirmDialog
    v-model:show="showDeleteConfirm"
    title="确认删除项目"
    :message="`确定要删除项目「${props.project.name}」吗？项目内的所有对话、记忆和知识关联都会一并清理，此操作不可撤销。`"
    :confirm-label="deleting ? '删除中...' : '删除'"
    confirm-variant="destructive"
    @confirm="confirmDelete"
    @cancel="showDeleteConfirm = false"
  />
</template>
