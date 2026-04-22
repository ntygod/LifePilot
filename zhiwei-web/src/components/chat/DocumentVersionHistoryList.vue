<script setup lang="ts">
/**
 * 文档版本历史列表 —— 共享子件，docx / xlsx DiffCard 都挂。
 *
 * 内置二次确认、成功后触发 rollback-complete 让父组件刷新 diff。
 *
 * @author zsg
 * @since 2026-04-21
 */
import { ref, onMounted, computed } from 'vue'
import { History, RotateCcw, ChevronDown, ChevronUp } from 'lucide-vue-next'
import {
  listVersions,
  rollback,
  type DocumentVersionInfo,
} from '@/api/documents'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
const pendingRollbackVersion = ref<number | null>(null)

interface Props {
  documentId: string
  /** 当前最新版本号 —— 该版本不显示"回滚"按钮（回滚到当前无意义） */
  currentVersion: number
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'rollback-complete', newVersion: number): void
}>()

const expanded = ref(false)
const loading = ref(false)
const versions = ref<DocumentVersionInfo[]>([])

async function toggleExpand() {
  expanded.value = !expanded.value
  if (expanded.value && versions.value.length === 0) await load()
}

async function load() {
  loading.value = true
  try {
    // pageSize=100 覆盖历史展开视图的全量需求；后续若版本数超 100 再做真正分页 UI
    const page = await listVersions(props.documentId, 1, 100)
    versions.value = page.items
    // 倒序：最新在上
    versions.value.sort((a, b) => b.versionNo - a.versionNo)
  } catch (e) {
    console.warn('加载版本历史失败', e)
  } finally {
    loading.value = false
  }
}

function onRollback(version: number) {
  pendingRollbackVersion.value = version
}

async function confirmRollback() {
  const version = pendingRollbackVersion.value
  pendingRollbackVersion.value = null
  if (version === null) return
  try {
    const r = await rollback(props.documentId, version)
    emit('rollback-complete', r.newVersion)
    // 刷新列表
    await load()
  } catch (e) {
    console.error('回滚失败', e)
    ui.showToast('error', '回滚失败，请查看控制台日志')
  }
}

const headerLabel = computed(() =>
  versions.value.length > 0
    ? `版本历史 (${versions.value.length} 个版本)`
    : '版本历史'
)

// onMounted 不预加载（只有展开时才请求）
void onMounted
</script>

<template>
  <div class="document-version-history rounded-md border border-border bg-muted/40 p-md text-sm">
    <button
      type="button"
      class="flex w-full items-center justify-between gap-sm text-left"
      @click="toggleExpand"
    >
      <span class="flex items-center gap-xs">
        <History class="size-md text-muted-foreground" />
        <span>{{ headerLabel }}</span>
      </span>
      <component :is="expanded ? ChevronUp : ChevronDown" class="size-md" />
    </button>

    <div v-if="expanded" class="mt-md">
      <div v-if="loading" class="text-muted-foreground">加载中…</div>

      <ul v-else-if="versions.length > 0" class="space-y-xs">
        <li
          v-for="v in versions"
          :key="v.versionNo"
          class="flex items-center justify-between gap-sm rounded-md border border-border bg-background p-sm"
        >
          <div class="min-w-0">
            <div class="truncate">
              <span class="font-medium">v{{ v.versionNo }}</span>
              <span class="ml-xs text-muted-foreground">· {{ v.source }}</span>
              <span v-if="v.patchSummary" class="ml-xs text-muted-foreground">
                · {{ v.patchSummary }}
              </span>
            </div>
            <div class="text-xs text-muted-foreground">
              {{ new Date(v.createdAt).toLocaleString() }}
            </div>
          </div>
          <button
            v-if="v.versionNo !== currentVersion"
            type="button"
            class="shrink-0 inline-flex items-center gap-xs rounded-md border border-border px-md py-xs text-xs"
            @click="onRollback(v.versionNo)"
          >
            <RotateCcw class="size-md" />
            <span>回滚</span>
          </button>
          <span v-else class="shrink-0 text-xs text-muted-foreground">（当前）</span>
        </li>
      </ul>

      <div v-else class="text-muted-foreground">尚无历史版本</div>
    </div>

    <ConfirmDialog
      v-if="pendingRollbackVersion !== null"
      :show="true"
      title="回滚文档版本"
      :message="`确认回滚到版本 v${pendingRollbackVersion}？将生成一个新版本，历史不会丢失。`"
      @confirm="confirmRollback"
      @cancel="pendingRollbackVersion = null"
    />
  </div>
</template>
