import type { Component } from 'vue'
import {
  BarChart3,
  BookOpen,
  Bot,
  Brain,
  Database,
  FlaskConical,
  GitBranch,
  MessageSquare,
  Puzzle,
  Server,
  Settings,
  ShoppingBag,
  Wrench,
  Workflow,
} from 'lucide-vue-next'

export interface SecondaryNavItem {
  label: string
  path: string
  icon: Component
}

export interface PrimaryNavItem {
  id: string
  label: string
  path: string
  icon: Component
  description: string
  matchPrefixes: string[]
  mode: 'conversation-list' | 'links'
  children?: SecondaryNavItem[]
}

export const primaryNavigationItems: PrimaryNavItem[] = [
  {
    id: 'conversations',
    label: '对话',
    path: '/conversations',
    icon: MessageSquare,
    description: '继续上次对话。',
    matchPrefixes: ['/conversations'],
    mode: 'conversation-list',
  },
  {
    id: 'knowledge',
    label: '资料',
    path: '/knowledge-bases',
    icon: BookOpen,
    description: '你的资料。',
    matchPrefixes: ['/knowledge-bases', '/datastores'],
    mode: 'links',
    children: [
      { label: '知识库', path: '/knowledge-bases', icon: BookOpen },
      { label: '资料仓库', path: '/datastores', icon: Database },
    ],
  },
  {
    id: 'memories',
    label: '记忆',
    path: '/memories',
    icon: Brain,
    description: '知微记住的内容。',
    matchPrefixes: ['/memories'],
    mode: 'links',
    children: [
      { label: '记忆管理', path: '/memories', icon: Brain },
    ],
  },
  {
    id: 'workspace',
    label: '能力',
    path: '/agents',
    icon: Bot,
    description: '技能、工具和工作流。',
    matchPrefixes: ['/agents', '/workflows', '/skills', '/marketplace', '/tools', '/mcp-servers', '/dependencies'],
    mode: 'links',
    children: [
      { label: '智能体', path: '/agents', icon: Bot },
      { label: '工作流', path: '/workflows', icon: Workflow },
      { label: '技能', path: '/skills', icon: Puzzle },
      { label: '扩展市场', path: '/marketplace', icon: ShoppingBag },
      { label: '工具', path: '/tools', icon: Wrench },
      { label: 'MCP 服务', path: '/mcp-servers', icon: Server },
      { label: '能力关系', path: '/dependencies', icon: GitBranch },
    ],
  },
  {
    id: 'analytics',
    label: '回顾',
    path: '/analytics/usage',
    icon: BarChart3,
    description: '轨迹和用量。',
    matchPrefixes: ['/analytics', '/eval', '/traces'],
    mode: 'links',
    children: [
      { label: '用量统计', path: '/analytics/usage', icon: BarChart3 },
      { label: '智能体分析', path: '/analytics/agents', icon: Bot },
      { label: '工具统计', path: '/analytics/tools', icon: Wrench },
      { label: '评估测试', path: '/eval', icon: FlaskConical },
      { label: '轨迹回放', path: '/traces', icon: GitBranch },
    ],
  },
  {
    id: 'settings',
    label: '我的',
    path: '/settings/general',
    icon: Settings,
    description: '偏好和设置。',
    matchPrefixes: ['/settings'],
    mode: 'links',
    children: [
      { label: '通用', path: '/settings/general', icon: Settings },
      { label: '模型与路由', path: '/settings/models', icon: Bot },
      { label: '知识与检索', path: '/settings/knowledge', icon: BookOpen },
      { label: '集成渠道', path: '/settings/channels', icon: MessageSquare },
      { label: '授权与自动执行', path: '/settings/permissions', icon: Wrench },
    ],
  },
]

export function isPathActive(pathname: string, targetPath: string) {
  return pathname === targetPath || pathname.startsWith(`${targetPath}/`)
}

export function resolvePrimaryNavigation(pathname: string) {
  return primaryNavigationItems.find(item =>
    item.matchPrefixes.some(prefix => isPathActive(pathname, prefix)),
  ) ?? primaryNavigationItems[0]
}
