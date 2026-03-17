# ZhiWei 安全策略

> 本文档说明 ZhiWei（知微）项目的安全架构、防护机制和漏洞报告流程。

---

## 1. 安全架构概览

ZhiWei 采用本地优先（Local-First）架构，所有数据存储在用户本地 SQLite 数据库中，不依赖云端存储。安全防护贯穿请求处理全链路，从网关中间件到工具执行护栏，形成多层纵深防御。

---

## 2. 密钥与凭证管理

- 所有 LLM API Key（DeepSeek / OpenAI / Qwen）、搜索和 Reranker API Key 通过环境变量注入，禁止硬编码
- `.env` 文件已加入 `.gitignore`，不会提交到版本控制
- 项目提供 `.env.example` 模板，仅包含占位符，不含真实凭证
- 渠道适配器（飞书 / 钉钉 / 企业微信）的签名密钥同样通过环境变量配置

---

## 3. 网关中间件安全链

请求进入系统后，按优先级依次经过以下中间件处理：

| 顺序 | 中间件 | 职责 |
|------|--------|------|
| 100 | AuthMiddleware | 渠道认证（按渠道类型分派认证策略） |
| 200 | RateLimitMiddleware | 令牌桶限流（按用户隔离，可配置每分钟/每小时上限） |
| 300 | SecurityMiddleware | Prompt 注入检测 + 敏感数据检测 + 信任评分计算 |
| 400 | RouterMiddleware | 请求路由 |
| 500 | ExecutionMiddleware | Agent 执行 |
| 600 | AuditMiddleware | 审计日志记录（自动脱敏后写入） |

所有中间件均可通过 `application.yml` 独立启用/禁用和调整优先级。

---

## 4. 工具执行护栏（GuardrailEngine）

### 4.1 工具风险分级

每个工具在注册时声明风险等级，护栏引擎在执行前强制检查：

| 风险等级 | 说明 | 审批模式 |
|---------|------|---------|
| LOW | 只读操作，无副作用 | 自动执行 |
| MEDIUM | 有副作用但可撤销 | 自动执行 + 审计日志 |
| HIGH | 不可逆操作 | 需用户确认 |
| CRITICAL | 涉及敏感数据或资金 | 用户确认 + 二次验证 |

### 4.2 护栏策略体系

GuardrailEngine 支持以下策略类型（`sealed interface GuardrailPolicy`）：

- **ToolRiskPolicy** — 基于工具风险等级的访问控制
- **BudgetLimitPolicy** — Token 用量预算限制
- **ContentSafetyPolicy** — 内容安全检查（敏感词 / 敏感话题过滤）
- **RateLimitPolicy** — 工具调用频率限制
- **DataRedactionPolicy** — 数据脱敏策略

所有策略按优先级排序执行，任一策略返回 Blocked 即终止工具调用。

---

## 5. 敏感数据脱敏（DataRedactor）

DataRedactor 在以下场景自动脱敏：

- LLM 调用前：对发送到云端 LLM 的内容脱敏
- 审计日志写入时：对审计记录中的用户数据脱敏
- Trace 记录时：对工具调用参数和返回值脱敏

内置脱敏规则：

| 数据类型 | 脱敏效果 |
|---------|---------|
| 手机号 | `138****5678` |
| 身份证号 | `110***********1234` |
| 银行卡号 | `6222****0123` |
| 邮箱 | `u***@example.com` |
| IP 地址 | `***.***.***.***` |
| API 密钥 | `sk-****` |

支持通过 `ObservabilityProperties.Redaction` 配置自定义脱敏规则。

---

## 6. Prompt 注入防护

### 6.1 网关层

SecurityMiddleware 内置 `PromptInjectionDetector`，在请求进入 Agent 前检测常见注入模式。

### 6.2 Skill 验证层

`SecurityValidator` 在 Skill 加载时执行安全校验：

- 检查 Skill 引用的工具是否存在于注册表
- 拒绝引用 HIGH / CRITICAL 风险工具的 Skill
- 检测 Skill instructions 中的 Prompt 注入模式（jailbreak、DAN mode 等）

验证流程：FormatValidator → SecurityValidator → SandboxValidator，任一阶段失败即拒绝加载。

---

## 7. 数据存储安全

- 所有数据存储在本地 SQLite 数据库，不上传云端
- SQLite PRAGMA 配置：`journal_mode=WAL`、`foreign_keys=ON`
- 数据库迁移通过 Flyway 管理，脚本版本化控制
- 数据库文件（`*.db`、`*.db-journal`、`*.db-wal`）已加入 `.gitignore`

---

## 8. 漏洞报告

如果你发现安全漏洞，请遵循以下流程：

1. **不要**在 GitHub Issues 中公开报告安全漏洞
2. 发送邮件至项目维护者（查看 GitHub 项目主页获取联系方式）
3. 报告中请包含：
   - 漏洞类型和严重程度评估
   - 受影响的模块和版本
   - 详细复现步骤
   - 潜在影响范围

我们会在确认漏洞后尽快修复，并在 Release Notes 中说明安全更新内容。

---

## 9. 安全开发实践

- 代码中禁止硬编码密钥或敏感信息
- 日志输出必须通过 `DataRedactor.redact()` 脱敏
- 新增工具必须声明风险等级
- Skill 上线前必须通过三阶段安全验证
- 跨模块接口变更需评估安全影响
