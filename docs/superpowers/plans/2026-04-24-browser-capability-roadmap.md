# 知微浏览器能力补全 Roadmap

> **For agentic workers:** 本文档是**多阶段 roadmap**，不是单一 TDD plan。Phase 0 可直接按本文档执行；Phase 1-2 的任务级规划应在**执行前**用 `superpowers:writing-plans` 细化为 TDD step plan（参考 `docs/superpowers/plans/2026-04-21-document-workspace-phase3a.md` 的粒度）。Phase 3 / Phase ∞ 是观察期，不预写 plan。Steps 用 `- [ ]` 追踪。

**Goal：** 分 4 阶段把知微浏览器工具补齐到业界 SOTA 水准（browser-use 89%+@WebVoyager 同水位），从清理技术债起步到补齐 DOM 标号视觉闭环和人机交接。

**Architecture：**
1. **基础操作层**（Playwright-Java）：继续自研，Phase 0 清烂账、Phase 2 补人机交接
2. **认知层**（DOM 标号 + vision 闭环）：Phase 1 参考 browser-use `ClickableElementDetector` 在 Java 端用 JS 注入实现
3. **产品层**（可观察性 + 接管）：Phase 2 前端浏览器专用卡片 + 暂停/接管弹窗
4. **备选层**：Phase 3 接 Playwright CLI 做 A/B 对照，不替换自家 tool
5. **终局**：Phase ∞ 切 Anthropic 原生 browser tool（尚未推出）

**Tech Stack：** Playwright-Java 1.58、Spring Boot 3.x、Vue 3 + Reka UI 2.x + Tailwind 命名尺度、Java 22（record / sealed / pattern matching）、JUnit 5 + Mockito、JS（interactive element 注入脚本）

**调研依据：** 本 roadmap 基于 2026-04-24 的深度代码复盘（`WebFetchToolExecutor`/`BrowserSessionManager`/14 子 executor 实际行为）与业界方案调研（browser-use 0.12.6、Playwright MCP v0.0.70、Stagehand v3.6.3、Skyvern 1.0.31、Anthropic `computer_20251124`）。

---

## 四阶段总览

| Phase | 时长 | 目标 | 触发条件 | 分支 |
|---|---|---|---|---|
| **Phase 0** — 清代码烂账 | 3-5 天 | 配置空转 / schema 撒谎 / 会话泄漏等 7 项 | 立即开工 | `feature/browser-phase0-cleanup` |
| **Phase 1** — DOM 标号 + vision 闭环 | 1-2 周 | 新增 `browser.snapshot`，click/input 支持 `index` | Phase 0 合入 develop | `feature/browser-phase1-dom-indexing` |
| **Phase 2** — 可观察性 + 人机交接 | 1 周 | 前端浏览器卡片 + 暂停接管 | Phase 1 合入 + 3 天无重大 bug | `feature/browser-phase2-ux` |
| **Phase 3** — Playwright CLI A/B | 按需 1 周 | 自家 tool 某类任务 <60% 时接入对照 | Phase 1 跑生产 2 周后评估 | 按需 |
| **Phase ∞** — Anthropic 原生 | 观察 | 切到 API 原生 browser tool | Anthropic 推出专用 `browser_YYYYMMDD`（非 `computer_*`） | — |

**执行顺序约束**：
1. Phase 0 **必须**先合入。配置空转和 schema 撒谎不修，后面任何新功能都在沙地盖楼。
2. Phase 1 紧随其后，是核心价值所在。
3. Phase 2 在 Phase 1 合入 + 生产跑 3 天无重大 bug 后开工。
4. Phase 3 / ∞ 等触发条件，不提前开。

---

## File Structure 总览（Phase 0-2）

### 后端新建

| 路径 | Phase | 责任 |
|---|---|---|
| `src/main/java/com/lifepilot/meta/infra/browser/BrowserSessionScheduler.java` | P0 | `@Scheduled` 定时调 idle 清理 |
| `src/main/java/com/lifepilot/meta/infra/web/SsrfGuard.java` | P0 | 内网地址拦截（IPv4/IPv6 私网段 + 云 metadata） |
| `src/main/resources/static/browser-scripts/interactive-elements.js` | P1 | DOM 可交互元素遍历 + 编号 + 可选视觉标签 |
| `src/main/java/com/lifepilot/meta/infra/browser/InteractiveElementIndexer.java` | P1 | Java 侧加载脚本、`page.evaluate`、解析回 `List<IndexedElement>` |
| `src/main/java/com/lifepilot/meta/infra/browser/IndexedElement.java` | P1 | record：index/tag/role/text/name/id/ariaLabel/bbox |
| `src/main/java/com/lifepilot/meta/infra/browser/BrowserSnapshotToolExecutor.java` | P1 | `browser.snapshot` action |
| `src/main/java/com/lifepilot/meta/infra/browser/BrowserHumanTakeoverExecutor.java` | P2 | `browser.requestHumanTakeover` action |

