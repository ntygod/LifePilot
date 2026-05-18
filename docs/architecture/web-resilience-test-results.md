# Web 韧性测试结果报告

> 测试时间：2026-05-18
> 测试方式：通过知微流式对话 API（`POST /api/chat/messages/stream`）真实调用 Agent 执行工具
> 测试环境：本地开发环境，Playwright headless Chromium
> 代码版本：feature/web-resilience 分支（Phase A 修复已提交但未重启应用，测试基于修复前的代码行为）

---

## 1. 浏览器反检测测试

### 1.1 bot.sannysoft.com

| 检测类别 | 总项数 | 通过 | 失败 | 详情 |
|---------|:-----:|:----:|:----:|------|
| General Detection | 12 | 11 | 1 | ❌ **WebDriver (New)** — `navigator.webdriver` 返回 `undefined`，但 `document` 上残留 `__webdriver_evaluate`、`__selenium_evaluate` 等 Playwright 底层注入属性 |
| Bot Framework Detection | 20 | 20 | 0 | ✅ PhantomJS/Headless Chrome/Selenium 等全部通过 |
| Navigator Properties | 25 | 25 | 0 | ✅ 屏幕尺寸/色深/WebGL/Canvas 等全部通过 |
| **总计** | **56** | **55** | **1** | |

**结论：** Playwright stealth 脚本整体有效，仅在 WebDriver 底层属性扫描维度暴露。`navigator.webdriver` 本身已被正确隐藏（返回 `undefined`），但 Playwright 在 `document` 上注入的内部属性（`__webdriver_evaluate` 等）被检测到。

**修复方向：** 需要在 stealth 脚本中删除 `document` 上的 `__webdriver_*` 和 `__selenium_*` 属性。

### 1.2 CreepJS (abrahamjuliot.github.io/creepjs)

- **状态：** 测试超时（页面加载 + 指纹计算耗时超过 200 秒）
- **待后续验证**

---

## 2. web.fetch 静态抓取测试

### 2.1 成功场景

| 站点 | URL | renderMode | 内容质量 | 备注 |
|------|-----|:----------:|:--------:|------|
| httpbin.org | `/html` | static | ✅ 完整 | 基准测试，无反爬 |
| 观察者网 | `/politics/2024_01_01_721000.shtml` | static | ✅ 完整 | 无反爬拦截 |
| V2EX | `/t/1000000` | static | ✅ 完整 | 无反爬拦截 |
| GitHub | `/nicehash/.../releases` | static | ✅ 完整 | 无反爬拦截 |

### 2.2 失败场景

| 站点 | URL | 失败原因 | 错误信息 | 是否触发浏览器回退 |
|------|-----|---------|---------|:------------------:|
| 知乎 | `/question/19550977` | HTTP 403 | `HTTP error fetching URL. Status=403` | ❌ 未回退（当前代码 bug） |
| NGA | `/thread.php?fid=489` | HTTP 403 | `HTTP error fetching URL. Status=403` | ❌ 未回退（当前代码 bug） |
| tls.browserleaks.com | `/json` | TLS 指纹被拒 | `HTTP/2 GOAWAY` — 服务器主动断开 | ❌ 未回退 |
| 12306 | `/index/` | SSL 证书验证失败 | `PKIX path building failed: unable to find valid certification path` | ❌ 不适用 |

### 2.3 部分成功（需要 JS 渲染）

| 站点 | URL | static 结果 | browser 结果 | 说明 |
|------|-----|:-----------:|:------------:|------|
| 百度搜索 | `/s?wd=test` | ⚠️ 空壳 | 未测试 | static 拿到页面框架但无搜索结果（JS 动态渲染） |
| 微信公众号 | `/s/fake_id` | 自动回退 browser | ✅ 页面加载 | 内容过短触发了浏览器回退 |

---

## 3. web.fetch 浏览器渲染回退测试

| 站点 | 触发条件 | browser 结果 | 说明 |
|------|---------|:------------:|------|
| 微博热搜 | 内容过短自动回退 | ✅ 成功 | 拿到完整热搜榜单，穿透了微博反爬 |
| 知乎 | 手动 `renderJs=true` | ❌ 崩溃 | `Execution context was destroyed, most likely because of a navigation` |
| NGA | 手动 `renderJs=true` | ⚠️ 拦截页 | 页面加载成功但返回"访客不能直接访问 (ERROR:15)"，需要登录态 |
| tls.browserleaks.com | 手动 `renderJs=true` | ✅ 成功 | 浏览器 TLS 栈正常，拿到了 JA3/JA4 指纹数据 |

---

## 4. 浏览器导航稳定性测试

| 目标 | 结果 | 错误信息 | 根因 |
|------|:----:|---------|------|
| 知乎问题页 | ❌ 崩溃 | `Execution context was destroyed, most likely because of a navigation` | 知乎在 DOMContentLoaded 后触发 JS 重定向到登录页，`page.title()` 在新导航期间执行导致 context 销毁 |
| 知乎首页 | ⚠️ 重定向 | 无崩溃 | 被重定向到 `/signin?next=%2F`，页面标题正常获取 |
| bot.sannysoft.com | ✅ 成功 | — | 正常导航，无重定向 |

---

## 5. TLS 指纹测试

通过 `tls.browserleaks.com/json` 获取的指纹对比：

| 指标 | Java HttpClient (static) | Headless Chrome (browser) | 说明 |
|------|:------------------------:|:-------------------------:|------|
| 连接结果 | ❌ HTTP/2 GOAWAY 被拒 | ✅ 成功 | Java TLS 指纹被识别为非浏览器 |
| JA3 Hash | 未获取（被拒） | `099ad55c231a09fad6922e33f9161a97` | Chrome 标准指纹 |
| JA4 | 未获取 | `t13d1516h2_8daaf6152771_d8a2da3f94cd` | h2 = HTTP/2 |
| Akamai Hash | 未获取 | `52d84b11737d980aef856699f885ca86` | Chrome 特有 |

