import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Datastore } from '@/types'
import { datastoreApi } from '@/api/client'

export const useDatastoreStore = defineStore('datastore', () => {
  const list = ref<Datastore[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetchList(q?: string) {
    loading.value = true
    error.value = null
    try {
      list.value = await datastoreApi.list(q)
    } catch (e: any) {
      error.value = e.message ?? '加载 datastore 列表失败'
    } finally {
      loading.value = false
    }
  }

  return {
    list,
    loading,
    error,
    fetchList,
  }
})
