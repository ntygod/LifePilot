<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useToolStore } from '@/stores/tool'
import type { ToolSummary } from '@/types'

const router = useRouter()
const toolStore = useToolStore()

const searchQuery = ref('')
const typeFilter = ref<string>('')
const statusFilter = ref<string>('')
const riskFilter = ref<string>('')

onMounted(() => {
  toolStore.fetchTools()
})

const filteredTools = computed(() => {
  let result = toolStore.tools

  if (searchQuery.value) {
    const q = searchQuery.value.toLowerCase()
    result = result.filter(t => 
      t.name.toLowerCase().includes(q) || 
      t.displayName?.toLowerCase().includes(q) ||
      t.description?.toLowerCase().includes(q)
    )
  }

  if (typeFilter.value) {
    result = result.filter(t => t.type === typeFilter.value)
  }

  if (statusFilter.value) {
    if (statusFilter.value === 'enabled') {
      result = result.filter(t => t.enabled)
    } else if (statusFilter.value === 'disabled') {
      result = result.filter(t => !t.enabled)
    }
  }

  if (riskFilter.value) {
    result = result.filter(t => t.riskLevel === riskFilter.value)
  }

  return result
})

const typeLabel: Record<string, string> = {
  PLUGIN: 'Java 原生',
  SKILL: 'YAML Skill',
  MCP: 'MCP 工具'
}

const riskLabel: Record<string, { label: string; class: string }> = {
  LOW: { label: '低风险', class: 'bg-green-100 text-green-800' },
  MEDIUM: { label: '中风险', class: 'bg-yellow-100 text-yellow-800' },
  HIGH: { label: '高风险', class: 'bg-red-100 text-red-800' },
}
</script>

<template>
  <div class="flex flex-col h-full overflow-hidden">
    <!-- 头部操作栏 -->
    <div class="flex-shrink-0 border-b border-border">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-md">
        <h2 class="text-2xl font-semibold text-foreground mb-md leading-tight">工具管理</h2>

        <!-- 搜索和过滤 -->
        <div class="flex flex-wrap gap-sm">
          <div class="flex-1 min-w-[220px]">
            <input
              v-model="searchQuery"
              type="text"
              placeholder="搜索工具名称或描述..."
              class="w-full px-3 py-2 rounded-2xl border border-input bg-background text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent transition-all duration-200"
            />
          </div>
          <select
            v-model="typeFilter"
            class="px-md py-sm rounded-lg border border-input bg-background text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          >
            <option value="">全部类型</option>
            <option value="PLUGIN">Java 原生</option>
            <option value="SKILL">YAML Skill</option>
            <option value="MCP">MCP 工具</option>
          </select>
          <select
            v-model="statusFilter"
            class="px-md py-sm rounded-lg border border-input bg-background text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          >
            <option value="">全部状态</option>
            <option value="enabled">已启用</option>
            <option value="disabled">已禁用</option>
          </select>
          <select
            v-model="riskFilter"
            class="px-md py-sm rounded-lg border border-input bg-background text-foreground text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          >
            <option value="">全部风险等级</option>
            <option value="LOW">低风险</option>
            <option value="MEDIUM">中风险</option>
            <option value="HIGH">高风险</option>
          </select>
        </div>
      </div>
    </div>

    <!-- Tool 列表 -->
    <div class="flex-1 overflow-y-auto">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-lg">
        <div v-if="toolStore.loading" class="text-sm text-muted-foreground">加载中...</div>
        <div v-else-if="filteredTools.length === 0" class="text-sm text-muted-foreground">
          {{ searchQuery || typeFilter || statusFilter || riskFilter ? '未找到匹配的工具' : '暂无工具' }}
        </div>
        <div v-else class="space-y-sm">
          <div
            v-for="tool in filteredTools"
            :key="tool.id"
            class="border border-border rounded-lg p-md hover:border-primary/50 hover:shadow-sm transition-all duration-200 cursor-pointer bg-card"
            @click="router.push(`/tools/${tool.id}`)"
          >
            <div class="flex items-start justify-between gap-sm">
              <div class="flex-1">
                <div class="flex items-center gap-xs mb-xs">
                  <h3 class="font-medium text-foreground text-sm leading-snug">
                    {{ tool.displayName || tool.name }}
                  </h3>
                  <span
                    class="text-xs px-sm py-xs rounded-full"
                    :class="tool.enabled ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-800'"
                  >
                    {{ tool.enabled ? '已启用' : '已禁用' }}
                  </span>
                  <span class="text-xs px-sm py-xs rounded-full bg-accent text-accent-foreground">
                    {{ typeLabel[tool.type] ?? tool.type }}
                  </span>
                  <span
                    class="text-xs px-sm py-xs rounded-full shrink-0"
                    :class="riskLabel[tool.riskLevel]?.class ?? 'bg-gray-100 text-gray-800'"
                  >
                    {{ riskLabel[tool.riskLevel]?.label ?? tool.riskLevel }}
                  </span>
                </div>
                <p class="text-sm text-muted-foreground mb-xs leading-normal">
                  {{ tool.description || '无描述' }}
                </p>
                <div class="text-xs text-muted-foreground">
                  ID: {{ tool.id }} | 来源: {{ tool.source }}
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