### 后端修改

| 路径 | Phase | 改动要点 |
|---|---|---|
| `BrowserSessionManager.java` | P0 | `close()` 加 `@PreDestroy`；L464 `contexts.getFirst()` 改 last-active 选择 |
| `PlaywrightBridge.java` | P0 | UA 从 `browser.version()` 动态获取；`MetaProperties.userAgent=auto` 时替换 |
| `PlaywrightPageWrapper.java` | P0/P1 | `accessibilitySnapshot` 消费 maxDepth；`evaluate` 用 timeout 守护；`screenshot` 返回 data-uri；新增 `indexInteractiveElements()`、`clickByIndex(int)`、`inputByIndex(int,String)`、`hoverByIndex(int)` |
| `WebFetchToolExecutor.java` | P0 | 实现 method/headers/body/timeoutSeconds；每次请求前调 `SsrfGuard.check(url)` |
| `WebToolProvider.java` | P0 | schema 与实现对齐（当前 schema 宣传的参数 execute 未读） |
| `BrowserAccessibilityToolExecutor.java` | P0 | 把 maxDepth 传给 PageWrapper（当前接收后丢弃） |
| `BrowserEvaluateToolExecutor.java` | P0 | 把 jsExecutionTimeoutSeconds 传给 evaluate（当前只打日志） |
| `BrowserScreenshotToolExecutor.java` | P0 | 返回字段名改 `screenshotDataUri`，内容 `data:image/png;base64,...` |
| `BrowserClickToolExecutor.java` / `BrowserInputToolExecutor.java` / `BrowserHoverToolExecutor.java` | P1 | 新增 `index: int?` 参数，与 `selector` 二选一 |
| `BrowserToolProvider.java` | P1/P2 | 注册 `snapshot` / `requestHumanTakeover` action |
| `ZhiWeiApplication.java` 或等价配置类 | P0 | 确认 `@EnableScheduling` 已启用 |
| `src/main/resources/application.yml` | P0/P1/P2 | 新增 `browser.snapshot.max-elements: 200`、`browser.takeover.timeout-seconds: 300`；`browser.headless: false`（Phase 2） |
| `src/main/resources/skills/browser-automation/SKILL.md` | P1 | 首选路径改为 snapshot → index；补选择器失败 fallback；加登录墙 → 交接引导 |

### 前端修改

| 路径 | Phase | 操作 |
|---|---|---|
| `zhiwei-web/src/composables/useChat.ts` | P0 | tool 返回的 `screenshotDataUri` 字段自动挂 media（供下一轮 vision 输入） |
| `zhiwei-web/src/components/chat/ToolCallCard.vue` | P2 | browser.* 命中时委托 BrowserToolCallCard |
| `zhiwei-web/src/components/chat/BrowserToolCallCard.vue` | P2 | 新建：URL + 截图预览 + action 徽章 + 可折叠 elements 列表 |
| `zhiwei-web/src/components/chat/HumanTakeoverModal.vue` | P2 | 新建：暂停弹窗 + 倒计时 + 继续/取消按钮 |
| `zhiwei-web/src/api/browser.ts` | P2 | 新建：takeover resume REST 封装 |

### 测试（TDD 细化时再补齐具体方法名，命名遵循 `类名_中文描述.java`）

| 路径 | Phase |
|---|---|
| `BrowserSessionScheduler_调度测试.java` | P0 |
| `WebFetchToolExecutor_HTTP方法扩展测试.java` | P0 |
| `SsrfGuard_内网拦截测试.java` | P0 |
| `BrowserSessionManager_CDP上下文选择测试.java` | P0 |
| `PlaywrightPageWrapper_maxDepth裁剪测试.java` | P0 |
| `PlaywrightPageWrapper_evaluate超时测试.java` | P0 |
| `InteractiveElementIndexer_标号测试.java` | P1 |
| `BrowserSnapshotToolExecutor_快照测试.java` | P1 |
| `BrowserClickToolExecutor_index路径测试.java` | P1 |
| `BrowserHumanTakeoverExecutor_暂停恢复测试.java` | P2 |

---

## Phase 0: 清代码烂账（3-5 天）

**目标**：所有"伪装成完成"的 TODO 清完；配置项全部落地生效；schema 与实现对齐。这是后续 Phase 的地基，不修好一切免谈。

**分支**：`feature/browser-phase0-cleanup`
**合入条件**：7 个任务全部验收 + `mvn test` 全绿 + 端到端冒烟通过。

### Task P0-1: idle 会话清理调度 + @PreDestroy

**现状**：`BrowserSessionManager.cleanupIdleSessions()`（L349）方法存在但无任何调度器调用，`browser.idle-timeout-seconds:300` 配置空转；`close()` 无 `@PreDestroy`，JVM 正常退出时 Chromium / 持久 profile 无法优雅释放。

