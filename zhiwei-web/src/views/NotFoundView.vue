<script setup lang="ts">
import { useRouter } from 'vue-router'
import { Compass, MoveLeft } from 'lucide-vue-next'
import StatePanel from '@/components/common/StatePanel.vue'
import PageContainer from '@/components/layout/PageContainer.vue'
import { Button } from '@/components/ui/button'

const router = useRouter()

function goHome() {
  router.push('/')
}

function goBack() {
  router.back()
}
</script>

<template>
  <div class="min-h-full overflow-y-auto">
    <PageContainer class="flex min-h-[70vh] items-center py-10 sm:py-14">
      <StatePanel
        title="未找到该页面"
        description="页面地址可能已变更、被删除，或暂时不可用。"
        class="w-full"
      >
        <template #icon>
          <Compass class="size-5" />
        </template>

        <template #actions>
          <Button variant="outline" @click="goBack">
            <MoveLeft class="size-4" />
            返回上一页
          </Button>
          <Button @click="goHome">
            返回首页
          </Button>
        </template>
      </StatePanel>
    </PageContainer>
  </div>
</template>
