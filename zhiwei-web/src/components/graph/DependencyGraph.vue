<!--
  依赖关系力导向图组件。

  使用 D3-force 力导向布局 + SVG 渲染，可视化 Agent、Skill、Tool 之间的引用关系。
  支持节点颜色区分、点击高亮、搜索定位、缩放平移。

  @author zsg
  @since 2026-03-16
-->
<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import 'd3-transition'
import {
  forceSimulation,
  forceLink,
  forceManyBody,
  forceCenter,
  forceCollide,
  type Simulation,
  type SimulationNodeDatum,
  type SimulationLinkDatum,
} from 'd3-force'
import { select } from 'd3-selection'
import { zoom, zoomIdentity, type ZoomBehavior } from 'd3-zoom'
import type { DependencyNode, DependencyEdge } from '@/types'

// Props 定义
const props = defineProps<{
  nodes: DependencyNode[]
  edges: DependencyEdge[]
}>()

// 节点颜色映射：Agent 蓝色、Skill 绿色、Tool 紫色
const NODE_COLORS: Record<string, string> = {
  AGENT: '#3b82f6',
  SKILL: '#10b981',
  TOOL: '#8b5cf6',
}

// 节点类型中文标签
const TYPE_LABELS: Record<string, string> = {
  AGENT: '智能体',
  SKILL: '技能',
  TOOL: '工具',
}

// 节点半径
const NODE_RADIUS = 20

// ========== 响应式状态 ==========

// SVG 容器引用
const svgRef = ref<SVGSVGElement | null>(null)
const containerRef = ref<HTMLDivElement | null>(null)

// 搜索关键词
const searchQuery = ref('')

// 选中的节点（点击高亮用）
const selectedNode = ref<DependencyNode | null>(null)

// SVG 尺寸
const svgWidth = ref(800)
const svgHeight = ref(600)

// D3 simulation 实例
let simulation: Simulation<SimNode, SimLink> | null = null
let zoomBehavior: ZoomBehavior<SVGSVGElement, unknown> | null = null

// 内部节点/边数据（D3 会修改这些对象，添加 x/y/vx/vy 等属性）
interface SimNode extends SimulationNodeDatum {
  id: string
  name: string
  type: 'AGENT' | 'SKILL' | 'TOOL'
  enabled: boolean
}

interface SimLink extends SimulationLinkDatum<SimNode> {
  relation: string
}

const simNodes = ref<SimNode[]>([])
const simLinks = ref<SimLink[]>([])

// 当前变换状态（缩放/平移）
const transform = ref({ x: 0, y: 0, k: 1 })

function getLinkNodeId(value: SimNode | string | number): string {
  return typeof value === 'object' ? value.id : String(value)
}

// ========== 计算属性 ==========

// 搜索匹配的节点 ID 集合
const matchedNodeIds = computed<Set<string>>(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return new Set()
  return new Set(
    simNodes.value
      .filter(n => n.name.toLowerCase().includes(q) || n.id.toLowerCase().includes(q))
      .map(n => n.id)
  )
})

// 选中节点直接关联的节点 ID 集合
const connectedNodeIds = computed<Set<string>>(() => {
  if (!selectedNode.value) return new Set()
  const sid = selectedNode.value.id
  const ids = new Set<string>([sid])
  for (const link of simLinks.value) {
    const src = getLinkNodeId(link.source)
    const tgt = getLinkNodeId(link.target)
    if (src === sid) ids.add(tgt)
    if (tgt === sid) ids.add(src)
  }
  return ids
})

// 选中节点关联的边索引集合
const connectedLinkIndices = computed<Set<number>>(() => {
  if (!selectedNode.value) return new Set()
  const sid = selectedNode.value.id
  const indices = new Set<number>()
  simLinks.value.forEach((link, i) => {
    const src = getLinkNodeId(link.source)
    const tgt = getLinkNodeId(link.target)
    if (src === sid || tgt === sid) indices.add(i)
  })
  return indices
})

// 选中节点的关联节点列表（侧边面板用）
const connectedNodes = computed(() => {
  if (!selectedNode.value) return []
  const sid = selectedNode.value.id
  const result: Array<{ id: string; name: string; type: string; relation: string; direction: string }> = []
  for (const link of simLinks.value) {
    const src = typeof link.source === 'object' ? (link.source as SimNode) : simNodes.value.find(n => n.id === link.source)
    const tgt = typeof link.target === 'object' ? (link.target as SimNode) : simNodes.value.find(n => n.id === link.target)
    if (!src || !tgt) continue
    if (src.id === sid) {
      result.push({ id: tgt.id, name: tgt.name, type: tgt.type, relation: link.relation, direction: '→' })
    } else if (tgt.id === sid) {
      result.push({ id: src.id, name: src.name, type: src.type, relation: link.relation, direction: '←' })
    }
  }
  return result
})