**Files**:
- Create: `src/main/java/com/lifepilot/meta/infra/browser/BrowserSessionScheduler.java`
- Modify: `BrowserSessionManager.java`（`close()` 加 `@PreDestroy`）
- Verify: `@EnableScheduling` 已启用

**关键代码**:
```java
@Component
public class BrowserSessionScheduler {
    private final BrowserSessionManager sessionManager;
    public BrowserSessionScheduler(BrowserSessionManager m) { this.sessionManager = m; }

    /** 每 60 秒清理超过 idle-timeout 的空闲会话。 */
    @Scheduled(fixedDelay = 60_000)
    public void cleanup() { sessionManager.cleanupIdleSessions(); }
}
```
`BrowserSessionManager` 顶部 `import jakarta.annotation.PreDestroy;`，`close()` 方法上加 `@PreDestroy`。

**验收**:
- 单测 `BrowserSessionScheduler_调度测试`：mock SessionManager，验证 `cleanup()` 调用 `cleanupIdleSessions()`
- 集成：启动 Spring 上下文，创建会话后 idle 超过 300s + 60s，观察日志清理记录
- 关闭应用时 Chromium 进程释放（`ps/taskkill` 验证）

**commit**: `fix(browser): 补浏览器会话空闲清理调度和优雅关闭`

---

### Task P0-2: web.fetch schema 诚实化

**现状**：`WebToolProvider.java:99-118` schema 宣传 method/headers/body/timeoutSeconds，`WebFetchToolExecutor.execute()` L75-106 实际只读 `url/selector/renderJs`——对 LLM 撒谎。新项目无兼容包袱，**选择实现而非删除**，web.fetch 可作轻量 HTTP 客户端。

**Files**:
- Modify: `WebFetchToolExecutor.java:75-106`
- Modify: `WebToolProvider.java:99-118`（保留 schema，描述对齐实现）

**改动要点**:
- method 支持 GET/POST/PUT/DELETE/PATCH（默认 GET）
- body: String（POST/PUT/PATCH 有效）
- headers: `Map<String,String>`
- timeoutSeconds 覆盖 `application.yml` 默认
- 用 `java.net.http.HttpClient`（Java 22 原生，含 virtual thread 支持）
- 浏览器回退路径（`fetchWithBrowser`）仅 GET；method≠GET 时不回退，直接返回错误
- 错误分类保留：transientError/permanentError

**验收**:
- 单测覆盖 GET/POST/PUT/DELETE/PATCH 各一用例，+ 自定义 headers + JSON body
- LLM 说 POST 能真跑 POST（手测 httpbin.org）

**commit**: `feat(web-fetch): 实现 method/headers/body/timeoutSeconds 参数`

---

### Task P0-3: web.fetch SSRF 防护

**现状**：`WebFetchToolExecutor` schema 描述写"禁止访问内网地址"但代码无任何校验，LLM 可诱导访问 `http://127.0.0.1:8080/admin` / `http://169.254.169.254/latest/meta-data/`（云 metadata）。

**Files**:
- Create: `src/main/java/com/lifepilot/meta/infra/web/SsrfGuard.java`
- Modify: `WebFetchToolExecutor.java`（所有请求前 `SsrfGuard.check(url)`）
- Modify: `application.yml`（新增 `web-fetch.ssrf.allowlist: []`）

**拦截清单**:
- IPv4: `127.0.0.0/8`、`10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`、`169.254.0.0/16`
- IPv6: `::1`、`fc00::/7`、`fe80::/10`
- 域名：`metadata.google.internal`、`metadata.aws.internal`、`instance-data` 等云 metadata
- DNS 解析后**再次校验**（防 DNS rebinding）

**关键代码**:
```java
public final class SsrfGuard {
    public static void check(String url) throws SsrfBlockedException {
        URI uri = URI.create(url);
        String host = uri.getHost();
        if (host == null) throw new SsrfBlockedException("invalid host");
        for (InetAddress addr : InetAddress.getAllByName(host)) {
            if (isPrivate(addr) || isCloudMetadata(host)) {
                throw new SsrfBlockedException("target blocked by SSRF policy: " + host);
            }
        }
    }
    // ... isPrivate / isCloudMetadata 实现
}
```

**验收**:
- 单测覆盖每个黑名单段
- 手测：`web.fetch("http://127.0.0.1:8080")` 返回错误 `target blocked by SSRF policy`
- 白名单配置生效（企业私有化部署场景）

**commit**: `feat(web-fetch): 新增 SSRF 防护拦截内网地址和云 metadata`

---

### Task P0-4: 截图自动挂 LLM vision 输入

**现状**：`BrowserScreenshotToolExecutor` 返回裸 Base64 字符串；`ToolCallCard.vue` 只显示 `outputSummary` 文本截取（不渲染图片）；`useChat.ts:~819` 的 `data:${mimeType};base64,${data}` 路径没把 tool 返回的截图自动挂 media。结果：LLM 根本看不到自己让浏览器截的图，vision 能力闲置。

