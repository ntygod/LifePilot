# 浏览器决策参考

> action 清单与参数见 tool schema。本文档仅含浏览器特有的决策规则。

## 元素定位 fallback 链

连续 2 次失败切下一级。页面变化后旧 elements 失效，操作前重新 snapshot。

1. `index`（snapshot 返回）— 抗 layout 抖动
2. `id` 选择器：`#<unique-id>`
3. `data-testid`：`[data-testid="<id>"]`
4. 无障碍角色：accessibility 拿语义结构
5. CSS 选择器
6. 仍失败 → web.fetch → web.search → requestHumanTakeover

## 人机接管

触发条件（任一满足即调 requestHumanTakeover，不要假装填密码）：

- navigate 后 URL 含 login / signin / auth / sso
- snapshot elements 含 type=password 的 input
- 截图明显是登录页 / 验证码 / 滑块
- 连续 2 次 snapshot 相同且无法推进

reason 由 Agent 自行组织（如"需要扫码登录"），挂起后用户点继续则从下一步恢复。
不要用于：页面加载慢、元素未出现 → 用 wait。

## 会话模式（acquisitionMode）

仅首次创建会话生效：

| 模式 | 适用 | 额外参数 |
|------|------|---------|
| LAUNCH（默认） | 一般抓取 | — |
| CDP | 用户已登录的站点、绕过验证码 | cdpUrl |
| PERSISTENT | 长期反复访问需登录的站点 | userDataDir |
