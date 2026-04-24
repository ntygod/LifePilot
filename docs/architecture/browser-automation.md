# 浏览器自动化 — 架构设计

> **文档性质**：架构设计文档
> **模块归属**：`com.lifepilot.meta.infra.browser`
> **最后更新**：2026-04-24

## 1. 模块概述

浏览器自动化模块为 Agent 提供基于 Playwright 的完整 Web 交互能力。通过单一 `browser` 工具暴露 16 种 action（navigate / click / input / scroll / wait / hover / select / keyboard / screenshot / evaluate / accessibility / tab / storage / snapshot / requestHumanTakeover / close），支持三种浏览器获取模式（LAUNCH / CDP / PERSISTENT）、多会话多标签页管理、反检测指纹注入、DOM 元素标号 + vision 闭环、人机接管挂起，以及空闲超时清理。

**核心设计决策**：
- **Playwright 可选依赖**：通过反射检测 Playwright classpath 可用性，缺失时所有工具优雅降级返回安装指引
- **单工具多 action**：16 种操作合并为 1 个 `browser` 工具，通过 `ActionDispatchExecutor` 路由，减少 LLM 工具选择负担
- **Object 类型桥接**：`BrowserSessionManager` 不直接引用 Playwright 类型，通过 `PlaywrightBridge` 静态方法桥接，确保 Playwright 缺失时不触发 ClassNotFoundException
- **DOM 元素标号 + vision 闭环**：`snapshot` 一次性返回截图（自动挂 vision 输入）+ 可交互元素编号表，后续 click/input/hover 优先用 `index` 代替 CSS 选择器，定位更稳且抗 layout 抖动
- **人机接管挂起**：遇到验证码 / 登录墙 / 扫码等必须人工完成的场景，`requestHumanTakeover` 通过标准 `SuspendReason.BrowserTakeover` 机制挂起 Agent，用户在浏览器中完成后经 REST 恢复

## 2. 架构图

```mermaid
flowchart TD
    subgraph tool["工具层"]
        BTP["BrowserToolProvider<br/>工具定义 + Schema"]
        BADE["BrowserActionDispatchExecutor<br/>action 路由 + 前置检查"]
    end

    subgraph executors["Action Executor 层"]
        NAV["NavigateExecutor"]
        CLK["ClickExecutor"]
        INP["InputExecutor"]
        SCR["ScrollExecutor"]
        WAIT["WaitExecutor"]
        HOV["HoverExecutor"]
        SEL["SelectExecutor"]
        KBD["KeyboardExecutor"]
        SS["ScreenshotExecutor"]
        EVAL["EvaluateExecutor"]
        A11Y["AccessibilityExecutor"]
        TAB["TabExecutor"]
        STG["StorageExecutor"]
        SNAP["SnapshotExecutor"]
        HT["HumanTakeoverExecutor"]
    end

    subgraph session["会话管理层"]
        BSM["BrowserSessionManager<br/>会话 + 标签页 + 生命周期"]
        BSS["BrowserSessionScheduler<br/>@Scheduled 空闲清理"]
        PPW["PlaywrightPageWrapper<br/>Page 操作封装"]
        TSC["TextSnapshotCleaner<br/>14 步文本清洗"]
        IEI["InteractiveElementIndexer<br/>DOM 元素标号注入"]
    end

    subgraph bridge["Playwright 桥接层"]
        PB["PlaywrightBridge<br/>静态方法桥接 Playwright API"]
        UAB["UserAgentBuilder<br/>UA 从 Chromium 版本动态拼接"]
        PW["Playwright / Browser / BrowserContext / Page"]
    end

    BTP --> BADE
    BADE --> NAV & CLK & INP & SCR & WAIT & HOV & SEL & KBD & SS & EVAL & A11Y & TAB & STG & SNAP & HT
    NAV --> BSM
    NAV --> TSC
    CLK & INP & SCR & WAIT & HOV & SEL & KBD & SS & EVAL & A11Y & TAB & STG & SNAP --> BSM
    SNAP --> IEI
    BSS --> BSM
    BSM --> PPW
    BSM --> PB
    PB --> UAB
    PB --> PW
```

