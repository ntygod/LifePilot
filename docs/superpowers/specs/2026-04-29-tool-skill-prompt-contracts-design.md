# Tool / Skill / Prompt 三层契约工程设计

> 面向 2026+ Agent 应用爆发期，"文字描述"已从"给人看的文档"演变为"给 LLM 执行的运行时契约"。
> 本文档定义知微三层系统的目标架构、数据结构、跨层协议与验收标准。
>
> @author zsg
> @since 2026-04-29

---

## 一、设计原则

| 原则 | 含义 | 违反示例 |
|------|------|---------|
| **LLM-Centric Writing** | 结构化、显式约束、示例驱动；禁止隐喻/省略/常识假设 | description 只写"管理文件"而不列 action |
| **硬控制下放 Schema** | 参数约束、action 互斥、required 条件用 JSON Schema 表达，不放 description 文本警告 | shell.exec 在 description 写"不要删系统文件"而非 schema 约束 |
| **返回即契约** | 每种返回状态的结构、字段、空值策略对 LLM 可见 | outputSchema = {}，LLM 不知道错误何时可重试 |
| **单一信息源** | 同一规则只在一个层定义，其他层引用 | tool 参数细节在 description、skill reference、prompt 三处重复 |
| **上下文经济** | 核心信息前置，细节按需加载；避免全量堆砌稀释注意力 | skill_catalog 展示全部 20 个 skill 的完整 description |
| **可校验** | 每层输出有机器可验证的断言 | 自然语言"检查一下结果对不对" |

---

## 二、工具清单（最终决策）

> 从 27 工具精简到 16 工具。决策记录见附录。

### 2.1 工具矩阵

| # | 工具 ID | 原名 | action 数 | 风险 | 说明 |
|---|---------|------|-----------|------|------|
| 1 | `memory` | — | 7 | MED | 统一记忆入口。search/get/save/delete/status/tag/query-at-time |
| 2 | `browser` | — | 16 | HIGH | 浏览器自动化（Playwright） |
| 3 | `code` | code.execute + code.kernel | 1+3 | HIGH | Python沙箱执行 + 内核管理 |
| 4 | `shell.exec` | — | 1 | HIGH | Shell 命令执行（安全护栏） |
| 5 | `shell.process` | — | 7 | MED | 进程/会话管理 |
| 6 | `file.read` | file.read + file.list + file.attach | 4 | LOW | 读文件 + 目录浏览 + 附件解析 |
| 7 | `file.write` | file.write + file.edit + file.history | 8 | MED | 写文件 + 内容编辑 + 撤销/重做/对比 |
| 8 | `file.manage` | — | 5 | MED | 文件系统操作：move/copy/delete/mkdir/rename |
| 9 | `web.search` | — | 1 | LOW | 网页搜索 |
| 10 | `web.fetch` | — | 1 | MED | 页面抓取/API调用（JS渲染+SSRF守卫） |
| 11 | `cron` | — | 4 | MED | 定时任务调度 |
| 12 | `notify` | notify.send_message | 1 | LOW | 渠道消息推送 |
| 13 | `status` | system.status | 1 | LOW | 系统内省（工具/Skill/Agent数量、datastore列表） |
| 14 | `ui.render` | — | 1 | LOW | 前端交互组件渲染 |
| 15 | `skill.load` | — | 1 | LOW | Skill 激活 |
| 16 | `tool.search` | tools.search + tools.describe | 1 | LOW | 工具发现（BM25+语义混合，返回 top-3 含 inputSchema） |

### 2.2 各工具 action 详情

#### memory（7 actions）

| action | 参数 | 说明 |
|--------|------|------|
| `search` | query, scope?, top_k? | 统一搜索。scope: memory(默认)/knowledge/transcript |
| `get` | id | 取原文：callId→工具结果, entityId→实体详情 |
| `save` | name, type, content, id? | upsert：有id=更新, 无id=创建 |
| `delete` | id | 删除实体 |
| `status` | id, to | 状态变更：completed/cancelled/superseded_by=X |
| `tag` | from, to, relation, strength? | 建立关系 |
| `query-at-time` | timestamp, query | 时间旅行查询 |

#### browser（16 actions）

navigate, snapshot, click, input, hover, scroll, wait, select, keyboard, screenshot, evaluate, accessibility, tab, storage, takeover (原 requestHumanTakeover), close

#### code（1+3 actions）

