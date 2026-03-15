<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { Bell } from 'lucide-vue-next'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { Button } from '@/components/ui/button'
import { useNotificationStore } from '@/stores/notification'
import NotificationPanel from './NotificationPanel.vue'

const notificationStore = useNotificationStore()
const { unreadCount } = storeToRefs(notificationStore)
</script>

<template>
  <Popover>
    <PopoverTrigger as-child>
      <Button variant="ghost" size="icon" class="relative">
        <Bell class="size-5" />
        <span
          v-if="unreadCount > 0"
          class="absolute -right-1 -top-1 flex h-5 min-w-5 items-center justify-center rounded-full bg-destructive px-1 text-[10px] font-medium text-destructive-foreground"
        >
          {{ unreadCount > 99 ? '99+' : unreadCount }}
        </span>
      </Button>
    </PopoverTrigger>
    <PopoverContent align="start" class="w-96 p-0">
      <NotificationPanel />
    </PopoverContent>
  </Popover>
</template>