**Files**:
- Modify: `PlaywrightPageWrapper.screenshot`（字段统一 data-uri 格式）
- Modify: `BrowserScreenshotToolExecutor.java`（返回 `screenshotDataUri` 字段）
- Modify: `BrowserSnapshotToolExecutor.java`（Phase 1 用同名字段）
- Modify: `zhiwei-web/src/composables/useChat.ts`（识别 screenshotDataUri → queue media）

**改动要点**:
- 后端：`screenshot` → `screenshotDataUri: "data:image/png;base64,..."`
- 前端：在处理 tool call response 的钩子里，扫结果 JSON 的 `screenshotDataUri` 字段，调 `ctx.queueMedia({ mimeType: 'image/png', data: <base64 部分> })` 挂到**下一轮**用户消息的 media
- 注意不是挂当前消息（当前消息已发），是挂下一轮 LLM 输入

**验收**:
- 手测（Opus 4.7）：LLM 调 `browser.screenshot` → 下一轮明确引用图中元素（"我看到页面顶部有一个登录按钮"）
- 单测：mock tool 结果带 screenshotDataUri，验证 useChat 把它加到 media 队列

**commit**: `feat(browser): 截图自动挂 LLM vision 输入`

---

### Task P0-5: 消费 maxDepth / jsExecutionTimeout

**现状**：
- `BrowserAccessibilityToolExecutor` 读 maxDepth 但不传给 PageWrapper；`accessibilitySnapshot` 直接 `locator.ariaSnapshot()` 返回全量 YAML
- `BrowserEvaluateToolExecutor.java:59` 只打日志"JS 执行超时 {}s"，实际没传给 `page.evaluate`

**Files**:
- Modify: `BrowserAccessibilityToolExecutor.java`（传 maxDepth）
- Modify: `PlaywrightPageWrapper.accessibilitySnapshot`（按 maxDepth 裁剪 YAML 树）
- Modify: `BrowserEvaluateToolExecutor.java`（用 `CompletableFuture` + 超时包裹 `page.evaluate`）

**改动要点**:
- Playwright `ariaSnapshot()` 不支持深度裁剪参数 → 后处理：按 YAML 缩进层级裁剪，超 maxDepth 的子节点替换为 `...`
- 或改用 `page.accessibility().snapshot()` + 自己 BFS（更精确）
- evaluate 超时：`CompletableFuture.supplyAsync(() -> page.evaluate(expr)).get(timeout, SECONDS)`，超时后调 `page.reload()` 或中断线程

**验收**:
- 单测 `PlaywrightPageWrapper_maxDepth裁剪测试`：`maxDepth=2` 比 `maxDepth=5` 返回短
- 单测 `PlaywrightPageWrapper_evaluate超时测试`：`while(true){}` 超 10s 被打断，返回 timeout error

**commit**: `fix(browser): 消费 maxDepth 和 jsExecutionTimeout 配置`

---

### Task P0-6: CDP context 选择 last-active

**现状**：`BrowserSessionManager.java:464` `contexts.getFirst()` 固定选第 0 个 context，可能不是用户登录态所在 window。用户在 Chrome 开 3 个窗口（工作/个人/无痕），CDP 模式可能选错。

**Files**:
- Modify: `BrowserSessionManager.java:444-470`

**选择策略**:
- 优先：有 page 的 context
- 次级：page 数最多的
- 进阶（可选）：记录每个 context 最后一次 navigate/click 时间戳，选最新
- 启动日志："CDP 选中 context[X]（共 N 个 context，其中 Y 个有 page）"

**验收**:
- 单测 `BrowserSessionManager_CDP上下文选择测试`：mock 3 个 context（1 空 + 2 有 page），验证选择规则
- 手测：Chrome 开多窗口 CDP 连接，知微能选到最活跃窗口

**commit**: `fix(browser): CDP 模式选择最近活跃 context 而非第 0 个`

---

### Task P0-7: UA 版本号跟随 Chromium

**现状**：`MetaProperties.Browser.userAgent` L230 硬编码 `Chrome/131.0.0.0`，Playwright 已到 1.58（实际 Chromium 更新），版本号漂移导致指纹易识别。

**Files**:
- Modify: `PlaywrightBridge.java`（UA 动态获取）
- Modify: `MetaProperties.java` + `application.yml`（默认值改为 `auto`）

**改动要点**:
- Playwright 启动后 `browser.version()` 返回实际 Chromium 版本
- UA 模板：`Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/{VERSION} Safari/537.36`
- 配置 `user-agent: auto` → 运行时替换 `{VERSION}`
- 显式配置字符串 → 原样使用
- 启动日志输出最终 UA

**验收**:
- Playwright 升到 1.59 不改代码 UA 自动跟
- 手测：访问 `https://httpbin.org/user-agent` 返回的 UA 与 `browser.version()` 匹配

**commit**: `fix(browser): UA 版本号从 Chromium runtime 动态获取`

---

### Task P0-8: Phase 0 合并验收