**结论：** Java HttpClient 的 TLS 指纹与 Chrome 完全不同，部分严格站点会在 TLS 握手阶段直接拒绝连接。

---

## 6. 搜索功能测试

| Provider | 结果 | 说明 |
|----------|:----:|------|
| Tavily | ✅ 成功 | 搜索 "Playwright stealth 2026" 返回结果，但内容被系统截断 |
| SearXNG | 未测试 | 当前未配置 |
| DuckDuckGo HTML | 未测试 | 当前未实现 |

---

## 7. 问题汇总与优先级

### P0 — 紧急修复（影响核心功能）

| # | 问题 | 影响 | 复现方式 |
|---|------|------|---------|
| 1 | `navigateWithResult` 崩溃 | 知乎等站点 JS 重定向导致浏览器工具完全失败 | 导航到 `zhihu.com/question/*` |
| 2 | 403 不触发浏览器回退 | 知乎/NGA 等 403 站点无法自动降级到浏览器 | `web.fetch` 知乎 URL |
| 3 | Jsoup UA 暴露 | `"ZhiWei/1.0 (Web Fetch Tool)"` 被反爬系统直接识别 | 任何有 UA 检测的站点 |

### P1 — 重要改进（提升成功率）

| # | 问题 | 影响 | 说明 |
|---|------|------|------|
| 4 | TLS 指纹暴露 | Java HttpClient 被严格站点在 TLS 层拒绝 | tls.browserleaks.com 直接 GOAWAY |
| 5 | 无域名级限流 | 高频请求同一站点触发 429 | 未在本次测试中复现但架构文档已确认 |
| 6 | 搜索单一依赖 | Tavily 不可用时搜索完全中断 | 当前仅 Tavily 一个 provider |
| 7 | `document.__webdriver_*` 残留 | bot.sannysoft.com WebDriver (New) 检测失败 | 需要在 stealth 脚本中清理 |

### P2 — 增强（提升体验）

| # | 问题 | 影响 | 说明 |
|---|------|------|------|
| 8 | 12306 SSL 证书不信任 | 国铁自签 CA 不在 JVM truststore | 需要导入证书或配置信任 |
| 9 | 百度搜索 static 空壳 | 需要 JS 渲染才能拿到搜索结果 | 可通过域名策略自动选择 browser |
| 10 | 无登录态复用 | 知乎/NGA 等需要登录的站点无法访问 | 需要 CDP 模式或 Cookie 导入 |

---

## 8. Phase A 修复验证计划

Phase A 修复已提交（commit `d69fa630`），需要重启应用后验证：

1. **navigateWithResult 崩溃修复** → 重新导航知乎问题页，应返回 partial=true 而非崩溃
2. **403 浏览器回退** → `web.fetch` 知乎 URL，应自动尝试浏览器渲染
3. **UA 伪装** → 检查请求头是否为 Chrome UA（可通过 httpbin.org/headers 验证）
4. **stealth 脚本升级** → 重新测试 bot.sannysoft.com，检查 WebDriver (New) 是否通过

---

## 9. 修复后验证结果（2026-05-18 重启后）

应用重启后在新会话中重新测试，验证所有修复效果：

| # | 验证项 | 修复前 | 修复后 | 状态 |
|---|--------|--------|--------|:----:|
| 1 | 知乎 403 浏览器回退 | `HTTP error fetching URL. Status=403`（直接报错） | `renderMode: "browser"`，自动回退浏览器，返回知乎 404 拦截页内容 | ✅ |
| 2 | 知乎导航崩溃 | `Execution context was destroyed`（工具完全失败） | 正常返回结果（标题为空、URL 正确、内容是拦截页），无崩溃 | ✅ |
| 3 | bot.sannysoft.com stealth | `docProps` 有 `__webdriver_evaluate` 等残留 | `docProps = []`（已清理），`chromeApp = true`（新增伪造） | ✅ |
| 4 | NGA 403 浏览器回退 | `HTTP error fetching URL. Status=403`（直接报错） | `renderMode: "browser"`，自动回退浏览器，返回访客拦截页内容 | ✅ |
| 5 | tls.browserleaks.com GOAWAY | `HTTP/2 GOAWAY`（直接报错） | Agent 自动 `renderJs=true` 重试成功，拿到完整 TLS 指纹 | ✅ |
| 6 | UA 伪装 | `ZhiWei/1.0 (Web Fetch Tool)` | `Chrome/136.0.0.0 Safari/537.36`（标准 Chrome UA） | ✅ |
| 7 | 搜索功能 | 仅 Tavily | Tavily 正常工作（DuckDuckGo 降级已实现，待 Tavily 不可用时验证） | ✅ |

### 仍存在的已知限制

| 问题 | 说明 | 解决方案 |
|------|------|---------|
| bot.sannysoft.com WebDriver (New) 仍 failed | `hasOwnProperty('webdriver')` 返回 true，检测脚本用了更深层的探测 | 需要 Playwright 底层修改（如 Patchright fork），JS 层面无法完全解决 |
| 知乎/NGA 需要登录态 | 浏览器回退成功但内容是拦截页 | 使用 CDP 模式复用已登录 Chrome，或 `requestHumanTakeover` 人工登录 |
| Java HttpClient TLS 指纹 | 静态路径仍被严格站点在 TLS 层拒绝 | 需引入 impersonator 库（Phase B），当前通过浏览器回退绕过 |
