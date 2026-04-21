<script setup lang="ts">
/**
 * xlsx 文档 Diff 卡片 —— Phase 3B 新增，按 cell / row / range 粒度渲染。
 *
 * 与 docx DiffCard 的差异：
 * - update_cell 双列 "[before] → [after]"，不走段内 inline segment
 * - insert_row / delete_row 以 "sheet!row N" 标注，单向 insert / delete
 * - set_range 显示 "rows × cols 批量（预览略）"，避免 2D 数据过载
 *
 * @author zsg
 * @since 2026-04-21
 */
import { computed, ref, onMounted } from 'vue'
import DocumentDiffHeader from './DocumentDiffHeader.vue'
import DocumentDiffActions from './DocumentDiffActions.vue'
import DocumentVersionHistoryList from './DocumentVersionHistoryList.vue'
import {
  getDocument,
  getDiff,
  commit,
  discardWorkingCopy,
  parseDiffJson,
  type DocumentMetadata,
  type DiffPayload,
  type DiffChange,
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

const canOverwrite = computed(() => !!metadata.value?.sourcePath)
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
    console.warn('加载 xlsx diff 失败', e)
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

async function onRollbackComplete() {
  diff.value = null
  await loadData()
}

/** 为 update_cell 的 diff 生成 before / after 预览文本 */
function cellBefore(c: DiffChange): string {
  const delSeg = c.segments.find(s => s.type === 'delete')
  return delSeg?.text || '(空)'
}

function cellAfter(c: DiffChange): string {
  const insSeg = c.segments.find(s => s.type === 'insert')
  return insSeg?.text || '(空)'
}

/** 为 insert_row / delete_row / set_range 的 change 生成单向文本 */
function rowSingleText(c: DiffChange): string {
  const seg = c.segments[0]
  return seg?.text ?? ''
}

/** change 头部的位置标签 */
function locationLabel(c: DiffChange): string {
  const sheet = c.sheet ?? ''
  if (c.op === 'update_cell' && c.cell) return `${sheet}!${c.cell}`
  if ((c.op === 'insert_row' || c.op === 'delete_row') && c.row !== undefined) {
    return `${sheet}!row ${c.row}`
  }
  if (c.op === 'set_range' && c.range) {
    const size = c.rows && c.cols ? ` (${c.rows}×${c.cols})` : ''
    return `${sheet}!${c.range}${size}`
  }
  return sheet
}

onMounted(loadData)
</script>

<template>
  <div class="document-xlsx-diff-card rounded-md border border-border bg-card p-md text-sm">
    <DocumentDiffHeader
      :file-name="metadata?.fileName"
      :summary="diff?.summary"
      :from-version="diff?.fromVersion"
      :to-version="diff?.toVersion"
      :expanded="expanded"
      @toggle="expanded = !expanded"
    />

    <div v-if="expanded" class="mt-md">
      <div v-if="loading" class="text-muted-foreground">加载中…</div>

      <div v-else-if="diff" class="space-y-md">
        <div
          v-for="(c, idx) in diff.changes"
          :key="c.patch_id"
          class="rounded-md border border-border p-md"
        >
          <div class="mb-xs text-xs text-muted-foreground">
            修改 {{ idx + 1 }}：{{ c.op }} · {{ locationLabel(c) }}
          </div>

          <!-- update_cell：双列 before → after -->
          <div v-if="c.op === 'update_cell'" class="flex items-center gap-sm">
            <span class="diff-delete rounded px-xs line-through">{{ cellBefore(c) }}</span>
            <span class="text-muted-foreground">→</span>
            <span class="diff-insert rounded px-xs">{{ cellAfter(c) }}</span>
          </div>

          <!-- insert_row / set_range：单向 insert -->
          <div v-else-if="c.op === 'insert_row' || c.op === 'set_range'">
            <span class="diff-insert rounded px-xs">{{ rowSingleText(c) }}</span>
          </div>

          <!-- delete_row：单向 delete -->
          <div v-else-if="c.op === 'delete_row'">
            <span class="diff-delete rounded px-xs line-through">{{ rowSingleText(c) }}</span>
          </div>

          <!-- 兜底：未知 op 直接展示 segments -->
          <div v-else>
            <span v-for="(seg, i) in c.segments" :key="i">{{ seg.text }}</span>
          </div>

          <div v-if="c.reason" class="mt-xs text-xs text-muted-foreground">原因：{{ c.reason }}</div>
        </div>
      </div>

      <div v-else-if="hasChanges" class="text-muted-foreground">diff 数据暂不可用</div>
      <div v-else class="text-muted-foreground">尚无改动</div>

      <DocumentVersionHistoryList
        v-if="metadata"
        :document-id="documentId"
        :current-version="metadata.latestVersion"
        class="mt-md"
        @rollback-complete="onRollbackComplete"
      />

      <DocumentDiffActions
        v-if="hasChanges"
        :can-overwrite="canOverwrite"
        class="mt-md"
        @overwrite="onOverwrite"
        @save-as="onSaveAs"
        @discard="onDiscard"
      />
    </div>
  </div>
</template>

<style scoped>
.diff-delete {
  background-color: hsl(0 80% 94%);
  color: hsl(0 72% 38%);
}

.diff-insert {
  background-color: hsl(142 70% 92%);
  color: hsl(142 64% 30%);
}
</style>
