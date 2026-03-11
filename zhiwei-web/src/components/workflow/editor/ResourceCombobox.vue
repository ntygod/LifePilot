<!--
  资源选择 Combobox 组件。
  通用的 Popover + Command 搜索选择器，用于 toolId / skillId / workflowId 选择。
  支持搜索过滤、手动输入、显示资源描述。
-->
<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import {
  Command, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList
} from '@/components/ui/command'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

export interface ResourceOption {
  id: string
  name: string
  description?: string
}

const props = withDefaults(defineProps<{
  /** 当前选中的资源 ID */
  modelValue: string
  /** 可选资源列表 */
  options: ResourceOption[]
  /** 占位文本 */
  placeholder?: string
  /** 搜索框占位文本 */
  searchPlaceholder?: string
  /** 无结果提示 */
  emptyText?: string
  /** 是否加载中 */
  loading?: boolean
}>(), {
  placeholder: '选择或输入 ID',
  searchPlaceholder: '搜索...',
  emptyText: '无匹配结果',
  loading: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const open = ref(false)
const searchQuery = ref('')

/** 当前选中项的显示名称 */
const displayLabel = computed(() => {
  if (!props.modelValue) return ''
  const match = props.options.find(o => o.id === props.modelValue)
  return match ? `${match.name} (${match.id})` : props.modelValue
})

/** 过滤后的选项列表 */
const filteredOptions = computed(() => {
  if (!searchQuery.value) return props.options
  const q = searchQuery.value.toLowerCase()
  return props.options.filter(o =>
    o.id.toLowerCase().includes(q) ||
    o.name.toLowerCase().includes(q) ||
    (o.description?.toLowerCase().includes(q) ?? false)
  )
})

function selectOption(id: string) {
  emit('update:modelValue', id)
  open.value = false
  searchQuery.value = ''
}

/** 弹窗关闭时，如果搜索框有内容且无精确匹配，将搜索内容作为手动输入值 */
watch(open, (isOpen) => {
  if (!isOpen && searchQuery.value) {
    const exactMatch = props.options.find(o => o.id === searchQuery.value)
    if (!exactMatch && searchQuery.value !== props.modelValue) {
      emit('update:modelValue', searchQuery.value)
    }
    searchQuery.value = ''
  }
})
</script>

<template>
  <Popover v-model:open="open">
    <PopoverTrigger as-child>
      <Button
        variant="outline"
        role="combobox"
        :aria-expanded="open"
        :class="cn('w-full justify-between h-8 text-sm font-normal', !modelValue && 'text-muted-foreground')"
      >
        <span class="truncate">{{ displayLabel || placeholder }}</span>
        <svg
          xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24"
          fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"
          stroke-linejoin="round" class="ml-2 shrink-0 opacity-50"
        >
          <path d="m7 15 5 5 5-5" /><path d="m7 9 5-5 5 5" />
        </svg>
      </Button>
    </PopoverTrigger>
    <PopoverContent class="w-[--reka-popover-trigger-width] p-0" align="start">
      <Command>
        <CommandInput
          v-model="searchQuery"
          :placeholder="searchPlaceholder"
          class="h-8 text-sm"
        />
        <CommandList class="max-h-48">
          <CommandEmpty>
            <span v-if="loading" class="text-muted-foreground text-xs">加载中...</span>
            <span v-else class="text-muted-foreground text-xs">{{ emptyText }}</span>
          </CommandEmpty>
          <CommandGroup>
            <CommandItem
              v-for="option in filteredOptions"
              :key="option.id"
              :value="option.id"
              class="flex flex-col items-start gap-0.5 py-1.5"
              @select="selectOption(option.id)"
            >
              <div class="flex items-center gap-1.5 w-full">
                <svg
                  v-if="modelValue === option.id"
                  xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24"
                  fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"
                  stroke-linejoin="round" class="shrink-0"
                >
                  <path d="M20 6 9 17l-5-5" />
                </svg>
                <div v-else class="w-3.5 shrink-0" />
                <span class="text-sm font-medium truncate">{{ option.name }}</span>
                <span class="text-xs text-muted-foreground truncate ml-auto">{{ option.id }}</span>
              </div>
              <span
                v-if="option.description"
                class="text-xs text-muted-foreground pl-5 line-clamp-1"
              >{{ option.description }}</span>
            </CommandItem>
          </CommandGroup>
        </CommandList>
      </Command>
    </PopoverContent>
  </Popover>
</template>
