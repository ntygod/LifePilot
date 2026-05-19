# Web 韧性架构设计（Web Resilience）

> 模块定位：系统性治理知微的搜索、抓取、浏览器自动化在面对反爬/反自动化时的可用性问题。
> 调研时间：2026-05-18
> 状态：调研完成，待评审

---

## 1. 问题域定义

知微作为 AI Agent，其核心能力之一是从互联网获取实时信息。当前面临的系统性问题：

- **搜索**：单一 Tavily 依赖，API key 缺失或服务异常时完全不可用
- **抓取**：静态抓取路径（Jsoup/HttpClient）被大量站点 403/429 拒绝
- **浏览器自动化**：Playwright stealth 脚本覆盖不全，知乎等站点触发重定向导致崩溃
- **整体**：无域名级策略学习、无 TLS 指纹伪装、无代理支持、无请求间隔控制

---

## 2. 2026 年反爬技术全景

### 2.1 现代反爬系统的检测层次

基于 2025-2026 年行业调研，现代反爬系统（Cloudflare、DataDome、Akamai、长亭雷池）采用多层检测：

| 检测层 | 检测手段 | 知微当前状态 |
|--------|---------|-------------|
| **L1 IP 信誉** | IP 黑名单、数据中心 IP 识别、请求频率 | ❌ 无代理支持，单 IP 出口，无域名级限流 |
| **L2 TLS 指纹** | JA3/JA4 指纹识别非浏览器客户端 | ❌ Java HttpClient（JDK 内置）的 TLS 指纹与 Chrome 完全不同 |
| **L3 HTTP 指纹** | User-Agent、请求头顺序、HTTP/2 设置帧 | ❌ Jsoup 路径 UA 硬编码为 `"ZhiWei/1.0 (Web Fetch Tool)"`；HttpClient 路径同样 |
| **L4 JS 挑战** | navigator.webdriver、Canvas/WebGL 指纹 | ✅ 浏览器路径有较完整的 stealth 脚本（PlaywrightBridge.injectStealthScripts），覆盖 webdriver/chrome/languages/permissions/connection/hardwareConcurrency/WebGL/iframe |
| **L5 行为分析** | 鼠标轨迹、滚动模式、请求时序 | ⚠️ 有 humanDelay（100-500ms 随机），但无鼠标轨迹/滚动模拟 |
| **L6 会话一致性** | Cookie 连续性、登录态、Turnstile 验证 | ✅ 支持 CDP/PERSISTENT/storageState 持久化，有 requestHumanTakeover 机制 |

### 2.2 关键技术趋势（2025-2026）

1. **TLS 指纹成为第一道防线**：JA3/JA4 指纹可在 TLS 握手阶段（HTTP 请求之前）识别非浏览器客户端。Java 的 `HttpClient` 和 Jsoup 的 TLS 指纹与 Chrome 完全不同，这是 403 的首要原因。

2. **Stealth 插件已不够用**：Playwright-stealth 只修补 JS 层面的泄露（navigator.webdriver 等），但无法解决 TLS 指纹、HTTP/2 设置帧、Canvas 指纹等底层问题。

3. **Java 生态的 TLS 伪装方案已出现**：`zhkl0228/impersonator`（Maven Central 可用）基于 BouncyCastle + OkHttp，可伪装 Chrome 的 JA3/JA4 指纹。

4. **自建搜索成为 Agent 标配**：SearXNG + MCP 方案（如 agent-search、one-search-mcp）提供零 API key 的搜索能力，作为 Tavily 的降级方案。

5. **反检测浏览器分化**：Camoufox（Firefox 内核，C++ 层面修改指纹）、Patchright（Playwright fork，修补 CDP 泄露）代表了比 stealth 脚本更深层的方案。

---

## 3. 架构设计

### 3.0 代码现状精确审计（基于源码）

以下结论均基于实际源码核对，非文档推断：

