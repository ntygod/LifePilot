<script setup lang="ts">
/**
 * 文件产物卡片 —— 渲染 Agent 工具调用产生的 ToolArtifact。
 *
 * <p>覆盖三种环境：</p>
 * <ul>
 *   <li>桌面 Tauri：「打开文件」唤起系统默认应用、「显示位置」在文件管理器定位、
 *       下载到浏览器、复制路径</li>
 *   <li>本地/远程 Web：复制路径 + 下载到浏览器</li>
 *   <li>渠道侧（飞书/企微/钉钉/Telegram）：本组件不直接负责，由后端 dispatcher
 *       通过 connector RPC 投递文件消息</li>
 * </ul>
 *
 * <p>IMAGE 类型显示缩略图（来自 downloadUrl），FILE 类型显示通用文件图标。</p>
 *
 * @author zsg
 * @since 2026-05-17
 */
import { computed, onMounted, ref, watch } from 'vue'
import {
  AlertTriangle,
  BookOpenCheck,
  CheckCircle2,
  Copy,
  Download,
  ExternalLink,
  File,
  FileImage,
  FileText,
  Folder,
  Loader2,
  Presentation,
  Sheet,
} from 'lucide-vue-next'
import {
  buildArtifactDownloadUrl,
  getArtifactMetadata,
  type ArtifactKind,
} from '@/api/artifacts'
import { knowledgeBaseApi } from '@/api/client'
import {
  isTauriEnv,
  openDocumentPath,
  revealInFileManager,
} from '@/composables/useSaveFilePicker'
import { useUiStore } from '@/stores/ui'
import { copyToClipboard } from '@/utils/clipboard'

interface Props {
  artifactId: string
  fileName: string
  mimeType: string
  kind: ArtifactKind
  size: number
  /** 已知的下载 URL；不传时自动按 artifactId 构造 */
  downloadUrl?: string
  /** 明确的资料库目标；为空时不显示“存资料”入口，避免主对话里追加选择负担。 */
  knowledgeBaseId?: string | null
  knowledgeBaseName?: string | null
  /** 历史消息已记录的沉淀目标，用于刷新后恢复完成态。 */
  savedKnowledgeBaseName?: string | null
  /** 上传成功后记录会话沉淀状态；失败时卡片保持可重试。 */
  persistKnowledgeSettlement?: (payload: ArtifactKnowledgeSavedPayload) => Promise<void> | void
}

interface ArtifactKnowledgeSavedPayload {
  artifactId: string
  fileName: string
  knowledgeBaseId: string
  knowledgeBaseName: string
}

const emit = defineEmits<{
  (e: 'preview', url: string): void
  (e: 'saved-knowledge', payload: ArtifactKnowledgeSavedPayload): void
}>()

const props = defineProps<Props>()
const ui = useUiStore()

const tauriAvailable = ref(isTauriEnv())
const absolutePath = ref<string | null>(null)
const savingToKnowledge = ref(false)
const saveKnowledgeError = ref<string | null>(null)
const savedKnowledgeBaseNameLocal = ref<string | null>(null)
const pendingSettlementPayload = ref<ArtifactKnowledgeSavedPayload | null>(null)
const downloadUrl = computed(() => props.downloadUrl ?? buildArtifactDownloadUrl(props.artifactId))
const knowledgeBaseName = computed(() => props.knowledgeBaseName?.trim() || '资料库')
const effectiveSavedKnowledgeBaseName = computed(() => {
  const persistedName = props.savedKnowledgeBaseName?.trim()
  return savedKnowledgeBaseNameLocal.value ?? (persistedName || null)
})
const canSaveToKnowledge = computed(() =>
  Boolean(props.knowledgeBaseId?.trim()) && isParseableArtifact(),
)
const hasSavedToKnowledge = computed(() => effectiveSavedKnowledgeBaseName.value !== null)
const saveKnowledgeButtonTitle = computed(() => {
  if (hasSavedToKnowledge.value) return `已存入资料库：${effectiveSavedKnowledgeBaseName.value}`
  if (saveKnowledgeError.value) return `重试存入资料库：${knowledgeBaseName.value}`
  return `存入资料库：${knowledgeBaseName.value}`
})
const saveKnowledgeButtonText = computed(() => {
  if (savingToKnowledge.value) return '存入中'
  if (hasSavedToKnowledge.value) return '已存入'
  if (saveKnowledgeError.value) return '重试'
  return '存资料'
})
// 安全说明：downloadUrl 指向同源后端 /api/artifacts/{id}/download，id 经 encodeURIComponent 编码。
// 若未来支持外部 CDN URL，需在 CSP 中限制 img-src 白名单。

