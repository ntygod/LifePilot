<script setup lang="ts">
/**
 * 创建项目对话框 —— Plan 1 Task 19 + Plan 2 polish（初始文件上传落地）。
 *
 * 职责：
 * - 收集项目名（必填，≤ 64 字符）
 * - 高级设置（默认折叠）：记忆隔离模式、项目指示词（≤ 1000 字）、初始文件
 * - 提交：先创建项目（后端自动建"项目默认知识库"），再依次把选中的文件
 *   上传到该 KB；文件上传失败不回滚项目，只在界面上提示部分失败
 *
 * 设计说明：
 * - 文件选择走隐藏 {@code <input type="file" multiple>} + 包装 Button 触发
 * - 支持的类型与后端 {@code KnowledgeBaseController.ALLOWED_EXTENSIONS} 对齐
 *   （.pdf / .docx / .md / .txt），不匹配的文件直接在前端过滤并提示
 * - 上传串行化避免并发写向量索引，出错时保留已上传部分
 * - 测试覆盖参见 {@code CreateProjectDialog.spec.ts}
 *
 * @author zsg
 * @since 2026-04-24
 */
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ChevronDown, ChevronRight, Paperclip, X } from 'lucide-vue-next'
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
import { knowledgeBaseApi } from '@/api/client'
import type { ProjectIsolation } from '@/api/project'

const props = withDefaults(defineProps<{ open?: boolean }>(), { open: false })
const emit = defineEmits<{ 'update:open': [value: boolean] }>()

const router = useRouter()
const store = useProjectStore()

/** 项目名上限，与后端 CreateProjectRequest 校验保持一致 */
const NAME_MAX = 64
/** 指示词上限 */
const INSTRUCTIONS_MAX = 1000
/**
 * 允许的文件扩展名（小写） —— 与后端
 * {@code KnowledgeBaseController.ALLOWED_EXTENSIONS} 对齐。
 */
const ALLOWED_EXTENSIONS = ['.pdf', '.docx', '.md', '.txt'] as const
/** 前端 input[accept] 值：用相同的扩展名列表 */
const ACCEPT_ATTR = ALLOWED_EXTENSIONS.join(',')

const name = ref('')
const instructions = ref('')
const isolation = ref<ProjectIsolation>('ISOLATED')
const advancedOpen = ref(false)
const submitting = ref(false)
const submitError = ref<string | null>(null)
const selectedFiles = ref<File[]>([])
const fileInputRef = ref<HTMLInputElement | null>(null)

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
  selectedFiles.value = []
  // 不重置 fileInputRef：它由 template ref 自动管理
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

/** 是否是允许的扩展名（大小写不敏感） */
function hasAllowedExtension(fileName: string): boolean {
  const lower = fileName.toLowerCase()
  return ALLOWED_EXTENSIONS.some(ext => lower.endsWith(ext))
}

/** 触发隐藏 input 的点击 —— 浏览器原生打开文件选择框 */
function triggerFilePicker() {
  fileInputRef.value?.click()
}

/**
 * input[type=file] 的 change 事件处理。
 *
 * - 去掉扩展名不匹配的文件（前端友好提示 + 与后端校验对齐，避免提交后再 400）
 * - 与已有列表去重（按 name + size，浏览器通常不会暴露稳定指纹，这是实用近似）
 * - 选完后清空 input.value，允许同一个文件被重新选择触发 change
 */
function onFilesSelected(event: Event) {
  const target = event.target as HTMLInputElement
  const picked = Array.from(target.files ?? [])
  const rejected: string[] = []
  const accepted: File[] = []
  for (const f of picked) {
    if (!hasAllowedExtension(f.name)) {
      rejected.push(f.name)
      continue
    }
    const duplicate = selectedFiles.value.some(
      existing => existing.name === f.name && existing.size === f.size,
    )
    if (!duplicate) accepted.push(f)
  }
  selectedFiles.value = [...selectedFiles.value, ...accepted]
  if (rejected.length > 0) {
    submitError.value = `以下文件格式不支持，已忽略：${rejected.join('、')}`
  } else {
    submitError.value = null
  }
  // 清空 input，允许再次选择同一个文件触发 change
  target.value = ''
}