**已有能力（不需要重复建设）**：
- ✅ `ToolExecutionPipeline.executeWithRetry`：已有指数退避重试机制（默认 2 次），支持 `TRANSIENT_ERROR` / `RATE_LIMITED` 状态自动重试
- ✅ `ToolBudget.DEFAULT`：web.fetch/web.search 默认 30s 超时 + 2 次重试
- ✅ `PlaywrightBridge.injectStealthScripts`：覆盖 webdriver/chrome/languages/permissions/connection/hardwareConcurrency/deviceMemory/WebGL/outerWidth/iframe 等检测点
- ✅ `UserAgentBuilder`：浏览器路径已实现动态 UA（从 Chromium 运行时版本号拼接）
- ✅ `BrowserSessionManager`：支持 LAUNCH/CDP/PERSISTENT 三种模式，有 storageState 持久化
- ✅ `PlaywrightPageWrapper.humanDelay`：操作前 100-500ms 随机延迟
- ✅ `SsrfGuard`：完整的 SSRF 防护（含 DNS rebinding 防御、云 metadata 拦截）
- ✅ `WebFetchToolExecutor`：已有 HEAD 探测 → Jsoup/直连 → 浏览器回退的多路径架构
- ✅ `ToolResult.transientError`：超时已正确标记为 transient（可重试）

**确认缺失（需要新建）**：
- ❌ **Jsoup/HttpClient 路径 UA 伪装**：硬编码 `"ZhiWei/1.0 (Web Fetch Tool)"`（`WebFetchToolExecutor.java:415` 行）
- ❌ **403 时浏览器回退**：`fetchWithJsoup` 收到 `HttpStatusException(403)` 时直接抛出 IOException 到上层，未触发浏览器回退（回退仅在内容过短时触发）
- ❌ **`navigateWithResult` 防崩溃**：`page.title()` 在 JS 重定向期间执行会抛 `PlaywrightException`，未被内部 catch
- ❌ **域名级限流**：无任何请求间隔控制
- ❌ **TLS 指纹伪装**：Java HttpClient 的 JA3/JA4 指纹与 Chrome 完全不同
- ❌ **搜索多源降级**：`normalizeProvider()` 硬编码返回 `"tavily"`
- ❌ **域名策略学习**：无历史成功率记录，无自适应策略切换
- ❌ **代理支持**：无任何代理配置或轮换机制
- ❌ **标准浏览器请求头**：Jsoup/HttpClient 路径缺少 Accept、Accept-Language、Sec-Fetch-* 等头

**关键 Bug（需紧急修复）**：
- 🐛 `PlaywrightPageWrapper.navigateWithResult()`：知乎等站点导航后触发 JS 重定向，`page.title()` 在新导航期间执行抛出 "Execution context was destroyed"，导致整个工具调用失败
- 🐛 `WebFetchToolExecutor.fetchWithJsoup()`：Jsoup 抛出 `HttpStatusException(403)` 时，异常传播到 `execute()` 的通用 catch，返回 `ToolResult.error()`（不可重试），而非尝试浏览器回退或标记为 transient

### 3.1 分层防御架构

```
┌─────────────────────────────────────────────────────┐
│                   Agent 决策层                        │
│  ReflectContentBuilder · 错误分类 · 策略选择         │
├─────────────────────────────────────────────────────┤
│                   策略路由层（新增）                   │
│  DomainStrategyRouter · 自适应学习 · 回退链          │
├─────────────────────────────────────────────────────┤
│              请求执行层（增强）                        │
│  ┌──────────┐  ┌──────────┐  ┌──────────────┐      │
│  │ 静态抓取  │  │ 浏览器渲染 │  │ TLS 伪装客户端│      │
│  │ (Jsoup)  │  │(Playwright)│  │(Impersonator)│      │
│  └──────────┘  └──────────┘  └──────────────┘      │
├─────────────────────────────────────────────────────┤
│              基础设施层（新增）                        │
│  DomainRateLimiter · ProxyPool · RequestHeaderFactory│
└─────────────────────────────────────────────────────┘
```