| action | 说明 |
|--------|------|
| `exec` | 执行代码（Python沙箱） |
| `kernel_list` | 列出活跃内核 |
| `kernel_inspect` | 查看内核变量/状态 |
| `kernel_reset` | 重置内核 |

#### shell.exec（1 action）

| action | 说明 |
|--------|------|
| `exec` | 执行 shell 命令。background=true 后台运行 |

#### shell.process（7 actions）

list, output, write, kill, session_list, session_info, session_kill

#### file.read（4 actions）

| action | 说明 |
|--------|------|
| `read` | 读取文件（docx/xlsx/pdf/md自动解析） |
| `list` | 列出目录内容 |
| `info` | 查看文件元数据 |
| `attach` | 处理附件 |

#### file.write（8 actions）

| action | 说明 |
|--------|------|
| `write` | 创建/覆盖/追加文件（mode=write/append） |
| `insert` | 行级插入 |
| `replace` | 行级替换 |
| `delete` | 行级删除 |
| `find_replace` | 搜索替换 |
| `undo` | 撤销最近编辑 |
| `redo` | 重做被撤销编辑 |
| `diff` | 输出 unified diff |

#### file.manage（5 actions）

move, copy, delete, mkdir, rename

#### cron（4 actions）

create, list, update, delete（原 remove）

#### 单 action 工具

web.search, web.fetch, notify, status, ui.render, skill.load, tool.search — 无需 action 参数，直接调用。

### 2.3 已删除工具及替代方案

| 已删除 | 替代 |
|--------|------|
| git.query | `shell.exec(action="exec", command="git log/status/diff...")` |
| git.mutate | `shell.exec(action="exec", command="git commit/stash...")` |
| transcript.search | `memory(action="search", scope="transcript")` |
| transcript.get | `memory(action="get", id="{callId}")` |
| knowledge.search | `memory(action="search", scope="knowledge")` |
| code.kernel | `code(action="kernel_*")` |
| tools.describe | `tool.search` 直接返回 inputSchema |
| file.list | `file.read(action="list")` |
| file.attach | `file.read(action="attach")` |
| file.edit | `file.write(action="insert/replace/delete/find_replace")` |
| file.history | `file.write(action="undo/redo/diff")` |
| notify.send_message | 重命名为 `notify` |
| system.status | 重命名为 `status` |
| code.execute | 重命名为 `code` |
| tools.search | 重命名为 `tool.search`（单数） |

### 2.4 命名规范

| 规则 | 示例 |
|------|------|
| Tool ID: `{领域}.{动词}` 或 `{领域}`（唯一工具时） | `file.read`, `memory`, `notify` |
| 领域名统一单数 | `tool.search` 非 `tools.search` |
| action 名统一 snake_case | `find_replace` 非 `search_replace`, `takeover` 非 `requestHumanTakeover` |
| 同义同词：删除统一 `delete` | cron 的 `remove` → `delete` |
| 跨工具同义参数同名 | `query` / `top_k` / `session_id` |

---

## 三、Tool 层目标架构

### 3.1 BuiltinTool 记录（当前已具备，无需改结构）

```java
public record BuiltinTool(
    String id,                          // 统一使用下划线（已对齐 LLM schema）
    String name,                        // 中文显示名
    String description,                 // ★ 目标：含触发条件 + 参数要点 + 返回结构 + 错误契约
    JsonSchema inputSchema,             // ★ 目标：含 dependentRequired / oneOf / enum
    JsonSchema outputSchema,            // ★ 目标：不再为空，暴露 ToolResult 结构
    RiskLevel riskLevel,
    boolean idempotent,
    ToolExecutionSemantics executionSemantics,
    ToolBudget budget,
    List<String> tags,
    boolean exportable,
    ToolCategory category,
    Map<String, ActionMetadata> actionMetadata,
    ToolExecutor executor
) implements ToolContract { }
```

### 3.2 description 分层策略

每个工具的 description 必须覆盖以下 5 个维度（当前状态：P0-4 已补到 3 维，缺 ④⑤）：

```
① 功能概述：一句话说清楚工具做什么
② action 清单（多 action 工具）：每个 action 的用途与关键参数
③ 触发边界：何时用 / 何时不用
④ 返回结构：成功时的关键字段（从 outputSchema 摘要）
⑤ 错误契约：每种错误状态的含义与处理策略
```

**目标示例（browser 工具）**：

