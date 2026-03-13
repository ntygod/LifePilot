<script setup lang="ts">
import type { HTMLAttributes } from "vue"
import { cn } from "@/lib/utils"

const props = withDefaults(defineProps<{
  class?: HTMLAttributes["class"]
  variant?: "default" | "destructive" | "success" | "warning"
}>(), {
  variant: "default",
})
</script>

<template>
  <div
    :class="
      cn(
        'relative flex w-full items-center rounded-lg border p-4 shadow-sm',
        {
          'bg-destructive/15 border-destructive/25 text-destructive': variant === 'destructive',
          'bg-green-100 border-green-300 text-green-800 dark:bg-green-900/30 dark:border-green-700': variant === 'success',
          'bg-yellow-100 border-yellow-300 text-yellow-800 dark:bg-yellow-900/30 dark:border-yellow-700': variant === 'warning',
          'bg-muted text-muted-foreground border-input': variant === 'default',
        },
        props.class
      )
    "
  >
    <slot />
  </div>
</template>