### 3.2 核心组件设计

#### 3.2.1 DomainStrategyRouter（域名策略路由器）

根据目标域名自动选择最优请求策略：

```
策略优先级链：
1. 用户配置的域名规则（DB 热加载）
2. 自动学习的域名策略（基于历史成功率）
3. 默认策略（先 TLS 伪装 → 失败则浏览器 → 失败则报错）
```

域名策略数据模型：
- domain: 域名模式（支持通配符）
- preferredMethod: STATIC / TLS_IMPERSONATE / BROWSER / BROWSER_WITH_LOGIN
- failCount: 连续失败次数
- lastSuccess: 最后成功时间
- requiresProxy: 是否需要代理
- notes: 备注（如"需要登录"、"有 Cloudflare"）

#### 3.2.2 TLS 伪装 HTTP 客户端

引入 `zhkl0228/impersonator`（已发布到 Maven Central），替代 Java 原生 HttpClient：

- 伪装 Chrome 的 JA3/JA4 指纹
- 正确的 HTTP/2 设置帧（SETTINGS、WINDOW_UPDATE 顺序）
- 标准浏览器请求头（Accept、Accept-Language、Sec-Fetch-* 等）
- 作为 Jsoup 和原生 HttpClient 之间的中间层

#### 3.2.3 RequestHeaderFactory（请求头工厂）

统一管理所有 HTTP 请求的头部，确保一致性：

- 动态 UA（从 Playwright Chromium 版本号生成，与浏览器路径一致）
- 标准浏览器头集合（Accept、Accept-Language、Accept-Encoding、Sec-Fetch-*）
- Referer 策略（搜索结果 URL 自动补 Google Referer）
- Cookie 透传（支持从浏览器会话导出 Cookie 给静态请求复用）

#### 3.2.4 DomainRateLimiter（域名级限流器）

- 令牌桶算法，按域名独立计数
- 默认：同一域名最小间隔 1 秒，突发上限 3 请求
- 可配置：特定域名自定义限流参数
- 429 响应时自动加大间隔（指数退避）

#### 3.2.5 搜索多源降级

```
搜索执行链：
1. Tavily（主，需 API key）
2. SearXNG（自建实例，零 API key，Docker 一键部署）
3. DuckDuckGo HTML（兜底，无需 API key，解析 HTML 结果）
```

#### 3.2.6 浏览器引擎加固

- **导航防崩溃**：`navigateWithResult` 中所有 Playwright API 调用加 try-catch，context destroyed 不再导致工具失败
- **重定向检测**：导航后检查最终 URL，识别登录墙/验证码页面，返回结构化错误而非通用失败
- **会话复用**：web.fetch 的浏览器回退路径复用共享 BrowserContext（带 stealth），而非每次新建
- **Stealth 脚本升级**：补充 `navigator.plugins` 长度伪装、`Notification.permission` 修正、`window.chrome.app` 等 2026 年新增检测点

#### 3.2.7 代理支持（可选，远期）

- 配置层面预留 `lifepilot.meta.infra.proxy.*` 配置项
- 支持 HTTP/SOCKS5 代理
- 支持代理池轮换（多个代理地址随机选择）
- 初期不强制要求，作为"最后手段"预留接口

---

## 4. 关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| TLS 伪装方案 | zhkl0228/impersonator (Java) | Maven Central 可用，基于 OkHttp + BouncyCastle，与项目技术栈一致 |
| 搜索降级方案 | SearXNG Docker 自建 | 零 API key、零成本、隐私友好、可本地部署 |
| 浏览器引擎 | 维持 Playwright，不换 Camoufox | Camoufox 2025 有维护断档，Playwright Java 生态成熟，stealth 脚本可持续升级 |
| 代理方案 | 预留接口，不强制 | 个人用户场景下代理成本高，优先通过 TLS 伪装 + 浏览器回退解决 |
| 域名策略存储 | SQLite（复用现有 DB） | 与项目一致，Flyway 迁移管理 |

