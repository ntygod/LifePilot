import { computed, ref, type ComputedRef, type Ref } from 'vue'
import { useSettingsStore } from '@/stores/settings'

/** 主题模式类型 */
export type ThemeMode = 'light' | 'dark' | 'system'

/** localStorage 存储键 */
const STORAGE_KEY = 'lifepilot_theme'

/** 主题循环顺序 */
const CYCLE_ORDER: ThemeMode[] = ['light', 'dark', 'system']

/** 全局单例状态（跨组件共享） */
const mode = ref<ThemeMode>('system')

/** matchMedia 监听器引用，用于 cleanup */
let mediaQuery: MediaQueryList | null = null
let mediaHandler: ((e: MediaQueryListEvent) => void) | null = null

/**
 * 判断当前是否应使用暗色主题。
 */
function isDarkResolved(themeMode: ThemeMode): boolean {
  return themeMode === 'dark'
    || (themeMode === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches)
}

/**
 * 将主题应用到 DOM（添加/移除 dark class）。
 */
function applyTheme(themeMode: ThemeMode): void {
  if (isDarkResolved(themeMode)) {
    document.documentElement.classList.add('dark')
  } else {
    document.documentElement.classList.remove('dark')
  }
}

/**
 * 主题管理 composable。
 *
 * 提供主题模式切换、持久化、系统主题监听等功能。
 * 使用模块级单例 ref，确保多个组件共享同一主题状态。
 */
export function useTheme(): {
  mode: Ref<ThemeMode>
  resolvedTheme: ComputedRef<'light' | 'dark'>
  cycleTheme: () => void
  setTheme: (newMode: ThemeMode) => void
  init: () => void
  cleanup: () => void
} {
  const settingsStore = useSettingsStore()

  /** 解析后的实际主题（light 或 dark） */
  const resolvedTheme = computed<'light' | 'dark'>(() =>
    isDarkResolved(mode.value) ? 'dark' : 'light'
  )

  /**
   * 设置主题模式。
   * 同时更新 localStorage、DOM dark class、useSettingsStore.theme，异步 save 不阻塞。
   */
  function setTheme(newMode: ThemeMode): void {
    mode.value = newMode
    localStorage.setItem(STORAGE_KEY, newMode)
    applyTheme(newMode)
    settingsStore.theme = newMode
    settingsStore.save().catch(() => {})
  }

  /**
   * 循环切换主题：light → dark → system → light。
   */
  function cycleTheme(): void {
    const currentIndex = CYCLE_ORDER.indexOf(mode.value)
    const nextMode = CYCLE_ORDER[(currentIndex + 1) % CYCLE_ORDER.length]
    setTheme(nextMode)
  }

  /**
   * 初始化主题。
   * 读取 localStorage，无值默认 system；应用 dark class；注册系统主题变化监听。
   */
  function init(): void {
    const stored = localStorage.getItem(STORAGE_KEY) as ThemeMode | null
    const initialMode = stored ?? 'system'
    mode.value = initialMode
    applyTheme(initialMode)

    // 监听系统主题偏好变化（仅在 system 模式下生效）
    mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
    mediaHandler = () => {
      if (mode.value === 'system') {
        applyTheme('system')
      }
    }
    mediaQuery.addEventListener('change', mediaHandler)
  }

  /**
   * 清理：移除 matchMedia 监听器。
   */
  function cleanup(): void {
    if (mediaQuery && mediaHandler) {
      mediaQuery.removeEventListener('change', mediaHandler)
      mediaQuery = null
      mediaHandler = null
    }
  }

  return {
    mode,
    resolvedTheme,
    cycleTheme,
    setTheme,
    init,
    cleanup,
  }
}
