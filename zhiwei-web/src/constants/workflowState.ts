/**
 * 工作流状态共享配置映射。
 * 供 ExecutionDetail、WorkflowManageView、WorkflowDetailView 共用。
 */
import type { Component } from 'vue'
import {
  CircleDot,
  Timer,
  PauseCircle,
  Clock,
  CheckCircle2,
  XCircle,
  AlertCircle,
} from 'lucide-vue-next'

export interface StateConfigItem {
  label: string
  class: string
  icon: Component
  badgeVariant: 'default' | 'secondary' | 'outline' | 'destructive'
}

/** 7 种 WorkflowState 的完整映射 */
export const stateConfig: Record<string, StateConfigItem> = {
  CREATED:   { label: '已创建', class: 'bg-gray-100 text-gray-800',     icon: CircleDot,   badgeVariant: 'secondary' },
  RUNNING:   { label: '运行中', class: 'bg-blue-100 text-blue-800',     icon: Timer,       badgeVariant: 'default' },
  PAUSED:    { label: '审批中', class: 'bg-amber-100 text-amber-800',   icon: PauseCircle, badgeVariant: 'default' },
  WAITING:   { label: '等待中', class: 'bg-sky-100 text-sky-800',       icon: Clock,       badgeVariant: 'secondary' },
  COMPLETED: { label: '已完成', class: 'bg-green-100 text-green-800',   icon: CheckCircle2, badgeVariant: 'outline' },
  FAILED:    { label: '失败',   class: 'bg-red-100 text-red-800',       icon: XCircle,     badgeVariant: 'destructive' },
  CANCELLED: { label: '已取消', class: 'bg-yellow-100 text-yellow-800', icon: AlertCircle, badgeVariant: 'secondary' },
}

/** 获取状态配置，未知状态返回默认值 */
export function getStateConfig(state: string): StateConfigItem {
  return stateConfig[state] ?? stateConfig.CREATED
}