```
浏览器自动化（Playwright）。所有操作带 sessionId 复用会话。
action: navigate(导航) snapshot(截图+元素编号表，点击/输入前必调)
click/input/hover(优先用index) scroll/wait/select/keyboard
screenshot(仅截图) evaluate(执行JS) accessibility(无障碍树)
tab(标签页管理) storage(Cookie/localStorage)
requestHumanTakeover(挂起让用户接管登录/验证码) close(关闭会话)。

元素定位 fallback: index → #id → [data-testid] → accessibility → CSS选择器。
页面变化后旧元素失效，操作前重新 snapshot。
人机接管触发: 登录页/验证码/密码输入框/连续2次操作无推进 →
requestHumanTakeover，不要假装填密码。
会话模式: LAUNCH(默认) CDP(复用用户Chrome) PERSISTENT(永久profile)。

返回: { status, data: { screenshot?, elements?[], url, title }, error? }
错误处理:
- TRANSIENT_ERROR(网络/超时) → 自动重试最多3次指数退避
- RATE_LIMITED → 等待 data.retryAfterMs 后重试
- ERROR(终态失败) → 分析原因，切换策略或告知用户
- 导航返回 partial:true → 部分渲染可用，需完整时配合 wait
- Browser closed → 重新 navigate 起新 session
```

### 3.3 outputSchema 目标

不再空置。暴露 ToolResult 的标准信封结构：

```java
.outputSchema(JsonSchema.of(Map.of(
    "type", "object",
    "properties", Map.of(
        "status", Map.of(
            "type", "string",
            "enum", List.of("SUCCESS", "ERROR", "TRANSIENT_ERROR",
                            "PARTIAL_SUCCESS", "RATE_LIMITED", "SUSPENDED"),
            "description", "执行状态。SUCCESS=成功 ERROR=终态失败 TRANSIENT_ERROR=可自动重试 PARTIAL_SUCCESS=部分成功 RATE_LIMITED=限流 SUSPENDED=挂起等用户"
        ),
        "data", Map.of(
            "type", "object",
            "description", "成功时的结构化数据，字段见各工具具体声明"
        ),
        "error", Map.of(
            "type", "string",
            "description", "失败时非空。含具体原因；TRANSIENT_ERROR/RATE_LIMITED 含重试建议"
        ),
        "meta", Map.of(
            "type", "object",
            "properties", Map.of(
                "durationMs", Map.of("type", "integer"),
                "retryAfterMs", Map.of("type", "integer", "description", "仅 RATE_LIMITED 时存在")
            )
        )
    )
)))
```

**分阶段实施**：
- Phase 1：8 个高频工具先补齐（browser / shell.exec / shell.process / code.execute / file.read / file.write / file.manage / git.query）
- Phase 2：剩余 18 个工具跟随

### 3.4 硬控制矩阵（P0-2）

> 8 个多 action 工具，按 action 维度定义 dependentRequired / oneOf。
> 实施方法：在 `inputSchema` 的 JSON Schema 中添加 `dependentRequired` 对象。

#### 3.4.1 memory（11 actions）

| action | 必填参数 |
|--------|---------|
| search | query |
| recall | query |
| create | name, entityType |
| update | entityId |
| delete | entityId |
| cancel | `oneOf:` entityId ⊕ query |
| complete | entityId |
| supersede | entityId, new_entity_id |
| tag | sourceEntityId, targetEntityId |
| query-at-time | timestamp |
| search-experience | query |

**oneOf**：cancel 时 `entityId`（单条）或 `query`+`entityTypes`（批量）二选一。

#### 3.4.2 browser（16 actions）

| action | 必填参数 | 备注 |
|--------|---------|------|
| navigate | url | |
| snapshot | — | 可选 injectLabels/maxElements |
| click | `oneOf:` index ⊕ selector | |
| input | `oneOf:` index ⊕ selector, value | |
| hover | `oneOf:` index ⊕ selector | |
| scroll | direction | |
| wait | selector | state/timeout 可选 |
| select | selector, `oneOf:` value ⊕ label | |
| keyboard | type | key 或 text 二选一 |
| screenshot | — | fullPage 可选 |
| evaluate | expression | |
| accessibility | — | rootSelector/maxDepth 可选 |
| tab | tabAction | tabId/url 按 tabAction 定 |
| storage | target, storageAction | name 按 action 定 |
| takeover | reason | |
| close | — | |

**最多 oneOf 处**：5 处（click/input/hover 的 index⊕selector, select 的 value⊕label, keyboard 的 key⊕text）。

