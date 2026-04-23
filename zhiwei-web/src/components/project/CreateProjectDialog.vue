<script setup lang="ts">
/**
 * 创建项目对话框 —— Plan 1 Task 19。
 *
 * 职责：
 * - 收集项目名（必填，≤ 64 字符）
 * - 高级设置（默认折叠）：记忆隔离模式、项目指示词（≤ 1000 字）、文件占位
 * - 提交时调用 {@link useProjectStore.createProject}，成功后关闭并导航到
 *   `/projects/:id`（路由实装在 Task 20+）
 *
 * 设计说明：
 * - 使用 v-model:open 与父组件解耦，打开时主动重置表单状态
 * - 文件上传是 Plan 2+ 特性，此处仅保留占位按钮，点击无副作用
 * - 测试覆盖参见 {@code CreateProjectDialog.spec.ts}
 *
 * @author zsg
 * @since 2026-04-23
 */
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ChevronDown, ChevronRight, Paperclip } from 'lucide-vue-next'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { useProjectStore } from '@/stores/project'
import type { ProjectIsolation } from '@/api/project'

const props = withDefaults(defineProps<{ open?: boolean }>(), { open: false })
const emit = defineEmits<{ 'update:open': [value: boolean] }>()

const router = useRouter()
const store = useProjectStore()

/** 项目名上限，与后端 CreateProjectRequest 校验保持一致 */
const NAME_MAX = 64
/** 指示词上限 */
const INSTRUCTIONS_MAX = 1000

const name = ref('')
const instructions = ref('')
const isolation = ref<ProjectIsolation>('ISOLATED')
const advancedOpen = ref(false)
const submitting = ref(false)
const submitError = ref<string | null>(null)

const isolationOptions: Array<{ value: ProjectIsolation; label: string; hint: string }> = [
  { value: 'ISOLATED', label: '隔离', hint: '项目内记忆独立，不与其他会话共享' },
  { value: 'SHARED', label: '共享', hint: '与默认空间共享记忆，跨项目可见' },
]

const instructionsCount = computed(() => instructions.value.length)
const trimmedName = computed(() => name.value.trim())
const canSubmit = computed(() =>
  !submitting.value
  && trimmedName.value.length > 0
  && trimmedName.value.length <= NAME_MAX
  && instructionsCount.value <= INSTRUCTIONS_MAX,
)

/** 重置所有字段回到初始态 —— 每次打开对话框前调用 */
function resetForm() {
  name.value = ''
  instructions.value = ''
  isolation.value = 'ISOLATED'
  advancedOpen.value = false
  submitting.value = false
  submitError.value = null
}

watch(
  () => props.open,
  open => {
    if (open) {
      resetForm()
    }
  },
)

function handleOpenChange(value: boolean) {
  emit('update:open', value)
}

async function handleSubmit() {
  if (!canSubmit.value) return
  submitting.value = true
  submitError.value = null
  try {
    const created = await store.createProject({
      name: trimmedName.value,
      instructions: instructions.value.trim(),
      isolation: isolation.value,
    })
    emit('update:open', false)
    // 导航失败不应阻断用户 —— Task 20 实装路由前这里会命中通配 404 fallback
    router.push(`/projects/${created.id}`).catch(() => {})
  } catch (err: any) {
    submitError.value = err?.message ?? '创建项目失败'
  } finally {
    submitting.value = false
  }
}

function handleCancel() {
  emit('update:open', false)
}

/** 文件上传占位 —— 后续（Plan 2+ 或项目文件 Phase）实装时替换 */
function handleAddFilePlaceholder() {
  // 故意留空：Plan 1 只展示入口，不实现上传
}
</script>

