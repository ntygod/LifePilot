<script setup lang="ts">
import { type HTMLAttributes, computed } from 'vue'
import {
  CalendarRoot,
  type CalendarRootEmits,
  type CalendarRootProps,
  useForwardPropsEmits,
} from 'reka-ui'
import {
  CalendarCell,
  CalendarCellTrigger,
  CalendarGrid,
  CalendarGridBody,
  CalendarGridHead,
  CalendarGridRow,
  CalendarHeadCell,
  CalendarHeader,
  CalendarHeading,
  CalendarNext,
  CalendarPrev,
} from 'reka-ui'
import { ChevronLeft, ChevronRight } from 'lucide-vue-next'
import { cn } from '@/lib/utils'

const props = defineProps<CalendarRootProps & { class?: HTMLAttributes['class'] }>()
const emits = defineEmits<CalendarRootEmits>()

const delegatedProps = computed(() => {
  const { class: _, ...rest } = props
  return rest
})

const forwarded = useForwardPropsEmits(delegatedProps, emits)
</script>

<template>
  <CalendarRoot
    v-slot="{ grid, weekDays }"
    v-bind="forwarded"
    :class="cn('p-3', props.class)"
  >
    <CalendarHeader class="relative flex w-full items-center justify-between pb-1">
      <CalendarPrev
        class="inline-flex items-center justify-center rounded-md text-sm font-medium transition-colors hover:bg-accent hover:text-accent-foreground h-9 w-9 bg-transparent p-0 text-muted-foreground hover:text-foreground"
        aria-label="上一月"
      >
        <ChevronLeft class="h-4 w-4" />
      </CalendarPrev>
      <CalendarHeading class="text-sm font-semibold text-foreground" />
      <CalendarNext
        class="inline-flex items-center justify-center rounded-md text-sm font-medium transition-colors hover:bg-accent hover:text-accent-foreground h-9 w-9 bg-transparent p-0 text-muted-foreground hover:text-foreground"
        aria-label="下一月"
      >
        <ChevronRight class="h-4 w-4" />
      </CalendarNext>
    </CalendarHeader>

    <div class="flex flex-col gap-y-4 mt-2 sm:flex-row sm:gap-x-4 sm:gap-y-0">
      <CalendarGrid v-for="month in grid" :key="month.value.toString()">
        <CalendarGridHead>
          <CalendarGridRow class="flex">
            <CalendarHeadCell
              v-for="day in weekDays"
              :key="day"
              class="text-muted-foreground w-9 font-medium text-xs"
            >
              {{ day }}
            </CalendarHeadCell>
          </CalendarGridRow>
        </CalendarGridHead>
        <CalendarGridBody>
          <CalendarGridRow
            v-for="(weekDates, index) in month.rows"
            :key="`weekDate-${index}`"
            class="flex mt-1 w-full"
          >
            <CalendarCell
              v-for="weekDate in weekDates"
              :key="weekDate.toString()"
              :date="weekDate"
              class="relative text-center text-sm p-0 first:[&:has([data-selected])]:rounded-l-md last:[&:has([data-selected])]:rounded-r-md focus-within:relative focus-within:z-20"
            >
              <CalendarCellTrigger
                :day="weekDate"
                :month="month.value"
                class="inline-flex items-center justify-center rounded-lg text-sm transition-all duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1 hover:bg-accent hover:text-accent-foreground h-9 w-9 p-0 font-normal aria-selected:opacity-100 data-[selected]:bg-primary data-[selected]:text-primary-foreground data-[selected]:hover:bg-primary data-[selected]:hover:text-primary-foreground data-[selected]:focus:bg-primary data-[selected]:focus:text-primary-foreground data-[selected]:font-semibold data-[selected]:shadow-sm data-[today]:bg-primary/10 data-[today]:text-primary data-[today]:font-semibold data-[outside-view]:text-muted-foreground/40 data-[disabled]:text-muted-foreground/40 data-[disabled]:pointer-events-none data-[unavailable]:text-destructive data-[unavailable]:line-through"
              />
            </CalendarCell>
          </CalendarGridRow>
        </CalendarGridBody>
      </CalendarGrid>
    </div>
  </CalendarRoot>
</template>
