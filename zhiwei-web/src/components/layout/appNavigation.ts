import type { Component } from 'vue'
import {
  BarChart3,
  BookOpen,
  Bot,
  Brain,
  Cpu,
  GitBranch,
  Key,
  MessageSquare,
  MonitorCog,
  Puzzle,
  Server,
  Settings,
  ShoppingBag,
  Sparkles,
  Wrench,
} from 'lucide-vue-next'

export interface NavItem {
  label: string
  path: string
  icon: Component
  matchPrefixes: string[]
  /** 图标颜色 class（覆盖分组默认色） */
  iconColor?: string
}

export interface NavGroup {
  id: string
  label: string
  items: NavItem[]
  /** 分组内图标的默认颜色 class */
  iconColor?: string
}

/** 工作台 + 资料库 分组（草稿中的下半段） */
export const sidebarNavGroups: NavGroup[] = [
  {
    id: 'workspace',
    label: '工作台',
    iconColor: 'text-violet-500/70',
    items: [
      { label: '智能体', path: '/agents', icon: Bot, matchPrefixes: ['/agents'] },
      { label: '技能', path: '/skills', icon: Puzzle, matchPrefixes: ['/skills'] },
      { label: '扩展市场', path: '/marketplace', icon: ShoppingBag, matchPrefixes: ['/marketplace'] },
      { label: '工具', path: '/tools', icon: Wrench, matchPrefixes: ['/tools'] },
      { label: 'MCP 服务', path: '/mcp-servers', icon: Server, matchPrefixes: ['/mcp-servers'] },
    ],
  },
  {
    id: 'knowledge',
    label: '资料库',
    iconColor: 'text-amber-500/70',
    items: [
      { label: '知识库', path: '/knowledge-bases', icon: BookOpen, matchPrefixes: ['/knowledge-bases'] },
      { label: '记忆', path: '/memories', icon: Brain, matchPrefixes: ['/memories'] },
    ],
  },
]

/** 对话入口 */
export const conversationNav: NavItem = {
  label: '对话',
  path: '/conversations',
  icon: MessageSquare,
  matchPrefixes: ['/conversations'],
}

/** 偏好设置 + 回顾分析导航（管理 Tab 草稿中的上半段） */
export const settingsNavGroups: NavGroup[] = [
  {
    id: 'settings',
    label: '偏好设置',
    iconColor: 'text-sky-500/70',
    items: [
      { label: '通用', path: '/settings/general', icon: Settings, matchPrefixes: ['/settings/general'] },
      { label: '模型与路由', path: '/settings/models', icon: MonitorCog, matchPrefixes: ['/settings/models'] },
      { label: '知识与检索', path: '/settings/knowledge', icon: BookOpen, matchPrefixes: ['/settings/knowledge'] },
      { label: '集成渠道', path: '/settings/channels', icon: MessageSquare, matchPrefixes: ['/settings/channels'] },
      { label: '授权与执行', path: '/settings/permissions', icon: Key, matchPrefixes: ['/settings/permissions'] },
      { label: '主动助手', path: '/settings/proactive', icon: Sparkles, matchPrefixes: ['/settings/proactive'] },
      { label: '代码执行环境', path: '/settings/code-execution', icon: Cpu, matchPrefixes: ['/settings/code-execution'] },
    ],
  },
  {
    id: 'analytics',
    label: '回顾与分析',
    iconColor: 'text-emerald-500/70',
    items: [
      { label: '用量统计', path: '/analytics/usage', icon: BarChart3, matchPrefixes: ['/analytics/usage'] },
      { label: '智能体分析', path: '/analytics/agents', icon: Bot, matchPrefixes: ['/analytics/agents'] },
      { label: '工具统计', path: '/analytics/tools', icon: Wrench, matchPrefixes: ['/analytics/tools'] },
      { label: '轨迹回放', path: '/traces', icon: GitBranch, matchPrefixes: ['/traces'] },
    ],
  },
]

/**
 * 管理 Tab 草稿自上而下的 4 大分组：偏好设置 → 回顾与分析 → 工作台 → 资料库。
 *
 * <p>这个数组顺序直接决定了侧栏管理 Tab 的视觉顺序，{@link UnifiedSidebar} 按下标渲染。</p>
 */
export const manageNavGroups: NavGroup[] = [...settingsNavGroups, ...sidebarNavGroups]

/** 管理 Tab 关联的路由前缀，用于自动切换 Tab */
export const manageRoutePrefixes = manageNavGroups.flatMap(g => g.items.flatMap(i => i.matchPrefixes))

export function isPathActive(pathname: string, targetPath: string) {
  return pathname === targetPath || pathname.startsWith(`${targetPath}/`)
}

export function isNavItemActive(pathname: string, item: NavItem) {
  return item.matchPrefixes.some(prefix => isPathActive(pathname, prefix))
}