## 3. 浏览器获取模式

`BrowserAcquisitionMode` 枚举决定 Playwright 如何获取浏览器实例：

| 模式 | 启动方式 | BrowserContext 来源 | 登录态 | 适用场景 |
|------|---------|-------------------|--------|---------|
| **LAUNCH** | Playwright 自行启动 Chromium | 每会话独立上下文 | storageState 持久化 | 默认模式，全隔离 |
| **CDP** | 连接用户已运行的 Chrome（`--remote-debugging-port`） | 共享远程 Chrome 的默认上下文 | 复用用户登录态 | 需要利用已登录网站 |
| **PERSISTENT** | Playwright 启动带 userDataDir 的 Chromium | 共享持久上下文（绑定磁盘 profile） | Cookie/IndexedDB/SW 全持久化 | 长期保持登录态 |

### 模式初始化流程

```
createSessionPages(sessionId)
  ├─ LAUNCH     → ensureLaunchBrowser() → createContext(独立) → newPage
  ├─ CDP        → ensureCdpBrowserForSession(override) → 共享上下文 → newPage
  └─ PERSISTENT → ensurePersistentContextForSession(override) → 共享上下文 → newPage
```

**会话级模式覆盖**：LLM 可在首次调用时传入 `acquisitionMode` / `cdpUrl` / `userDataDir` 覆盖全局默认模式。覆盖参数通过 `registerSessionMode()` 注册，`createSessionPages()` 消费后立即删除。

## 4. 核心组件

### 4.1 BrowserSessionManager

| 职责 | 说明 |
|------|------|
| Playwright 检测 | 反射检测 API JAR + driver-bundle，缺一返回降级消息 |
| 懒初始化 | 首次 `getOrCreatePage()` 时创建 Playwright → Browser → Context → Page |
| 多会话管理 | `ConcurrentHashMap<sessionId, SessionPages>` |
| 多标签页 | `SessionPages` 内 `ConcurrentHashMap<tabId, PlaywrightPageWrapper>` + `activeTabId` |
| 空闲清理 | `cleanupIdleSessions()` 按 `idleTimeoutSeconds` 清理全部 page 超时的会话，由 {@link BrowserSessionScheduler} 每 60 秒触发 |
| 资源关闭 | `close()` 标注 `@PreDestroy`，按 Pages → Context → Browser → Playwright 顺序销毁（`synchronized`），保证 JVM 退出时 Chromium 子进程优雅释放 |

**线程安全设计**：
- `ensureBrowser()` / `ensureCdpBrowserForSession()` / `ensurePersistentContextForSession()` / `close()` 均为 `synchronized`
- `playwrightInstance` / `browserInstance` / `sharedBrowserContext` 为 `volatile`
- `sessions` / `sessionModeOverrides` 为 `ConcurrentHashMap`
- stealth 注入通过 `AtomicBoolean` 保证只执行一次

### 4.2 PlaywrightBridge

Playwright API 隔离层。所有方法为 `static`，参数和返回值均为 `Object` 类型，避免 `BrowserSessionManager` 直接引用 Playwright 类。内部 cast 为强类型调用。

关键功能：
- `launchBrowser()` — 包含 STEALTH_ARGS（12 个反自动化检测启动参数）
- `injectStealthScripts()` — 注入 9 项运行时反检测脚本（webdriver / chrome / languages / permissions / connection / hardware / viewport / WebGL / iframe）
- `connectOverCDP()` — CDP 连接（`browser.close()` 仅断开连接，不杀 Chrome）
- `launchPersistentContext()` — 持久上下文启动
- UA 拼接委托 `UserAgentBuilder`：`user-agent=auto` 时读 `browser.version()` 动态拼 Chrome UA，避免硬编码漂移

### 4.3 PlaywrightPageWrapper

