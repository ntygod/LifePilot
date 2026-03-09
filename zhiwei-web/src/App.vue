<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppLayout from '@/components/layout/AppLayout.vue'

const route = useRoute()

// 根路径显示 LandingPage，其他路径显示 AppLayout
const showAppLayout = computed(() => route.path !== '/')
</script>

<template>
  <AppLayout v-if="showAppLayout" />
  <router-view v-else />
</template>