**必跑清单**:
- `mvn clean compile && mvn test`：全绿
- 启动应用，前端发一条自然对话："打开 https://example.com 截图并告诉我页面标题" —— 验证：
  - 浏览器启动成功（非 headless 可看到）
  - screenshot 下一轮被 LLM 引用（vision 生效）
  - 60 秒空闲后会话自动清理（看日志）
- 自然对话："POST 请求到 https://httpbin.org/post，body 是 `{\"test\":1}`" —— 验证 web.fetch method 生效
- 自然对话："帮我 fetch http://127.0.0.1:8080" —— 验证 SSRF 拦截

**PR**: `feat(browser): Phase 0 清代码烂账`（引用本 roadmap 文档）

---

## Phase 1: DOM 标号 + vision 闭环（1-2 周）

**目标**：Agent 可以"看截图 + 标号表 → 选 index → 操作"，替代纯 CSS 选择器定位，达到 browser-use 89%@WebVoyager 同水位。

**分支**：`feature/browser-phase1-dom-indexing`
**前置**：Phase 0 合入 develop。
**执行前要求**：调用 `superpowers:writing-plans` 基于本章节细化为 TDD step plan。

### Task P1-1: DOM 标号 JS 注入脚本 + Java 端 indexer

**现状**：全库 grep `DOM.*标号|mark.*element|set-of-marks` 无命中，LLM 操作完全靠选择器+文本快照+ARIA 快照，缺视觉标号。

**Files**:
- Create: `src/main/resources/static/browser-scripts/interactive-elements.js`
- Create: `src/main/java/com/lifepilot/meta/infra/browser/InteractiveElementIndexer.java`
- Create: `src/main/java/com/lifepilot/meta/infra/browser/IndexedElement.java`（record）

**JS 脚本核心逻辑**（参考 browser-use `ClickableElementDetector`）:
```javascript
(function(opts) {
    const INTERACTIVE_TAGS = ['A','BUTTON','INPUT','SELECT','TEXTAREA','LABEL','SUMMARY'];
    const INTERACTIVE_ROLES = ['button','link','menuitem','checkbox','radio','tab',
                               'textbox','combobox','slider','switch','option'];
    const elements = [];
    let index = 0;

    function isVisible(el) {
        const r = el.getBoundingClientRect();
        if (r.width === 0 || r.height === 0) return false;
        const s = getComputedStyle(el);
        return s.display !== 'none' && s.visibility !== 'hidden' && s.opacity !== '0';
    }

    function isInteractive(el) {
        if (INTERACTIVE_TAGS.includes(el.tagName)) return true;
        const role = el.getAttribute('role');
        if (role && INTERACTIVE_ROLES.includes(role.toLowerCase())) return true;
        if (el.hasAttribute('onclick') || el.hasAttribute('contenteditable')) return true;
        if (el.tabIndex >= 0 && getComputedStyle(el).cursor === 'pointer') return true;
        return false;
    }

    // 清旧标签
    document.querySelectorAll('.__zhiwei-dom-label').forEach(n => n.remove());
    document.querySelectorAll('[data-zhiwei-idx]').forEach(n => n.removeAttribute('data-zhiwei-idx'));

    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_ELEMENT);
    let node;
    while ((node = walker.nextNode())) {
        if (!isVisible(node) || !isInteractive(node)) continue;
        const rect = node.getBoundingClientRect();
        node.setAttribute('data-zhiwei-idx', String(index));
        elements.push({
            index: index,
            tag: node.tagName.toLowerCase(),
            role: node.getAttribute('role') || '',
            text: (node.innerText || node.value || node.placeholder || '').trim().slice(0, 80),
            name: node.getAttribute('name') || '',
            id: node.id || '',
            ariaLabel: node.getAttribute('aria-label') || '',
            bbox: [Math.round(rect.left), Math.round(rect.top), Math.round(rect.width), Math.round(rect.height)]
        });
        if (opts.injectLabels) {
            const label = document.createElement('div');
            label.textContent = String(index);
            label.className = '__zhiwei-dom-label';
            label.style.cssText = `position:fixed;left:${rect.left}px;top:${rect.top}px;
                z-index:999999;background:#ff0050;color:white;padding:2px 4px;
                font-size:10px;font-family:monospace;border-radius:2px;pointer-events:none;`;
            document.body.appendChild(label);
        }
        index++;
    }
    return { elements, total: elements.length, viewport: { width: innerWidth, height: innerHeight } };
})(arguments[0]);
```

**Java 侧 `InteractiveElementIndexer`**:
- 启动时从 classpath 读脚本（`ClassPathResource("static/browser-scripts/interactive-elements.js")`）缓存
- `index(Page page, boolean injectLabels)` → `page.evaluate(script, Map.of("injectLabels", injectLabels))` → 解析 JSON
- 返回 `List<IndexedElement>`（record：`int index, String tag, String role, String text, String name, String id, String ariaLabel, int[] bbox`）

