<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { ChevronDown, Check } from 'lucide-vue-next'

const props = defineProps<{
  modelValue: string | number
  options: { value: string | number; label: string }[]
  disabled?: boolean
  placeholder?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string | number): void
  (e: 'change', value: string | number): void
}>()

const isOpen = ref(false)
const selectedIndex = ref(-1)
const dropdownRef = ref<HTMLElement>()
const buttonRef = ref<HTMLElement>()

const selectedOption = computed(() => {
  return props.options.find(opt => opt.value === props.modelValue)
})

const displayText = computed(() => {
  if (selectedOption.value) {
    return selectedOption.value.label
  }
  return props.placeholder || '请选择'
})

function toggleDropdown() {
  if (props.disabled) return
  isOpen.value = !isOpen.value
  if (isOpen.value) {
    selectedIndex.value = props.options.findIndex(opt => opt.value === props.modelValue)
  }
}

function selectOption(option: { value: string | number; label: string }) {
  if (option.value !== props.modelValue) {
    emit('update:modelValue', option.value)
    emit('change', option.value)
  }
  isOpen.value = false
}

function handleKeydown(event: KeyboardEvent) {
  if (props.disabled) return
  
  switch (event.key) {
    case 'Enter':
    case ' ':
      if (!isOpen.value) {
        event.preventDefault()
        toggleDropdown()
      } else if (selectedIndex.value >= 0) {
        event.preventDefault()
        selectOption(props.options[selectedIndex.value])
      }
      break
    case 'Escape':
      if (isOpen.value) {
        event.preventDefault()
        isOpen.value = false
      }
      break
    case 'ArrowDown':
      if (!isOpen.value) {
        event.preventDefault()
        toggleDropdown()
      } else {
        event.preventDefault()
        selectedIndex.value = Math.min(selectedIndex.value + 1, props.options.length - 1)
      }
      break
    case 'ArrowUp':
      if (isOpen.value) {
        event.preventDefault()
        selectedIndex.value = Math.max(selectedIndex.value - 1, 0)
      }
      break
  }
}

function handleClickOutside(event: MouseEvent) {
  if (
    dropdownRef.value &&
    buttonRef.value &&
    !dropdownRef.value.contains(event.target as Node) &&
    !buttonRef.value.contains(event.target as Node)
  ) {
    isOpen.value = false
  }
}

watch(isOpen, (newVal) => {
  if (newVal) {
    document.addEventListener('click', handleClickOutside)
    // 滚动到选中项
    nextTick(() => {
      const selectedElement = dropdownRef.value?.querySelector('[data-selected="true"]')
      if (selectedElement) {
        selectedElement.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
      }
    })
  } else {
    document.removeEventListener('click', handleClickOutside)
  }
})

onMounted(() => {
  if (isOpen.value) {
    document.addEventListener('click', handleClickOutside)
  }
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
})
</script>

<template>
  <div class="relative w-full">
    <!-- 下拉框按钮 -->
    <button
      ref="buttonRef"
      type="button"
      :disabled="disabled"
      :class="[
        'group flex h-9 w-full items-center justify-between rounded-[0.85rem] border bg-background/88 px-3 py-1.5 text-sm',
        'transition-all duration-200 ease-out',
        'shadow-[inset_0_1px_0_rgba(255,255,255,0.34)]',
        'border-input/72 hover:border-ring/42',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:ring-offset-1',
        'disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:border-input/72',
        isOpen && 'border-ring ring-2 ring-ring/20 ring-offset-1',
        !selectedOption && 'text-muted-foreground'
      ]"
      @click="toggleDropdown"
      @keydown="handleKeydown"
      :aria-expanded="isOpen"
      :aria-haspopup="true"
    >
      <span class="truncate font-medium">{{ displayText }}</span>
      <ChevronDown
        :size="16"
        :class="[
          'ml-2 shrink-0 text-muted-foreground transition-all duration-300 ease-out',
          isOpen && 'rotate-180 text-foreground'
        ]"
      />
    </button>

    <!-- 下拉菜单 -->
    <Transition
      enter-active-class="transition-all duration-250 ease-out"
      enter-from-class="opacity-0 scale-[0.98] translate-y-[-4px]"
      enter-to-class="opacity-100 scale-100 translate-y-0"
      leave-active-class="transition-all duration-200 ease-in"
      leave-from-class="opacity-100 scale-100 translate-y-0"
      leave-to-class="opacity-0 scale-[0.98] translate-y-[-4px]"
    >
      <div
        v-if="isOpen"
        ref="dropdownRef"
        class="absolute z-50 mt-1.5 w-full rounded-[0.95rem] border border-border/60 bg-popover/98 shadow-[0_18px_32px_-24px_hsl(var(--shadow-color)/0.18)]"
        role="listbox"
      >
        <div class="max-h-[300px] overflow-auto p-1.5 scrollbar-thin scrollbar-thumb-border scrollbar-track-transparent">
          <div
            v-for="(option, index) in options"
            :key="option.value"
            :data-selected="option.value === modelValue"
            :class="[
              'group relative flex cursor-pointer select-none items-center rounded-[0.75rem] px-3 py-2 text-sm',
              'transition-all duration-150 ease-out',
              'hover:bg-accent/66 hover:text-accent-foreground',
              'focus:bg-accent focus:text-accent-foreground focus:outline-none',
              option.value === modelValue && 'bg-primary/8 text-primary font-semibold',
              selectedIndex === index && selectedIndex !== props.options.findIndex(opt => opt.value === modelValue) && 'bg-accent/60'
            ]"
            role="option"
            :aria-selected="option.value === modelValue"
            @click="selectOption(option)"
            @mouseenter="selectedIndex = index"
          >
            <!-- 选中指示器 -->
            <div
              v-if="option.value === modelValue"
              class="absolute left-0 top-1/2 -translate-y-1/2 w-1 h-5 rounded-r-full bg-primary transition-all duration-200"
            />
            <span class="flex-1 truncate pl-1">{{ option.label }}</span>
            <Check
              v-if="option.value === modelValue"
              :size="16"
              class="ml-2 shrink-0 text-primary transition-all duration-200"
            />
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>
