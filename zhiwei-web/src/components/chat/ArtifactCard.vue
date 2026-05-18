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
import { computed, onMounted, ref } from 'vue'
import { File, FileImage, FileText, Sheet, Presentation, Folder, ExternalLink, Copy, Download } from 'lucide-vue-next'
import {
  buildArtifactDownloadUrl,
  getArtifactMetadata,
  type ArtifactKind,
} from '@/api/artifacts'
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
}

const emit = defineEmits<{
  (e: 'preview', url: string): void
}>()

const props = defineProps<Props>()
const ui = useUiStore()

const tauriAvailable = ref(isTauriEnv())
const absolutePath = ref<string | null>(null)
const downloadUrl = computed(() => props.downloadUrl ?? buildArtifactDownloadUrl(props.artifactId))

/** 拉取 artifact 的本地绝对路径（仅 Tauri 端按需展示）。 */
async function loadAbsolutePath() {
  if (!tauriAvailable.value && !ui) return
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
</style>