**已知限制（P1 暂不处理）**：
- iframe 内元素不纳入（后续 P1-1.1 补递归）
- shadow DOM 穿透不支持（后续 P1-1.2 用 `querySelectorAll(':scope *:not(*)') ` + ShadowRoot 遍历）
- CDP `getEventListeners` 检测事件绑定（browser-use 用，Java 端接 CDP 可补）

**验收**:
- 单测：mock Page 验证脚本内容正确加载和参数传递
- 手测：打开 github.com，`index()` 返回数组包含所有可见按钮/链接/输入框；`injectLabels=true` 时能看到红色编号
- bbox 坐标精度：与实际元素位置误差 < 2px

**commit**: `feat(browser): DOM 标号 JS 注入脚本和 Java 端 indexer`

---

### Task P1-2: 新增 browser.snapshot action

**Files**:
- Create: `BrowserSnapshotToolExecutor.java`
- Modify: `BrowserToolProvider.java`（注册 snapshot）
- Modify: `BrowserActionDispatchExecutor.java`（register 到 action map）
- Modify: `application.yml`（`browser.snapshot.max-elements: 200`）

**Schema**:
- `sessionId?: String`
- `injectLabels?: bool = true`
- `maxElements?: int = 200`
- `viewportOnly?: bool = true`（默认只截 viewport，全页用 fullPage screenshot）

**返回**: `{ screenshotDataUri, elements: [...], url, title, total, truncated }`

**执行流程**:
1. 获取 Page
2. `indexer.index(page, injectLabels)` → List<IndexedElement>
3. 截断到 maxElements，若超出 `truncated=true`
4. `page.screenshot(fullPage=!viewportOnly)` → data-uri
5. 组装返回

**验收**:
- 手测（Opus 4.7）：一次 snapshot 后 LLM 能说"点击 index=12 那个搜索按钮"（vision + elements 联合理解）
- 单测验证返回 JSON 结构稳定

**commit**: `feat(browser): 新增 browser.snapshot action`

---

### Task P1-3: click / input / hover 支持 index 参数

**Files**:
- Modify: `BrowserClickToolExecutor.java`（schema 加 `index: int?`，与 selector 二选一）
- Modify: `BrowserInputToolExecutor.java`
- Modify: `BrowserHoverToolExecutor.java`
- Modify: `PlaywrightPageWrapper.java`（新增 `clickByIndex` / `inputByIndex` / `hoverByIndex`）

**实现策略**:
- snapshot 时已给每个元素注入 `data-zhiwei-idx="{n}"` 属性
- clickByIndex(12) → `page.locator("[data-zhiwei-idx='12']").click()`
- 好处：稳定（比 bbox 坐标抗 layout 变动）、兼容 Playwright 原生 selector 语义
- 边界：index 对应元素如果已从 DOM 移除，返回明确错误 `elementStale`，让 LLM 重新 snapshot

**Schema 校验**:
- selector 和 index 必须恰好提供一个，两个都有或都无报错

**验收**:
- 单测 `BrowserClickToolExecutor_index路径测试`
- 手测：LLM 完成"打开 Google → 搜索 zhiwei → 点第 3 个结果" 全程用 index
- 负向：snapshot 后删除目标元素再 click，返回 elementStale

**commit**: `feat(browser): click/input/hover 支持 index 参数（配合 snapshot）`

---

### Task P1-4: browser-automation SKILL.md 更新

**Files**:
- Modify: `src/main/resources/skills/browser-automation/SKILL.md`

**改动要点**（不写 UI 话术模板，只写流程约束 —— 记忆 `feedback_skill_no_ui_wording`）:
- 首选路径从"navigate → accessibility 看结构 → click(selector)"改为"navigate → **snapshot** → click(index)"
- 加一节"失败 fallback 链"：click(index) 失败 → 再次 snapshot 对比元素是否变化 → 换 index 或 fallback 到 selector → 连续 2 次失败换策略
- 加"登录墙识别"：导航后检测到登录页应调 `browser.requestHumanTakeover`（P2 引入，先占位）
- 例子用抽象 step A/B/C 而非真实网站（记忆 `feedback_prompt_abstract_not_concrete`）

**验收**:
- Skill 通读无歧义
- 新版 skill 跑 3 个典型任务：开放搜索、带登录抓取、多步填表

**commit**: `docs(skill): browser-automation 改用 snapshot→index 优先路径`

---

### Task P1-5: Phase 1 合并验收

**自建 mini-benchmark**（新建 `src/test/java/.../BrowserE2eBenchmark.java`，`@Disabled` 默认不跑）:
- 10 个任务：开放搜索 3、填表 3、跨站导航 2、滚动加载 2
- 目标成功率 ≥ 80%
- 和 Phase 0 版本对比（Phase 0 估计 40-60%）

**PR**: `feat(browser): Phase 1 DOM 标号 + vision 闭环`

---

## Phase 2: 可观察性 + 人机交接（1 周）