/** 从待上传列表移除指定下标的文件 */
function removeFileAt(index: number) {
  selectedFiles.value = selectedFiles.value.filter((_, i) => i !== index)
}

/**
 * 把文件大小格式化为人类可读（KB / MB）。用于已选文件列表的辅助展示。
 */
function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/**
 * 串行上传文件到项目默认知识库。
 *
 * @returns 成功数 + 失败文件列表；全部成功时 failures 为空
 */
async function uploadFilesToKb(
  kbId: string,
  files: File[],
): Promise<{ successCount: number; failures: Array<{ name: string; message: string }> }> {
  let successCount = 0
  const failures: Array<{ name: string; message: string }> = []
  for (const file of files) {
    try {
      await knowledgeBaseApi.uploadDocument(kbId, file)
      successCount++
    } catch (err: any) {
      failures.push({
        name: file.name,
        message: err?.message ?? '上传失败',
      })
    }
  }
  return { successCount, failures }
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

    // 文件上传路径 —— 项目创建成功后才执行，失败不回滚项目
    if (selectedFiles.value.length > 0) {
      const kbId = created.knowledgeBaseIds?.[0]
      if (!kbId) {
        // 后端未自动建 KB（例如 KB 功能未启用），提示但不阻塞项目跳转
        submitError.value = '项目已创建，但未能创建默认知识库，文件未上传'
        emit('update:open', false)
        await router.push(`/projects/${created.id}`)
        return
      }
      const { successCount, failures } = await uploadFilesToKb(kbId, selectedFiles.value)
      if (failures.length > 0) {
        // 部分失败：不关闭对话框，保留错误提示让用户感知；项目已建，不回滚
        const names = failures.map(f => f.name).join('、')
        submitError.value = successCount > 0
          ? `项目已创建，${successCount} 个文件已上传，以下失败：${names}`
          : `项目已创建，但所有文件上传失败：${names}`
        return
      }
    }

    emit('update:open', false)
    await router.push(`/projects/${created.id}`)
  } catch (err: any) {
    submitError.value = err?.message ?? '创建项目失败'
  } finally {
    submitting.value = false
  }
}

function handleCancel() {
  emit('update:open', false)
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

            <!-- 文件 —— 上传到项目默认知识库 -->
            <div class="flex flex-col gap-xs">
              <Label>文件</Label>
              <!-- 隐藏的真实文件输入 —— 通过按钮转发 click；保持在 template 里而不是 document 上挂载 -->
              <input
                ref="fileInputRef"
                type="file"
                multiple
                :accept="ACCEPT_ATTR"
                class="hidden"
                data-testid="create-project-file-input"
                @change="onFilesSelected"
              />
              <Button
                type="button"
                variant="outline"
                size="sm"
                class="self-start"
                :disabled="submitting"
                data-testid="create-project-add-file"
                @click="triggerFilePicker"
              >
                <Paperclip class="size-md" />
                添加文件
              </Button>
              <span class="text-xs text-muted-foreground">
                上传后作为项目知识库的初始资料；支持 PDF / Word / Markdown / TXT
              </span>
              <!-- 已选文件列表 -->
              <ul
                v-if="selectedFiles.length > 0"
                class="flex flex-col gap-xs"
                data-testid="create-project-file-list"
              >
                <li
                  v-for="(file, index) in selectedFiles"
                  :key="`${file.name}-${file.size}-${index}`"
                  class="flex items-center justify-between gap-sm rounded-md border border-border/60 bg-muted/30 px-sm py-xs"
                  :data-testid="`create-project-file-item-${index}`"
                >
                  <div class="flex min-w-0 flex-1 flex-col">
                    <span class="truncate text-sm">{{ file.name }}</span>
                    <span class="text-xs text-muted-foreground">{{ formatFileSize(file.size) }}</span>
                  </div>
                  <button
                    type="button"
                    class="flex items-center justify-center rounded-sm text-muted-foreground hover:text-foreground"
                    :disabled="submitting"
                    :aria-label="`移除 ${file.name}`"
                    :data-testid="`create-project-file-remove-${index}`"
                    @click="removeFileAt(index)"
                  >
                    <X class="size-md" />
                  </button>
                </li>
              </ul>
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
