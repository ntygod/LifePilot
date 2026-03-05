<script setup lang="ts">
import { ref, watch } from 'vue'

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

function onSearchInput(e: Event) {
  const value = (e.target as HTMLInputElement).value
  localSearch.value = value
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => {
    emit('update:search', value.trim())
  }, 300)
}

function selectTag(tag: string) {
  emit('update:tag', tag === props.tag ? '' : tag)
}
</script>

<template>
  <div class="flex flex-col sm:flex-row items-start sm:items-center gap-sm">
    <!-- 搜索输入框 -->
    <div class="relative w-full sm:w-64">
      <span class="pointer-events-none absolute inset-y-0 left-2 flex items-center text-xs text-muted-foreground">
        🔍
      </span>
      <input
        :value="localSearch"
        type="search"
        placeholder="搜索 Skill 名称或描述..."
        aria-label="搜索 Skill"
        class="flex h-9 w-full rounded-md border border-input bg-transparent pl-7 pr-3 py-1 text-sm shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
        @input="onSearchInput"
      />
    </div>

    <!-- 标签筛选 -->
    <div v-if="availableTags.length > 0" class="inline-flex flex-wrap items-center gap-2">
      <button
        type="button"
        class="rounded-full border text-xs transition-colors px-2 py-1"
        :class="!tag
          ? 'bg-primary text-primary-foreground border-primary'
          : 'border-border text-muted-foreground hover:text-foreground'"
        @click="selectTag('')"
      >
        全部
      </button>
      <button
        v-for="t in availableTags"
        :key="t"
        type="button"
        class="rounded-full border text-xs transition-colors px-2 py-1"
        :class="tag === t
          ? 'bg-primary text-primary-foreground border-primary'
          : 'border-border text-muted-foreground hover:text-foreground'"
        @click="selectTag(t)"
      >
        {{ t }}
      </button>
    </div>
  </div>
</template>
