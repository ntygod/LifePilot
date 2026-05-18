# Web 韧性特性说明

> 模块：web-resilience
> 状态：设计完成，待实施

---

## 1. 功能概述

Web 韧性模块系统性提升知微从互联网获取信息的成功率，覆盖搜索、抓取、浏览器自动化三大能力。核心目标：**将 web.fetch 的整体成功率从当前约 60% 提升到 90%+**。

---

## 2. 用户价值

- Agent 调研任务不再因为"知乎 403"、"NGA 被拒"、"观察者网 SSL 握手失败"而中断
- 搜索不再因为 Tavily API key 缺失而完全不可用
- 浏览器自动化不再因为目标站点重定向而崩溃
- 系统自动学习哪些站点需要什么策略，无需用户手动配置

---

## 3. 核心特性

### 3.1 三级请求回退链

对每个 web.fetch 请求，系统自动按以下顺序尝试：

1. **TLS 伪装静态抓取**（新增）：使用 impersonator 库伪装 Chrome TLS 指纹 + 标准浏览器请求头
2. **Playwright 浏览器渲染**：带 stealth 脚本的完整浏览器环境
3. **错误报告**：结构化错误（区分"需要登录"、"被 WAF 拦截"、"限流"等）

回退触发条件：
- HTTP 403 / 429 → 自动尝试下一级
- TLS 握手失败 → 跳过静态，直接浏览器
- 内容过短（< 100 字符）→ 浏览器渲染

### 3.2 搜索多源降级

| 优先级 | Provider | 要求 | 特点 |
|--------|----------|------|------|
| 1 | Tavily | API key | 质量最高，AI 优化摘要 |
| 2 | SearXNG | Docker 自建 | 零成本，聚合多引擎 |
| 3 | DuckDuckGo HTML | 无 | 兜底，解析 HTML 搜索结果 |

### 3.3 域名级智能策略

系统自动记录每个域名的请求结果，学习最优策略：

- 连续 3 次 403 → 自动标记为"需要浏览器"
- 连续 3 次浏览器也失败 → 标记为"需要登录/代理"
- 成功后重置失败计数
- 策略可通过配置覆盖

### 3.4 域名级限流

- 同一域名默认最小间隔 1 秒
- 收到 429 后自动加大间隔（指数退避）
- 可按域名自定义限流参数

### 3.5 浏览器引擎稳定性

- 导航后 JS 重定向不再导致工具崩溃
- 自动识别登录墙/验证码页面
- 浏览器 Context 复用（减少指纹暴露）

---

## 4. 配置项

```yaml
lifepilot:
  meta:
    infra:
      web-fetch:
        # TLS 伪装开关（默认开启）
        tls-impersonate-enabled: true
        # 浏览器回退开关（默认开启，依赖 Playwright 可用）
        browser-fallback-on-403: true
        # 域名级限流
        rate-limit:
          enabled: true
          default-min-interval-ms: 1000
          default-burst: 3
        # 代理配置（可选）
        proxy:
          enabled: false
          urls: []
          rotation-strategy: RANDOM  # RANDOM / ROUND_ROBIN
      web-search:
        # 搜索 Provider 降级链
        providers:
          - type: tavily
            api-key: "${TAVILY_API_KEY:}"
          - type: searxng
            base-url: "http://localhost:8888"
          - type: duckduckgo-html
```

---

## 5. 使用场景

### 场景 A：调研任务遇到知乎 403

**之前**：web.fetch 返回 403 错误 → Agent 反思后换 URL → 可能连续失败多次
**之后**：403 自动触发 TLS 伪装重试 → 仍失败则浏览器渲染 → 知乎重定向被优雅处理 → 返回内容或结构化"需要登录"提示

### 场景 B：Tavily API key 过期

**之前**：web.search 返回"未配置 API Key" → 搜索完全不可用
**之后**：自动降级到 SearXNG → 仍可搜索（质量略低但可用）

### 场景 C：高频抓取同一站点

**之前**：连续请求触发 429 限流 → Agent 反思消耗 token
**之后**：DomainRateLimiter 自动控制请求间隔 → 429 大幅减少

---

## 6. 限制与未来扩展

### 当前限制
- 代理池需要用户自行配置（不内置免费代理）
- Cloudflare Turnstile 验证码无法自动绕过（需人工接管）
- SearXNG 需要用户自行 Docker 部署

### 未来扩展方向
- 集成验证码识别服务（2captcha / hCaptcha solver）
- 浏览器 Cookie 自动导出给静态请求复用
- 基于 ML 的请求时序模拟（更像真人的访问模式）
- 支持 Patchright（Playwright fork，更深层的 CDP 泄露修补）