Page 操作封装，职责：
- 封装 Playwright Page 方法为工具友好的接口（navigate / click / fill / screenshot / scroll / hover / selectOption / evaluate 等）
- `humanDelay()` — 操作前引入随机延迟模拟人工（virtual thread 安全）
- `touch()` — 更新 `lastAccessTime`，供空闲清理判断
- `navigateWithResult()` — 超时不抛异常，标记 `partial=true` 继续返回结果
- `evaluate()` — 配合 `TimeoutExecutor` 用 `js-execution-timeout-seconds` 硬超时包裹 Playwright 调用，避免死循环脚本阻塞线程
- `accessibilitySnapshot()` — 结合 `AccessibilityYamlTrimmer` 按 `accessibility-max-depth` 裁剪 ariaSnapshot YAML 输出
- `indexInteractiveElements()` — 委托 {@link InteractiveElementIndexer} 注入 JS 脚本扫描可交互元素，返回 {@link IndexedSnapshot}
- `clickByIndex` / `inputByIndex` / `hoverByIndex` — 通过 `data-zhiwei-idx="{n}"` 属性定位 snapshot 过的元素，抗 layout 抖动

### 4.4 BrowserActionDispatchExecutor

继承 `ActionDispatchExecutor`，职责：
- **构造时创建所有 16 个 sub-executor** 并注册到 action 路由表
- **`execute()` 统一前置检查**：Playwright 可用性 + `BrowserNotInstalledException` 捕获
- **会话模式覆盖**：分派前从输入提取 `acquisitionMode` 参数并注册

### 4.5 InteractiveElementIndexer

DOM 元素标号服务：
- 启动时从 classpath 加载 `static/browser-scripts/interactive-elements.js`
- 向页面 `evaluate` 注入脚本，扫描所有可交互元素（按 tag/role/tabindex/onclick 判定）
- 给每个元素打 `data-zhiwei-idx="{n}"` 属性，可选叠加视觉编号标签
- 返回 `List<IndexedElement>`（record：`index, tag, role, text, name, id, ariaLabel, bbox[4]`）包装为 {@link IndexedSnapshot}

### 4.6 TextSnapshotCleaner

14 步正则管线清洗 navigate 返回的页面文本：

| 步骤 | 清洗内容 |
|------|---------|
| 1-4 | 移除 `<script>` / `<style>` / `<noscript>` / `<svg>` 标签及内容 |
| 5 | 移除 HTML 注释 |
| 6 | 移除 `window.__pinia` 等全局状态注入行 |
| 7 | 移除 CSS 资源 URL 行 |
| 8 | 移除超长 JSON 配置行（>200 字符） |
| 9-12 | 移除 Base64 data URI / data-* 属性 / 内联 style / aria-*/role 属性 |
| 13 | 压缩连续空行 |
| 14 | strip + 截断到 `textSnapshotMaxLength` |

## 5. Action 参考

### 页面导航与内容

| Action | 风险 | 必需参数 | 说明 |
|--------|------|---------|------|
| `navigate` | MEDIUM | `url` | 导航并返回 title + textSnapshot（经 TextSnapshotCleaner 清洗） |
| `screenshot` | LOW | — | 返回 Base64 PNG（字段 `screenshot`），`fullPage=true` 截取整页；前端 `MediaDataExtractor` 自动挂下一轮 vision 输入 |
| `accessibility` | LOW | — | 返回 YAML 格式无障碍树（Playwright ariaSnapshot），按 `maxDepth` / `accessibility-max-depth` 裁剪 |
| `snapshot` | LOW | — | 一次性返回截图 + 可交互元素编号表，参数 `injectLabels` / `maxElements` / `viewportOnly`；返回 `{screenshot, elements:[{index,tag,role,text,name,id,ariaLabel,bbox}], total, truncated, viewport, url, title}` |

### 页面交互