// ========== 节点样式计算 ==========

/** 获取节点填充颜色 */
function getNodeFill(node: SimNode): string {
  return NODE_COLORS[node.type] ?? '#6b7280'
}

/** 获取节点透明度 */
function getNodeOpacity(node: SimNode): number {
  // 禁用节点降低透明度
  const baseOpacity = node.enabled ? 1 : 0.4
  // 搜索模式：匹配节点高亮，其余降低
  if (matchedNodeIds.value.size > 0) {
    return matchedNodeIds.value.has(node.id) ? baseOpacity : baseOpacity * 0.2
  }
  // 选中模式：关联节点高亮，其余降低
  if (selectedNode.value) {
    return connectedNodeIds.value.has(node.id) ? baseOpacity : baseOpacity * 0.2
  }
  return baseOpacity
}

/** 获取边透明度 */
function getLinkOpacity(index: number): number {
  // 搜索模式下所有边降低透明度
  if (matchedNodeIds.value.size > 0) return 0.1
  // 选中模式：关联边高亮，其余降低
  if (selectedNode.value) {
    return connectedLinkIndices.value.has(index) ? 0.8 : 0.1
  }
  return 0.4
}

/** 获取节点文字颜色 */
function getTextOpacity(node: SimNode): number {
  if (matchedNodeIds.value.size > 0) {
    return matchedNodeIds.value.has(node.id) ? 1 : 0.15
  }
  if (selectedNode.value) {
    return connectedNodeIds.value.has(node.id) ? 1 : 0.15
  }
  return 1
}

// ========== 交互处理 ==========

/** 点击节点：高亮该节点及关联边/节点 */
function handleNodeClick(node: SimNode) {
  if (selectedNode.value?.id === node.id) {
    // 再次点击取消选中
    selectedNode.value = null
  } else {
    selectedNode.value = {
      id: node.id,
      name: node.name,
      type: node.type,
      enabled: node.enabled,
    }
  }
}

/** 点击空白区域取消选中 */
function handleBackgroundClick() {
  selectedNode.value = null
}

/** 搜索并居中到第一个匹配节点 */
function handleSearch() {
  if (matchedNodeIds.value.size === 0 || !svgRef.value || !zoomBehavior) return
  // 找到第一个匹配节点
  const firstId = matchedNodeIds.value.values().next().value
  const node = simNodes.value.find(n => n.id === firstId)
  if (!node || node.x == null || node.y == null) return
  // 使用 d3-zoom 平滑过渡到目标节点
  const svgEl = select(svgRef.value)
  const targetX = svgWidth.value / 2 - node.x * transform.value.k
  const targetY = svgHeight.value / 2 - node.y * transform.value.k
  ;(svgEl as any).transition().duration(500).call(
    zoomBehavior.transform as any,
    zoomIdentity.translate(targetX, targetY).scale(transform.value.k)
  )
}

/** 清除搜索 */
function clearSearch() {
  searchQuery.value = ''
}

// ========== D3 Simulation 管理 ==========

/** 初始化或重启 simulation */
function initSimulation() {
  // 停止旧 simulation
  if (simulation) {
    simulation.stop()
    simulation = null
  }

  // 空数据不创建 simulation
  if (props.nodes.length === 0) return

  // 复制节点和边数据（D3 会修改这些对象）
  const nodes: SimNode[] = props.nodes.map(n => ({
    id: n.id,
    name: n.name,
    type: n.type,
    enabled: n.enabled,
  }))

  // 构建节点 ID 集合，过滤掉引用不存在节点的边
  const nodeIdSet = new Set(nodes.map(n => n.id))

  const links: SimLink[] = props.edges
    .filter(e => nodeIdSet.has(e.source) && nodeIdSet.has(e.target))
    .map(e => ({
      source: e.source,
      target: e.target,
      relation: e.relation,
    }))

  simNodes.value = nodes
  simLinks.value = links

  // 创建力导向 simulation
  simulation = forceSimulation<SimNode>(nodes)
    .force('link', forceLink<SimNode, SimLink>(links)
      .id(d => d.id)
      .distance(120)
    )
    .force('charge', forceManyBody().strength(-300))
    .force('center', forceCenter(svgWidth.value / 2, svgHeight.value / 2))
    .force('collide', forceCollide(NODE_RADIUS + 10))
    .on('tick', () => {
      // 触发 Vue 响应式更新
      simNodes.value = [...nodes]
      simLinks.value = [...links]
    })
}

/** 初始化 SVG 缩放/平移 */
function initZoom() {
  if (!svgRef.value) return

  zoomBehavior = zoom<SVGSVGElement, unknown>()
    .scaleExtent([0.2, 4])
    .on('zoom', (event) => {
      transform.value = {
        x: event.transform.x,
        y: event.transform.y,
        k: event.transform.k,
      }
    })

  select(svgRef.value).call(zoomBehavior)
}

