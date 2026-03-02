<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useToolStore } from '@/stores/tool'
import ToolTestDialog from '@/components/tool/ToolTestDialog.vue'

const route = useRoute()
const router = useRouter()
const toolStore = useToolStore()

const toolId = computed(() => route.params.id as string)
const tool = computed(() => toolStore.currentTool)
const showTestDialog = ref(false)

onMounted(async () => {
  await toolStore.fetchToolDetail(toolId.value)
})

async function toggleTool() {
  if (!tool.value) return
  try {
    if (tool.value.enabled) {
      await toolStore.disableTool(tool.value.id)
    } else {
      await toolStore.enableTool(tool.value.id)
    }
  } catch (e: any) {
    alert(e.message || '操作失败')
  }
}

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
    <!-- 头部 -->
    <div class="flex-shrink-0 p-6 border-b border-border">
      <div class="flex items-center justify-between mb-4">
        <div class="flex items-center gap-3">
          <button
            class="text-sm text-muted-foreground hover:text-foreground transition-colors"
            @click="router.push('/tools')"
          >
            ← 返回列表
          </button>
          <h2 class="text-2xl font-semibold text-foreground">
            {{ tool?.displayName || tool?.name || '加载中...' }}
          </h2>
          <span
            class="text-xs px-2 py-0.5 rounded-full"
            :class="tool?.enabled ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-800'"
          >
            {{ tool?.enabled ? '已启用' : '已禁用' }}
          </span>
        </div>
        <div class="flex gap-2">
          <button
            class="px-4 py-2 rounded-md border border-input hover:bg-accent transition-colors"
            @click="showTestDialog = true"
          >
            测试调用
          </button>
          <button
            class="px-4 py-2 rounded-md border border-input hover:bg-accent transition-colors"
            @click="toggleTool"
          >
            {{ tool?.enabled ? '禁用' : '启用' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 内容区域 -->
    <div class="flex-1 overflow-y-auto p-6">
      <div v-if="!tool" class="text-sm text-muted-foreground">加载中...</div>
      <div v-else class="max-w-4xl mx-auto space-y-6">
        <!-- 基础信息 -->
        <div class="border border-border rounded-lg p-6">
          <h3 class="text-lg font-semibold text-foreground mb-4">基础信息</h3>
          <div class="space-y-3 text-sm">
            <div>
              <span class="text-muted-foreground">工具 ID：</span>
              <span class="font-mono">{{ tool.id }}</span>
            </div>
            <div>
              <span class="text-muted-foreground">显示名：</span>
              <span>{{ tool.displayName || tool.name }}</span>
            </div>
            <div>
              <span class="text-muted-foreground">描述：</span>
              <p class="mt-1">{{ tool.description || '无描述' }}</p>
            </div>
            <div class="flex items-center gap-4">
              <div>
                <span class="text-muted-foreground">类型：</span>
                <span class="px-2 py-0.5 rounded-full bg-accent text-accent-foreground">
                  {{ typeLabel[tool.type] ?? tool.type }}
                </span>
              </div>
              <div>
                <span class="text-muted-foreground">风险等级：</span>
                <span
                  class="px-2 py-0.5 rounded-full"
                  :class="riskLabel[tool.riskLevel]?.class ?? 'bg-gray-100 text-gray-800'"
                >
                  {{ riskLabel[tool.riskLevel]?.label ?? tool.riskLevel }}
                </span>
              </div>
              <div>
                <span class="text-muted-foreground">幂等性：</span>
                <span>{{ tool.idempotent ? '是' : '否' }}</span>
              </div>
            </div>
            <div>
              <span class="text-muted-foreground">来源：</span>
              <span>{{ tool.source }}</span>
            </div>
            <div v-if="tool.tags && tool.tags.length > 0">
              <span class="text-muted-foreground">标签：</span>
              <div class="flex flex-wrap gap-1 mt-1">
                <span
                  v-for="tag in tool.tags"
                  :key="tag"
                  class="text-xs px-2 py-0.5 rounded-full bg-accent text-accent-foreground"
                >
                  {{ tag }}
                </span>
              </div>
            </div>
          </div>
        </div>

        <!-- 行为与副作用说明 -->
        <div v-if="tool.sideEffects && tool.sideEffects.length > 0" class="border border-border rounded-lg p-6">
          <h3 class="text-lg font-semibold text-foreground mb-4">行为与副作用说明</h3>
          <ul class="space-y-2 text-sm">
            <li
              v-for="(effect, index) in tool.sideEffects"
              :key="index"
              class="flex items-start gap-2"
            >
              <span class="text-muted-foreground">•</span>
              <span>{{ effect }}</span>
            </li>
          </ul>
        </div>

        <!-- Schema 信息 -->
        <div v-if="tool.inputSchema || tool.outputSchema" class="border border-border rounded-lg p-6">
          <h3 class="text-lg font-semibold text-foreground mb-4">Schema</h3>
          <div class="space-y-4">
            <div v-if="tool.inputSchema">
              <h4 class="text-sm font-medium text-foreground mb-2">输入 Schema</h4>
              <pre class="p-3 rounded-md bg-muted text-xs overflow-x-auto">{{ JSON.stringify(tool.inputSchema, null, 2) }}</pre>
            </div>
            <div v-if="tool.outputSchema">
              <h4 class="text-sm font-medium text-foreground mb-2">输出 Schema</h4>
              <pre class="p-3 rounded-md bg-muted text-xs overflow-x-auto">{{ JSON.stringify(tool.outputSchema, null, 2) }}</pre>
            </div>
          </div>
        </div>

        <!-- 预算配置 -->
        <div v-if="tool.budget" class="border border-border rounded-lg p-6">
          <h3 class="text-lg font-semibold text-foreground mb-4">预算配置</h3>
          <div class="space-y-2 text-sm">
            <div v-if="tool.budget.timeoutSeconds">
              <span class="text-muted-foreground">超时时间：</span>
              <span>{{ tool.budget.timeoutSeconds }} 秒</span>
            </div>
            <div v-if="tool.budget.maxRetries">
              <span class="text-muted-foreground">最大重试次数：</span>
              <span>{{ tool.budget.maxRetries }}</span>
            </div>
            <div v-if="tool.budget.maxCostCents">
              <span class="text-muted-foreground">最大成本：</span>
              <span>{{ (tool.budget.maxCostCents / 100).toFixed(2) }} 元</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 测试对话框 -->
    <ToolTestDialog
      v-if="showTestDialog && tool"
      :tool="tool"
      @close="showTestDialog = false"
    />
  </div>
</template>