**目标**：用户能实时看到 Agent 在浏览什么；验证码 / 登录场景能暂停让用户介入。

**分支**：`feature/browser-phase2-ux`
**前置**：Phase 1 合入 + 生产跑 3 天无重大 bug。
**执行前要求**：调用 `superpowers:writing-plans` 细化。

### Task P2-1: BrowserToolCallCard 特化

**现状**：`ToolCallCard.vue` 完全通用，只显示 `toolId/action/inputSummary/outputSummary` 纯文本，对 browser.* 零特化。用户看不到浏览器当前在哪页、截图、操作了什么。

**Files**:
- Create: `zhiwei-web/src/components/chat/BrowserToolCallCard.vue`
- Modify: `ToolCallCard.vue`（toolId 以 `browser.` 开头时委托新组件）

**UI 结构**（Reka UI + Tailwind 命名尺度）:
- 顶部 bar：`url` + `title` + 状态 chip
- 中部：screenshot 缩略图（点击放大用 Dialog）；如果是 snapshot，右侧折叠列表前 20 个 elements（index + tag + text 截取）
- 底部：action 名 + 关键参数徽章 + latency

**验收**:
- 用户看一眼知道 Agent 在哪个页面、刚做了什么
- 截图点击能放大查看

**commit**: `feat(ui): 浏览器工具专用对话卡片`

---

### Task P2-2: headless 默认切 false

**Files**:
- Modify: `application.yml`（`browser.headless: false`）
- Modify: `MetaProperties.Browser.headless` 默认值 false
- Modify: `docker-compose.yml` / `.env.example`（容器部署用 env 覆写 `BROWSER_HEADLESS=true`）

**改动要点**:
- 桌面 Tauri 部署自然能看到浏览器，符合"本地定位"记忆
- 容器部署保持 headless（无 X server）

**验收**:
- 本地运行 Agent 任务能看到 Chromium 弹出
- Docker compose 默认仍 headless

**commit**: `chore(browser): 本地开发默认 headless=false`

---

### Task P2-3: browser.requestHumanTakeover action

**Files**:
- Create: `BrowserHumanTakeoverExecutor.java`
- Create: REST 端点 `POST /api/browser/takeover/{sessionId}/resume`
- Modify: `BrowserToolProvider.java`
- Modify: `application.yml`（`browser.takeover.timeout-seconds: 300`）

**Schema**:
- `sessionId`: String
- `reason`: String（展示给用户）

**执行流程**:
1. Agent 调 `browser.requestHumanTakeover({sessionId, reason: "需要短信验证码"})`
2. 后端：通过 SSE 推 `browserTakeoverRequest` 事件到前端；阻塞当前 tool 执行（`CompletableFuture` + 超时）
3. 前端收事件弹窗（P2-4 实现）；浏览器自动切 headless=false 并 focus 窗口
4. 用户完成后点"继续" → REST resume → 后端 CompletableFuture complete
5. 超时（默认 300s）→ 返回 `takeoverTimedOut`，Agent 自行决定重试或放弃
6. 会话保持，Agent 从下一步继续

**验收**:
- 手测：LLM 遇登录墙主动暂停；用户扫码后点继续，Agent 恢复
- 超时场景：不点击，300s 后返回超时

**commit**: `feat(browser): 新增人机交接 action 支持验证码 / 登录场景`

---

### Task P2-4: HumanTakeoverModal + API

**Files**:
- Create: `zhiwei-web/src/components/chat/HumanTakeoverModal.vue`
- Create: `zhiwei-web/src/api/browser.ts`
- Modify: `useChat.ts`（订阅 `browserTakeoverRequest` SSE）

**UI**（Reka UI Dialog）:
- 标题：需要你接管
- 内容：Agent 暂停原因（从 `reason` 字段）+ "请在浏览器窗口完成操作" + 倒计时（300s）
- 按钮：[已完成，继续] / [取消任务]

**验收**:
- 端到端：触发登录墙 → 弹窗 → 用户操作 → 点继续 → Agent 继续
- 取消：点取消关闭弹窗，后端收到取消通知，Agent 收到 `userCancelled` 结果

**commit**: `feat(ui): 人机交接弹窗`

---

### Task P2-5: Phase 2 合并验收

**PR**: `feat(browser): Phase 2 可观察性 + 人机交接`

---

## Phase 3: Playwright CLI A/B 对照（按需，1 周）

**触发条件（必须全部满足才启动）**:
- Phase 1 合入 develop 满 2 周
- 存在某类任务（填复杂表单 / 多 tab 协作 / 强反爬站点）自家 tool 成功率稳定 < 60%
- 失败原因**不是** Phase 0 遗留 bug
- 对比价值明确（不是"看起来像能有用"）

**粗粒度任务**（执行前再细化）:
1. `PlaywrightCliBridge`：ProcessBuilder 起 `@playwright/cli` 子进程（Node runtime 依赖）
2. `PlaywrightCliToolExecutor`：和自家 browser tool 暴露**同一 action 集合**
3. Feature flag `browser.backend: native | playwright-cli`，同一 session 只能选一种
4. 扩展 P1-5 benchmark 跑两次，出对比报告

