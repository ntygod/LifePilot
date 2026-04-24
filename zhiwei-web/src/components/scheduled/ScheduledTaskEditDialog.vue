<script setup lang="ts">
/**
 * 定时任务编辑对话框 —— Plan 2+3 Task A5 UI 增强。
 *
 * <p>职责：编辑已有定时任务的 name / schedule / instruction 字段。
 * projectId 不可迁移——不在表单里露出；status 通过卡片上的暂停/恢复
 * 快捷按钮处理，不在此表单里。</p>
 *
 * <p>cron 表达式只做**极简**前端校验——Spring 的 6 位 cron 至少要有空格分隔
 * 的 6 段；更复杂的合法性（比如月份字段的取值域）交给后端兜底。前端本意
 * 是防止"空串"/"11111"这类一眼看得出的误触。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
import { computed, ref, watch } from 'vue'
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
import type { ScheduledTaskDto } from '@/api/scheduledTask'
import { useScheduledTaskStore } from '@/stores/scheduledTask'

const props = defineProps<{
  /** 是否打开对话框（v-model:open 语义） */
  open: boolean
  /** 要编辑的任务；关闭态可以为 null */
  task: ScheduledTaskDto | null
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
  /** 编辑成功后派发，便于父组件刷新/反馈 */
  'updated': [task: ScheduledTaskDto]
}>()

const store = useScheduledTaskStore()

/** 任务名最大长度——与后端 CronTaskEntry 的 name 列保持宽松对齐 */
const NAME_MAX = 128
/** 指令最大长度——防止输入框意外黏贴超长内容卡顿 */
const INSTRUCTION_MAX = 2000

const name = ref('')
const schedule = ref('')
const instruction = ref('')
const submitting = ref(false)
const submitError = ref<string | null>(null)

/** cron 表达式极简校验——至少 6 段非空（空格分隔） */
const scheduleInvalid = computed(() => {
  const trimmed = schedule.value.trim()
  if (trimmed.length === 0) return '请输入 cron 表达式'
  const parts = trimmed.split(/\s+/)
  if (parts.length < 6) return 'Spring cron 至少需要 6 段（秒 分 时 日 月 周）'
  return null
})

const nameInvalid = computed(() => {
  const trimmed = name.value.trim()
  if (trimmed.length === 0) return '请输入任务名'
  if (trimmed.length > NAME_MAX) return `任务名不超过 ${NAME_MAX} 字`
  return null
})

const canSubmit = computed(() =>
  !submitting.value && !nameInvalid.value && !scheduleInvalid.value,
)

// 每次打开时从传入 task 初始化
watch(
  () => [props.open, props.task?.id] as const,
  ([open]) => {
    if (open && props.task) {
      name.value = props.task.name
      schedule.value = props.task.schedule
      instruction.value = props.task.instruction
      submitting.value = false
      submitError.value = null
    }
  },
  { immediate: true },
)

function handleOpenChange(value: boolean) {
  emit('update:open', value)
}

async function handleSubmit() {
  if (!canSubmit.value || !props.task) return
  submitting.value = true
  submitError.value = null
  try {
    const updated = await store.updateTask(props.task.id, {
      name: name.value.trim(),
      schedule: schedule.value.trim(),
      instruction: instruction.value,
    })
    emit('updated', updated)
    emit('update:open', false)
  } catch (err: any) {
    submitError.value = err?.message ?? '更新定时任务失败'
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
    <DialogContent class="sm:max-w-[32rem]" data-testid="scheduled-task-edit-dialog">
      <DialogHeader>
        <DialogTitle class="text-lg font-semibold tracking-tight">
          编辑定时任务
        </DialogTitle>
        <DialogDescription class="text-sm leading-6 text-muted-foreground">
          可以修改任务名、执行周期（cron 表达式）和执行指令。项目归属不可迁移。
        </DialogDescription>
      </DialogHeader>

      <div class="flex flex-col gap-md">
        <!-- 任务名 -->
        <div class="flex flex-col gap-xs">
          <Label for="edit-scheduled-task-name">
            任务名 <span class="text-destructive">*</span>
          </Label>
          <Input
            id="edit-scheduled-task-name"
            v-model="name"
            :maxlength="NAME_MAX"
            placeholder="例如：每周日 21 点周报提醒"
            data-testid="edit-scheduled-task-name"
          />
          <span
            v-if="nameInvalid"
            class="text-xs text-destructive"
            data-testid="edit-scheduled-task-name-error"
          >
            {{ nameInvalid }}
          </span>
        </div>

        <!-- cron -->
        <div class="flex flex-col gap-xs">
          <Label for="edit-scheduled-task-schedule">
            执行周期（cron） <span class="text-destructive">*</span>
          </Label>
          <Input
            id="edit-scheduled-task-schedule"
            v-model="schedule"
            placeholder="例如：0 0 21 ? * SUN（每周日 21:00）"
            class="font-mono"
            data-testid="edit-scheduled-task-schedule"
          />
          <span
            v-if="scheduleInvalid"
            class="text-xs text-destructive"
            data-testid="edit-scheduled-task-schedule-error"
          >
            {{ scheduleInvalid }}
          </span>
          <span v-else class="text-xs text-muted-foreground">
            Spring 6 位格式：秒 分 时 日 月 周
          </span>
        </div>

        <!-- 指令 -->
        <div class="flex flex-col gap-xs">
          <Label for="edit-scheduled-task-instruction">
            执行指令
          </Label>
          <Textarea
            id="edit-scheduled-task-instruction"
            v-model="instruction"
            :maxlength="INSTRUCTION_MAX"
            rows="4"
            placeholder="定时触发时，Agent 要做什么？用自然语言描述"
            data-testid="edit-scheduled-task-instruction"
          />
        </div>

        <!-- 提交错误提示 -->
        <div
          v-if="submitError"
          class="rounded-md border border-destructive/40 bg-destructive/10 px-md py-xs text-sm text-destructive"
          data-testid="edit-scheduled-task-error"
        >
          {{ submitError }}
        </div>
      </div>

      <DialogFooter class="gap-sm">
        <Button
          type="button"
          variant="outline"
          :disabled="submitting"
          data-testid="edit-scheduled-task-cancel"
          @click="handleCancel"
        >
          取消
        </Button>
        <Button
          type="button"
          :disabled="!canSubmit"
          data-testid="edit-scheduled-task-submit"
          @click="handleSubmit"
        >
          {{ submitting ? '保存中...' : '保存' }}
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