| Action | 风险 | 必需参数 | 说明 |
|--------|------|---------|------|
| `click` | MEDIUM | `selector` 或 `index`（二选一） | CSS 选择器点击，或按 snapshot 返回的元素编号点击（更稳） |
| `input` | MEDIUM | (`selector` 或 `index`) + `value` | 表单填充（`page.fill`） |
| `scroll` | MEDIUM | — | 方向滚动（`direction` + `pixels`）或元素定位滚动（`selector`） |
| `wait` | LOW | `selector` | 等待元素状态（visible / hidden / attached） |
| `hover` | MEDIUM | `selector` 或 `index`（二选一） | 悬停，返回 tagName + textContent |
| `select` | MEDIUM | `selector` + (`value` 或 `label`) | 下拉选择 |
| `keyboard` | MEDIUM | `key` 或 `text` | 按键（`type=key`）或逐字符输入（`type=text`） |
| `evaluate` | HIGH | `expression` | 执行任意 JavaScript；受 `js-execution-timeout-seconds` 硬超时保护 |

> **Index 定位路径**：调用 `snapshot` 后每个可交互元素被注入 `data-zhiwei-idx="{n}"` 属性，`click/input/hover` 传 `index` 通过该属性定位。页面结构变化需重新 `snapshot` 才能复用 index；否则返回 `elementStale` 类错误。

### 标签页与会话

| Action | 风险 | 必需参数 | 说明 |
|--------|------|---------|------|
| `tab` | MEDIUM | `tabAction` | open / switch / close / list |
| `storage` | MEDIUM | `target`, `storageAction` | cookie 或 localStorage 的 get / set / clear |
| `requestHumanTakeover` | LOW | `reason` | 挂起 Agent 等待用户在浏览器中完成人工操作（验证码 / 登录 / 扫码 / 人机验证），可选 `sessionId`；详见 §10 |
| `close` | LOW | — | 关闭当前会话所有标签页 |

## 6. 反检测（Stealth）

启用条件：`meta.infra.browser.stealth-mode=true`（默认开启）

### 启动参数（STEALTH_ARGS）

```
--disable-blink-features=AutomationControlled
--disable-background-networking
--disable-component-update
--disable-extensions
--disable-hang-monitor
--disable-popup-blocking
--metrics-recording-only
--password-store=basic
... (共 12 项)
```

### 运行时脚本注入

通过 `BrowserContext.addInitScript()` 在每个新页面加载前执行：

1. **navigator.webdriver** → undefined
2. **window.chrome** → 伪造运行时对象
3. **navigator.languages** → 从 locale 动态构建
4. **navigator.permissions.query** → 修正 notifications 行为
5. **navigator.connection** → 伪造 4g 网络信息
6. **hardwareConcurrency / deviceMemory** → 8 / 8
7. **outerWidth/outerHeight** → headless 下窗口尺寸修正
8. **WebGL renderer** → 隐藏 SwiftShader 特征
9. **iframe contentWindow** → MutationObserver 同步覆盖子框架

> **注意**：不 mock `navigator.plugins`，Chromium 已有真实值，mock 反而暴露自动化。

## 7. 配置参考