**不要做**:
- 不替换自家 browser tool（已达业界水准）
- 不接 browser-use 本体（Python 栈冲突 GenerationRouter）
- 不接 Playwright MCP（token 开销是 CLI 的 4 倍，业界实测 114k vs 27k）

---

## Phase ∞: Anthropic 原生 browser tool（观察期）

**触发信号**:
- Anthropic API 文档新增 `browser_YYYYMMDD` 工具（专用浏览器工具，区别于 `computer_YYYYMMDD` 桌面级）
- 或 ChatGPT Atlas / Project Mariner 开放可编程 API

**出现时评估清单**:
- 是否支持私有化（不能强制 Anthropic managed 浏览器）
- 登录态能否保持本地（知微私有化卖点）
- 定价（浏览器时间 or token）
- 可否和自家 tool 并存（作 fallback）

**预期行动**：切官方工具主用，自家 tool 保留作兜底，只维护不迭代新功能。

---

## 低优先级遗留（Phase 0 合入后按需调度）

以下问题 Agent 报告里有列出但不阻塞主路径，单独记录防忘：

| ID | 问题 | 建议 Phase | 备注 |
|---|---|---|---|
| L-1 | web.fetch 无缓存层，同一 URL 短期内重抓全流程重跑 | Phase 1.5 | 加 Caffeine 本地缓存，key=url+method+body hash，TTL 60s，配置可关 |
| L-2 | web.fetch 输出剥了 HTML 的纯文本，丢失链接/图片/结构 | Phase 1.5 | 改返 Markdown（用 `CommonMark` 或 `Readability4J`），保留 `links[]` / `images[]` 结构化字段 |
| L-3 | `textSnapshotMaxLength:10000` 硬截断非语义边界 | Phase 0.5 | 截断前按段落/句号边界对齐，避免半句截断误导 LLM |
| L-4 | Agent 任务结束无强制关会话机制 | Phase 2.5 | `ReactAgentLoop` 完成/失败钩子遍历 session 注册表 `browser.close` 本任务创建的所有 sessionId（非 PERSISTENT） |

触发规则：生产观测到**具体**影响（日志看到重复 fetch / LLM 误解截断内容 / 浏览器进程长期残留）再调度。

## 风险与回滚

| 风险 | 概率 | 缓解 |
|---|---|---|
| Phase 1 DOM 标号对 shadow DOM / canvas 失效 | 中 | 保留 CSS selector 路径作 fallback（click 同时支持 index 和 selector） |
| Phase 2 headless=false 让 Docker 部署失败 | 低 | `.env.example` 显式设置 `BROWSER_HEADLESS=true`，容器镜像模板同步更新 |
| SSRF 防护误伤企业私有化内网场景 | 中 | 白名单配置 + 可配置关闭（`web-fetch.ssrf.enabled: false`） |
| `@PreDestroy` 执行不及时导致 Chrome 残留 | 低 | 关闭钩子加超时兜底：5s 未退出强制 `process.destroy()` |
| Playwright 1.58 → 1.59 升级 UA 逻辑失效 | 低 | 单测覆盖 `browser.version()` 调用 |

---

## 参考资料

- **browser-use `ClickableElementDetector`**: https://deepwiki.com/browser-use/browser-use/5.3-interactive-element-detection
- **Set-of-Mark Prompting**: arXiv 2310.11441
- **Playwright CLI vs MCP token 对比**: https://scrolltest.medium.com/playwright-mcp-burns-114k-tokens-per-test-the-new-cli-uses-27k-heres-when-to-use-each-65dabeaac7a0
- **Anthropic computer use tool**: https://platform.claude.com/docs/en/agents-and-tools/tool-use/computer-use-tool
- **IBM CUGA**（企业级通用 Agent 参考架构）: https://research.ibm.com/blog/cuga-agent-framework
- **WebVoyager leaderboard**: https://leaderboard.steel.dev/
- **本项目调研依据**: 2026-04-24 两个 Agent 深度报告（Explore + WebSearch）

---

## 状态追踪

| 日期 | 事件 | 备注 |
|---|---|---|
| 2026-04-24 | Roadmap 创建 | Phase 0 待开工 |
| — | Phase 0 开工 | — |
| — | Phase 0 合入 develop | — |
| — | Phase 1 开工 | 先调 `superpowers:writing-plans` 细化 |
| — | Phase 1 合入 | — |
| — | Phase 2 开工 | — |
| — | Phase 2 合入 | — |
| — | Phase 3 评估 | 生产跑 2 周后 |
| — | Phase ∞ 触发 | Anthropic 发原生 browser tool 时 |

**维护约定**：每次 Phase 推进更新本表；Roadmap 本身在有架构调整时才改，日常 phase plan 在独立文档。