**sessionId**：所有 action 可选（复用已有会话），首次创建时可不传。

#### 3.4.3 code（4 actions）

| action | 必填参数 |
|--------|---------|
| exec | code |
| kernel_list | — |
| kernel_inspect | kernelId |
| kernel_reset | kernelId |

#### 3.4.4 shell.process（7 actions）

| action | 必填参数 |
|--------|---------|
| list | — |
| output | sessionId |
| write | sessionId, input |
| kill | sessionId |
| session_list | — |
| session_info | sessionId |
| session_kill | sessionId |

#### 3.4.5 file.read（4 actions）

| action | 必填参数 |
|--------|---------|
| read | `oneOf:` path ⊕ attachmentId |
| list | path |
| search | path, pattern |
| info | path |

**oneOf**：read 时 path 或 attachmentId 二选一（原已在 description 中声明，schema 未约束）。

#### 3.4.6 file.write（8 actions）

| action | 必填参数 |
|--------|---------|
| write | path, content |
| insert | path, line, content |
| replace | path, line |
| delete_line | path, line |
| find_replace | path, oldText, newText |
| undo | path |
| redo | path |
| diff | path |

#### 3.4.7 file.manage（5 actions）

| action | 必填参数 |
|--------|---------|
| move | source, destination |
| copy | source, destination |
| delete | path |
| mkdir | path |
| rename | source, destination |

#### 3.4.8 cron（4 actions）

| action | 必填参数 |
|--------|---------|
| create | name, schedule, instruction |
| list | — |
| update | taskId |
| delete | taskId |

### 3.5 实施策略

```
JSON Schema dependentRequired 写法：

{
  "type": "object",
  "required": ["action"],              ← 全局必填
  "properties": { ... },
  "dependentRequired": {               ← 按 action 条件必填
    "write":  ["path", "content"],
    "insert": ["path", "line", "content"],
    "undo":   ["path"],
    "diff":   ["path"]
  }
}
```

**工具优先级**（按参数复杂度）：memory → browser → file.write → file.manage → file.read → cron → code → shell.process。

**注意**：`dependentRequired` 是 JSON Schema Draft 2020-12 特性，需确认 Spring AI / OpenAI 协议兼容性。若不支持，降级为 `if-then-else` 模式。

---

## 四、Skill 层目标架构

### 3.1 SKILL.md frontmatter 目标

```yaml
---
name: research-assistant
description: 当用户要做多源搜索、交叉验证、调研行业动态、对比分析、
  技术选型、竞品分析或事实核查时使用。
  # ★ 只有场景描述，不含关键词，不含反向引导
version: 2.1.0
metadata:
  zhiwei:
    priority: normal
    tags:
      # ★ 英文 + 中文触发词都放这里，BM25 召回用
      - research
      - investigation
      - fact-check
      - trend-analysis
      - competitive-analysis
      - 调研
      - 查资料
      - 对比分析
      - 技术选型
      - 竞品分析
      - 最新动态
      - 行业趋势
    suggested_tools:
      # ★ 与 reference 正文保持一致，定期审计无腐化
      - web_search
      - web_fetch
      - knowledge_search
      - memory
      - file_write
    # ★ 新增字段：反向引导（从 description 剥离）
    excludes: "代码库内搜索用 code-assistant，数据集统计用 data-analyst，
      已绑定知识库的精确查询直接用 knowledge_search。"
---
```

### 3.2 新增字段说明

| 字段 | 类型 | 必须 | 用途 |
|------|------|------|------|
| `metadata.zhiwei.tags` | `List<String>` | 是 | 中英文混合触发词，用于 BM25 召回 |
| `metadata.zhiwei.excludes` | `String` | 否 | 反向引导——告诉 LLM 什么情况不要选本 skill |
| `metadata.zhiwei.verification` | `List<Verification>` | 否 | 关键步骤的机器可校验断言（未来） |
| `examples/` 目录 | `.md` 文件 | 否 | few-shot 执行轨迹（未来） |

### 3.3 reference 瘦身目标

当前状态：14/20 skill 的 reference 充当"工具速查表"（action 清单 + 命令模板 + 错误处理）。

目标状态：
- **工具用法** → 已上移到 tool schema description（P0-4）
- **reference 只保留**：场景流程（典型编排步骤）、边界决策树（什么情况走什么路径）、领域规则（如 cron 6 位格式）
- **命令模板** → 不再写 `tool_name(action="...", param=...)` 完整模板，改为自然语言引导 + 工具名引用

