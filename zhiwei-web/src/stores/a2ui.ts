import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { A2uiComponent } from '@/types'

export const useA2uiStore = defineStore('a2ui', () => {
  // 当前 A2UI 组件列表（邻接表扁平数组）
  const components = ref<A2uiComponent[]>([])

  /** 更新组件树 */
  function updateComponents(newComponents: A2uiComponent[]) {
    components.value = newComponents
  }

  /** 清空组件树 */
  function clearComponents() {
    components.value = []
  }

  return { components, updateComponents, clearComponents }
})
