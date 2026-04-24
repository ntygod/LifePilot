<script setup lang="ts">
/**
 * 定时任务全局管理页 —— Plan 2+3 Task A5 + UI 增强。
 *
 * <p>侧栏一级入口；展示所有定时任务（主账户 + 所有项目，含隔离项目）。</p>
 *
 * <p><b>UI 结构</b>：</p>
 * <ul>
 *   <li>卡片化：一张卡片一个任务，项目 tag + 名称 + 状态 badge + cron + 下次执行时间。</li>
 *   <li>点卡片空白处展开：显示完整指令 + 最近 5 次执行日志。</li>
 *   <li>右上角操作区：暂停/恢复 + 编辑 + 删除。</li>
 * </ul>
 *
 * <p><b>创建入口不在此页</b>：由 LLM 在对话中自然语言触发创建（如"每周日 21 点
 * 提醒我..."），避免引入独立的表单弹窗稀释"帮你做事"的对话入口定位。</p>
 *
 * <p>删除使用轻量 {@code window.confirm}；编辑走
 * {@link ScheduledTaskEditDialog}（Reka UI Dialog）。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Clock,
  Play,
  Pause,
  Pencil,
  Trash2,
  Folder,
  ChevronDown,
  ChevronUp,
  CalendarClock,
} from 'lucide-vue-next'
import { useScheduledTaskStore } from '@/stores/scheduledTask'
import { useProjectStore } from '@/stores/project'
import { formatNextExecutionTime, formatExecutedAtTime } from '@/utils/nextExecutionTime'
import {
  listScheduledTaskLogs,
  type ScheduledTaskDto,
  type ScheduledTaskLogDto,
} from '@/api/scheduledTask'
import { Badge } from '@/components/ui/badge'
import ScheduledTaskEditDialog from '@/components/scheduled/ScheduledTaskEditDialog.vue'

const store = useScheduledTaskStore()
const projectStore = useProjectStore()
const router = useRouter()

onMounted(async () => {
  // 并行拉取，失败只写入各自 store.error，不阻塞另一条
  await Promise.all([
    store.fetchAll().catch(() => {}),
    projectStore.fetchProjects().catch(() => {}),
  ])
})

/** 项目 ID → 名称 的查找表，供 tag 展示使用 */
const projectNameById = computed(() => {
  const map = new Map<string, string>()
  for (const p of projectStore.projects) map.set(p.id, p.name)
  return map
})

/** 主账户任务显示"主"，项目任务显示项目名；项目已删除则退化为 ID */
function projectLabel(projectId: string | null): string {
  if (!projectId) return '主'
  return projectNameById.value.get(projectId) ?? projectId
}

/** 跳转到项目详情页 */
function openProject(projectId: string) {
  router.push({ name: 'projectDetail', params: { id: projectId } })
}

/** 根据当前状态在 active/paused 之间切换 */
async function togglePause(taskId: string, currentStatus: string) {
  if (currentStatus === 'active') {
    await store.pauseTask(taskId)
  } else {
    await store.resumeTask(taskId)
  }
}

/** 删除前弹窗确认；取消则直接返回 */
async function deleteTask(taskId: string, taskName: string) {
  if (!window.confirm(`确认删除定时任务「${taskName}」？此操作不可撤销。`)) return
  await store.deleteTask(taskId)
}

// ---- 展开 / 日志 ----

/** 记录每个任务是否已展开；按 taskId 索引。 */
const expanded = reactive<Record<string, boolean>>({})
/** 已拉取的日志缓存；按 taskId 索引。 */
const logsByTask = reactive<Record<string, ScheduledTaskLogDto[]>>({})
/** 正在加载日志的任务 id 集合——界面上可选显示 loading。 */
const loadingLogs = reactive<Record<string, boolean>>({})
/** 日志加载失败提示；按 taskId 索引。 */
const logErrors = reactive<Record<string, string | null>>({})

/**
 * 点击卡片主体触发展开；展开时懒加载执行日志。
 *
 * <p>已加载过就不再重拉——如需强刷，可以在展开状态下再点一次（收起）
 * 然后再点一次（展开）——此路径不自动触发重拉，保持简单；
 * 后续需要再加"刷新"按钮。</p>
 */
async function toggleExpand(taskId: string) {
  const next = !expanded[taskId]
  expanded[taskId] = next
  if (next && !logsByTask[taskId] && !loadingLogs[taskId]) {
    loadingLogs[taskId] = true
    logErrors[taskId] = null
    try {
      logsByTask[taskId] = await listScheduledTaskLogs(taskId, 5)
    } catch (e: any) {
      logErrors[taskId] = e?.message ?? '加载执行历史失败'
    } finally {
      loadingLogs[taskId] = false
    }
  }
}