watch(
  () => [props.artifactId, props.knowledgeBaseId] as const,
  () => {
    savedKnowledgeBaseNameLocal.value = null
    saveKnowledgeError.value = null
    pendingSettlementPayload.value = null
  },
)

/** 拉取 artifact 的本地绝对路径（仅 Tauri 端按需展示）。 */
async function loadAbsolutePath() {
  if (!tauriAvailable.value) return
  try {
    const meta = await getArtifactMetadata(props.artifactId)
    // metadata 不直接含 path（path 是后端内部字段，元数据不暴露），
    // 但 Web 复制路径功能改为复制下载 URL；Tauri 端通过另一接口拉真实路径。
    // 当前 ArtifactController.metadata 返回不含 path —— 安全考虑：
    // 路径只服务 Tauri shell.open / explorer 调用，前端拿不到也没问题，
    // Tauri 端"打开文件"应直接调 invoke 命令传 artifactId，由 Tauri 端从后端拉路径。
    // 这里临时回退：复制 URL 到剪贴板而非本地路径。
    void meta
  } catch (e) {
    console.warn('artifact metadata 加载失败', e)
  }
}

onMounted(() => {
  void loadAbsolutePath()
})

/** 大小格式化为 KB / MB / GB。 */
function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(2)} MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`
}

/** 按 mime/扩展名挑图标。 */
function iconFor() {
  if (props.kind === 'IMAGE') return FileImage
  const m = props.mimeType.toLowerCase()
  if (m.includes('wordprocessingml') || m.includes('msword')) return FileText
  if (m.includes('spreadsheetml') || m.includes('excel')) return Sheet
  if (m.includes('presentationml') || m.includes('powerpoint')) return Presentation
  if (m.includes('pdf')) return FileText
  if (m.includes('zip') || m.includes('compressed')) return Folder
  return File
}

function isParseableArtifact(): boolean {
  const mimeType = props.mimeType.toLowerCase()
  if (mimeType === 'application/pdf') return true
  if (mimeType.includes('wordprocessingml')) return true
  if (mimeType.includes('spreadsheetml')) return true
  if (mimeType.includes('presentationml')) return true
  if (mimeType === 'text/markdown' || mimeType === 'text/plain' || mimeType === 'text/csv'
      || mimeType === 'text/tab-separated-values') return true
  const ext = props.fileName.split('.').pop()?.toLowerCase()
  return ['pdf', 'docx', 'xlsx', 'pptx', 'md', 'txt', 'csv', 'log', 'tsv'].includes(ext ?? '')
}

function resolveErrorMessage(error: unknown): string {
  if (error instanceof Error) return error.message
  if (typeof error === 'object' && error !== null && 'message' in error) {
    return String((error as { message?: unknown }).message ?? '未知错误')
  }
  return typeof error === 'string' ? error : '未知错误'
}

async function handleSaveToKnowledge() {
  const knowledgeBaseId = props.knowledgeBaseId?.trim()
  if (!knowledgeBaseId || savingToKnowledge.value || hasSavedToKnowledge.value) return

  savingToKnowledge.value = true
  saveKnowledgeError.value = null
  try {
    let payload = pendingSettlementPayload.value
    if (!payload) {
      const response = await fetch(downloadUrl.value)
      if (!response.ok) {
        throw new Error(`下载产物失败：${response.status}`)
      }
      const blob = await response.blob()
      const type = props.mimeType || blob.type || 'application/octet-stream'
      const file = new globalThis.File([blob], props.fileName, { type })
      await knowledgeBaseApi.uploadDocument(knowledgeBaseId, file)
      payload = {
        artifactId: props.artifactId,
        fileName: props.fileName,
        knowledgeBaseId,
        knowledgeBaseName: knowledgeBaseName.value,
      }
    }

    try {
      await props.persistKnowledgeSettlement?.(payload)
      pendingSettlementPayload.value = null
    } catch (error) {
      pendingSettlementPayload.value = payload
      throw new Error(`记录沉淀状态失败：${resolveErrorMessage(error)}`)
    }

    savedKnowledgeBaseNameLocal.value = knowledgeBaseName.value
    emit('saved-knowledge', payload)
    ui.showToast('success', `已存入资料库：${knowledgeBaseName.value}`)
  } catch (error) {
    const message = resolveErrorMessage(error)
    saveKnowledgeError.value = message
    ui.showToast('error', `存入资料失败：${message}`)
  } finally {
    savingToKnowledge.value = false
  }
}

async function handleCopyPath() {
  // 没有本地绝对路径时，回退为复制下载 URL —— Web 端用户可粘到浏览器或工具
  const text = absolutePath.value ?? downloadUrl.value
  const ok = await copyToClipboard(text)
  ui.showToast(ok ? 'success' : 'error',
      ok ? (absolutePath.value ? '路径已复制' : '下载链接已复制')
         : '复制失败')
}

async function handleOpenFile() {
  if (!tauriAvailable.value) return
  if (!absolutePath.value) {
    ui.showToast('error', '本地路径不可用')
    return
  }
  try {
    await openDocumentPath(absolutePath.value)
  } catch (e) {
    ui.showToast('error', `打开失败：${(e as Error).message}`)
  }
}

async function handleReveal() {
  if (!tauriAvailable.value) return
  if (!absolutePath.value) {
    ui.showToast('error', '本地路径不可用')
    return
  }
  try {
    await revealInFileManager(absolutePath.value)
  } catch (e) {
    ui.showToast('error', `定位失败：${(e as Error).message}`)
  }
}
</script>

<template>
  <!-- IMAGE 类型：inline 渲染大图，直接嵌入消息流 -->
  <figure v-if="kind === 'IMAGE'" class="artifact-image my-sm">
    <button
      type="button"
      class="block cursor-zoom-in"
      @click="emit('preview', downloadUrl)"
    >
      <img
        :src="downloadUrl"
        :alt="fileName"
        class="max-w-full rounded-lg border border-border shadow-sm transition-shadow hover:shadow-md"
        style="max-height: 400px; object-fit: contain;"
        loading="lazy"
      />
    </button>
    <figcaption class="mt-xs flex items-center gap-sm text-xs text-muted-foreground">
      <span class="truncate">{{ fileName }}</span>
      <span>{{ formatSize(size) }}</span>
      <button
        type="button"
        class="ml-auto flex items-center gap-xs rounded-md px-xs py-xs hover:bg-muted"
        :title="tauriAvailable ? '复制本地路径' : '复制下载链接'"
        @click="handleCopyPath"
      >
        <Copy class="h-3 w-3" />
      </button>
      <a
        :href="downloadUrl"
        :download="fileName"
        class="flex items-center gap-xs rounded-md px-xs py-xs hover:bg-muted"
        title="下载"
      >
        <Download class="h-3 w-3" />
      </a>
    </figcaption>
  </figure>

  <!-- 非图片：附件卡片 -->
  <div v-else class="artifact-card flex items-stretch gap-sm rounded-md border border-border bg-muted/30 p-sm">
    <!-- FILE：显示文件图标 -->
    <component
      :is="iconFor()"
      class="h-md w-md shrink-0 self-center text-muted-foreground"
    />

    <!-- 文件名 + 大小 -->
    <div class="min-w-0 flex-1 self-center">
      <div class="truncate text-sm font-medium" :title="fileName">{{ fileName }}</div>
      <div class="text-xs text-muted-foreground">{{ formatSize(size) }}</div>
      <div v-if="effectiveSavedKnowledgeBaseName" class="artifact-card__settled">
        <CheckCircle2 class="h-3 w-3" />
        <span>已存入 {{ effectiveSavedKnowledgeBaseName }}</span>
      </div>
      <div v-else-if="saveKnowledgeError" class="artifact-card__settle-error" role="alert">
        <AlertTriangle class="h-3 w-3" />
        <span>存入失败：{{ saveKnowledgeError }}</span>
      </div>
    </div>

    <!-- 操作按钮 -->
    <div class="flex shrink-0 items-center gap-xs self-center">
      <!-- Tauri：打开文件 -->
      <button
        v-if="tauriAvailable"
        type="button"
        class="flex items-center gap-xs rounded-md px-xs py-xs text-xs text-primary hover:bg-primary/10"
        title="打开文件"
        @click="handleOpenFile"
      >
        <ExternalLink class="h-3 w-3" />
        <span>打开</span>
      </button>

      <!-- Tauri：显示位置 -->
      <button
        v-if="tauriAvailable"
        type="button"
        class="flex items-center gap-xs rounded-md px-xs py-xs text-xs text-muted-foreground hover:bg-muted"
        title="在文件管理器中定位"
        @click="handleReveal"
      >
        <Folder class="h-3 w-3" />
        <span>位置</span>
      </button>

      <!-- 存入明确的资料库目标 -->
      <button
        v-if="canSaveToKnowledge"
        type="button"
        class="flex items-center gap-xs rounded-md px-xs py-xs text-xs text-primary hover:bg-primary/10 disabled:cursor-default disabled:opacity-80"
        :title="saveKnowledgeButtonTitle"
        :aria-label="saveKnowledgeButtonTitle"
        :disabled="savingToKnowledge || hasSavedToKnowledge"
        @click="handleSaveToKnowledge"
      >
        <Loader2 v-if="savingToKnowledge" class="h-3 w-3 animate-spin" />
        <CheckCircle2 v-else-if="hasSavedToKnowledge" class="h-3 w-3" />
        <BookOpenCheck v-else class="h-3 w-3" />
        <span>{{ saveKnowledgeButtonText }}</span>
      </button>

      <!-- 复制路径（Web 端复制下载 URL） -->
      <button
        type="button"
        class="flex items-center gap-xs rounded-md px-xs py-xs text-xs text-muted-foreground hover:bg-muted"
        :title="tauriAvailable ? '复制本地路径' : '复制下载链接'"
        @click="handleCopyPath"
      >
        <Copy class="h-3 w-3" />
      </button>

      <!-- 下载到浏览器 -->
      <a
        :href="downloadUrl"
        :download="fileName"
        class="flex items-center gap-xs rounded-md px-xs py-xs text-xs text-primary hover:bg-primary/10"
        title="下载到浏览器"
      >
        <Download class="h-3 w-3" />
        <span>下载</span>
      </a>
    </div>
  </div>
</template>

<style scoped>
.artifact-card {
  transition: border-color 120ms ease, background 120ms ease;
}
.artifact-card:hover {
  border-color: hsl(from var(--primary) h s l / 0.45);
  background: hsl(from var(--muted) h s l / 0.55);
}

.artifact-card__settled {
  display: inline-flex;
  max-width: 100%;
  align-items: center;
  gap: 4px;
  margin-top: 3px;
  color: hsl(from var(--primary) h s l / 0.86);
  font-size: 11px;
  line-height: 1.35;
}

.artifact-card__settled span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.artifact-card__settle-error {
  display: inline-flex;
  max-width: 100%;
  align-items: center;
  gap: 4px;
  margin-top: 3px;
  color: hsl(var(--destructive));
  font-size: 11px;
  line-height: 1.35;
}

.artifact-card__settle-error span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
