<script setup lang="ts">
/**
 * 文档 Diff 卡片 —— docx 版，流程交互全部委托给 useDocumentDiffCard composable，
 * 本组件只负责 docx 专属的"inline segment"渲染。
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

      <!-- Tauri 环境：commit 成功后显示"打开 / 定位"按钮（Web 浏览器无此原生能力） -->
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
