import { ref } from 'vue'
import { getDocument, type DocumentMetadata } from '@/api/documents'

// 模块级共享 cache —— 所有 MessageBubble 实例 + useChat 都引用同一份，
// 在 AI 流式结束（见 useChat.ts done event）时全量失效，
// 让下次渲染重新拉 latestVersion，把过期的"下载链接"升级为 DocumentDiffCard
const documentMetaCache = ref<Record<string, DocumentMetadata | null>>({})

/** 幂等加载文档元数据到缓存；失败写 null 避免反复触发。已有缓存直接返回。 */
async function resolveDocumentMeta(docId: string) {
  if (documentMetaCache.value[docId] !== undefined) return
  try {
    documentMetaCache.value[docId] = await getDocument(docId)
  } catch {
    documentMetaCache.value[docId] = null
  }
}

/** 失效单个 docId 的 cache（DiffCard commit / discard 成功时调用）。 */
function invalidateDocumentMeta(docId: string) {
  delete documentMetaCache.value[docId]
}

/** 清空所有 cache（AI 流式结束后，任意文档都可能被 patch 过）。 */
function invalidateAllDocumentMeta() {
  for (const id of Object.keys(documentMetaCache.value)) {
    delete documentMetaCache.value[id]
  }
}

export function useDocumentMeta() {
  return {
    documentMetaCache,
    resolveDocumentMeta,
    invalidateDocumentMeta,
    invalidateAllDocumentMeta,
  }
}
