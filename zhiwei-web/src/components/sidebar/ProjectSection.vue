<script setup lang="ts">
/**
 * 侧栏"项目"分组 —— Plan 1 Task 18。
 *
 * 职责：
 * - 挂载时拉取项目列表（依赖 {@link useProjectStore}）
 * - 渲染"新建项目"按钮（通过 emit 交给父组件弹出创建对话框）
 * - 渲染项目条目列表，点击跳转到 /projects/:id（路由由 Task 20 建，点击先 404）
 *
 * 使用 Tailwind 命名尺度 + lucide-vue-next 图标，按项目前端规范书写。
 *
 * @author zsg
 * @since 2026-04-23
 */
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Folder, FolderPlus } from 'lucide-vue-next'
import { useProjectStore } from '@/stores/project'

defineEmits<{ create: [] }>()

const store = useProjectStore()
const router = useRouter()

onMounted(() => {
  // 拉取失败的错误已经在 store 内部捕获并写入 store.error，这里不再处理
  store.fetchProjects().catch(() => {})
})

/** 跳转到项目详情页（Task 20 实现路由） */
function openProject(id: string) {
  router.push(`/projects/${id}`)
}
</script>

<template>
  <div class="flex flex-col gap-xs py-sm">
    <div class="px-md text-xs uppercase tracking-wider text-muted-foreground">项目</div>

    <button
      type="button"
      data-testid="create-project-btn"
      class="flex items-center gap-sm px-md py-xs rounded-md text-left hover:bg-accent"
      @click="$emit('create')"
    >
      <FolderPlus class="size-md" />
      <span class="text-sm">新建项目</span>
    </button>

    <button
      v-for="p in store.projects"
      :key="p.id"
      type="button"
      class="flex items-center gap-sm px-md py-xs rounded-md text-left hover:bg-accent"
      @click="openProject(p.id)"
    >
      <Folder class="size-md shrink-0" />
      <span class="text-sm truncate">{{ p.name }}</span>
    </button>
  </div>
</template>