所有配置项在 `meta.infra.browser` 命名空间下：

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `enabled` | boolean | true | 是否启用浏览器工具 |
| `headless` | boolean | false（`${BROWSER_HEADLESS:false}`） | 无头模式；桌面/本地默认 false 可看到 Agent 浏览过程，容器部署用环境变量 `BROWSER_HEADLESS=true` 覆写 |
| `acquisition-mode` | enum | LAUNCH | 浏览器获取模式 |
| `cdp-url` | string | — | CDP 模式的远程调试端口 URL |
| `user-data-dir` | string | `${zhiwei.data-dir}/cache/browser/profile` | PERSISTENT 模式的用户数据目录 |
| `stealth-mode` | boolean | true | 启用反检测 |
| `idle-timeout-seconds` | int | 300 | 空闲会话超时（秒），由 `BrowserSessionScheduler` 每 60 秒触发清理 |
| `tool-timeout-seconds` | int | 30 | navigate 导航超时（秒） |
| `install-timeout-seconds` | int | 600 | 浏览器二进制安装超时（秒） |
| `wait-timeout-seconds` | int | 10 | wait action 默认超时（秒） |
| `js-execution-timeout-seconds` | int | 10 | evaluate JS 执行超时（秒），通过 `TimeoutExecutor` 硬超时包裹 |
| `text-snapshot-max-length` | int | 10000 | 文本快照最大字符数 |
| `default-scroll-pixels` | int | 500 | scroll 默认像素数 |
| `accessibility-max-depth` | int | 5 | 无障碍树最大深度，由 `AccessibilityYamlTrimmer` 裁剪 |
| `human-delay-min-ms` | int | 100 | 人工延迟最小毫秒 |
| `human-delay-max-ms` | int | 500 | 人工延迟最大毫秒 |
| `user-agent` | string | `auto` | `auto` 时从 Chromium `browser.version()` 动态拼 UA；自定义字符串原样使用 |
| `viewport-width` | int | 1920 | 视口宽度 |
| `viewport-height` | int | 1080 | 视口高度 |
| `locale` | string | zh-CN | 浏览器语言区域 |
| `timezone-id` | string | Asia/Shanghai | 时区 |
| `extra-launch-args` | list | [] | 额外 Chromium 启动参数 |
| `persist-storage-state` | boolean | false | LAUNCH 模式下关闭会话时保存 storageState |
| `storage-state-dir` | string | `${zhiwei.data-dir}/cache/browser/storage-state` | storageState 持久化目录 |
| `snapshot.max-elements` | int | 200 | 单次 snapshot 最多返回元素数，超出部分不入 `elements` 但计入 `total` 且 `truncated=true` |
| `snapshot.viewport-only` | boolean | true | snapshot 截图是否只截 viewport；false 截全页 |
| `snapshot.inject-labels` | boolean | false | 是否叠加视觉编号标签；桌面 `headless=false` 场景可打开 |
| `takeover.timeout-seconds` | int | 300 | `requestHumanTakeover` 挂起等待超时（秒） |

## 8. 生命周期

```
应用启动
  └─ InfraToolProvider.registerTools()
       └─ BrowserToolProvider.buildBrowserTools()
            └─ BrowserActionDispatchExecutor 构造（注册 16 个 action）
            └─ BuiltinTool 注册到 DynamicToolRegistry
  └─ BrowserSessionScheduler @Scheduled 注册（每 60 秒触发 idle 清理）

首次调用 browser(action=navigate)
  └─ BrowserActionDispatchExecutor.execute()
       ├─ 可用性检查
       ├─ applySessionModeOverride()
       └─ super.execute() → NavigateExecutor
            └─ BrowserSessionManager.getOrCreatePage("default")
                 └─ 懒初始化 Playwright → Browser → Context → Page

后续调用复用已有会话和 Page

空闲清理（BrowserSessionScheduler 触发）
  └─ BrowserSessionManager.cleanupIdleSessions()
       └─ 关闭全部 page 超时的会话

应用关闭（@PreDestroy）
  └─ BrowserSessionManager.close()
       └─ Pages → Context → Browser → Playwright 逐层销毁
```

## 9. 包内文件清单