**瘦身前（browser-actions.md）vs 瘦身后**：

| 章节 | 瘦身前 | 瘦身后 |
|------|--------|--------|
| action 清单表 | 14 行 action × 3 列 | **删除**（已在 tool description） |
| 典型流程（4 个模板） | 含完整命令 | **保留**，但去掉参数细节 |
| 元素定位 fallback 链 | 有 | **保留**（场景决策） |
| 人机接管触发条件 | 有 | **保留**（领域规则） |
| 会话模式表 | 有 | **上移**到 tool description |
| 错误处理表 | 8 行 | **上移**到 tool description |

### 3.4 few-shot trace 目标（Phase 3）

每个核心 skill 提供 1-2 条 `examples/` 下的执行轨迹：

```markdown
# 示例：调研对比"Claude Code vs Codex CLI"

## 输入
用户说："帮我对比一下 Claude Code 和 Codex CLI，选一个适合我们团队的"

## 执行轨迹

Step 1 — 广度搜索
  → web_search(query="Claude Code vs Codex CLI 2026")
  → 返回 8 个结果，含 3 篇测评、2 篇官方文档、3 篇社区讨论
  → 决策：3 篇测评覆盖度够，先 fetch 测评

Step 2 — 深度抓取（并行）
  → web_fetch(url=<测评1>) + web_fetch(url=<测评2>) + web_fetch(url=<测评3>)
  → 全部成功，拿到完整正文
  → 决策：信息量足够，不需要额外搜索

Step 3 — 补充官方信息
  → web_fetch(url=<官方1>) + web_fetch(url=<官方2>)
  → 成功
  → 决策：功能列表和定价都已确认

Step 4 — 整理输出
  → file_write(path="workspace/claude-code-vs-codex.md", content="...")
  → SUCCESS

## 关键决策点
- Step 1 只做广度搜，不直接 fetch（节省 token）
- Step 2 并行抓取多个来源（互不依赖）
- 不再搜第三轮（信息饱和判断）
```

**实施优先级**：先为 research-assistant / code-assistant / cron-scheduler 各写 1 条。

---

## 五、Prompt 层目标架构

### 4.1 组装后的 System Prompt 目标结构

```
┌─────────────────────────────────────────────┐
│ <identity>                                  │
│ 身份 + 人格（role-definition.st）            │
├─────────────────────────────────────────────┤
│ <context_guide>                             │
│ 记忆使用决策表（context-guide.st）            │
├─────────────────────────────────────────────┤
│ <tool_protocol>                             │
│ 工具选择顺序 + 执行规范 + 禁止反模式          │
├─────────────────────────────────────────────┤
│ <error_handling>          ← ★ 新增          │
│ 错误分类 → 重试策略 → 降级路径               │
├─────────────────────────────────────────────┤
│ <groundedness>                              │
│ 回复真实性约束（已压缩）                      │
├─────────────────────────────────────────────┤
│ <skill_catalog>           ← 按需注入         │
│ 匹配的 skill 摘要 + references 加载规则       │
└─────────────────────────────────────────────┘
```

### 4.2 新增 `<error_handling>` 分区

```
<error_handling>
工具返回错误时的决策树：

TRANSIENT_ERROR（网络/超时/502/503/连接拒绝）
  → 自动重试，指数退避（1s→2s→4s），最多3次
  → 3次后仍失败：降级为 ERROR 处理

RATE_LIMITED（限流）
  → 检查 data.retryAfterMs，等待指定时间后重试
  → 无 retryAfterMs 时默认等 30 秒
  → 2次限流后告知用户"当前服务繁忙"

PARTIAL_SUCCESS（部分成功）
  → data 中已成功的部分直接使用
  → error 中失败部分：分析原因 → 换参数重试失败项 → 仍失败则告知用户哪些没完成

ERROR（终态失败）
  → 读 error 消息分析根因
  → 参数错误 → 修正后重试 1 次
  → 权限/配置/资源不存在 → 告知用户具体原因，建议解决路径
  → 同一工具连续 2 次 ERROR → 必须换方案，禁止原参数第三次调用

SUSPENDED（挂起）
  → 向用户说明挂起原因，等待用户操作后自动恢复
  → 不要在挂起期间重复调用同一工具
</error_handling>
```

### 4.3 prompt 文件职责分界（去重后）

