import { copyToClipboard } from './clipboard'

/**
 * 为容器内所有 <pre><code> 代码块注入复制按钮。
 * 幂等：已注入的代码块不会重复添加按钮。
 */
export function injectCopyButtons(container: HTMLElement): void {
  const codeBlocks = container.querySelectorAll('pre > code')
  for (const block of codeBlocks) {
    const pre = block.parentElement
    if (!pre || pre.querySelector('.code-copy-btn')) continue

    pre.style.position = 'relative'
    const btn = document.createElement('button')
    btn.className =
      'code-copy-btn absolute top-2 right-2 p-1 rounded bg-muted/80 hover:bg-muted ' +
      'text-muted-foreground hover:text-foreground text-xs transition-colors'
    btn.textContent = '复制'
    btn.addEventListener('click', async () => {
      const success = await copyToClipboard(block.textContent ?? '')
      if (success) {
        btn.textContent = '已复制'
        setTimeout(() => { btn.textContent = '复制' }, 2000)
      } else {
        btn.textContent = '失败'
        setTimeout(() => { btn.textContent = '复制' }, 2000)
      }
    })
    pre.appendChild(btn)
  }
}
