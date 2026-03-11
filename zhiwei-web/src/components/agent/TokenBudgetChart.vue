<!--
  Token 预算分配环形图组件。

  以 ECharts donut chart 展示六个槽位的预算分配：
  System Prompt / 对话历史 / 记忆检索 / 工具 Schema / 工具结果 / 预留空间。
  Hover tooltip 展示槽位名称、分配 token 数、占比百分比。
  右侧图例列表展示各槽位颜色、名称和 token 数。

  @author zsg
  @since 2026-03-15
-->
<script setup lang="ts">
import { computed } from 'vue'
import VChart from 'vue-echarts'
import type { TokenBudgetData } from '@/types'
import '@/plugins/echarts'

// Props 定义
const props = defineProps<{
  budget: TokenBudgetData
}>()

// 六个槽位的配置：名称、budget 字段、used 字段、颜色
const SLOTS = [
  { name: 'System Prompt', budgetKey: 'systemPromptBudget', usedKey: 'systemPromptUsed', color: '#3b82f6' },
  { name: '对话历史', budgetKey: 'historyBudget', usedKey: 'historyUsed', color: '#10b981' },
  { name: '记忆检索', budgetKey: 'memoryBudget', usedKey: 'memoryUsed', color: '#f59e0b' },
  { name: '工具 Schema', budgetKey: 'toolSchemaBudget', usedKey: 'toolSchemaUsed', color: '#8b5cf6' },
  { name: '工具结果', budgetKey: 'toolResultBudget', usedKey: 'toolResultUsed', color: '#ef4444' },
  { name: '预留空间', budgetKey: 'reservedBuffer', usedKey: null, color: '#6b7280' },
] as const

// 计算总预算
const totalBudget = computed(() =>
  props.budget.systemPromptBudget
  + props.budget.historyBudget
  + props.budget.memoryBudget
  + props.budget.toolSchemaBudget
  + props.budget.toolResultBudget
  + props.budget.reservedBuffer
)

// 构建图表数据
const chartData = computed(() =>
  SLOTS.map(slot => ({
    name: slot.name,
    value: props.budget[slot.budgetKey],
    used: slot.usedKey ? props.budget[slot.usedKey] : 0,
    itemStyle: { color: slot.color },
  }))
)

// ECharts 配置
const chartOption = computed(() => ({
  tooltip: {
    trigger: 'item' as const,
    formatter: (params: any) => {
      const data = params.data
      const percent = totalBudget.value > 0
        ? ((data.value / totalBudget.value) * 100).toFixed(1)
        : '0.0'
      // tooltip 展示槽位名称、分配 token 数、占比、已使用 token 数
      let html = `<strong>${data.name}</strong><br/>`
      html += `分配: ${data.value.toLocaleString()} tokens (${percent}%)`
      if (data.used > 0) {
        html += `<br/>已使用: ${data.used.toLocaleString()} tokens`
      }
      return html
    },
  },
  legend: {
    show: false, // 使用自定义图例
  },
  series: [
    {
      type: 'pie' as const,
      radius: ['50%', '75%'], // 环形图
      center: ['50%', '50%'],
      avoidLabelOverlap: true,
      label: { show: false },
      emphasis: {
        label: { show: false },
        scaleSize: 6,
      },
      data: chartData.value,
    },
  ],
}))
</script>

<template>
  <div class="flex items-start gap-6">
    <!-- 环形图 -->
    <div class="shrink-0" style="width: 200px; height: 200px;">
      <VChart
        :option="chartOption"
        :autoresize="true"
        style="width: 100%; height: 100%;"
      />
    </div>

    <!-- 右侧图例列表 -->
    <div class="flex-1 space-y-2 pt-2">
      <div
        v-for="(slot, index) in SLOTS"
        :key="slot.name"
        class="flex items-center justify-between text-sm"
      >
        <div class="flex items-center gap-2">
          <span
            class="inline-block w-3 h-3 rounded-sm shrink-0"
            :style="{ backgroundColor: slot.color }"
          />
          <span class="text-foreground">{{ slot.name }}</span>
        </div>
        <div class="flex items-center gap-3 text-muted-foreground tabular-nums">
          <span>{{ chartData[index].value.toLocaleString() }}</span>
          <span class="w-12 text-right">
            {{ totalBudget > 0 ? ((chartData[index].value / totalBudget) * 100).toFixed(1) : '0.0' }}%
          </span>
        </div>
      </div>

      <!-- 总计 -->
      <div class="flex items-center justify-between text-sm pt-2 border-t border-border">
        <span class="font-medium text-foreground">总预算</span>
        <span class="font-medium text-foreground tabular-nums">
          {{ totalBudget.toLocaleString() }} tokens
        </span>
      </div>
    </div>
  </div>
</template>
