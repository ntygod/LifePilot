import { useRouter } from 'vue-router'

/**
 * 快捷键定义接口。
 */
export interface ShortcutDefinition {
  /** 快捷键标识（用于展示） */
  id: string
  /** 分类 */
  category: string
  /** 显示名称 */
  name: string
  /** 按键组合（展示用） */
  keys: string[]
  /** macOS 按键组合（展示用，可选） */
  macKeys?: string[]
}

/**
 * 已注册的全局快捷键定义列表。
 * 供 useKeyboardShortcuts composable 和 SettingsShortcutsView 共用。
 */
export const SHORTCUT_DEFINITIONS: ShortcutDefinition[] = [
  {
    id: 'new-chat',
    category: '全局快捷键',
    name: '新建对话',
    keys: ['Ctrl', 'N'],
    macKeys: ['⌘', 'N'],
  },
  {
    id: 'close-modal',
    category: '全局快捷键',
    name: '关闭弹窗',
    keys: ['Escape'],
  },
]

/**
 * 全局键盘快捷键 composable。
 *
 * 在 AppLayout 的 onMounted 中调用 install() 注册监听器，
 * onUnmounted 中调用 uninstall() 移除监听器。
 */
export function useKeyboardShortcuts() {
  const router = useRouter()

  function handleKeydown(event: KeyboardEvent) {
    const target = event.target as HTMLElement
    const isInputFocused =
      target.tagName === 'INPUT' ||
      target.tagName === 'TEXTAREA' ||
      target.isContentEditable

    // Escape 始终生效
    if (event.key === 'Escape') {
      const dismissable = document.querySelector('[data-dismissable]:last-of-type')
      if (dismissable) {
        dismissable.dispatchEvent(new CustomEvent('dismiss'))
        event.preventDefault()
      }
      return
    }

    // 输入框中禁用其他快捷键
    if (isInputFocused) return

    const isMac = navigator.platform.includes('Mac')
    const mod = isMac ? event.metaKey : event.ctrlKey

    // Ctrl/Cmd + N：新建对话
    if (mod && event.key === 'n') {
      event.preventDefault()
      router.push({ name: 'conversations' })
    }
  }

  function install() {
    window.addEventListener('keydown', handleKeydown)
  }

  function uninstall() {
    window.removeEventListener('keydown', handleKeydown)
  }

  return {
    shortcuts: SHORTCUT_DEFINITIONS,
    install,
    uninstall,
  }
}
