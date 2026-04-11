import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { CreateDatastoreRequest, Datastore, UpdateDatastoreRequest } from '@/types'
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

  async function createDatastore(data: CreateDatastoreRequest): Promise<Datastore> {
    const created = await datastoreApi.create(data)
    list.value = [created, ...list.value]
    return created
  }

  async function updateDatastore(id: string, data: UpdateDatastoreRequest): Promise<Datastore> {
    const updated = await datastoreApi.update(id, data)
    const idx = list.value.findIndex(item => item.id === id)
    if (idx !== -1) {
      list.value[idx] = updated
    }
    return updated
  }

  async function deleteDatastore(id: string) {
    await datastoreApi.delete(id)
    list.value = list.value.filter(item => item.id !== id)
  }

  return {
    list,
    loading,
    error,
    fetchList,
    createDatastore,
    updateDatastore,
    deleteDatastore,
  }
})
