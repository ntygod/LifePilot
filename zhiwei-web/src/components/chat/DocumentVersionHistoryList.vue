<script setup lang="ts">
/**
 * 文档版本历史列表 —— 共享子件，docx / xlsx DiffCard 都挂。
 *
 * 支持：
 * - 回滚到任一历史版本（内置二次确认）
 * - P1-7：多选任意两版本对比（emit compare-versions 让父组件拉 diff）
 *
 * @author zsg
 * @since 2026-04-21
 */
import { ref, onMounted, computed } from 'vue'
import { History, RotateCcw, ChevronDown, ChevronUp, GitCompare, X } from 'lucide-vue-next'
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
  (e: 'compare-versions', from: number, to: number): void
}>()

const expanded = ref(false)
const loading = ref(false)
const versions = ref<DocumentVersionInfo[]>([])

// P1-7 对比模式：true 时每行出现选框，用户可选 1 个或 2 个版本
const compareMode = ref(false)
const selected = ref<number[]>([])

async function toggleExpand() {
  expanded.value = !expanded.value
  if (expanded.value && versions.value.length === 0) await load()
}

async function load() {
  loading.value = true
  try {
    const page = await listVersions(props.documentId, 1, 100)
    versions.value = page.items
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
    await load()
  } catch (e) {
    console.error('回滚失败', e)
    ui.showToast('error', '回滚失败，请查看控制台日志')
  }
}

function toggleCompareMode() {
  compareMode.value = !compareMode.value
  selected.value = []
}

function toggleSelect(versionNo: number) {
  const idx = selected.value.indexOf(versionNo)
  if (idx >= 0) {
    selected.value.splice(idx, 1)
    return
  }
  if (selected.value.length >= 2) {
    // 已选两个，新点击的替换第一个（FIFO）
    selected.value.shift()
  }
  selected.value.push(versionNo)
}

function doCompare() {
  if (selected.value.length !== 2) return
  const [a, b] = selected.value
  const from = Math.min(a, b)
  const to = Math.max(a, b)
  emit('compare-versions', from, to)
  compareMode.value = false
  selected.value = []
}

const headerLabel = computed(() =>
  versions.value.length > 0
    ? `版本历史 (${versions.value.length} 个版本)`
    : '版本历史'
)

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
      <!-- 对比模式工具栏 -->
      <div v-if="versions.length >= 2" class="mb-sm flex items-center justify-between gap-sm">
        <button
          type="button"
          class="inline-flex items-center gap-xs rounded-md border border-border px-md py-xs text-xs"
          @click="toggleCompareMode"
        >
          <component :is="compareMode ? X : GitCompare" class="size-md" />
          <span>{{ compareMode ? '取消对比' : '对比两版本' }}</span>
        </button>
        <button
          v-if="compareMode && selected.length === 2"
          type="button"
          class="inline-flex items-center gap-xs rounded-md bg-primary px-md py-xs text-xs text-primary-foreground"
          @click="doCompare"
        >
          <span>对比 v{{ Math.min(...selected) }} ↔ v{{ Math.max(...selected) }}</span>
        </button>
        <span v-else-if="compareMode" class="text-xs text-muted-foreground">
          已选 {{ selected.length }} / 2
        </span>
      </div>

      <div v-if="loading" class="text-muted-foreground">加载中…</div>

      <ul v-else-if="versions.length > 0" class="space-y-xs">
        <li
          v-for="v in versions"
          :key="v.versionNo"
          class="flex items-center justify-between gap-sm rounded-md border p-sm"
          :class="[
            compareMode && selected.includes(v.versionNo)
              ? 'border-primary bg-primary/10'
              : 'border-border bg-background',
          ]"
        >
          <label
            v-if="compareMode"
            class="flex min-w-0 flex-1 cursor-pointer items-center gap-sm"
          >
            <input
              type="checkbox"
              :checked="selected.includes(v.versionNo)"
              @change="toggleSelect(v.versionNo)"
            />
            <div class="min-w-0 flex-1">
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
          </label>

          <div v-else class="min-w-0">
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
            v-if="!compareMode && v.versionNo !== currentVersion"
            type="button"
            class="shrink-0 inline-flex items-center gap-xs rounded-md border border-border px-md py-xs text-xs"
            @click="onRollback(v.versionNo)"
          >
            <RotateCcw class="size-md" />
            <span>回滚</span>
          </button>
          <span
            v-else-if="!compareMode && v.versionNo === currentVersion"
            class="shrink-0 text-xs text-muted-foreground"
          >（当前）</span>
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