<template>
  <Dialog :open="props.open" @update:open="handleOpenChange">
    <DialogContent class="sm:max-w-[32rem]" data-testid="create-project-dialog">
      <DialogHeader>
        <DialogTitle class="text-lg font-semibold tracking-tight">
          新建项目
        </DialogTitle>
        <DialogDescription class="text-sm leading-6 text-muted-foreground">
          项目用来把相关对话、记忆和资料聚在一起。稍后可以在项目里补充更多信息。
        </DialogDescription>
      </DialogHeader>

      <div class="flex flex-col gap-md">
        <!-- 项目名 -->
        <div class="flex flex-col gap-xs">
          <Label for="create-project-name">
            项目名
            <span class="text-destructive">*</span>
          </Label>
          <Input
            id="create-project-name"
            v-model="name"
            :maxlength="NAME_MAX"
            placeholder="例如：毕业论文-MT 评估"
            autofocus
            data-testid="create-project-name"
            @keyup.enter="handleSubmit"
          />
        </div>

        <!-- 高级设置折叠 -->
        <div class="flex flex-col gap-sm">
          <button
            type="button"
            class="flex items-center gap-xs text-sm text-muted-foreground hover:text-foreground transition-colors"
            data-testid="create-project-advanced-toggle"
            @click="advancedOpen = !advancedOpen"
          >
            <ChevronDown v-if="advancedOpen" class="size-md" />
            <ChevronRight v-else class="size-md" />
            高级设置
          </button>

          <div v-if="advancedOpen" class="flex flex-col gap-md pl-md" data-testid="create-project-advanced-panel">
            <!-- 记忆隔离 -->
            <div class="flex flex-col gap-xs">
              <Label>记忆</Label>
              <div class="flex flex-col gap-xs">
                <label
                  v-for="option in isolationOptions"
                  :key="option.value"
                  class="flex items-start gap-sm cursor-pointer rounded-md px-xs py-xs hover:bg-accent/40"
                >
                  <input
                    v-model="isolation"
                    type="radio"
                    name="project-isolation"
                    :value="option.value"
                    class="mt-1 size-md accent-primary cursor-pointer"
                    :data-testid="`isolation-${option.value.toLowerCase()}`"
                  />
                  <div class="flex flex-col">
                    <span class="text-sm">{{ option.label }}</span>
                    <span class="text-xs text-muted-foreground">{{ option.hint }}</span>
                  </div>
                </label>
              </div>
            </div>

            <!-- 指示词 -->
            <div class="flex flex-col gap-xs">
              <div class="flex items-center justify-between">
                <Label for="create-project-instructions">指示词</Label>
                <span
                  class="text-xs"
                  :class="instructionsCount > INSTRUCTIONS_MAX ? 'text-destructive' : 'text-muted-foreground'"
                >
                  {{ instructionsCount }} / {{ INSTRUCTIONS_MAX }}
                </span>
              </div>
              <Textarea
                id="create-project-instructions"
                v-model="instructions"
                :maxlength="INSTRUCTIONS_MAX"
                rows="4"
                placeholder="AI 应该了解这个项目的哪些信息？（例如：具体规则、语气或格式）"
                data-testid="create-project-instructions"
              />
            </div>

            <!-- 文件（Plan 1 占位，后续 Phase 实装真实上传能力） -->
            <div class="flex flex-col gap-xs">
              <Label>文件</Label>
              <Button
                type="button"
                variant="outline"
                size="sm"
                class="self-start"
                data-testid="create-project-add-file"
                @click="handleAddFilePlaceholder"
              >
                <Paperclip class="size-md" />
                添加文件
              </Button>
              <span class="text-xs text-muted-foreground">文件上传将在后续迭代中提供</span>
            </div>
          </div>
        </div>

        <!-- 提交错误提示 -->
        <div
          v-if="submitError"
          class="rounded-md border border-destructive/40 bg-destructive/10 px-md py-xs text-sm text-destructive"
          data-testid="create-project-error"
        >
          {{ submitError }}
        </div>
      </div>

      <DialogFooter class="gap-sm">
        <Button
          type="button"
          variant="outline"
          :disabled="submitting"
          data-testid="create-project-cancel"
          @click="handleCancel"
        >
          取消
        </Button>
        <Button
          type="button"
          :disabled="!canSubmit"
          data-testid="create-project-submit"
          @click="handleSubmit"
        >
          {{ submitting ? '创建中...' : '创建项目' }}
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