---

## 5. 与已有模块的集成点

| 模块 | 集成方式 |
|------|---------|
| `ToolExecutionPipeline` | 利用已有的 `executeWithRetry` + `TRANSIENT_ERROR` 机制 |
| `MetaProperties` | 新增 `infra.web-fetch.tls-impersonate`、`infra.web-fetch.rate-limit` 等配置 |
| `WebFetchToolExecutor` | 重构请求路径：静态 → TLS 伪装 → 浏览器，三级回退 |
| `WebSearchToolExecutor` | 新增 Provider 抽象，支持多源 |
| `BrowserSessionManager` | 修复 navigateWithResult 崩溃，新增共享 context 复用 |
| `ReflectContentBuilder` | 增强错误分类，区分"需要登录"、"需要代理"、"TLS 被拒" |

---

## 6. 调研参考

### 前沿技术来源
- [Playwright Stealth: What Works in 2026 and Where It Falls Short](https://dicloak.com/blog-detail/playwright-stealth-what-works-in-2026-and-where-it-falls-short) — stealth 插件的局限性分析
- [How to bypass Anti-Bots in 2026](https://roundproxies.com/blog/how-to-bypass-anti-bots/) — 多层检测绕过的完整方案
- [TLS Fingerprinting: How It Works & How to Bypass It](https://cloud.browserless.io/blog/tls-fingerprinting-explanation-detection-and-bypassing-it-in-playwright-and-puppeteer) — JA3/JA4 指纹原理与绕过
- [Bypass Cloudflare Turnstile in 2026](https://nerdbot.com/2026/04/28/bypass-cloudflare-turnstile-in-2026-headless-browser-scaling-and-deep-dive-into-native-chromium-patching/) — Zero-Trust Client Fingerprinting 趋势

### 开源项目参考
- [zhkl0228/impersonator](https://github.com/zhkl0228/impersonator) — Java TLS/JA3/JA4 指纹伪装（Maven Central: `com.github.zhkl0228:impersonator`）
- [brcrusoe72/agent-search](https://github.com/brcrusoe72/agent-search) — 自建搜索 API + MCP，捆绑 SearXNG
- [daijro/camoufox](https://github.com/daijro/camoufox) — Firefox 内核反检测浏览器
- [jo-inc/camofox-browser](https://github.com/jo-inc/camofox-browser) — AI Agent 专用的反检测浏览器服务

### 竞品参考
- Perplexity Comet — AI 原生浏览器，内置 Agent 能力，云端浏览器隔离
- Browserless.io — 商业化的反检测浏览器即服务，BrowserQL 查询语言
- ScrapFly / ZenRows — 商业抓取 API，内置反爬绕过

---

## 7. 实施路线图

### Phase A — 紧急修复（1-2 天）
- 修复 `navigateWithResult` context destroyed 崩溃
- web.fetch UA 伪装 + 标准浏览器请求头
- 403/429 自动浏览器回退

### Phase B — 核心加固（3-5 天）
- 引入 impersonator 做 TLS 指纹伪装
- DomainRateLimiter 域名级限流
- 搜索多源降级（SearXNG 作为 Tavily 备选）
- web.fetch/web.search maxRetries 调整

### Phase C — 智能策略（3-5 天）
- DomainStrategyRouter 域名策略路由
- 自适应学习（基于历史成功率自动切换策略）
- 域名策略 DB 持久化 + Flyway 迁移
- 前端策略管理页面（Phase 5 Web UI 模块 19 范围）

### Phase D — 远期增强（按需）
- 代理池支持
- Stealth 脚本持续升级（跟踪 Playwright 版本）
- 浏览器 Cookie 导出给静态请求复用
- 验证码自动识别（集成第三方服务或本地模型）
