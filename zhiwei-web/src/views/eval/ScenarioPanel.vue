<script setup lang="ts">
import { computed, ref } from 'vue'
import { ChevronDown, ChevronUp, History, X, FlaskConical } from 'lucide-vue-next'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import StatePanel from '@/components/common/StatePanel.vue'
import { useEvalStore } from '@/stores/eval'

defineEmits<{
  (e: 'viewHistory', scenarioId: string, scenarioName: string): void
}>()

const store = useEvalStore()

// ── 标签过滤状态 ──
const activeTag = ref<string | null>(null)

// ── 展开详情状态 ──
const expandedId = ref<string | null>(null)

// ── 过滤后的场景列表 ──
const filteredScenarios = computed(() => {
  if (!activeTag.value) return store.scenarios
  return store.scenarios.filter(s => s.tags.includes(activeTag.value!))
})

/** 点击标签过滤 */
function selectTag(tag: string) {
  activeTag.value = activeTag.value === tag ? null : tag
}

/** 清除过滤 */
function clearFilter() {
  activeTag.value = null
}

/** 切换场景详情展开 */
function toggleExpand(id: string) {
  expandedId.value = expandedId.value === id ? null : id
}

/** 格式化维度权重为可读文本 */
function formatWeights(weights: Record<string, number>): string {
  return Object.entries(weights)
    .map(([k, v]) => `${k}: ${(v * 100).toFixed(0)}%`)
    .join('、')
}
</script>

<template>
  <div class="space-y-4">
    <!-- 标签过滤栏 -->
    <div v-if="store.allTags.length > 0" class="detail-card p-4">
      <div class="flex flex-wrap items-center gap-2">
        <span class="text-xs text-muted-foreground mr-1">标签过滤</span>
        <Badge
          v-for="tag in store.allTags"
          :key="tag"
          :variant="activeTag === tag ? 'default' : 'outline'"
          class="cursor-pointer select-none"
          @click="selectTag(tag)"
        >
          {{ tag }}
        </Badge>
        <Button
          v-if="activeTag"
          variant="ghost"
          size="sm"
          class="ml-1 h-6 px-2 text-xs"
          @click="clearFilter"
        >
          <X class="mr-1 size-3" />
          清除
        </Button>
      </div>
    </div>

    <!-- 空状态：无场景 -->
    <StatePanel
      v-if="store.scenarios.length === 0 && !store.loading"
      title="暂无评估场景"
      description="当前没有已配置的 Benchmark 场景，请先在后端添加场景定义。"
    >
      <template #icon>
        <FlaskConical class="size-5" />
      </template>
    </StatePanel>

    <!-- 空状态：过滤无结果 -->
    <div
      v-else-if="filteredScenarios.length === 0 && activeTag"
      class="detail-card px-6 py-12 text-center"
    >
      <p class="text-sm text-muted-foreground">
        没有匹配标签「{{ activeTag }}」的场景
      </p>
      <Button variant="outline" size="sm" class="mt-3" @click="clearFilter">
        清除过滤
      </Button>
    </div>

    <!-- 场景卡片列表 -->
    <div v-else class="space-y-3">
      <div
        v-for="scenario in filteredScenarios"
        :key="scenario.id"
        class="list-card overflow-hidden"
      >
        <!-- 卡片主体 -->
        <div
          class="flex cursor-pointer items-start justify-between gap-4 px-5 py-4 transition-colors hover:bg-muted/50"
          @click="toggleExpand(scenario.id)"
        >
          <div class="min-w-0 flex-1 space-y-2">
            <!-- 名称 + ID -->
            <div class="flex items-center gap-2">
              <span class="text-sm font-medium text-foreground">{{ scenario.name }}</span>
              <span class="text-xs text-muted-foreground font-mono">{{ scenario.id }}</span>
            </div>
            <!-- 标签 -->
            <div class="flex flex-wrap gap-1.5">
              <Badge
                v-for="tag in scenario.tags"
                :key="tag"
                variant="secondary"
                class="cursor-pointer text-[11px]"
                @click.stop="selectTag(tag)"
              >
                {{ tag }}
              </Badge>
            </div>
            <!-- 指标行 -->
            <div class="flex flex-wrap gap-4 text-xs text-muted-foreground">
              <span>预期步数 <strong class="text-foreground">{{ scenario.expectedStepCount }}</strong></span>
              <span>Token 预算 <strong class="text-foreground">{{ scenario.expectedTokenBudget }}</strong></span>
              <span>超时 <strong class="text-foreground">{{ scenario.timeoutSeconds }}s</strong></span>
            </div>
          </div>

          <!-- 右侧操作区 -->
          <div class="flex shrink-0 items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              class="h-7 text-xs"
              @click.stop="$emit('viewHistory', scenario.id, scenario.name)"
            >
              <History class="mr-1 size-3" />
              查看历史
            </Button>
            <component
              :is="expandedId === scenario.id ? ChevronUp : ChevronDown"
              class="size-4 text-muted-foreground"
            />
          </div>
        </div>

        <!-- 展开详情 -->
        <div
          v-if="expandedId === scenario.id"
          class="border-t border-border/60 bg-muted/30 px-5 py-4 space-y-3 text-sm"
        >
          <!-- 用户输入 -->
          <div>
            <span class="text-xs font-medium text-muted-foreground">用户输入</span>
            <p class="mt-1 whitespace-pre-wrap text-foreground">{{ scenario.userInput }}</p>
          </div>

          <!-- 预期工具调用 -->
          <div v-if="scenario.expectedToolCalls.length > 0">
            <span class="text-xs font-medium text-muted-foreground">预期工具调用</span>
            <div class="mt-1 flex flex-wrap gap-1.5">
              <Badge v-for="tool in scenario.expectedToolCalls" :key="tool" variant="outline">
                {{ tool }}
              </Badge>
            </div>
          </div>

          <!-- 维度权重 -->
          <div v-if="Object.keys(scenario.dimensionWeights).length > 0">
            <span class="text-xs font-medium text-muted-foreground">维度权重</span>
            <p class="mt-1 text-foreground">{{ formatWeights(scenario.dimensionWeights) }}</p>
          </div>

          <!-- LLM 评判标准 -->
          <div v-if="scenario.llmJudgeCriteria">
            <span class="text-xs font-medium text-muted-foreground">LLM 评判标准</span>
            <p class="mt-1 whitespace-pre-wrap text-foreground">{{ scenario.llmJudgeCriteria }}</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
