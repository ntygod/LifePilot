// 知微浏览器工具 - 可交互元素标号脚本
// 返回 { elements, total, viewport, truncated, injectionId }
// 参数 opts: { injectLabels: bool, maxElements: int }
// 注意：本文件为 Playwright page.evaluate 的箭头函数表达式，
//       Playwright 会把第二参数作为 opts 直接传入函数，不要改成 IIFE。
//
// 清理策略（防误伤）：
//   每次注入生成一次性 nonce（injectionId），写入 data-zhiwei-injection-id；
//   开始注入前只清理"之前同脚本"注入的节点（按前一次 nonce + 旧属性名），
//   不会擦掉页面原生 data-zhiwei-idx / 其它样式类似的节点。

(opts) => {
    const options = opts || {};
    const maxElements = options.maxElements || 200;
    const injectLabels = options.injectLabels === true;

    const INTERACTIVE_TAGS = new Set(['A','BUTTON','INPUT','SELECT','TEXTAREA','LABEL','SUMMARY','DETAILS']);
    const INTERACTIVE_ROLES = new Set([
        'button','link','menuitem','menuitemcheckbox','menuitemradio',
        'checkbox','radio','tab','treeitem','textbox','combobox',
        'slider','switch','option','searchbox','spinbutton'
    ]);

    function isVisible(el) {
        const r = el.getBoundingClientRect();
        if (r.width === 0 || r.height === 0) return false;
        const s = getComputedStyle(el);
        if (s.display === 'none' || s.visibility === 'hidden' || s.opacity === '0') return false;
        if (r.bottom < 0 || r.top > innerHeight) return false;
        if (r.right < 0 || r.left > innerWidth) return false;
        return true;
    }

    function isInteractive(el) {
        if (INTERACTIVE_TAGS.has(el.tagName)) {
            if (el.tagName === 'INPUT' && el.type === 'hidden') return false;
            return true;
        }
        const role = el.getAttribute('role');
        if (role && INTERACTIVE_ROLES.has(role.toLowerCase())) return true;
        if (el.hasAttribute('onclick')) return true;
        if (el.hasAttribute('contenteditable') && el.getAttribute('contenteditable') !== 'false') return true;
        if (el.tabIndex >= 0 && getComputedStyle(el).cursor === 'pointer') return true;
        return false;
    }

    // 生成一次性 nonce，区分本次注入和页面上可能已有的同名属性节点
    const injectionId = 'zw-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);

    // 清理阶段：只删除带 data-zhiwei-injection-id 的旧注入痕迹，绝不触碰页面原生节点
    document.querySelectorAll('.__zhiwei-dom-label[data-zhiwei-injection-id]').forEach(n => n.remove());
    document.querySelectorAll('[data-zhiwei-injection-id]').forEach(n => {
        // 清理 data-zhiwei-idx / data-zhiwei-injection-id，保留节点本身
        n.removeAttribute('data-zhiwei-idx');
        n.removeAttribute('data-zhiwei-injection-id');
    });

    const elements = [];
    let total = 0;
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_ELEMENT);
    let node;
    while ((node = walker.nextNode())) {
        if (!isInteractive(node)) continue;
        if (!isVisible(node)) continue;
        total++;
        if (elements.length >= maxElements) continue;

        const rect = node.getBoundingClientRect();
        const index = elements.length;
        node.setAttribute('data-zhiwei-idx', String(index));
        node.setAttribute('data-zhiwei-injection-id', injectionId);

        const text = (node.innerText || node.value || node.placeholder || '').trim().slice(0, 80);

        elements.push({
            index: index,
            tag: node.tagName.toLowerCase(),
            role: (node.getAttribute('role') || '').toLowerCase(),
            text: text,
            name: node.getAttribute('name') || '',
            id: node.id || '',
            ariaLabel: node.getAttribute('aria-label') || '',
            bbox: [Math.round(rect.left), Math.round(rect.top),
                   Math.round(rect.width), Math.round(rect.height)]
        });

        if (injectLabels) {
            const label = document.createElement('div');
            label.textContent = String(index);
            label.className = '__zhiwei-dom-label';
            label.setAttribute('data-zhiwei-injection-id', injectionId);
            label.style.cssText = 'position:fixed;z-index:2147483647;' +
                'background:#ff0050;color:white;padding:1px 4px;' +
                'font-size:10px;font-family:monospace;border-radius:2px;' +
                'pointer-events:none;line-height:1.2;' +
                'left:' + rect.left + 'px;top:' + Math.max(0, rect.top - 14) + 'px;';
            document.body.appendChild(label);
        }
    }

    return {
        elements: elements,
        total: total,
        viewport: [innerWidth, innerHeight],
        truncated: total > elements.length,
        injectionId: injectionId
    };
}
