<script setup lang="ts">
/**
 * 文档 Diff 卡片 —— Phase 3A 消息气泡附件渲染组件。
 *
 * 三状态：未加载 / 加载中 / 已加载；
 * 折叠态展示文件名 + 改动摘要 + 版本号；
 * 展开态逐条展示 change（含 inline diff）并提供三按钮：
 * 应用到原路径（仅 sourcePath 非空时可见）/ 另存为 / 丢弃。
 *
 * @author zsg
 * @since 2026-04-21
 */
import { computed, ref, onMounted } from 'vue'
import { ChevronDown, ChevronUp, FileText, Upload, Save, Trash2 } from 'lucide-vue-next'
import {
  getDocument,
  getDiff,
  commit,
  discardWorkingCopy,
  parseDiffJson,
  type DocumentMetadata,
  type DiffPayload,
} from '@/api/documents'

interface Props {
  documentId: string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'committed', documentId: string, backupPath: string | undefined): void
  (e: 'discarded', documentId: string): void
}>()

const expanded = ref(false)
const loading = ref(false)
const metadata = ref<DocumentMetadata | null>(null)
const diff = ref<DiffPayload | null>(null)

/** 覆盖按钮仅在原始路径存在时可用（外部导入的附件 sourcePath 为 null） */
const canOverwrite = computed(() => !!metadata.value?.sourcePath)
/** 是否存在可提交的改动 —— latestVersion > 0 意味着至少经历过一次 patch */
const hasChanges = computed(
  () => !!metadata.value && metadata.value.latestVersion > 0,
)

async function loadData() {
  loading.value = true
  try {
    metadata.value = await getDocument(props.documentId)
    if (metadata.value.latestVersion > 0) {
      const from = metadata.value.latestVersion - 1
      const to = metadata.value.latestVersion
      const payload = await getDiff(props.documentId, from, to)
      diff.value = parseDiffJson(payload.diffJson)
    }
  } catch (e) {
    console.warn('加载文档 diff 失败', e)
  } finally {
    loading.value = false
  }
}

async function onOverwrite() {
  if (!canOverwrite.value) return
  const sourcePath = metadata.value?.sourcePath ?? ''
  if (!confirm(`确认覆盖原文件 ${sourcePath} 吗？系统会自动生成 .bak 备份。`)) return
  try {
    const result = await commit(props.documentId, 'overwrite')
    emit('committed', props.documentId, result.backupPath)
    alert('已覆盖；备份：' + (result.backupPath || '无'))
  } catch (e) {
    console.error('覆盖失败', e)
    alert('覆盖失败，请查看控制台日志。')
  }
}

async function onSaveAs() {
  const path = prompt('另存为绝对路径：')
  if (!path || !path.trim()) return
  try {
    const result = await commit(props.documentId, 'saveAs', path.trim())
    emit('committed', props.documentId, undefined)
    alert('已另存到：' + result.committedPath)
  } catch (e) {
    console.error('另存失败', e)
    alert('另存失败，请查看控制台日志。')
  }
}

async function onDiscard() {
  if (!confirm('丢弃工作副本将不可恢复，继续？')) return
  try {
    await discardWorkingCopy(props.documentId)
    emit('discarded', props.documentId)
  } catch (e) {
    console.error('丢弃失败', e)
    alert('丢弃失败，请查看控制台日志。')
  }
}

onMounted(loadData)
</script>

<template>
  <div class="document-diff-card rounded-md border border-border bg-card p-md text-sm">
    <button
      type="button"
      class="flex w-full items-center justify-between gap-sm text-left"
      @click="expanded = !expanded"
    >
      <span class="flex min-w-0 items-center gap-xs">
        <FileText class="size-md shrink-0 text-muted-foreground" />
        <span class="truncate font-medium">{{ metadata?.fileName || '加载中…' }}</span>
        <span v-if="diff" class="truncate text-muted-foreground">
          · {{ diff.summary }} · v{{ diff.fromVersion }} → v{{ diff.toVersion }}
        </span>
      </span>
      <component :is="expanded ? ChevronUp : ChevronDown" class="size-md shrink-0" />
    </button>

    <div v-if="expanded" class="mt-md">
      <div v-if="loading" class="text-muted-foreground">加载中…</div>

      <div v-else-if="diff" class="space-y-md">
        <div
          v-for="(c, idx) in diff.changes"
          :key="c.patch_id"
          class="rounded-md border border-border p-md"
        >
          <div class="mb-xs text-xs text-muted-foreground">修改 {{ idx + 1 }}：{{ c.op }}</div>
          <div class="mb-xs text-xs text-muted-foreground">{{ c.paragraph_preview }}</div>
          <p class="leading-relaxed">
            <template v-for="(seg, i) in c.segments" :key="i">
              <span v-if="seg.type === 'keep'">{{ seg.text }}</span>
              <span
                v-else-if="seg.type === 'delete'"
                class="diff-delete rounded px-xs line-through"
              >
                {{ seg.text }}
              </span>
              <span v-else class="diff-insert rounded px-xs">{{ seg.text }}</span>
            </template>
          </p>
          <div v-if="c.reason" class="mt-xs text-xs text-muted-foreground">原因：{{ c.reason }}</div>
        </div>
      </div>

      <div v-else-if="hasChanges" class="text-muted-foreground">diff 数据暂不可用</div>
      <div v-else class="text-muted-foreground">尚无改动</div>

      <div v-if="hasChanges" class="mt-md flex items-center gap-sm">
        <button
          v-if="canOverwrite"
          type="button"
          class="action-btn action-btn-primary"
          @click="onOverwrite"
        >
          <Upload class="size-md" />
          <span>应用到原路径</span>
        </button>
        <button
          type="button"
          class="action-btn action-btn-secondary"
          @click="onSaveAs"
        >
          <Save class="size-md" />
          <span>另存为…</span>
        </button>
        <button
          type="button"
          class="action-btn action-btn-secondary text-destructive"
          @click="onDiscard"
        >
          <Trash2 class="size-md" />
          <span>丢弃</span>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* inline diff 的删除/插入高亮；避免 Tailwind 任意值 bg-red-100/bg-green-100 */
.diff-delete {
  background-color: hsl(0 80% 94%);
  color: hsl(0 72% 38%);
}

.diff-insert {
  background-color: hsl(142 70% 92%);
  color: hsl(142 64% 30%);
}

/* 三按钮复用 class variant：内边距 / 圆角 / 图标对齐 */
.action-btn {
  @apply inline-flex items-center gap-xs rounded-md px-md py-xs text-sm;
}

.action-btn-primary {
  @apply bg-primary text-primary-foreground;
}

.action-btn-secondary {
  @apply border border-border bg-transparent;
}
</style>
