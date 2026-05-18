# Web 智能体升级设计（Web Intelligence）

> 定位：将知微的 web 能力从"工具调用"升级为"智能决策"
> 核心理念：Agent 应该像一个有经验的人类用户一样上网 — 会判断、会记忆、会应变、会求助
> 状态：设计中

---

## 1. 问题本质

当前知微的 web 能力是**工具级**的：Agent 调用 web.fetch/browser 工具，工具返回结果或报错，Agent 再决定下一步。这导致：

- **没有经验**：每次都从零开始，不知道哪些站点需要什么策略
- **没有视觉**：只看 DOM 文本，不理解页面"长什么样"
- **没有应变**：遇到验证码/登录墙就卡住，只能交给人
- **没有规划**：不会为了达成目标主动设计多步操作路径

真正的智能应该是：Agent 知道"知乎需要登录"、"微博用浏览器就能过"、"这个验证码我能自己解"、"这个页面我需要先看一眼截图再决定怎么操作"。

---

## 2. 设计哲学

### 2.1 三层智能

```
┌─────────────────────────────────────────────────┐
│  L3 — 目标规划层                                  │
│  "我要从知乎获取这个问题的回答"                      │
│  → 判断需要登录 → 检查有无可用会话 → 规划操作路径     │
├─────────────────────────────────────────────────┤
│  L2 — 感知决策层                                  │
│  "当前页面是什么状态？我该做什么？"                   │
│  → 截图理解 → 验证码识别 → 异常检测 → 策略选择       │
├─────────────────────────────────────────────────┤
│  L1 — 执行层（当前已有）                           │
│  "点击这个按钮、输入这段文字、抓取这个页面"            │
│  → Playwright 操作 → HTTP 请求 → DOM 解析          │
└─────────────────────────────────────────────────┘
```

当前知微只有 L1。需要补齐 L2 和 L3。

### 2.2 核心原则

1. **先看再做**：关键操作前先截图理解页面状态，不盲目执行
2. **记住经验**：每个域名的成功/失败策略持久化，下次直接用
3. **能力边界清晰**：能自动处理的自动处理，不能的快速升级给人，不浪费时间反复重试
4. **渐进式降级**：自动尝试 → 半自动（提示用户选择）→ 人工接管，每一步都有价值

---

## 3. 核心模块设计

### 3.1 域名智能体（DomainIntelligence）

**职责：** 记住每个域名的"性格"，下次直接用正确策略。

```java
/**
 * 域名智能记忆 — 持久化每个域名的访问经验。
 *
 * 不是简单的"成功/失败计数"，而是结构化的域名画像：
 * - 需要什么访问方式（static/browser/登录态）
 * - 有什么防护（WAF 类型、验证码类型）
 * - 最佳请求参数（UA、headers、间隔）
 * - 历史成功率和最近状态
 */
public record DomainProfile(
    String domain,
    AccessStrategy preferredStrategy,    // STATIC / BROWSER / AUTHENTICATED
    DefenseType detectedDefense,         // NONE / CLOUDFLARE / CUSTOM_WAF / LOGIN_WALL / CAPTCHA
    CaptchaType captchaType,             // NONE / IMAGE_TEXT / SLIDER / CLICK_SELECT / TURNSTILE / RECAPTCHA
    boolean requiresAuthentication,
    int consecutiveFailures,
    int totalAttempts,
    int totalSuccesses,
    Instant lastSuccess,
    Instant lastFailure,
    String lastErrorCategory,            // TLS_REJECTED / HTTP_403 / CAPTCHA_BLOCKED / LOGIN_REQUIRED
    Map<String, String> metadata         // 额外信息：如 cookie 域、登录 URL 等
) {}
```

**学习逻辑：**
- 首次访问：用默认策略（static + Chrome UA）
- 403 → 标记为需要 browser，下次直接走 browser
- browser 也失败 + 检测到登录页 → 标记为需要认证
- 检测到验证码 → 记录验证码类型，下次准备好对应处理器
- 连续成功 5 次 → 可以尝试降级回 static（节省资源）

### 3.2 页面感知引擎（PagePerceptionEngine）

