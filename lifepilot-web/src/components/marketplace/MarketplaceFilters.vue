<!-- MarketplaceFilters — 搜索与标签筛选，使用 shadcn-vue 组件 -->
<script setup lang="ts">
import { ref, watch } from 'vue'
import SearchBar from '@/components/common/SearchBar.vue'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'

const props = defineProps<{
  search: string
  tag: string
  availableTags: string[]
}>()

const emit = defineEmits<{
  'update:search': [value: string]
  'update:tag': [value: string]
}>()

// 搜索防抖
const localSearch = ref(props.search)
let debounceTimer: ReturnType<typeof setTimeout> | null = null

watch(() => props.search, (val) => {
  localSearch.value = val
})

function onSearchUpdate(value: string) {
  localSearch.value = value
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => {
    emit('update:search', value.trim())
  }, 300)
}

/** ToggleGroup 值变化：空字符串表示"全部" */
function handleTagChange(val: string | undefined) {
  emit('update:tag', val || '')
}
</script>

<template>
  <div class="flex flex-col sm:flex-row items-start sm:items-center gap-sm">
    <!-- 搜索输入框 -->
    <SearchBar
      :model-value="localSearch"
      placeholder="搜索 Skill 名称或描述..."
      aria-label="搜索 Skill"
      class="w-full sm:w-64"
      @update:model-value="onSearchUpdate"
    />

    <!-- 标签筛选 -->
    <ToggleGroup
      v-if="availableTags.length > 0"
      type="single"
      variant="outline"
      size="sm"
      :model-value="tag || 'all'"
      class="flex-wrap"
      @update:model-value="handleTagChange($event === 'all' ? '' : ($event as string))"
    >
      <ToggleGroupItem value="all">
        全部
      </ToggleGroupItem>
      <ToggleGroupItem
        v-for="t in availableTags"
        :key="t"
        :value="t"
      >
        {{ t }}
      </ToggleGroupItem>
    </ToggleGroup>
  </div>
</template>
