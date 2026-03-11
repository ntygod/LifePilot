const THEME_DISPLAY_LABELS = {
  light: '浅色',
  dark: '深色',
  system: '跟随系统',
} as const

const DENSITY_DISPLAY_LABELS = {
  compact: '紧凑',
  standard: '标准',
} as const

const FONT_SIZE_DISPLAY_LABELS = {
  small: '小',
  medium: '中',
  large: '大',
} as const

export function getThemeDisplayLabel(value?: string | null) {
  if (!value) return '未设置'
  return THEME_DISPLAY_LABELS[value as keyof typeof THEME_DISPLAY_LABELS] ?? value
}

export function getDensityDisplayLabel(value?: string | null) {
  if (!value) return '未设置'
  return DENSITY_DISPLAY_LABELS[value as keyof typeof DENSITY_DISPLAY_LABELS] ?? value
}

export function getFontSizeDisplayLabel(value?: string | null) {
  if (!value) return '未设置'
  return FONT_SIZE_DISPLAY_LABELS[value as keyof typeof FONT_SIZE_DISPLAY_LABELS] ?? value
}