**职责：** 让 Agent 真正"看懂"页面当前状态。

当前知微的 browser 工具返回的是 DOM 文本或元素编号表。但很多决策需要视觉理解：
- 这是登录页还是内容页？
- 有没有验证码？什么类型？
- 页面加载完了吗？有没有错误提示？
- 弹窗/遮罩层挡住了内容吗？

**实现方式：** 利用知微已有的 Vision 管道（screenshot → pendingMedia → LLM 看图），在关键时刻自动触发视觉分析。

```
触发时机：
1. 导航后页面内容异常短（< 200 字符）→ 截图 + 视觉分析
2. 浏览器回退后 → 截图确认页面状态
3. 操作后页面无变化 → 截图检查是否有弹窗/验证码
4. 检测到已知验证码 DOM 特征 → 截图识别具体类型
```

**不是每次都截图**（太贵太慢），而是在"不确定"的时候截图辅助决策。

### 3.3 验证码处理器（CaptchaResolver）

**职责：** 分类型自动处理验证码，处理不了的快速升级。

```
验证码处理链：
┌──────────────────┐
│ 检测验证码类型     │ ← DOM 特征 + 截图视觉分析
├──────────────────┤
│ 图片文字验证码     │ → 本地 OCR（ddddocr-java）→ 自动填写
│ 滑块验证码        │ → 轨迹模拟算法 → 自动滑动
│ 点选验证码        │ → Vision 模型识别目标 → 自动点击
│ reCAPTCHA v2     │ → 2captcha API（付费）→ 自动提交
│ Cloudflare       │ → 等待 5 秒自动通过 / 无法处理 → 升级
│ 无法识别的类型     │ → 截图 + 人工接管（附带上下文）
└──────────────────┘
```

**关键设计：** 不是"遇到验证码就挂起"，而是先尝试自动处理，只有确认处理不了才升级给人。升级时附带截图和上下文，让用户一眼就知道要做什么。

### 3.4 认证会话管理器（AuthSessionManager）

**职责：** 智能管理登录态，让 Agent 能访问需要认证的站点。

当前的问题：web.fetch 浏览器回退每次创建匿名 Page，不带任何登录态。即使用户通过 CDP 登录过，web.fetch 也用不上。

**设计：**

```
认证状态管理：
1. 域名 → 认证状态映射（已登录/未登录/过期）
2. web.fetch 浏览器回退时，检查目标域名是否有可用认证会话
3. 有 → 复用已认证的 BrowserContext（共享 Cookie）
4. 无 → 匿名访问，失败后提示"需要登录"并记录

登录态获取路径（优先级）：
1. CDP 模式 — 复用用户已登录的 Chrome（最强，零成本）
2. PERSISTENT 模式 — 持久化 profile，登录一次永久可用
3. Cookie 导入 — 从浏览器会话导出 Cookie 给 static 路径复用
4. 自动登录 — Agent 自己走登录流程（需要凭据配置）
5. 人工接管 — 最后手段
```

### 3.5 自适应请求路由（AdaptiveRequestRouter）

**职责：** 根据域名画像 + 实时反馈，选择最优请求路径。

替代当前的"固定流程"（static → 失败 → browser → 失败 → 报错），变成智能路由：

```java
public ToolResult route(String url, FetchOptions options) {
    var profile = domainIntelligence.getProfile(url);
    
    // 根据域名画像选择策略
    var strategy = switch (profile.preferredStrategy()) {
        case STATIC -> tryStatic(url, options);
        case BROWSER -> tryBrowser(url, options);
        case AUTHENTICATED -> tryAuthenticated(url, options);
    };
    
    // 执行后更新画像
    if (strategy.isSuccess()) {
        domainIntelligence.recordSuccess(url, strategy.method());
    } else {
        domainIntelligence.recordFailure(url, strategy.error());
        // 自动升级策略并重试
        strategy = escalateAndRetry(url, options, profile, strategy.error());
    }
    
    return strategy;
}
```

---

## 4. 集成架构

