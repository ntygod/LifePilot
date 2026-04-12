import { copyToClipboard } from './clipboard'

/** 从 code 元素的 class 中提取语言名称 */
function extractLanguage(code: HTMLElement): string | null {
  const match = code.className.match(/(?:language|hljs)-(\w+)/)
  return match ? match[1] : null
}

/**
 * 为容器内所有 <pre><code> 代码块注入工具栏（语言标签 + 复制按钮）。
 * 幂等：已注入的代码块不会重复添加。
 */
export function injectCopyButtons(container: HTMLElement): void {
  const codeBlocks = container.querySelectorAll('pre > code')
  for (const block of codeBlocks) {
    const pre = block.parentElement
    if (!pre || pre.querySelector('.code-block-toolbar')) continue

    pre.style.position = 'relative'

    const toolbar = document.createElement('div')
    toolbar.className = 'code-block-toolbar'

    const lang = extractLanguage(block as HTMLElement)
    const langLabel = document.createElement('span')
    langLabel.className = 'code-lang-label'
    langLabel.textContent = lang ?? ''
    toolbar.appendChild(langLabel)

    const btn = document.createElement('button')
    btn.className = 'code-copy-btn'
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
    toolbar.appendChild(btn)
    pre.appendChild(toolbar)
  }
}
