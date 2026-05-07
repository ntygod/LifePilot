<script setup lang="ts">
/**
 * 项目资料抽屉 —— Plan 1 Task 21（polish 2026-04-24）。
 *
 * 职责：
 * - 从右侧滑入，展示项目的资料聚合视图
 * - 「知识库」tab：展示项目默认 KB 下的真实文档列表（文件名 / 状态 / 大小 / 上传时间）
 *   点击某条跳转到 {@code knowledgeBaseDocumentDetail} 路由（已有分块/下载页）
 * - 「文档」tab：项目产出文档入口 —— 目前 session_documents 只按 sessionId 聚合，
 *   没有按 projectId 维度的服务端列表 API，先展示引导文案并指向会话详情的文档工作区
 *
 * 设计说明：
 * - 使用 {@link FormSheetShell} 保持与仓库其他右侧面板一致的
 *   视觉语言与宽度规范（480px / maxWidth 92vw）
 * - Tabs 使用 Reka UI 组件，样式对齐 {@code components/workflow/ExecutionDetail.vue}
 * - 状态徽标配色对齐 {@code views/KnowledgeBaseDocumentView.vue}
 * - 文件图标按 MIME 类型映射：PDF / Word / 图片 / 其它
 *
 * @author zsg
 * @since 2026-04-24
 */
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  FileText,
  FileImage,
  FileSpreadsheet,
  FileCode,
  File as FileIcon,
  Folder,
} from 'lucide-vue-next'
import FormSheetShell from '@/components/common/FormSheetShell.vue'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Badge } from '@/components/ui/badge'
import { knowledgeBaseApi } from '@/api/client'
import { logger } from '@/utils/logger'
import type { KbDocument } from '@/types'
import type { ProjectDto } from '@/api/project'

const props = defineProps<{
  open: boolean
  project: ProjectDto
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
}>()

const router = useRouter()

/** 当前激活 tab：knowledge（知识库）/ documents（文档） */
const activeTab = ref<'knowledge' | 'documents'>('knowledge')

/** 项目默认知识库 id —— 创建项目时自动建，取 knowledgeBaseIds[0] */
const defaultKbId = computed(() => props.project.knowledgeBaseIds?.[0] ?? null)

