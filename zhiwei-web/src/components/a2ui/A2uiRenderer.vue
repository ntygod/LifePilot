<script setup lang="ts">
import { computed } from 'vue'
import type { A2uiComponent } from '@/types'
import { resolveComponent } from './componentCatalog'

const props = defineProps<{
  /** 扁平邻接表组件数组 */
  components: A2uiComponent[]
  /** 要渲染的根节点 ID 列表（不传则自动计算顶层节点） */
  rootIds?: string[]
}>()

/** 按 ID 索引组件，便于子节点查找 */
const componentMap = computed(() => {
  const map = new Map<string, A2uiComponent>()
  for (const c of props.components) {
    map.set(c.id, c)
  }
  return map
})

/** 计算根节点：如果传入 rootIds 则使用，否则找出不被任何节点引用为 children 的节点 */
const rootComponents = computed(() => {
  if (props.rootIds) {
    return props.rootIds
      .map(id => componentMap.value.get(id))
      .filter((c): c is A2uiComponent => c !== undefined)
  }
  // 收集所有被引用为子节点的 ID
  const childIds = new Set<string>()
  for (const c of props.components) {
    for (const childId of c.children) {
      childIds.add(childId)
    }
  }
  return props.components.filter(c => !childIds.has(c.id))
})

/** 获取指定组件的子节点 */
function getChildren(component: A2uiComponent): A2uiComponent[] {
  return component.children
    .map(id => componentMap.value.get(id))
    .filter((c): c is A2uiComponent => c !== undefined)
}
</script>

<template>
  <template v-for="comp in rootComponents" :key="comp.id">
    <component
      :is="resolveComponent(comp.type)"
      v-bind="comp.properties"
      :signal="comp.signal"
      :type="comp.type"
    >
      <!-- 递归渲染子节点 -->
      <A2uiRenderer
        v-if="getChildren(comp).length"
        :components="components"
        :root-ids="comp.children"
      />
    </component>
  </template>
</template>
