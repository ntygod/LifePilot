<script setup lang="ts">
/**
 * 项目资料抽屉 —— Plan 1 Task 21。
 *
 * 职责：
 * - 从右侧滑入，展示项目的资料聚合视图
 * - 「知识库」tab：展示本项目关联的知识库文件（Plan 1 占位）
 * - 「文档」tab：展示本项目产出的文档（Plan 1 占位）
 *
 * 设计说明：
 * - 使用 {@link FormSheetShell} 保持与仓库其他右侧面板（Agents、Datastore 等）一致的
 *   视觉语言与宽度规范（480px / maxWidth 92vw）
 * - Tabs 使用 Reka UI 组件，样式对齐 {@code components/workflow/ExecutionDetail.vue}
 * - 数据实装留给后续 plan：
 *   - 知识库列表：后端通过 memory_space_knowledge_bases 关联 project 的 MemorySpace，
 *     调用 `GET /api/knowledge-bases?projectSpaceId=xxx`（待 Plan 2+）
 *   - 文档列表：后端通过 session_store.project_id → session_documents 间接关联，
 *     或 documents 表自带归属字段（Phase 3 Document Workspace 的产物）
 *
 * @author zsg
 * @since 2026-04-23
 */
import { ref, watch } from 'vue'
import FormSheetShell from '@/components/common/FormSheetShell.vue'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'

const props = defineProps<{
  open: boolean
  projectId: string
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
}>()

/** 当前激活 tab：knowledge（知识库）/ documents（文档） */
const activeTab = ref<'knowledge' | 'documents'>('knowledge')

// 每次打开抽屉时重置到默认 tab，避免上次残留状态
watch(
  () => props.open,
  open => {
    if (open) {
      activeTab.value = 'knowledge'
    }
  },
)

function handleOpenChange(value: boolean) {
  emit('update:open', value)
}

function onTabChange(tab: string | number) {
  activeTab.value = tab === 'documents' ? 'documents' : 'knowledge'
}
</script>

<template>
  <FormSheetShell
    :open="props.open"
    title="项目资料"
    description="本项目关联的知识库与产出的文档。"
    body-class="flex flex-col gap-md"
    @update:open="handleOpenChange"
  >
    <Tabs :model-value="activeTab" class="flex-1" @update:model-value="onTabChange">
      <TabsList class="w-full" data-testid="project-resource-tabs">
        <TabsTrigger value="knowledge" data-testid="tab-knowledge">
          知识库
        </TabsTrigger>
        <TabsTrigger value="documents" data-testid="tab-documents">
          文档
        </TabsTrigger>
      </TabsList>

      <TabsContent value="knowledge" class="pt-md">
        <!--
          TODO(Plan 1 后续 / Plan 2):
          实装知识库列表 —— 后端通过 memory_space_knowledge_bases 关联到项目的 MemorySpace。
          调用流程：GET /api/knowledge-bases?projectSpaceId=xxx
          组件可复用 `views/KnowledgeBaseView.vue` 中的列表项渲染
        -->
        <div
          data-testid="knowledge-placeholder"
          class="rounded-md border border-dashed border-border/60 p-xl text-center text-sm text-muted-foreground"
        >
          本项目的知识库文件将在此展示（Plan 1 占位）。
        </div>
      </TabsContent>

      <TabsContent value="documents" class="pt-md">
        <!--
          TODO(Plan 1 后续):
          实装文档列表 —— 后端通过 session_store.project_id → session_documents 间接关联，
          或 documents 表直接归属到 project（Phase 3 Document Workspace 产物）。
        -->
        <div
          data-testid="documents-placeholder"
          class="rounded-md border border-dashed border-border/60 p-xl text-center text-sm text-muted-foreground"
        >
          本项目产出的文档将在此展示（Plan 1 占位）。
        </div>
      </TabsContent>
    </Tabs>
  </FormSheetShell>
</template>
