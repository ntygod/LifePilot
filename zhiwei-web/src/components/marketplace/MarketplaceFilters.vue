<!-- MarketplaceFilters — 搜索 + 扩展类型筛选 -->
<script setup lang="ts">
import { ref, watch } from 'vue'
import SearchBar from '@/components/common/SearchBar.vue'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'

const props = defineProps<{
  search: string
  type: string
}>()

const emit = defineEmits<{
  'update:search': [value: string]
  'update:type': [value: string]
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

function handleTypeChange(val: string | undefined) {
  emit('update:type', val || '')
}
</script>

<template>
  <div class="flex flex-col sm:flex-row items-start sm:items-center gap-sm">
    <SearchBar
      :model-value="localSearch"
      placeholder="搜索扩展名称或描述..."
      aria-label="搜索扩展"
      class="w-full sm:w-64"
      @update:model-value="onSearchUpdate"
    />

    <ToggleGroup
      type="single"
      variant="outline"
      size="sm"
      :model-value="type || 'all'"
      @update:model-value="handleTypeChange($event === 'all' ? '' : ($event as string))"
    >
      <ToggleGroupItem value="all">全部</ToggleGroupItem>
      <ToggleGroupItem value="SKILL">技能</ToggleGroupItem>
      <ToggleGroupItem value="AGENT">智能体</ToggleGroupItem>
      <ToggleGroupItem value="WORKFLOW">工作流</ToggleGroupItem>
      <ToggleGroupItem value="CHANNEL">渠道</ToggleGroupItem>
    </ToggleGroup>
  </div>
</template>
