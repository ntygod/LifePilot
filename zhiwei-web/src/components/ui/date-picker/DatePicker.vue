<script setup lang="ts">
import { computed } from 'vue'
import { CalendarDate, type DateValue } from '@internationalized/date'
import { Calendar as CalendarIcon } from 'lucide-vue-next'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { Button } from '@/components/ui/button'
import { Calendar } from '@/components/ui/calendar'
import { cn } from '@/lib/utils'

const props = withDefaults(defineProps<{
  /** ISO 日期字符串 yyyy-MM-dd */
  modelValue?: string
  placeholder?: string
  class?: string
}>(), {
  placeholder: '选择日期',
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

// 将 ISO 字符串转为 CalendarDate
const calendarValue = computed<DateValue | undefined>({
  get() {
    if (!props.modelValue) return undefined
    const [y, m, d] = props.modelValue.split('-').map(Number)
    if (!y || !m || !d) return undefined
    return new CalendarDate(y, m, d)
  },
  set(val) {
    if (!val) return
    const y = String(val.year).padStart(4, '0')
    const m = String(val.month).padStart(2, '0')
    const d = String(val.day).padStart(2, '0')
    emit('update:modelValue', `${y}-${m}-${d}`)
  },
})

// 格式化显示
const displayText = computed(() => {
  if (!props.modelValue) return ''
  return props.modelValue
})
</script>

<template>
  <Popover>
    <PopoverTrigger as-child>
      <Button
        variant="outline"
        size="sm"
        :class="cn(
          'justify-start text-left font-normal gap-2',
          !modelValue && 'text-muted-foreground',
          props.class,
        )"
      >
        <CalendarIcon class="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
        <span class="truncate">{{ displayText || placeholder }}</span>
      </Button>
    </PopoverTrigger>
    <PopoverContent
      class="w-auto p-0 rounded-xl border-border/60 shadow-lg"
      align="start"
      :side-offset="6"
    >
      <Calendar
        v-model="calendarValue"
        locale="zh-CN"
        :week-starts-on="1"
        initial-focus
      />
    </PopoverContent>
  </Popover>
</template>