// ---- 编辑弹窗 ----

const editDialogOpen = ref(false)
const editingTask = ref<ScheduledTaskDto | null>(null)

function openEdit(task: ScheduledTaskDto) {
  editingTask.value = task
  editDialogOpen.value = true
}

// ---- 展示辅助 ----

/**
 * 状态 badge 的样式类——success/active=绿系、paused=灰系、completed=次级。
 *
 * <p>直接用 Tailwind 色号（bg-emerald-500/15 等），因为 Reka UI Badge 的
 * 现成 variant 没有"成功/暂停"语义；保持一致性直接手搓。</p>
 */
function statusClass(status: string): string {
  switch (status) {
    case 'active':
      return 'bg-emerald-500/15 text-emerald-600 border-emerald-500/30'
    case 'paused':
      return 'bg-muted text-muted-foreground border-border'
    case 'completed':
      return 'bg-sky-500/15 text-sky-600 border-sky-500/30'
    default:
      return 'bg-muted text-muted-foreground border-border'
  }
}

/** 日志状态对应的颜色（dot + 文字） */
function logStatusDotClass(status: string): string {
  switch (status) {
    case 'success':
      return 'bg-emerald-500'
    case 'failed':
      return 'bg-destructive'
    case 'running':
      return 'bg-sky-500'
    case 'timeout':
      return 'bg-amber-500'
    default:
      return 'bg-muted-foreground'
  }
}

function logStatusLabel(status: string): string {
  switch (status) {
    case 'success':
      return '成功'
    case 'failed':
      return '失败'
    case 'running':
      return '运行中'
    case 'timeout':
      return '超时'
    default:
      return status
  }
}

function statusLabel(status: string): string {
  switch (status) {
    case 'active':
      return '运行中'
    case 'paused':
      return '已暂停'
    case 'completed':
      return '已完成'
    default:
      return status
  }
}
</script>

