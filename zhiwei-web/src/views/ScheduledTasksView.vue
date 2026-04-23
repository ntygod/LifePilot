<script setup lang="ts">
/**
 * 定时任务全局管理页 —— Plan 2+3 Task A5。
 *
 * <p>侧栏一级入口；展示所有定时任务（主账户 + 所有项目，含隔离项目），
 * 每行带项目 tag + 暂停/恢复/删除操作。</p>
 *
 * <p><b>创建入口不在此页</b>：由 LLM 在对话中自然语言触发创建（如"每周日 21 点
 * 提醒我..."），避免引入独立的表单弹窗稀释"帮你做事"的对话入口定位。</p>
 *
 * <p>删除使用轻量 {@code window.confirm}；项目设置级别的高风险确认才用
 * {@code ConfirmDialog}。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Clock, Play, Pause, Trash2, Folder } from 'lucide-vue-next'
import { useScheduledTaskStore } from '@/stores/scheduledTask'
import { useProjectStore } from '@/stores/project'

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
      <p
        v-else-if="store.tasks.length === 0"
        class="text-sm text-muted-foreground"
      >
        暂无定时任务。你可以在对话中对微微说"每周日 21 点提醒我..."来创建。
      </p>
      <ul v-else class="flex flex-col gap-sm">
        <li
          v-for="task in store.tasks"
          :key="task.id"
          class="flex items-center gap-md rounded-md border px-md py-sm hover:bg-accent"
        >
          <button
            class="flex items-center gap-xs rounded-md bg-muted px-sm py-xs text-xs"
            :data-testid="`project-tag-${task.id}`"
            :disabled="!task.projectId"
            @click="task.projectId && openProject(task.projectId)"
          >
            <Folder class="size-xs" />
            {{ projectLabel(task.projectId) }}
          </button>

          <div class="flex-1 min-w-0">
            <div class="text-sm font-medium">{{ task.name }}</div>
            <div class="text-xs text-muted-foreground truncate">
              <code>{{ task.schedule }}</code>
              <span class="mx-xs">·</span>
              <span>{{ task.instruction }}</span>
            </div>
          </div>

          <span class="text-xs text-muted-foreground">
            {{ task.status === 'active' ? '运行中' : '已暂停' }}
          </span>

          <button
            :data-testid="`pause-${task.id}`"
            class="rounded-md p-xs hover:bg-accent"
            :title="task.status === 'active' ? '暂停' : '恢复'"
            @click="togglePause(task.id, task.status)"
          >
            <Pause v-if="task.status === 'active'" class="size-sm" />
            <Play v-else class="size-sm" />
          </button>
          <button
            :data-testid="`delete-${task.id}`"
            class="rounded-md p-xs text-destructive hover:bg-destructive/10"
            title="删除"
            @click="deleteTask(task.id, task.name)"
          >
            <Trash2 class="size-sm" />
          </button>
        </li>
      </ul>
    </div>
  </div>
</template>