/** 知识库文档列表 */
const documents = ref<KbDocument[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

/**
 * 文档状态徽标配色 —— 对齐 {@code KnowledgeBaseDocumentView.vue} 的视觉规范。
 * 处理中的中间态使用蓝/橙色调，终态 READY 绿 / ERROR 红。
 */
const statusConfig: Record<KbDocument['status'], { label: string; badgeClass: string }> = {
  READY: {
    label: '已完成',
    badgeClass:
      'border-emerald-200/80 bg-emerald-50/80 text-emerald-700 dark:border-emerald-500/20 dark:bg-emerald-500/10 dark:text-emerald-200',
  },
  ERROR: {
    label: '失败',
    badgeClass: 'border-destructive/25 bg-destructive/10 text-destructive',
  },
  UPLOADING: {
    label: '上传中',
    badgeClass:
      'border-sky-200/80 bg-sky-50/80 text-sky-700 dark:border-sky-500/20 dark:bg-sky-500/10 dark:text-sky-200',
  },
  PARSING: {
    label: '解析中',
    badgeClass:
      'border-sky-200/80 bg-sky-50/80 text-sky-700 dark:border-sky-500/20 dark:bg-sky-500/10 dark:text-sky-200',
  },
  CHUNKING: {
    label: '分块中',
    badgeClass:
      'border-sky-200/80 bg-sky-50/80 text-sky-700 dark:border-sky-500/20 dark:bg-sky-500/10 dark:text-sky-200',
  },
  INDEXING: {
    label: '索引中',
    badgeClass:
      'border-amber-200/80 bg-amber-50/80 text-amber-700 dark:border-amber-500/20 dark:bg-amber-500/10 dark:text-amber-200',
  },
  EXTRACTING: {
    label: '提取中',
    badgeClass:
      'border-amber-200/80 bg-amber-50/80 text-amber-700 dark:border-amber-500/20 dark:bg-amber-500/10 dark:text-amber-200',
  },
  UPDATING: {
    label: '更新中',
    badgeClass:
      'border-amber-200/80 bg-amber-50/80 text-amber-700 dark:border-amber-500/20 dark:bg-amber-500/10 dark:text-amber-200',
  },
  DELETING: {
    label: '删除中',
    badgeClass:
      'border-slate-200/80 bg-slate-50/80 text-slate-700 dark:border-slate-500/20 dark:bg-slate-500/10 dark:text-slate-200',
  },
}

/**
 * 按文件 MIME 类型映射到 lucide 图标。
 * fallback 到通用 File 图标。
 */
function iconForDoc(doc: KbDocument) {
  const mime = (doc.mimeType ?? '').toLowerCase()
  const name = (doc.fileName ?? '').toLowerCase()

  if (mime.includes('pdf') || name.endsWith('.pdf')) return FileText
  if (mime.includes('word') || name.endsWith('.docx') || name.endsWith('.doc')) return FileText
  if (mime.startsWith('image/')) return FileImage
  if (
    mime.includes('sheet') ||
    name.endsWith('.xlsx') ||
    name.endsWith('.xls') ||
    name.endsWith('.csv')
  ) {
    return FileSpreadsheet
  }
  if (name.endsWith('.md') || mime.includes('markdown')) return FileCode
  return FileIcon
}

/** 人类可读的文件大小 */
function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(1)} GB`
}

/** 时间格式化：相对时间优先（今天/昨天/MM-DD），fallback 到本地化 */
function formatTime(iso?: string): string {
  if (!iso) return '—'
  const time = new Date(iso)
  if (Number.isNaN(time.getTime())) return '—'

  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const startOfYesterday = new Date(startOfToday)
  startOfYesterday.setDate(startOfYesterday.getDate() - 1)

  if (time >= startOfToday) {
    const hh = time.getHours().toString().padStart(2, '0')
    const mm = time.getMinutes().toString().padStart(2, '0')
    return `今天 ${hh}:${mm}`
  }
  if (time >= startOfYesterday) return '昨天'

  const month = (time.getMonth() + 1).toString().padStart(2, '0')
  const day = time.getDate().toString().padStart(2, '0')
  return `${month}-${day}`
}

async function loadDocuments() {
  const kbId = defaultKbId.value
  if (!kbId) {
    documents.value = []
    return
  }

  loading.value = true
  error.value = null
  try {
    documents.value = await knowledgeBaseApi.listDocuments(kbId)
  } catch (err: any) {
    error.value = err?.message ?? '加载文档列表失败'
    logger.warn('加载项目知识库文档失败:', err)
  } finally {
    loading.value = false
  }
}

/** 点击文档条目：跳到 KB 文档详情页（分块 + 下载） */
function openDocument(doc: KbDocument) {
  const kbId = defaultKbId.value
  if (!kbId) return
  // 关闭抽屉再跳转，避免 Sheet Portal 残留遮挡
  emit('update:open', false)
  void router.push({
    name: 'knowledgeBaseDocumentDetail',
    params: { id: kbId, docId: doc.id },
  })
}

// 打开抽屉时：重置 tab + 懒加载文档；immediate 覆盖初始 open=true 挂载场景
watch(
  () => props.open,
  open => {
    if (open) {
      activeTab.value = 'knowledge'
      void loadDocuments()
    }
  },
  { immediate: true },
)

// 项目切换 / knowledgeBaseIds 异步刷新时也重新加载
watch(defaultKbId, () => {
  if (props.open) {
    void loadDocuments()
  }
})

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

      <!-- 知识库 tab：真实文档列表 -->
      <TabsContent value="knowledge" class="pt-md">
        <!-- 加载中 -->
        <div
          v-if="loading"
          data-testid="knowledge-loading"
          class="rounded-md border border-dashed border-border/60 p-lg text-center text-sm text-muted-foreground"
        >
          加载中...
        </div>

        <!-- 错误态 -->
        <div
          v-else-if="error"
          data-testid="knowledge-error"
          class="rounded-md border border-destructive/40 bg-destructive/10 p-md text-sm text-destructive"
        >
          {{ error }}
        </div>

        <!-- 没有默认 KB（理论上创建项目时已自动建，兜底提示） -->
        <div
          v-else-if="!defaultKbId"
          data-testid="knowledge-no-kb"
          class="rounded-md border border-dashed border-border/60 p-lg text-center text-sm text-muted-foreground"
        >
          本项目尚未关联知识库。
        </div>

        <!-- 空态 -->
        <div
          v-else-if="documents.length === 0"
          data-testid="knowledge-empty"
          class="flex flex-col items-center gap-sm rounded-md border border-dashed border-border/60 p-xl text-center"
        >
          <Folder class="size-lg text-muted-foreground/60" />
          <p class="text-sm text-muted-foreground">本项目还没有上传任何文档</p>
          <p class="text-xs text-muted-foreground/80">
            在创建项目时或对话中发送附件即可加入本项目知识库。
          </p>
        </div>

        <!-- 文档列表 -->
        <ul v-else class="flex flex-col gap-xs" data-testid="knowledge-list">
          <li
            v-for="doc in documents"
            :key="doc.id"
            data-testid="knowledge-item"
          >
            <button
              type="button"
              class="group flex w-full items-start gap-sm rounded-md border border-border/60 bg-background/60 p-sm text-left transition-colors hover:border-primary/50 hover:bg-accent/40"
              @click="openDocument(doc)"
            >
              <component
                :is="iconForDoc(doc)"
                class="mt-xs size-md shrink-0 text-muted-foreground group-hover:text-primary"
              />
              <div class="min-w-0 flex-1">
                <div class="flex items-center gap-xs">
                  <span class="truncate text-sm font-medium text-foreground">
                    {{ doc.fileName }}
                  </span>
                  <Badge
                    variant="outline"
                    :class="statusConfig[doc.status]?.badgeClass"
                    class="shrink-0 text-xs"
                  >
                    {{ statusConfig[doc.status]?.label ?? doc.status }}
                  </Badge>
                </div>
                <div class="mt-xs flex items-center gap-sm text-xs text-muted-foreground">
                  <span>{{ formatSize(doc.fileSize) }}</span>
                  <span aria-hidden="true">·</span>
                  <span>{{ formatTime(doc.createdAt) }}</span>
                </div>
              </div>
            </button>
          </li>
        </ul>
      </TabsContent>

      <!-- 文档 tab：产出文档入口（当前 session_documents 无项目级 API，先做引导） -->
      <TabsContent value="documents" class="pt-md">
        <div
          data-testid="documents-placeholder"
          class="flex flex-col items-center gap-sm rounded-md border border-dashed border-border/60 p-xl text-center"
        >
          <FileText class="size-lg text-muted-foreground/60" />
          <p class="text-sm text-muted-foreground">
            本项目产出的文档将在此展示
          </p>
          <p class="text-xs text-muted-foreground/80">
            当前可在各会话的「文档工作区」中查看 AI 生成/修改的文档版本。
          </p>
        </div>
      </TabsContent>
    </Tabs>
  </FormSheetShell>
</template>
