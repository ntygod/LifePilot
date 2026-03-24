<script setup lang="ts">
import { computed } from 'vue'
import type { A2uiComponent } from '@/types'
import { resolveComponent } from './componentCatalog'
import { normalizeA2uiComponents } from '@/utils/a2ui'

const props = defineProps<{
  components: A2uiComponent[]
  rootIds?: string[]
  entryId?: string
  traceId?: string
  streaming?: boolean
  visitedIds?: string[]
  depth?: number
}>()

const MAX_DEPTH = 12

const normalizedComponents = computed(() => normalizeA2uiComponents(props.components))

const componentMap = computed(() => {
  const map = new Map<string, A2uiComponent>()
  for (const component of normalizedComponents.value) {
    map.set(component.id, component)
  }
  return map
})

const visitedSet = computed(() => new Set(props.visitedIds ?? []))
const currentDepth = computed(() => props.depth ?? 0)
const canRenderChildren = computed(() => currentDepth.value < MAX_DEPTH)

const rootComponents = computed(() => {
  if (props.rootIds) {
    return props.rootIds
      .filter(id => !visitedSet.value.has(id))
      .map(id => componentMap.value.get(id))
      .filter((component): component is A2uiComponent => component !== undefined)
  }

  const childIds = new Set<string>()
  for (const component of normalizedComponents.value) {
    for (const childId of component.children) {
      childIds.add(childId)
    }
  }

  const roots = normalizedComponents.value.filter(component =>
    !childIds.has(component.id) && !visitedSet.value.has(component.id),
  )

  return roots.length > 0
    ? roots
    : normalizedComponents.value.filter(component => !visitedSet.value.has(component.id)).slice(0, 1)
})

function getChildren(component: A2uiComponent): A2uiComponent[] {
  if (!canRenderChildren.value) {
    return []
  }

  return component.children
    .filter(id => !visitedSet.value.has(id))
    .map(id => componentMap.value.get(id))
    .filter((child): child is A2uiComponent => child !== undefined)
}

function nextVisitedIds(component: A2uiComponent) {
  return [...visitedSet.value, component.id]
}
</script>

<template>
  <template v-for="comp in rootComponents" :key="comp.id">
    <component
      :is="resolveComponent(comp.type)"
      v-bind="comp.properties"
      :signal="comp.signal"
      :type="comp.type"
      :component-id="comp.id"
      :entry-id="props.entryId"
      :trace-id="props.traceId"
      :streaming="props.streaming"
    >
      <A2uiRenderer
        v-if="getChildren(comp).length"
        :components="normalizedComponents"
        :root-ids="comp.children"
        :entry-id="props.entryId"
        :trace-id="props.traceId"
        :streaming="props.streaming"
        :visited-ids="nextVisitedIds(comp)"
        :depth="currentDepth + 1"
      />
    </component>
  </template>
</template>
