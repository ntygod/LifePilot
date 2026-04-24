# 浏览器 action 完整参考

| action | 说明 | 关键参数 |
|--------|------|---------|
| `navigate` | 导航到 URL | `url` |
| `click` | 点击元素 | `selector` |
| `input` | 输入文本 | `selector`, `value` |
| `scroll` | 滚动页面 | `direction`, `pixels` |
| `wait` | 等待元素 | `selector`, `state`, `timeout` |
| `hover` | 鼠标悬停 | `selector` |
| `select` | 选择下拉项 | `selector`, `value` |
| `keyboard` | 键盘操作 | `type`, `key` |
| `screenshot` | 截图 | `fullPage` |
| `evaluate` | 执行 JS | `expression` |
| `accessibility` | 获取无障碍树 | `rootSelector`, `maxDepth` |
| `tab` | 标签页管理 | `tabAction`, `tabId`, `url` |
| `storage` | Cookie/localStorage | `target`, `storageAction` |
| `close` | 关闭会话 | `sessionId` |

## 提取结构化数据示例

```
browser(action="evaluate",
  expression="JSON.stringify(Array.from(document.querySelectorAll('.item')).map(el => ({title: el.querySelector('h3').textContent, price: el.querySelector('.price').textContent})))",
  sessionId="task-name")
```

## 其他交互示例

```
browser(action="select", selector="#country", value="CN", sessionId="task-name")
browser(action="keyboard", key="Enter", type="key", sessionId="task-name")
browser(action="hover", selector=".menu-item", sessionId="task-name")
```
