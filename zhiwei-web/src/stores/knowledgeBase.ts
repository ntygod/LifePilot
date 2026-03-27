import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { KnowledgeBase, KbDocument, CreateKbRequest } from '@/types'
import { knowledgeBaseApi } from '@/api/client'

export const useKnowledgeBaseStore = defineStore('knowledgeBase', () => {
  const list = ref<KnowledgeBase[]>([])
  const current = ref<KnowledgeBase | null>(null)
  const documents = ref<KbDocument[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetchList() {
    loading.value = true
    error.value = null
    try {
      list.value = await knowledgeBaseApi.list()
    } catch (e: any) {
      error.value = e.message ?? '加载知识库列表失败'
    } finally {
      loading.value = false
    }
  }

  async function create(req: CreateKbRequest) {
    error.value = null
    try {
      const kb = await knowledgeBaseApi.create(req)
      list.value.push(kb)
      return kb
    } catch (e: any) {
      error.value = e.message ?? '创建知识库失败'
    }
  }

  async function remove(id: string) {
    error.value = null
    try {
      await knowledgeBaseApi.delete(id)
      list.value = list.value.filter(kb => kb.id !== id)
      if (current.value?.id === id) {
        current.value = null
        documents.value = []
      }
    } catch (e: any) {
      error.value = e.message ?? '删除知识库失败'
    }
  }

  async function fetchDocuments(kbId: string) {
    loading.value = true
    error.value = null
    try {
      documents.value = await knowledgeBaseApi.listDocuments(kbId)
    } catch (e: any) {
      error.value = e.message ?? '加载文档列表失败'
    } finally {
      loading.value = false
    }
  }

  async function uploadDocument(kbId: string, file: File, datastoreId?: string) {
    error.value = null
    try {
      const doc = await knowledgeBaseApi.uploadDocument(kbId, file, datastoreId)
      documents.value.push(doc)
      return doc
    } catch (e: any) {
      error.value = e.message ?? '上传文档失败'
    }
  }

  async function removeDocument(kbId: string, docId: string) {
    error.value = null
    try {
      await knowledgeBaseApi.deleteDocument(kbId, docId)
      documents.value = documents.value.filter(d => d.id !== docId)
    } catch (e: any) {
      error.value = e.message ?? '删除文档失败'
    }
  }

  // 向后兼容的别名
  const fetchKnowledgeBases = fetchList

  return {
    list, current, documents, loading, error,
    fetchList, fetchKnowledgeBases, create, remove, fetchDocuments, uploadDocument, removeDocument
  }
})