/** 更新 SVG 尺寸 */
function updateSize() {
  if (!containerRef.value) return
  const rect = containerRef.value.getBoundingClientRect()
  svgWidth.value = rect.width
  svgHeight.value = rect.height
}

// ========== 生命周期 ==========

let resizeObserver: ResizeObserver | null = null

onMounted(() => {
  updateSize()
  initZoom()
  initSimulation()

  // 监听容器尺寸变化
  if (containerRef.value) {
    resizeObserver = new ResizeObserver(() => {
      updateSize()
    })
    resizeObserver.observe(containerRef.value)
  }
})

onBeforeUnmount(() => {
  if (simulation) {
    simulation.stop()
    simulation = null
  }
  if (resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver = null
  }
})

// 监听 props 变化，重启 simulation
watch(() => [props.nodes, props.edges], () => {
  selectedNode.value = null
  searchQuery.value = ''
  nextTick(() => {
    initSimulation()
  })
}, { deep: true })

// 监听搜索输入，自动居中到第一个匹配
watch(searchQuery, (val) => {
  if (val.trim()) {
    // 延迟一帧让 matchedNodeIds 计算完成
    nextTick(() => handleSearch())
  }
})
</script>

<template>
  <div class="flex h-full w-full gap-0">
    <!-- 主图区域 -->
    <div class="flex-1 flex flex-col min-w-0">
      <!-- 搜索栏 -->
      <div class="flex items-center gap-2 p-3 border-b border-border">
        <div class="relative flex-1 max-w-[24rem]">
          <input
            v-model="searchQuery"
            type="text"
            placeholder="搜索节点名称..."
            class="w-full h-9 px-3 pr-8 rounded-md border border-input bg-background text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            @keydown.enter="handleSearch"
          />
          <button
            v-if="searchQuery"
            class="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
            @click="clearSearch"
          >
            <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
          </button>
        </div>
        <!-- 搜索结果计数 -->
        <span v-if="searchQuery && matchedNodeIds.size > 0" class="text-xs text-muted-foreground">
          匹配 {{ matchedNodeIds.size }} 个节点
        </span>
        <span v-else-if="searchQuery && matchedNodeIds.size === 0" class="text-xs text-destructive">
          未找到匹配节点
        </span>
        <!-- 图例 -->
        <div class="ml-auto flex items-center gap-3">
          <div v-for="(color, type) in NODE_COLORS" :key="type" class="flex items-center gap-1.5">
            <span class="inline-block w-3 h-3 rounded-full" :style="{ backgroundColor: color }" />
            <span class="text-xs text-muted-foreground">{{ TYPE_LABELS[type] }}</span>
          </div>
        </div>
      </div>

      <!-- SVG 图形区域 -->
      <div ref="containerRef" class="flex-1 relative overflow-hidden bg-muted/30">
        <!-- 空数据提示 -->
        <div
          v-if="props.nodes.length === 0"
          class="absolute inset-0 flex items-center justify-center"
        >
          <div class="text-center">
            <svg xmlns="http://www.w3.org/2000/svg" width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1" stroke-linecap="round" stroke-linejoin="round" class="mx-auto text-muted-foreground/50 mb-3"><circle cx="12" cy="12" r="10"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg>
            <p class="text-sm text-muted-foreground">暂无依赖关系数据</p>
          </div>
        </div>

        <svg
          v-else
          ref="svgRef"
          :width="svgWidth"
          :height="svgHeight"
          class="w-full h-full"
          @click.self="handleBackgroundClick"
        >
          <!-- 箭头标记定义 -->
          <defs>
            <marker
              id="arrowhead"
              viewBox="0 0 10 7"
              refX="10"
              refY="3.5"
              markerWidth="8"
              markerHeight="6"
              orient="auto"
            >
              <polygon points="0 0, 10 3.5, 0 7" fill="#94a3b8" />
            </marker>
          </defs>

          <!-- 变换容器（缩放/平移） -->
          <g :transform="`translate(${transform.x}, ${transform.y}) scale(${transform.k})`">
            <!-- 边 -->
            <line
              v-for="(link, index) in simLinks"
              :key="`link-${index}`"
              :x1="(link.source as SimNode).x ?? 0"
              :y1="(link.source as SimNode).y ?? 0"
              :x2="(link.target as SimNode).x ?? 0"
              :y2="(link.target as SimNode).y ?? 0"
              stroke="#94a3b8"
              :stroke-width="1.5"
              :opacity="getLinkOpacity(index)"
              marker-end="url(#arrowhead)"
              class="transition-opacity duration-200"
            />

            <!-- 边标签 -->
            <text
              v-for="(link, index) in simLinks"
              :key="`link-label-${index}`"
              :x="(((link.source as SimNode).x ?? 0) + ((link.target as SimNode).x ?? 0)) / 2"
              :y="(((link.source as SimNode).y ?? 0) + ((link.target as SimNode).y ?? 0)) / 2 - 6"
              text-anchor="middle"
              class="text-[9px] fill-muted-foreground pointer-events-none select-none"
              :opacity="getLinkOpacity(index)"
            >
              {{ link.relation }}
            </text>

            <!-- 节点组 -->
            <g
              v-for="node in simNodes"
              :key="node.id"
              :transform="`translate(${node.x ?? 0}, ${node.y ?? 0})`"
              class="cursor-pointer"
              @click.stop="handleNodeClick(node)"
            >
              <!-- 选中高亮光环 -->
              <circle
                v-if="selectedNode?.id === node.id"
                :r="NODE_RADIUS + 4"
                :fill="getNodeFill(node)"
                :opacity="0.2"
              />
              <!-- 搜索匹配光环 -->
              <circle
                v-if="matchedNodeIds.has(node.id)"
                :r="NODE_RADIUS + 4"
                fill="#f59e0b"
                :opacity="0.3"
              />
              <!-- 节点圆形 -->
              <circle
                :r="NODE_RADIUS"
                :fill="getNodeFill(node)"
                :opacity="getNodeOpacity(node)"
                :stroke="selectedNode?.id === node.id ? '#ffffff' : 'none'"
                :stroke-width="selectedNode?.id === node.id ? 2 : 0"
                class="transition-opacity duration-200"
              />
              <!-- 节点类型首字母 -->
              <text
                text-anchor="middle"
                dominant-baseline="central"
                class="text-[11px] font-semibold fill-white pointer-events-none select-none"
                :opacity="getNodeOpacity(node)"
              >
                {{ node.type.charAt(0) }}
              </text>
              <!-- 节点名称 -->
              <text
                :y="NODE_RADIUS + 14"
                text-anchor="middle"
                class="text-[11px] fill-foreground pointer-events-none select-none"
                :opacity="getTextOpacity(node)"
              >
                {{ node.name.length > 12 ? node.name.slice(0, 12) + '…' : node.name }}
              </text>
            </g>
          </g>
        </svg>
      </div>
    </div>

    <!-- 侧边详情面板 -->
    <div
      v-if="selectedNode"
      class="w-72 border-l border-border bg-background p-4 overflow-y-auto shrink-0"
    >
      <div class="flex items-center justify-between mb-4">
        <h3 class="text-sm font-semibold text-foreground">节点详情</h3>
        <button
          class="text-muted-foreground hover:text-foreground"
          @click="selectedNode = null"
        >
          <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
        </button>
      </div>

      <!-- 节点基本信息 -->
      <div class="space-y-3 mb-4">
        <div>
          <span class="text-xs text-muted-foreground">名称</span>
          <p class="text-sm font-medium text-foreground">{{ selectedNode.name }}</p>
        </div>
        <div>
          <span class="text-xs text-muted-foreground">类型</span>
          <div class="mt-1">
            <span
              class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium text-white"
              :style="{ backgroundColor: NODE_COLORS[selectedNode.type] }"
            >
              {{ TYPE_LABELS[selectedNode.type] }}
            </span>
          </div>
        </div>
        <div>
          <span class="text-xs text-muted-foreground">状态</span>
          <div class="mt-1">
            <span
              class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium"
              :class="selectedNode.enabled
                ? 'bg-green-100 text-green-700 dark:bg-green-950 dark:text-green-400'
                : 'bg-muted text-muted-foreground'"
            >
              {{ selectedNode.enabled ? '已启用' : '已禁用' }}
            </span>
          </div>
        </div>
        <div>
          <span class="text-xs text-muted-foreground">ID</span>
          <p class="text-xs text-muted-foreground font-mono break-all">{{ selectedNode.id }}</p>
        </div>
      </div>

      <!-- 关联节点列表 -->
      <div v-if="connectedNodes.length > 0">
        <h4 class="text-xs font-medium text-muted-foreground mb-2">
          关联节点 ({{ connectedNodes.length }})
        </h4>
        <div class="space-y-1.5">
          <div
            v-for="cn in connectedNodes"
            :key="cn.id"
            class="flex items-center gap-2 p-2 rounded-md bg-muted/50 text-xs"
          >
            <span
              class="inline-block w-2.5 h-2.5 rounded-full shrink-0"
              :style="{ backgroundColor: NODE_COLORS[cn.type] }"
            />
            <span class="text-foreground truncate flex-1">{{ cn.name }}</span>
            <span class="text-muted-foreground shrink-0">{{ cn.direction }} {{ cn.relation }}</span>
          </div>
        </div>
      </div>
      <div v-else class="text-xs text-muted-foreground">
        无关联节点
      </div>
    </div>
  </div>
</template>
