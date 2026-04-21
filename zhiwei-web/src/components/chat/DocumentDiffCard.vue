<script setup lang="ts">
/**
 * 文档 Diff 卡片 —— Phase 3A（docx），Phase 3B 接入共享 Header/Actions/VersionHistoryList。
 *
 * @author zsg
 * @since 2026-04-21（P3B 重构）
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

/** 回滚完成后重载 metadata + diff */
async function onRollbackComplete() {
  diff.value = null
  await loadData()
}

onMounted(loadData)
</script>

<template>
  <div class="document-diff-card rounded-md border border-border bg-card p-md text-sm">
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
          <div class="mb-xs text-xs text-muted-foreground">修改 {{ idx + 1 }}：{{ c.op }}</div>
          <div v-if="c.paragraph_preview" class="mb-xs text-xs text-muted-foreground">
            {{ c.paragraph_preview }}
          </div>
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
