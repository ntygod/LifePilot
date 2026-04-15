/**
 * 行为插件视觉主题映射 — 贯穿浮窗和主界面的单一数据源。
 *
 * 每个行为有独立的颜色(HSL)、图标(lucide)和中文标签。
 * 浮窗通过 CSS 变量注入，主界面通过 Tailwind style binding。
 */

export interface BehaviorTheme {
  /** 行为名，与后端 ProactiveBehavior.name() 一致 */
  key: string
  /** 中文显示名 */
  label: string
  /** lucide 图标名（字符串，浮窗动态解析） */
  icon: string
  /** HSL 色相 */
  hue: number
  /** HSL 饱和度 */
  saturation: number
  /** HSL 亮度（亮色主题基准） */
  lightness: number
}

export const BEHAVIOR_THEMES: BehaviorTheme[] = [
  { key: 'follow-up', label: '追问进展', icon: 'MessageCircleQuestion', hue: 215, saturation: 65, lightness: 52 },
  { key: 'insight', label: '关联洞察', icon: 'Sparkles', hue: 38, saturation: 72, lightness: 50 },
  { key: 'clipboard', label: '剪贴板识别', icon: 'ClipboardCheck', hue: 24, saturation: 78, lightness: 52 },
  { key: 'report', label: '日报周报', icon: 'FileBarChart', hue: 152, saturation: 48, lightness: 42 },
  { key: 'info-supplement', label: '信息补充', icon: 'BookPlus', hue: 186, saturation: 55, lightness: 42 },
  { key: 'context-prep', label: '情境准备', icon: 'CalendarClock', hue: 230, saturation: 50, lightness: 52 },
  { key: 'task-execution', label: '任务代行', icon: 'Zap', hue: 2, saturation: 72, lightness: 54 },
  { key: 'reminder', label: '定时提醒', icon: 'BellRing', hue: 160, saturation: 30, lightness: 42 },
]

const themeMap = new Map(BEHAVIOR_THEMES.map(t => [t.key, t]))

/** 按 key 获取行为主题，未找到返回 undefined。 */
export function getBehaviorTheme(key: string | undefined | null): BehaviorTheme | undefined {
  if (!key) return undefined
  return themeMap.get(key)
}

/** 获取行为中文标签。 */
export function getBehaviorLabel(key: string | undefined | null): string {
  return getBehaviorTheme(key)?.label ?? '主动提醒'
}

/** 生成行为 CSS 变量对象 — 用于 :style 绑定。 */
export function behaviorCssVars(key: string | undefined | null): Record<string, string> {
  const theme = getBehaviorTheme(key)
  if (!theme) return { '--bh-hue': '160', '--bh-sat': '30%', '--bh-lit': '42%' }
  return {
    '--bh-hue': String(theme.hue),
    '--bh-sat': `${theme.saturation}%`,
    '--bh-lit': `${theme.lightness}%`,
  }
}

/**
 * 生成行为 Badge 内联样式 — 小色片标签用。
 * 背景半透明行为色，文字深色行为色。
 */
export function behaviorBadgeStyle(key: string | undefined | null): Record<string, string> {
  const theme = getBehaviorTheme(key)
  if (!theme) return {}
  const { hue: h, saturation: s, lightness: l } = theme
  return {
    background: `hsl(${h} ${s}% ${l}% / 0.12)`,
    color: `hsl(${h} ${s}% ${l - 8}%)`,
  }
}
