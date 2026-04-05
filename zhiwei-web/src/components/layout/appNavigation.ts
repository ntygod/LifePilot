import type { Component } from 'vue'
import {
  BookOpen,
  Bot,
  Brain,
  Database,
  MessageSquare,
  Puzzle,
  Server,
  Settings,
  ShoppingBag,
  Wrench,
  Workflow,
} from 'lucide-vue-next'

export interface NavItem {
  label: string
  path: string
  icon: Component
  matchPrefixes: string[]
}

export interface NavGroup {
  id: string
  label: string
  items: NavItem[]
}

/** 侧边栏导航分组 */
export const sidebarNavGroups: NavGroup[] = [
  {
    id: 'workspace',
    label: '工作台',
    items: [
      { label: '智能体', path: '/agents', icon: Bot, matchPrefixes: ['/agents'] },
      { label: '工作流', path: '/workflows', icon: Workflow, matchPrefixes: ['/workflows'] },
      { label: '技能', path: '/skills', icon: Puzzle, matchPrefixes: ['/skills'] },
      { label: '扩展市场', path: '/marketplace', icon: ShoppingBag, matchPrefixes: ['/marketplace'] },
      { label: '工具', path: '/tools', icon: Wrench, matchPrefixes: ['/tools'] },
      { label: 'MCP 服务', path: '/mcp-servers', icon: Server, matchPrefixes: ['/mcp-servers'] },
    ],
  },
  {
    id: 'knowledge',
    label: '资料库',
    items: [
      { label: '知识库', path: '/knowledge-bases', icon: BookOpen, matchPrefixes: ['/knowledge-bases'] },
      { label: '资料仓库', path: '/datastores', icon: Database, matchPrefixes: ['/datastores'] },
      { label: '记忆', path: '/memories', icon: Brain, matchPrefixes: ['/memories'] },
    ],
  },
]

/** 底部快捷入口 */
export const bottomNavItems: NavItem[] = [
  { label: '设置', path: '/settings/general', icon: Settings, matchPrefixes: ['/settings', '/analytics', '/eval', '/traces'] },
]

/** 对话入口 */
export const conversationNav: NavItem = {
  label: '对话',
  path: '/conversations',
  icon: MessageSquare,
  matchPrefixes: ['/conversations'],
}

export function isPathActive(pathname: string, targetPath: string) {
  return pathname === targetPath || pathname.startsWith(`${targetPath}/`)
}

export function isNavItemActive(pathname: string, item: NavItem) {
  return item.matchPrefixes.some(prefix => isPathActive(pathname, prefix))
}
