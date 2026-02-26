<script setup lang="ts">
import Sidebar from './Sidebar.vue'
</script>

<template>
  <div class="flex h-screen overflow-hidden">
    <Sidebar />
    <main class="flex-1 overflow-hidden">
      <router-view />
    </main>
  </div>
</template>
