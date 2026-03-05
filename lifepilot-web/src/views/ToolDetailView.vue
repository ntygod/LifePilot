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
const usage = ref<any | null>(null)

onMounted(async () => {
  await toolStore.fetchToolDetail(toolId.value)
  try {
    usage.value = await toolStore.fetchToolUsage(toolId.value)
  } catch {
    // 使用情况查询失败时不阻塞详情展示
  }
})

const sourceLabel: Record<string, string> = {
  builtin: 'Java 原生',
  yaml: 'YAML Tool',
  mcp: 'MCP 工具'
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
    <div class="flex-shrink-0 border-b border-border bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
      <div class="max-w-[1200px] mx-auto px-md md:px-lg py-md">
        <div class="flex items-center justify-between gap-sm">
          <div class="flex items-center gap-3">
            <button
              class="text-sm text-muted-foreground hover:text-foreground transition-colors"
              @click="router.push('/tools')"
            >
              ← 返回列表
            </button>
            <h2 class="text-2xl font-semibold text-foreground leading-tight">
              {{ tool?.displayName || tool?.name || '加载中...' }}
            </h2>
            <span class="text-xs px-2 py-0.5 rounded-full bg-green-100 text-green-800">
              默认可用
            </span>
          </div>
          <div class="flex gap-2">
            <button
              class="px-4 py-2 rounded-lg border border-input bg-background hover:bg-accent hover:shadow-sm text-sm transition-all duration-200 active:scale-[0.98]"
              @click="showTestDialog = true"
            >
              测试调用
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 内容区域 -->
    <div class="flex-1 overflow-y-auto">
      <div v-if="!tool" class="max-w-[1200px] mx-auto px-md md:px-lg py-lg text-sm text-muted-foreground">
        加载中...
      </div>
      <div v-else class="max-w-[1200px] mx-auto px-md md:px-lg py-lg space-y-6">
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
                <span class="text-muted-foreground">来源：</span>
                <span class="px-2 py-0.5 rounded-full bg-accent text-accent-foreground">
                  {{ sourceLabel[tool.source] ?? tool.source }}
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
            <div v-if="tool.exportable !== undefined">
              <span class="text-muted-foreground">可导出：</span>
              <span>{{ tool.exportable ? '是' : '否' }}</span>
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

        <!-- 使用情况 -->
        <div class="border border-border rounded-lg p-6">
          <h3 class="text-lg font-semibold text-foreground mb-4">使用情况</h3>
          <div v-if="!usage" class="text-sm text-muted-foreground">
            正在查询使用情况...
          </div>
          <div v-else class="space-y-3 text-sm">
            <div class="text-muted-foreground">
              被 {{ usage.skillCount ?? 0 }} 个 Skill 和 {{ usage.workflowCount ?? 0 }} 个 Workflow 使用。
            </div>
            <div class="grid grid-cols-1 md:grid-cols-2 gap-md">
              <div>
                <h4 class="text-sm font-medium text-foreground mb-xs">Skills</h4>
                <div v-if="usage.usedBySkills && usage.usedBySkills.length > 0" class="space-y-xs">
                  <button
                    v-for="skill in usage.usedBySkills"
                    :key="skill.id"
                    type="button"
                    class="w-full flex items-center justify-between px-md py-xs rounded-lg border border-input bg-background text-left text-sm hover:bg-accent hover:shadow-sm transition-all duration-200 active:scale-[0.98]"
                    @click="router.push(`/skills/${skill.id}`)"
                  >
                    <span class="truncate">{{ skill.name }}</span>
                    <span class="text-xs text-muted-foreground">查看</span>
                  </button>
                </div>
                <div v-else class="text-xs text-muted-foreground">
                  暂无 Skill 使用此 Tool
                </div>
              </div>
              <div>
                <h4 class="text-sm font-medium text-foreground mb-xs">Workflows</h4>
                <div v-if="usage.usedByWorkflows && usage.usedByWorkflows.length > 0" class="space-y-xs">
                  <button
                    v-for="wf in usage.usedByWorkflows"
                    :key="wf.id"
                    type="button"
                    class="w-full flex items-center justify-between px-md py-xs rounded-lg border border-input bg-background text-left text-sm hover:bg-accent hover:shadow-sm transition-all duration-200 active:scale-[0.98]"
                    @click="router.push(`/workflows/${wf.id}`)"
                  >
                    <span class="truncate">{{ wf.name }}</span>
                    <span class="text-xs text-muted-foreground">查看</span>
                  </button>
                </div>
                <div v-else class="text-xs text-muted-foreground">
                  暂无 Workflow 使用此 Tool
                </div>
              </div>
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
