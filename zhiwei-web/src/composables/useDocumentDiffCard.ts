import { computed, onMounted, ref, type Ref } from 'vue'
import {
  commit,
  discardWorkingCopy,
  getDiff,
  getDocument,
  parseDiffJson,
  type DiffPayload,
  type DocumentMetadata,
} from '@/api/documents'
import { useUiStore } from '@/stores/ui'

export interface DiffCardEmits {
  (e: 'committed', documentId: string, backupPath: string | undefined): void
  (e: 'discarded', documentId: string): void
}

interface PendingConfirm {
  title: string
  message: string
  variant: 'default' | 'destructive'
  onConfirm: () => void | Promise<void>
}

interface PendingPrompt {
  title: string
  message: string
  placeholder: string
  onSubmit: (value: string) => void | Promise<void>
}

/**
 * DocumentDiffCard / DocumentXlsxDiffCard 的共享逻辑 —— 元数据+diff 拉取、
 * 提交/另存/丢弃/回滚后刷新 5 个操作与它们的 dialog 交互全部在此。
 * 两个卡片组件只需写 docx / xlsx 差异的渲染 template。
 */
export function useDocumentDiffCard(
  documentId: Ref<string>,
  emit: DiffCardEmits,
) {
  const ui = useUiStore()

  const expanded = ref(false)
  const loading = ref(false)
  const metadata = ref<DocumentMetadata | null>(null)
  const diff = ref<DiffPayload | null>(null)

  const pendingConfirm = ref<PendingConfirm | null>(null)
  const pendingPrompt = ref<PendingPrompt | null>(null)

  const canOverwrite = computed(() => !!metadata.value?.sourcePath)
  const hasChanges = computed(
    () => !!metadata.value && metadata.value.latestVersion > 0,
  )

  async function loadData() {
    loading.value = true
    try {
      metadata.value = await getDocument(documentId.value)
      if (metadata.value.latestVersion > 0) {
        const from = metadata.value.latestVersion - 1
        const to = metadata.value.latestVersion
        const payload = await getDiff(documentId.value, from, to)
        diff.value = parseDiffJson(payload.diffJson)
      }
    } catch (e) {
      console.warn('加载文档 diff 失败', e)
    } finally {
      loading.value = false
    }
  }

  function onOverwrite() {
    if (!canOverwrite.value) return
    const sourcePath = metadata.value?.sourcePath ?? ''
    pendingConfirm.value = {
      title: '覆盖原文件',
      message: `确认覆盖 ${sourcePath} 吗？系统会自动生成 .bak 备份。`,
      variant: 'destructive',
      async onConfirm() {
        try {
          const result = await commit(documentId.value, 'overwrite')
          emit('committed', documentId.value, result.backupPath)
          ui.showToast('success', `已覆盖；备份：${result.backupPath || '无'}`)
        } catch (e) {
          console.error('覆盖失败', e)
          ui.showToast('error', '覆盖失败，请查看控制台日志')
        }
      },
    }
  }

  function onSaveAs() {
    pendingPrompt.value = {
      title: '另存为',
      message: '请输入绝对路径：',
      placeholder: '例如 D:/docs/output.docx',
      async onSubmit(path) {
        const trimmed = path.trim()
        if (!trimmed) return
        try {
          const result = await commit(documentId.value, 'saveAs', trimmed)
          emit('committed', documentId.value, undefined)
          ui.showToast('success', `已另存到：${result.committedPath}`)
        } catch (e) {
          console.error('另存失败', e)
          ui.showToast('error', '另存失败，请查看控制台日志')
        }
      },
    }
  }

  function onDiscard() {
    pendingConfirm.value = {
      title: '丢弃工作副本',
      message: '丢弃后将不可恢复，继续？',
      variant: 'destructive',
      async onConfirm() {
        try {
          await discardWorkingCopy(documentId.value)
          emit('discarded', documentId.value)
        } catch (e) {
          console.error('丢弃失败', e)
          ui.showToast('error', '丢弃失败，请查看控制台日志')
        }
      },
    }
  }

  async function onRollbackComplete() {
    diff.value = null
    await loadData()
  }

  async function runPendingConfirm() {
    const pending = pendingConfirm.value
    pendingConfirm.value = null
    if (pending) await pending.onConfirm()
  }

  async function runPendingPrompt(value: string) {
    const pending = pendingPrompt.value
    pendingPrompt.value = null
    if (pending) await pending.onSubmit(value)
  }

  onMounted(loadData)

  return {
    expanded,
    loading,
    metadata,
    diff,
    canOverwrite,
    hasChanges,
    pendingConfirm,
    pendingPrompt,
    loadData,
    onOverwrite,
    onSaveAs,
    onDiscard,
    onRollbackComplete,
    runPendingConfirm,
    runPendingPrompt,
  }
}