<template>
  <div class="flex h-full flex-col">
    <header class="flex items-center gap-sm border-b px-xl py-md">
      <Clock class="size-md" />
      <span class="text-lg font-semibold">定时任务</span>
    </header>

    <div class="flex-1 overflow-auto p-xl">
      <p
        v-if="store.loading && store.tasks.length === 0"
        class="text-sm text-muted-foreground"
      >
        加载中…
      </p>

      <!-- 空态：icon + 文案 -->
      <div
        v-else-if="store.tasks.length === 0"
        class="flex h-full flex-col items-center justify-center gap-md text-center"
        data-testid="empty-state"
      >
        <div class="flex size-2xl items-center justify-center rounded-full bg-muted">
          <CalendarClock class="size-xl text-muted-foreground" />
        </div>
        <div class="flex flex-col gap-xs">
          <p class="text-md font-medium">暂无定时任务</p>
          <p class="text-sm text-muted-foreground">
            在对话中对微微说"每周日 21 点提醒我写周报"即可创建。
          </p>
        </div>
      </div>

      <!-- 任务卡片列表 -->
      <ul v-else class="flex flex-col gap-md">
        <li
          v-for="task in store.tasks"
          :key="task.id"
          :data-testid="`task-card-${task.id}`"
          class="group rounded-xl border bg-card transition-colors hover:border-primary/40"
        >
          <!-- 卡片主体：用 role=button 包一层 div 而不是 <button>，避免按钮嵌套 -->
          <div
            role="button"
            tabindex="0"
            class="flex w-full cursor-pointer items-center gap-md rounded-xl px-lg py-md text-left"
            :data-testid="`task-card-toggle-${task.id}`"
            @click="toggleExpand(task.id)"
            @keydown.enter.prevent="toggleExpand(task.id)"
            @keydown.space.prevent="toggleExpand(task.id)"
          >
            <!-- 左：项目 tag -->
            <button
              type="button"
              class="flex shrink-0 items-center gap-xs rounded-full bg-muted px-sm py-xs text-xs hover:bg-accent disabled:cursor-default disabled:hover:bg-muted"
              :data-testid="`project-tag-${task.id}`"
              :disabled="!task.projectId"
              @click.stop="task.projectId && openProject(task.projectId)"
            >
              <Folder class="size-xs" />
              {{ projectLabel(task.projectId) }}
            </button>

            <!-- 中：任务名 + cron + 下次 -->
            <div class="flex min-w-0 flex-1 flex-col gap-xs">
              <div class="flex items-center gap-sm">
                <span class="text-md font-medium truncate">{{ task.name }}</span>
                <Badge
                  variant="outline"
                  :class="statusClass(task.status)"
                  :data-testid="`status-badge-${task.id}`"
                >
                  {{ statusLabel(task.status) }}
                </Badge>
              </div>
              <div class="flex flex-wrap items-center gap-sm text-xs text-muted-foreground">
                <code class="rounded-sm bg-muted px-xs py-xs font-mono">{{ task.schedule }}</code>
                <span
                  v-if="task.status === 'active' && task.nextExecutionAt"
                  :data-testid="`next-execution-${task.id}`"
                  class="flex items-center gap-xs"
                >
                  <Clock class="size-xs" />
                  下次：{{ formatNextExecutionTime(task.nextExecutionAt) }}
                </span>
              </div>
            </div>

            <!-- 右：操作按钮 + 展开图标 -->
            <div class="flex shrink-0 items-center gap-xs">
              <button
                type="button"
                :data-testid="`pause-${task.id}`"
                class="rounded-md p-xs text-muted-foreground hover:bg-accent hover:text-foreground"
                :title="task.status === 'active' ? '暂停' : '恢复'"
                @click.stop="togglePause(task.id, task.status)"
              >
                <Pause v-if="task.status === 'active'" class="size-sm" />
                <Play v-else class="size-sm" />
              </button>
              <button
                type="button"
                :data-testid="`edit-${task.id}`"
                class="rounded-md p-xs text-muted-foreground hover:bg-accent hover:text-foreground"
                title="编辑"
                @click.stop="openEdit(task)"
              >
                <Pencil class="size-sm" />
              </button>
              <button
                type="button"
                :data-testid="`delete-${task.id}`"
                class="rounded-md p-xs text-destructive hover:bg-destructive/10"
                title="删除"
                @click.stop="deleteTask(task.id, task.name)"
              >
                <Trash2 class="size-sm" />
              </button>
              <ChevronUp
                v-if="expanded[task.id]"
                class="size-sm text-muted-foreground"
              />
              <ChevronDown
                v-else
                class="size-sm text-muted-foreground"
              />
            </div>
          </div>

          <!-- 展开区：指令 + 执行历史 -->
          <div
            v-if="expanded[task.id]"
            :data-testid="`task-expanded-${task.id}`"
            class="flex flex-col gap-md border-t px-lg py-md"
          >
            <!-- 执行指令 -->
            <div class="flex flex-col gap-xs">
              <span class="text-xs font-medium text-muted-foreground">执行指令</span>
              <p class="text-sm whitespace-pre-wrap break-words">
                {{ task.instruction || '（未填写指令）' }}
              </p>
            </div>

            <!-- 执行历史 -->
            <div class="flex flex-col gap-xs">
              <span class="text-xs font-medium text-muted-foreground">
                最近执行（最多 5 次）
              </span>
              <p
                v-if="loadingLogs[task.id]"
                class="text-xs text-muted-foreground"
                :data-testid="`logs-loading-${task.id}`"
              >
                加载中…
              </p>
              <p
                v-else-if="logErrors[task.id]"
                class="text-xs text-destructive"
                :data-testid="`logs-error-${task.id}`"
              >
                {{ logErrors[task.id] }}
              </p>
              <p
                v-else-if="!logsByTask[task.id] || logsByTask[task.id].length === 0"
                class="text-xs text-muted-foreground"
                :data-testid="`logs-empty-${task.id}`"
              >
                还未执行过
              </p>
              <ul
                v-else
                class="flex flex-col gap-xs"
                :data-testid="`logs-list-${task.id}`"
              >
                <li
                  v-for="log in logsByTask[task.id]"
                  :key="log.id"
                  class="flex items-start gap-sm rounded-md bg-muted/40 px-sm py-xs"
                >
                  <span
                    class="mt-xs inline-block size-xs shrink-0 rounded-full"
                    :class="logStatusDotClass(log.status)"
                  />
                  <div class="flex min-w-0 flex-1 flex-col gap-xs">
                    <div class="flex flex-wrap items-center gap-sm text-xs text-muted-foreground">
                      <span>{{ formatExecutedAtTime(log.executedAt) }}</span>
                      <span>·</span>
                      <span>{{ logStatusLabel(log.status) }}</span>
                      <span>·</span>
                      <span>{{ log.durationMs }} ms</span>
                      <template v-if="log.tokensUsed > 0">
                        <span>·</span>
                        <span>{{ log.tokensUsed }} tokens</span>
                      </template>
                    </div>
                    <p
                      v-if="log.summary"
                      class="text-xs text-foreground whitespace-pre-wrap break-words line-clamp-3"
                    >
                      {{ log.summary }}
                    </p>
                  </div>
                </li>
              </ul>
            </div>
          </div>
        </li>
      </ul>
    </div>

    <!-- 编辑弹窗 -->
    <ScheduledTaskEditDialog
      v-model:open="editDialogOpen"
      :task="editingTask"
    />
  </div>
</template>