| 文件 | 唯一职责 | 禁止包含 |
|------|---------|---------|
| `role-definition.st` | 身份 + 人格 + 语气 | 工具规则、记忆规则 |
| `context-guide.st` | 记忆使用决策表 | 工具选择规则 |
| `react-system.st` | 工具协议 + 错误处理 + 真实性约束 | 人格描述（已迁出） |
| `react-system-task.st` | 任务模式附加规则（叠加 react-system） | 记忆规则（已补 contextGuide） |
| `skill-catalog.st` | skill 列表 + references 加载规则 | 工具选择顺序（已去重） |
| `react-user-prompt.st` | 用户消息包装 | — |
| `streaming-constraint.st` | 流式输出约束 | — |

---

## 六、跨层一致性协议

### 5.1 命名规范

| 层 | 命名格式 | 示例 |
|----|---------|------|
| Tool ID（Java 内部） | `snake_case` | `web_search`, `file_read` |
| Tool ID（LLM schema） | `snake_case` | `web_search`, `file_read` |
| Skill name | `kebab-case` | `research-assistant` |
| Skill 正文引用工具 | `snake_case` | `web_search(query=...)` |
| Prompt 模板引用工具 | `snake_case` | `web_search` |
| Prompt 模板变量 | `camelCase` | `{roleDefinition}`, `{contextGuide}` |

**已收敛**：P0-1 统一后，所有 LLM 可见文本与 schema 名一致。

### 5.2 信息分层（避免重复）

```
         ┌──────────────────────────┐
         │  Tool Schema             │
         │  - 参数定义（唯一权威源）  │
         │  - action 枚举           │
         │  - 错误契约              │
         │  - outputSchema          │
         └──────────┬───────────────┘
                    │ 被引用
         ┌──────────▼───────────────┐
         │  Skill Reference         │
         │  - 场景编排流程           │
         │  - 领域决策树             │
         │  - 边界规则               │
         │  ✗ 不再重复：action 清单   │
         │  ✗ 不再重复：命令模板      │
         └──────────┬───────────────┘
                    │ 被聚合
         ┌──────────▼───────────────┐
         │  System Prompt           │
         │  - 全局行为规则           │
         │  - 错误处理决策树          │
         │  ✗ 不再重复：工具参数       │
         │  ✗ 不再重复：skill 细节     │
         └──────────────────────────┘
```

### 5.3 一致性检查清单

发布前必须确认：

- [ ] 无 tool ID 在 prompt/skill 中用点号（全为 `_`）
- [ ] 无 tool 的 description 引用不存在的工具（如 `file.delete`）
- [ ] 多 action 工具的 schema 有 `dependentRequired` 或 `oneOf`
- [ ] 高频工具的 `outputSchema` 不为空
- [ ] Skill `suggestedTools` 与实际使用工具一致
- [ ] Skill reference 中无重复 tool description 内容（action 清单/命令模板）
- [ ] Prompt 分区间无职责重叠

---

## 七、验收标准

### 6.1 定量指标

| 指标 | 当前基线 | 目标 | 测量方式 |
|------|---------|------|---------|
| Tool description 覆盖 5 维度 | ~60% (3/5) | 100% (5/5) | 人工审查 + checklist |
| outputSchema 非空率 | 2/26 (7.7%) | 8/26 → 26/26 | 代码扫描 |
| 多 action 工具有硬约束 | 0/9 (0%) | 9/9 (100%) | 代码扫描 |
| Skill tags 含中文触发词 | 0/20 (0%) | 20/20 (100%) | YAML 审查 |
| Prompt 分区无重叠 | 有 4 处重叠 | 0 | 人工审查 |
| Reference 工具速查行数 | ~40% 内容 | <10% | 抽样 diff |
| 命名分裂（点号残留） | 0（P0-1 已清） | 0 | grep 扫描 |

### 6.2 行为指标（需评估基础设施，Phase 4）

| 指标 | 目标 | 测量方式 |
|------|------|---------|
| 工具调用准确率（无幻觉 ID） | >95% | 日志分析 |
| Skill 命中率（BM25 top-3 含正确 skill） | >85% | 离线回放 |
| 工具失败后正确重试率 | >80% | 日志分析（TRANSIENT_ERROR → 退避重试） |
| 同参数第三次重试次数 | → 0 | 日志分析 |

---

## 八、迁移路径

### Phase 1：低垂果实（已完成 ✅）

