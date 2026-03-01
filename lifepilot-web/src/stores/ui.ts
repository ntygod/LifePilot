import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

export type ToastType = 'success' | 'error' | 'info'

interface Toast {
  id: number
  type: ToastType
  message: string
}

export const useUiStore = defineStore('ui', () => {
  const loadingCount = ref(0)
  const toasts = ref<Toast[]>([])
  let nextId = 1

  const isGlobalLoading = computed(() => loadingCount.value > 0)

  function startLoading() {
    loadingCount.value += 1
  }

  function stopLoading() {
    if (loadingCount.value > 0) {
      loadingCount.value -= 1
    }
  }

  function showToast(type: ToastType, message: string | null, duration = 3000) {
    const id = nextId++
    const finalMessage = message ?? ''
    toasts.value.push({ id, type, message: finalMessage })

    window.setTimeout(() => {
      clearToast(id)
    }, duration)
  }

  function clearToast(id: number) {
    toasts.value = toasts.value.filter(t => t.id !== id)
  }

  return {
    isGlobalLoading,
    toasts,
    startLoading,
    stopLoading,
    showToast,
    clearToast,
  }
})