| 文件 | 职责 |
|------|------|
| `BrowserToolProvider` | 工具定义、Schema、注册 |
| `BrowserActionDispatchExecutor` | action 路由、前置检查、executor 创建 |
| `BrowserSessionManager` | 会话/标签页/生命周期管理（`@PreDestroy`） |
| `BrowserSessionScheduler` | `@Scheduled` 定时触发空闲会话清理 |
| `BrowserAcquisitionMode` | 浏览器获取模式枚举 |
| `PlaywrightBridge` | Playwright API 静态桥接（类型隔离） |
| `PlaywrightPageWrapper` | Page 操作封装 + 人工延迟 + 访问时间跟踪 + index 定位 |
| `UserAgentBuilder` | 从 Chromium 版本动态拼 User-Agent |
| `InteractiveElementIndexer` | 注入 JS 扫描可交互元素 + 编号返回 |
| `IndexedElement` | 元素编号 record（`index/tag/role/text/name/id/ariaLabel/bbox`） |
| `IndexedSnapshot` | snapshot 返回的 elements + total + truncated + viewport 聚合 record |
| `TextSnapshotCleaner` | 14 步正则清洗管线 |
| `AccessibilityYamlTrimmer` | 按 depth 裁剪 ariaSnapshot YAML 输出 |
| `TimeoutExecutor` | `CompletableFuture` 超时包裹工具（JS evaluate 硬超时） |
| `BrowserNavigateToolExecutor` | navigate action |
| `BrowserClickToolExecutor` | click action（`selector` 或 `index`） |
| `BrowserInputToolExecutor` | input action（`selector` 或 `index`） |
| `BrowserScrollToolExecutor` | scroll action（方向 + 元素定位） |
| `BrowserWaitToolExecutor` | wait action |
| `BrowserHoverToolExecutor` | hover action（`selector` 或 `index`） |
| `BrowserSelectToolExecutor` | select action |
| `BrowserKeyboardToolExecutor` | keyboard action（按键 + 文本） |
| `BrowserScreenshotToolExecutor` | screenshot action（Base64） |
| `BrowserEvaluateToolExecutor` | evaluate action（JS 执行） |
| `BrowserAccessibilityToolExecutor` | accessibility action（ARIA 快照） |
| `BrowserTabToolExecutor` | tab action（open / switch / close / list） |
| `BrowserStorageToolExecutor` | storage action（cookie / localStorage CRUD） |
| `BrowserSnapshotToolExecutor` | snapshot action（截图 + 元素编号表） |
| `BrowserHumanTakeoverExecutor` | requestHumanTakeover action（通过 `_suspend` 走标准挂起流程） |
| `BrowserNotInstalledException` | 浏览器二进制未安装异常 |
| `ScrollResult` | 滚动位置 record |
| `TabInfo` | 标签页信息 record |

## 10. 人机接管（requestHumanTakeover）

遇到验证码 / 登录墙 / 扫码登录 / 人机验证等必须由用户人工完成的场景，Agent 调用
`browser(action="requestHumanTakeover", reason="...")` 主动挂起，等用户在浏览器
里完成后恢复。不自建通信通道，直接复用引擎层的挂起-恢复机制
（见 `docs/architecture/agent-suspend-resume.md`）。

```
Agent 调用 requestHumanTakeover(reason, sessionId?)
  └─ BrowserHumanTakeoverExecutor.execute()
       └─ ToolResult.success({_suspend:true, _suspendReason:{type:"BrowserTakeover", ...}, ...})
            └─ ToolExecutionCoordinator.parseSuspendReasonFromOutput
                 └─ SuspendReason.BrowserTakeover(sessionId, reason, requestedAt)
                      └─ coreLoop 检测 state.suspended() → 持久化 + SSE AGENT_SUSPENDED
                           └─ 前端按 reasonType="BrowserTakeover" 唤起 HumanTakeoverModal

用户在浏览器中完成操作后
  └─ 前端 POST /api/agent/browser-takeover/{turnId}/resume?sessionId=...&cancelled=...&note=...
       └─ BrowserTakeoverController 发布 BrowserTakeoverCompletedEvent
            └─ AgentResumeListener 按 sessionId 匹配挂起 Agent
                 └─ ResumePayload.BrowserTakeoverCompleted(sessionId, note)
                      └─ AgentOrchestrator.resumeFromSuspend → 重新进入 coreLoop
```

**关键点**：
- `reason` 是必需参数，作为对用户的简短提示展示在弹窗中（如「需要扫码登录」「请输入短信验证码」）
- `sessionId` 是 `BrowserTakeover.sessionId` 与恢复 REST 的匹配键，默认 `default`
- 超时由 `browser.takeover.timeout-seconds` 控制（默认 300 秒），超时恢复策略由 Agent 侧决定
- `cancelled=true` 时前端传入取消信号，`note` 可携带备注