```
用户请求: "帮我查一下知乎上关于 XXX 的讨论"
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│ Agent ReAct Loop                                         │
│                                                          │
│  1. 搜索 → web.search("XXX site:zhihu.com")             │
│  2. 获取链接 → 准备 web.fetch                            │
│     │                                                    │
│     ▼                                                    │
│  ┌─────────────────────────────────────────────┐        │
│  │ AdaptiveRequestRouter                        │        │
│  │                                              │        │
│  │  DomainIntelligence: zhihu.com               │        │
│  │  → preferredStrategy: AUTHENTICATED          │        │
│  │  → detectedDefense: LOGIN_WALL               │        │
│  │                                              │        │
│  │  检查认证状态:                                │        │
│  │  → AuthSessionManager: 有 CDP 会话可用 ✓     │        │
│  │  → 使用已认证 BrowserContext 访问             │        │
│  │                                              │        │
│  │  结果: 成功获取内容                           │        │
│  └─────────────────────────────────────────────┘        │
│                                                          │
│  3. 返回内容给用户                                       │
└─────────────────────────────────────────────────────────┘
```

如果没有可用认证会话：

```
│  │  检查认证状态:                                │
│  │  → AuthSessionManager: 无可用会话             │
│  │  → 尝试匿名 browser 访问                     │
│  │  → 检测到登录墙                              │
│  │                                              │
│  │  PagePerceptionEngine:                       │
│  │  → 截图分析: 这是知乎登录页                   │
│  │  → 无验证码，有手机号/密码登录表单             │
│  │                                              │
│  │  决策: 无凭据配置，升级为人工接管              │
│  │  → requestHumanTakeover("知乎需要登录，       │
│  │     请在浏览器中完成登录")                     │
│  │  → 用户登录后，记录认证状态                   │
│  │  → 下次直接复用                              │
```

---

## 5. 数据模型

### 5.1 域名画像表（Flyway 迁移）

```sql
CREATE TABLE domain_profile (
    domain          TEXT PRIMARY KEY,
    strategy        TEXT NOT NULL DEFAULT 'STATIC',  -- STATIC/BROWSER/AUTHENTICATED
    defense_type    TEXT DEFAULT 'NONE',             -- NONE/CLOUDFLARE/CUSTOM_WAF/LOGIN_WALL/CAPTCHA
    captcha_type    TEXT DEFAULT 'NONE',             -- NONE/IMAGE_TEXT/SLIDER/CLICK_SELECT/TURNSTILE/RECAPTCHA
    requires_auth   INTEGER DEFAULT 0,
    consecutive_failures INTEGER DEFAULT 0,
    total_attempts  INTEGER DEFAULT 0,
    total_successes INTEGER DEFAULT 0,
    last_success_at TEXT,
    last_failure_at TEXT,
    last_error      TEXT,
    metadata_json   TEXT DEFAULT '{}',
    updated_at      TEXT NOT NULL DEFAULT (datetime('now'))
);
```

### 5.2 认证会话表

```sql
CREATE TABLE auth_session (
    id              TEXT PRIMARY KEY,
    domain          TEXT NOT NULL,
    session_type    TEXT NOT NULL,    -- CDP/PERSISTENT/COOKIE
    status          TEXT NOT NULL,    -- ACTIVE/EXPIRED/REVOKED
    cookie_json     TEXT,            -- 导出的 Cookie（加密存储）
    created_at      TEXT NOT NULL,
    expires_at      TEXT,
    last_used_at    TEXT
);
```

---

## 6. 验证码处理技术方案

### 6.1 本地 OCR — ddddocr-java

