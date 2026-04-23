<script setup lang="ts">
/**
 * 项目详情页（清爽款）—— Plan 1 Task 20。
 *
 * 布局参考 spec §3.3：
 * - 顶栏：Folder 图标 + 项目名 + 右上角「资料 / 设置」入口
 * - 主区：居中「开始新对话」按钮，下方为本项目下的对话列表占位
 * - 抽屉：项目资料抽屉（Task 21 已接入 {@link ProjectResourcePanel}）；
 *   项目设置抽屉将在 Task 22 接入
 *
 * 对话列表暂留占位：后端 `GET /api/chat/sessions?projectId=` 已支持（Task 13），
 * 前端 {@link chatApi.listSessions} / {@link useChatStore.loadSessions} 尚未暴露
 * projectId 参数；改动较大，留待 Task 23（新建对话带 projectId）或后续 polish。
 *
 * @author zsg
 * @since 2026-04-23
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Folder, Paperclip, Settings } from 'lucide-vue-next'
import { useProjectStore } from '@/stores/project'
import ProjectResourcePanel from '@/components/project/ProjectResourcePanel.vue'

const route = useRoute()
const router = useRouter()
const store = useProjectStore()

const projectId = computed(() => route.params.id as string)
const project = computed(() =>
  store.projects.find(p => p.id === projectId.value),
)

/** 项目资料抽屉（Task 21 实装真正的面板，本 task 仅占位状态） */
const showResource = ref(false)
/** 项目设置抽屉（Task 22 实装真正的面板，本 task 仅占位状态） */
const showSettings = ref(false)

onMounted(async () => {
  // 直达 URL 刷新时本地列表可能为空，先拉取一次
  if (store.projects.length === 0) {
    try {
      await store.fetchProjects()
    } catch {
      // 错误已经落到 store.error，这里不阻断渲染；下方条件会走到加载中分支
    }
  }
  // 拉取完成仍找不到对应项目 → 大概率 id 非法，回首页避免停在空白页
  if (!project.value) {
    router.replace({ name: 'home' })
  }
})

/** 跳转到新建对话页，把当前 projectId 带入 query（供 Task 23 接续使用） */
function createNewConversation() {
  router.push({
    name: 'newConversation',
    query: { projectId: projectId.value },
  })
}
</script>

<template>
  <div v-if="project" class="flex h-full flex-col">
    <header
      class="flex items-center justify-between border-b px-xl py-md"
      data-testid="project-detail-header"
    >
      <div class="flex items-center gap-sm min-w-0">
        <Folder class="size-md shrink-0" />
        <span class="text-lg font-semibold truncate">{{ project.name }}</span>
      </div>

      <div class="flex items-center gap-sm shrink-0">
        <button
          type="button"
          class="rounded-md p-xs hover:bg-accent transition-colors"
          title="项目资料"
          data-testid="open-resource-btn"
          @click="showResource = true"
        >
          <Paperclip class="size-md" />
        </button>
        <button
          type="button"
          class="rounded-md p-xs hover:bg-accent transition-colors"
          title="项目设置"
          data-testid="open-settings-btn"
          @click="showSettings = true"
        >
          <Settings class="size-md" />
        </button>
      </div>
    </header>

    <div class="flex flex-1 flex-col items-center overflow-auto p-xl">
      <button
        type="button"
        class="mb-xl rounded-xl bg-primary px-xl py-md text-primary-foreground hover:opacity-90 transition-opacity"
        data-testid="new-conversation-btn"
        @click="createNewConversation"
      >
        开始新对话
      </button>

      <div class="w-full max-w-[720px]">
        <div class="mb-md text-lg font-semibold">本项目的对话</div>
        <!-- TODO(Task 23+): 接入按 projectId 过滤的会话列表组件 -->
        <div class="text-sm text-muted-foreground" data-testid="conversation-list-placeholder">
          暂无对话。点击上方按钮开始第一段对话。
        </div>
      </div>
    </div>

    <ProjectResourcePanel
      v-model:open="showResource"
      :project-id="projectId"
    />

    <!-- 项目设置抽屉 —— Task 22 实装后接入 -->
  </div>

  <div v-else class="flex h-full items-center justify-center p-xl text-sm text-muted-foreground">
    加载中...
  </div>
</template>