- [x] P0-3：修复 `file.delete` 错引
- [x] P0-1：命名统一（点号 → 下划线）
- [x] P1-2：删除 `understanding.st` 孤儿
- [x] P1-1：Prompt 去重（response_style 合并 + groundedness 压缩 + skill-catalog 去重）
- [x] P1-3：任务模式注入 context-guide
- [x] P0-4：12 个工具 description 增强
- [x] P1-4：20 个 skill description 关键词清理

### Phase 2：结构补全（本次分支待完成）

| 步骤 | 内容 | 预计 |
|------|------|------|
| 2.1 | outputSchema 补齐（8 个高频工具）+ 全部 26 个工具 description 追加错误契约 | 半天 |
| 2.2 | react-system.st 新增 `<error_handling>` 分区 | 30 分钟 |
| 2.3 | 20 个 skill 的 `metadata.zhiwei.tags` 补中文触发词 | 1 小时 |
| 2.4 | P0-2 硬控制下放：9 个多 action 工具加 dependentRequired/oneOf | 1 周 |
| 2.5 | Reference 瘦身：去工具速查、去命令模板（与 2.1 联动） | 1 天 |

### Phase 3：能力提升（后续 PR）

| 步骤 | 内容 | 预计 |
|------|------|------|
| 3.1 | 3 个核心 skill 编写 few-shot trace（research-assistant / code-assistant / cron-scheduler） | 2 天 |
| 3.2 | scripts/ 试点（doc-processor / log-analyzer / data-analyst） | 1-2 周 |
| 3.3 | Skill 新增 `examples/` 目录规范 + `verification` 字段设计 | 1 天 |

### Phase 4：基础设施（未来 PR）

| 步骤 | 内容 | 预计 |
|------|------|------|
| 4.1 | 工具调用准确率 / Skill 命中率离线评估流水线 | 1 周 |
| 4.2 | 失败日志聚类 → LLM 重写 → A/B 测试 → 灰度发布闭环 | 2 周 |
| 4.3 | 参数命名风格统一（跨 tool 的同类参数统一命名） | 1 周 |

---

## 九、文件变更清单

### Phase 2 预计变更

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `BuiltinTool.java` | 不改 | outputSchema 字段已存在，仅填充 |
| `*ToolProvider.java` × 8 | 修改 | 构造 outputSchema + 描述追加错误契约 |
| `ShellToolProvider.java` | 修改 | 同上 |
| `BrowserToolProvider.java` | 修改 | 同上 |
| `CodeToolProvider.java` | 修改 | 同上 |
| `FileToolProvider.java` | 修改 | 同上（file.read/write/manage 三个工具） |
| `GitToolProvider.java` | 修改 | 同上 |
| `react-system.st` | 修改 | 新增 `<error_handling>` 分区 |
| `*.md` (20 个 SKILL.md) | 修改 | tags 列表追加中文触发词 |
| `*.md` (14 个 reference) | 修改 | 删除工具速查表/命令模板 |
| `*ToolProvider.java` × 9 | 修改 | P0-2 硬控制：dependentRequired/oneOf 改造 inputSchema |

---

## 十、设计决策记录

### 决策 1：不改 BuiltinTool record 结构

**选择**：复用现有 `outputSchema` 字段，不新增 `errors` / `triggers` / `version` 等字段。

**理由**：
- `outputSchema` 已经定义但未使用——填充即可暴露返回结构给 LLM
- 错误契约放在 description 尾部（LLM 能看到），状态字段在 outputSchema 中
- 新增字段需要改 ToolContract 接口、MCP 导出、插件兼容层、测试 —— 代价过高
- 当前 4 个现有字段足以承载最佳实践要求的全部信息

### 决策 2：错误契约放 description 而不是独立字段

**选择**：按固定模板追加到 description 末尾，不新增 `errorContract` 字段。

**理由**：
- LLM 阅读 description 的注意力优先于 outputSchema
- description 中的错误处理指南直接指导 LLM 的下一个 Thought
- 独立字段需要改 ToolContract 接口（破坏插件兼容性）

### 决策 3：Skill 中文触发词放 tags 而不是 description

**选择**：tags 同时承载英文分类标签和中文触发词。

**理由**：
- BM25 对 tags 和 description 的搜索权重相同
- 分离后 description 更短，LLM 评估 skill_catalog 时信号更集中
- tags 可在搜索时单独加权（未来优化空间）

### 决策 4：Phase 2 先不做 few-shot trace

**选择**：先补齐 outputSchema + 错误契约 + 硬控制，few-shot trace 放 Phase 3。