[GCS-ZHN/ddddocr-for-java](https://github.com/GCS-ZHN/ddddocr-for-java) 是 ddddocr 的 Java 移植，基于 ONNX Runtime，可本地运行无需网络。

适用：简单图片文字验证码（4-6 位字母数字）

### 6.2 滑块验证码

算法：
1. 截图获取滑块背景图和滑块图
2. 图像匹配找到缺口位置（OpenCV 模板匹配，或简单的像素差异检测）
3. 生成人类轨迹（贝塞尔曲线 + 随机抖动 + 加速减速）
4. 通过 Playwright 执行拖拽

### 6.3 点选验证码

利用 Vision 模型：
1. 截图验证码区域
2. 发送给 Vision LLM："请识别图中需要点击的目标，返回坐标"
3. 按坐标点击

### 6.4 Cloudflare Turnstile / reCAPTCHA

- Turnstile：大多数情况下等待 5 秒会自动通过（如果 stealth 足够好）
- reCAPTCHA v2：集成 2captcha Java SDK（需要 API key，付费）
- 无法处理：快速升级人工接管

### 6.5 验证码检测（DOM 特征）

```java
// 已知验证码 DOM 特征
Map<String, CaptchaType> CAPTCHA_SIGNATURES = Map.of(
    "iframe[src*='recaptcha']", CaptchaType.RECAPTCHA,
    "iframe[src*='challenges.cloudflare.com']", CaptchaType.TURNSTILE,
    ".geetest_panel", CaptchaType.SLIDER,        // 极验
    ".nc-container", CaptchaType.SLIDER,          // 阿里滑块
    "#captcha-image", CaptchaType.IMAGE_TEXT,
    ".verify-img-panel", CaptchaType.CLICK_SELECT // 点选
);
```

---

## 7. 实施路线

### Phase 1 — 域名智能 + 自适应路由（3-4 天）

最高 ROI，立即提升日常使用体验：
- DomainProfile 数据模型 + Flyway 迁移
- DomainIntelligence 服务（记录/查询/学习）
- AdaptiveRequestRouter 替代当前固定流程
- web.fetch 执行后自动更新域名画像

### Phase 2 — 页面感知 + 验证码检测（3-4 天）

让 Agent 能"看懂"页面状态：
- PagePerceptionEngine（关键时刻自动截图 + 视觉分析）
- 验证码 DOM 特征检测
- 登录墙检测（URL 模式 + DOM 特征）
- 异常页面检测（空白页、错误页、重定向页）

### Phase 3 — 验证码自动处理（4-5 天）

从"完全依赖人"到"能处理大部分"：
- 集成 ddddocr-java（图片文字 OCR）
- 滑块轨迹模拟算法
- Vision 模型辅助点选验证码
- 2captcha SDK 集成（可选，需 API key）
- CaptchaResolver 统一调度

### Phase 4 — 认证会话管理（2-3 天）

让 web.fetch 也能用上登录态：
- AuthSessionManager
- web.fetch 浏览器回退复用已认证 Context
- Cookie 导出给 static 路径
- 登录态过期检测 + 自动刷新

---

## 8. 效果预期

| 场景 | 当前 | 升级后 |
|------|------|--------|
| 知乎文章 | 403 → 浏览器 → 登录墙 → 失败 | 记住需要登录 → 复用 CDP 会话 → 直接成功 |
| NGA 论坛 | 403 → 浏览器 → 访客拦截 → 失败 | 记住需要登录 → 提示用户登录一次 → 后续永久可用 |
| 有图片验证码的站点 | 挂起等人 | OCR 自动识别 → 自动填写 → 成功 |
| 有滑块验证码的站点 | 挂起等人 | 轨迹模拟 → 自动滑动 → 成功 |
| Cloudflare 保护站点 | 可能失败 | 等待自动通过 / 检测到 Turnstile → 等 5 秒 |
| 首次访问未知站点 | 盲目尝试 static | 尝试 static → 失败 → 记住 → 下次直接 browser |
| 页面状态不确定 | 只看 DOM 文本猜 | 截图 + Vision 分析 → 精确判断 |

---

## 9. 与现有架构的关系

| 现有模块 | 升级方式 |
|---------|---------|
| WebFetchToolExecutor | 内部调用 AdaptiveRequestRouter 替代固定流程 |
| BrowserSessionManager | 新增 getAuthenticatedContext(domain) 方法 |
| PlaywrightBridge | 不变，仍是底层执行 |
| ToolExecutionPipeline | 不变，重试机制继续生效 |
| ReactAgentLoop | 不变，Vision 管道已就绪 |
| MediaDataExtractor | 不变，截图 → LLM 的管道已通 |
| BrowserHumanTakeoverExecutor | 不变，作为最终兜底 |
| MetaProperties | 新增 domain-intelligence / captcha 配置节 |
