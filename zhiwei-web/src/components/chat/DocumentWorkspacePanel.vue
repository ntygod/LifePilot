<script setup lang="ts">
/**
 * 文档工作区面板 —— 列出当前 session 下已开始编辑的文档工作副本。
 *
 * 由 ChatView 通过图标按钮触发展开；用户重开会话时可以从这里找回之前改过的文档
 * 而不用靠对话历史里残留的 DiffCard。
 *
 * @author zsg
 * @since 2026-04-22
 */
import { ref, watch } from 'vue'
import { FileText, Sheet, Presentation, File, RefreshCw, X } from 'lucide-vue-next'
import { listSessionDocuments, type DocumentMetadata } from '@/api/documents'

interface Props {
  sessionId: string
  open: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'close'): void
  (e: 'open-document', documentId: string): void
}>()

const loading = ref(false)
const docs = ref<DocumentMetadata[]>([])

async function load() {
  if (!props.sessionId) return
  loading.value = true
  try {
    docs.value = await listSessionDocuments(props.sessionId, 'working')
    // 最新编辑在上（按 createdAt 倒序）
    docs.value.sort((a, b) => b.createdAt.localeCompare(a.createdAt))
  } catch (e) {
    console.warn('加载会话文档失败', e)
    docs.value = []
  } finally {
    loading.value = false
  }
}

watch(() => [props.sessionId, props.open] as const, ([, isOpen]) => {
  if (isOpen) void load()
})

function iconFor(mime: string) {
  if (mime.includes('wordprocessingml')) return FileText
  if (mime.includes('spreadsheetml')) return Sheet
  if (mime.includes('presentationml')) return Presentation
  return File
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}
</script>

<template>
  <Transition
    enter-active-class="transition duration-200 ease-out"
    enter-from-class="opacity-0 translate-x-4"
    enter-to-class="opacity-100 translate-x-0"
    leave-active-class="transition duration-150 ease-in"
    leave-from-class="opacity-100 translate-x-0"
    leave-to-class="opacity-0 translate-x-4"
  >
    <aside
      v-if="open"
      class="fixed right-0 top-0 z-40 flex h-full w-[360px] flex-col border-l border-border bg-card shadow-xl"
    >
      <header class="flex items-center justify-between gap-sm border-b border-border px-lg py-md">
        <div class="flex items-center gap-xs">
          <span class="font-medium">文档工作区</span>
          <span class="text-xs text-muted-foreground">({{ docs.length }} 份)</span>
        </div>
        <div class="flex items-center gap-xs">
          <button
            type="button"
            class="rounded-md p-xs text-muted-foreground hover:bg-muted"
            title="刷新"
            :disabled="loading"
            @click="load"
          >
            <RefreshCw class="size-md" :class="{ 'animate-spin': loading }" />
          </button>
          <button
            type="button"
            class="rounded-md p-xs text-muted-foreground hover:bg-muted"
            title="关闭"
            @click="emit('close')"
          >
            <X class="size-md" />
          </button>
        </div>
      </header>

      <div class="flex-1 overflow-y-auto p-lg">
        <div v-if="loading" class="text-sm text-muted-foreground">加载中…</div>

        <ul v-else-if="docs.length > 0" class="space-y-sm">
          <li
            v-for="d in docs"
            :key="d.id"
            class="cursor-pointer rounded-md border border-border bg-background p-md text-sm hover:border-primary"
            @click="emit('open-document', d.id)"
          >
            <div class="flex items-start gap-sm">
              <component :is="iconFor(d.mimeType)" class="size-lg mt-xs shrink-0 text-muted-foreground" />
              <div class="min-w-0 flex-1">
                <div class="truncate font-medium">{{ d.fileName }}</div>
                <div class="mt-xs text-xs text-muted-foreground">
                  v{{ d.latestVersion }} · {{ formatSize(d.fileSize) }} · {{ d.origin }}
                </div>
                <div v-if="d.sourcePath" class="mt-xs truncate text-xs text-muted-foreground">
                  {{ d.sourcePath }}
                </div>
              </div>
            </div>
          </li>
        </ul>

        <div v-else class="text-sm text-muted-foreground">
          当前会话还没有编辑中的文档。<br />
          让 AI 帮你改一份 docx / xlsx，这里会记录工作副本。
        </div>
      </div>
    </aside>
  </Transition>
</template>