**理由**：
- few-shot trace 需要大量上下文空间（每条 200-400 tokens）
- 需要机制让 SkillActivator 按需注入（不能全量堆到 skill_catalog）
- 先做完上下文瘦身（Reference 瘦身），腾出空间再放 few-shot

---

## 十一、Reference 瘦身规划

> 原则：工具用法上移 tool description，场景流程留 reference。
> 单 skill 多 reference 的条件：内容跨越两个以上不同决策维度。

### 11.1 分类矩阵

| 类型 | 判据 | 数量 | 处理 |
|------|------|------|------|
| 🅰️ 工具速查 | action 清单 + 命令模板 + 错误表 | 9 | 大幅删减（已上移 tool） |
| 🅱️ 场景流程 | 路径判据 + 编排决策 | 6 | 保留精简 |
| 🅲 领域知识 | 平台差异 + 方言 + 规则 | 5 | 保留 |
| 🅳 代码片段 | 语言/格式模板 | 2 | 保留精简 |

### 11.2 逐 reference 规划

**🅰️ 工具速查（9 个，~990 → ~260 行）**

| reference | 改前 | 改后 | 删 | 留 |
|-----------|------|------|-----|-----|
| browser-actions.md | 122 | 30 | action 清单/命令模板/错误表 | 元素 fallback 链/接管触发/会话模式 |
| cron-reference.md | 86 | 30 | action 命令模板/错误表 | cron 6位表达式速查（领域知识） |
| log-commands.md | 142 | 35 | grep/awk/sed 模板 | 日志路径/脱敏正则 |
| diagnose-commands.md | 145 | 30 | top/free/df 命令 | 平台切换/诊断优先级 |
| doc-conversion.md | 143 | 25 | pandoc/python 命令 | 格式兼容矩阵 |
| curl-recipes.md | 108 | 25 | curl 命令模板 | HTTP 调试决策 |
| feishu/actions-ref.md | 80 | 30 | API 调用模板 | 权限/限制说明 |
| pyautogui-recipes.md | 132 | 30 | pyautogui 命令 | Windows 特有 API |
| organize-patterns.md | 134 | 60 | 命令模板 | 5步确认流程 |

**🅱️ 场景流程（6 个，~580 → ~350 行）**

| reference | 改前 | 改后 | 说明 |
|-----------|------|------|------|
| research-workflow.md | 105 | 65 | 去命令模板，留路径判据 |
| summary-formats.md | 153 | 90 | 去命令模板，留 4×4 格式矩阵 |
| teaching-patterns.md | 115 | 70 | 去命令模板，留教学路径 |
| agent-lifecycle.md | 66 | 35 | 去 shell 命令，留状态机 |
| orchestration.md | 58 | 35 | 去命令模板，留编排决策 |
| writing-patterns.md | 106 | 55 | 去冗余模板，留核心格式 |
| gh-cli-reference.md | 79 | 40 | 去冗余命令，留 gh 特有语法 |
| content-api.md | 68 | 35 | 精简冗余说明 |

**🅲🅳 领域知识 + 代码片段（5 个，保留）**

| reference | 行数 | 说明 |
|-----------|------|------|
| dialect-reference.md | 101 | SQLite 方言限制 |
| v2-spec-template.md | 128 | Skill 规范模板 |
| component-catalog.md | 149 | UI 组件参考 |
| pandas-recipes.md | 141 | pandas 片段（去 40 行冗余） |
| coordination-patterns.md | 70 | 多 Skill 编排模式 |

### 11.3 拆分计划

| skill | 当前 | 拆分后 | 原因 |
|-------|------|--------|------|
| research-assistant | 1 个 workflow | workflow.md + source-eval.md | 调研流程 vs 来源评估，不同决策维度 |
| file-organizer | 1 个 patterns | patterns.md + dedup-strategies.md | 整理流程 vs 去重策略 |
| skill-creator | 1 个 template | spec-template.md + validation-guide.md | 规范模板 vs 校验规则 |
| daily-manager | 1 个 patterns | 不拆 | 编排模式不复杂，1 个够 |
| code-assistant | 2 个 ✅ | 不变 | 已合理拆分 |
| github-workflow | 2 个 ✅ | 不变 | 已合理拆分 |

### 11.4 总量预估

| | 改前 | 改后 |
|---|------|------|
| 文件数 | 22 | 25（+3 拆分） |
| 总行数 | ~2,400 | ~1,200 |
| LLM 加载单 reference tokens | ~450 avg | ~250 avg |
