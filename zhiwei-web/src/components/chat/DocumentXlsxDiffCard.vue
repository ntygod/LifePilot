<script setup lang="ts">
/**
 * xlsx 文档 Diff 卡片 —— Phase 3B，cell/row/range 粒度渲染。
 *
 * 流程交互（commit/discard/rollback refresh + 对话框）全部委托给 useDocumentDiffCard；
 * 本组件只处理 xlsx 专属：update_cell 双列、insert/delete/set_range 单向渲染 + 位置标签。
 *
 * @author zsg
 * @since 2026-04-21
 */
import { toRef } from 'vue'
import DocumentDiffHeader from './DocumentDiffHeader.vue'
import DocumentDiffActions from './DocumentDiffActions.vue'
import DocumentVersionHistoryList from './DocumentVersionHistoryList.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import PromptDialog from '@/components/common/PromptDialog.vue'
import { useDocumentDiffCard } from '@/composables/useDocumentDiffCard'
import type { DiffChange } from '@/api/documents'

interface Props {
  documentId: string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'committed', documentId: string, backupPath: string | undefined): void
  (e: 'discarded', documentId: string): void
}>()

const {
  expanded,
  loading,
  metadata,
  diff,
  canOverwrite,
  hasChanges,
  pendingConfirm,
  pendingPrompt,
  lastCommittedPath,
  isTauri,
  onOverwrite,
  onSaveAs,
  onDiscard,
  onRollbackComplete,
  onCompareVersions,
  runPendingConfirm,
  runPendingPrompt,
  openCommittedFile,
  revealCommittedFile,
} = useDocumentDiffCard(toRef(props, 'documentId'), emit)

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

          <!-- set_range：折叠表格，点击展开看真实 2D 值（P1-9） -->
          <div v-else-if="c.op === 'set_range' && c.values && c.values.length > 0">
            <details class="text-xs">
              <summary class="cursor-pointer diff-insert rounded px-xs py-xs">
                {{ rowSingleText(c) }}（点击展开查看）
              </summary>
              <table class="mt-xs w-full border-collapse text-xs">
                <tbody>
                  <tr v-for="(row, rIdx) in c.values" :key="rIdx">
                    <td
                      v-for="(cell, cIdx) in row"
                      :key="cIdx"
                      class="diff-insert border border-border px-xs py-xs"
                    >{{ cell }}</td>
                  </tr>
                </tbody>
              </table>
            </details>
          </div>

          <!-- insert_row：单向 insert -->
          <div v-else-if="c.op === 'insert_row'">
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
        @compare-versions="onCompareVersions"
      />

      <DocumentDiffActions
        v-if="hasChanges"
        :can-overwrite="canOverwrite"
        class="mt-md"
        @overwrite="onOverwrite"
        @save-as="onSaveAs"
        @discard="onDiscard"
      />

      <!-- Tauri 环境：commit 成功后显示"打开 / 定位"按钮 -->
      <div v-if="isTauri && lastCommittedPath" class="mt-md flex items-center gap-sm text-sm">
        <span class="text-muted-foreground">已落盘：{{ lastCommittedPath }}</span>
        <button
          type="button"
          class="inline-flex items-center gap-xs rounded-md border border-border px-md py-xs text-xs"
          @click="openCommittedFile"
        >用默认程序打开</button>
        <button
          type="button"
          class="inline-flex items-center gap-xs rounded-md border border-border px-md py-xs text-xs"
          @click="revealCommittedFile"
        >在文件管理器中定位</button>
      </div>
    </div>

    <ConfirmDialog
      v-if="pendingConfirm"
      :show="true"
      :title="pendingConfirm.title"
      :message="pendingConfirm.message"
      :confirm-variant="pendingConfirm.variant"
      @confirm="runPendingConfirm"
      @cancel="pendingConfirm = null"
    />
    <PromptDialog
      v-if="pendingPrompt"
      :show="true"
      :title="pendingPrompt.title"
      :message="pendingPrompt.message"
      :placeholder="pendingPrompt.placeholder"
      @confirm="runPendingPrompt"
      @cancel="pendingPrompt = null"
    />
  </div>
</template>

<style scoped>
.diff-delete {
  background-color: var(--diff-delete-bg);
  color: var(--diff-delete-fg);
}

.diff-insert {
  background-color: var(--diff-insert-bg);
  color: var(--diff-insert-fg);
}
</style>
