<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Play } from 'lucide-vue-next'
import PageContainer from '@/components/layout/PageContainer.vue'
import PageHeader from '@/components/layout/PageHeader.vue'
import { Button } from '@/components/ui/button'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
import { useEvalStore } from '@/stores/eval'
import ScenarioPanel from './ScenarioPanel.vue'
import RunHistoryPanel from './RunHistoryPanel.vue'
import RunDialog from './RunDialog.vue'

const store = useEvalStore()
const activeTab = ref('scenarios')
const runDialogOpen = ref(false)

onMounted(() => {
  store.fetchScenarios()
})

/** 最新运行的平均分 */
const latestAvgScore = computed(() => {
  if (store.runs.length === 0) return '—'
  return `${(store.runs[0].averageOverallScore * 100).toFixed(1)}%`
})

/** 最新运行是否退化 */
const latestDegraded = computed(() => {
  if (store.runs.length === 0) return false
  return store.runs[0].degraded
})

/** 打开运行评估对话框 */
function openRunDialog() {
  runDialogOpen.value = true
}
</script>

<template>
  <div class="h-full overflow-y-auto">
    <PageContainer size="wide" class="py-4 sm:py-5">
      <div class="page-stack">
        <!-- 页面头部 -->
        <PageHeader
          title="评估管理"
          :description="`${store.scenarios.length} 个场景，${store.runs.length} 次运行，最新平均分 ${latestAvgScore}，${latestDegraded ? '已退化' : '状态正常'}`"
        >
          <template #actions>
            <Button @click="openRunDialog">
              <Play class="mr-1.5 size-4" />
              运行评估
            </Button>
          </template>
        </PageHeader>

        <!-- Tab 导航 -->
        <Tabs v-model="activeTab" default-value="scenarios" class="w-full">
          <TabsList class="w-full justify-start">
            <TabsTrigger value="scenarios">场景列表</TabsTrigger>
            <TabsTrigger value="history">运行历史</TabsTrigger>
          </TabsList>

          <TabsContent value="scenarios">
            <ScenarioPanel />
          </TabsContent>

          <TabsContent value="history">
            <RunHistoryPanel />
          </TabsContent>
        </Tabs>
      </div>
    </PageContainer>

    <!-- RunDialog 放在 PageContainer 外层，与其他页面的 Dialog 保持一致 -->
    <RunDialog v-model:open="runDialogOpen" />
  </div>
</template>
