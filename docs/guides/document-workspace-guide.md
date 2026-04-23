# 知微文档工作空间使用指南

> 本文档面向知微（ZhiWei）的 Skill 作者、Agent 开发者和后端集成开发者，介绍 `document.edit` 工具（含 `document.create` 的协同使用）的完整用法、设计意图、错误处理和反模式。
> 本文档**写给开发者阅读**，不假设 LLM 运行时能看到这份 Markdown。所有 LLM 可感知的行为由 `DocumentEditToolProvider.buildDescription()` 的 schema description + `skills/document-workspace/SKILL.md` 承载；本指南仅为开发者提供更详尽的原理与案例。

---

## 目录

- [模块定位](#模块定位)
- [快速开始：patch → commit 端到端](#快速开始patch--commit-端到端)
- [三种 source 用法](#三种-source-用法)
- [docx 4 种 op 详解](#docx-4-种-op-详解)
- [xlsx 4 种 op 详解](#xlsx-4-种-op-详解)
- [锚点最佳实践](#锚点最佳实践)
- [错误处理：failedOps + documentOutline](#错误处理failedops--documentoutline)
- [熔断机制](#熔断机制)
- [反模式清单](#反模式清单)
- [REST 与 Agent 工具通道的边界](#rest-与-agent-工具通道的边界)
- [相关源码](#相关源码)

---

## 模块定位

文档工作空间提供两个 BuiltinTool：

| 工具 | 功能 | 风险 | 格式支持 |
|------|------|------|---------|
| `document.create` | 从零生成 docx / xlsx / pptx | MEDIUM | docx（markdown）/ xlsx（sheets）/ pptx（slides） |
| `document.edit` | 在已有文档上做锚点增量编辑 + 版本管理 | MEDIUM | docx / xlsx only（pptx 不支持） |

`document.create` 的产物会自动挂到当前 assistant 消息附件上；`document.edit` 的产物以「工作副本 + 版本链」形式存在会话私有目录，直到用户显式 commit / discard 才落盘或回收。

工具可见性通过 `lifepilot.tool.tier1.pinned` 配置：在 pinned 列表中的工具 schema 常驻 prompt，LLM 直接可用；未 pin 的工具通过 `tools.search` 按需发现（BM25 检索）。`document.create` / `document.edit` 默认未 pin，LLM 有"文档编辑"相关意图时会先调 `tools.search` 找到这两个工具再用（参见 [工具系统架构](../architecture/tool-ecosystem.md)）。

---

## 快速开始：patch → commit 端到端

### 第一步：patch（会内置 checkout）

```json
{
  "tool": "document.edit",
  "args": {
    "action": "patch",
    "source": { "type": "path", "value": "D:/proposal.docx" },
    "operations": [
      {
        "op": "replace_text",
        "before_context": "本项目预计于 ",
        "target": "2024-Q4",
        "after_context": " 上线",
        "new_text": "2025-Q1",
        "reason": "交付节奏调整"
      }
    ]
  }
}
```

首次调用会自动 checkout（复制源文件到会话私有工作目录作为 v0），然后应用 ops 生成 v1。返回：

```json
{
  "success": true,
  "documentId": "9a3f8c1b-…",
  "newVersion": 1,
  "summary": "共 1 处修改(replace_text x1)",
  "diffJson": "{…}",
  "downloadUrl": "/api/documents/9a3f8c1b-…/download"
}
```

### 第二步：用户在前端预览（AI 不直接落盘）

LLM 应把 `downloadUrl` 告诉用户、或者让用户在消息气泡的 DiffCard 上预览。`commit` / `rollback` / `discard` 是**用户动作**。

### 第三步：commit（用户点了「应用到原路径」）

```json
{
  "tool": "document.edit",
  "args": {
    "action": "commit",
    "documentId": "9a3f8c1b-…",
    "commitTarget": "overwrite"
  }
}
```

返回：

```json
{
  "committedPath": "D:/proposal.docx",
  "backupPath": "D:/proposal.docx.20260422150330.bak"
}
```

系统自动生成 `.bak` 备份，保留最近 5 份自动备份（更早的按时间戳从旧到新删除）。

### 另存为场景

```json
{
  "action": "commit",
  "documentId": "9a3f8c1b-…",
  "commitTarget": "saveAs",
  "saveAsPath": "D:/Archive/proposal-v2.docx"
}
```

返回 `{committedPath: "D:/Archive/proposal-v2.docx"}`，无 `backupPath`。

---

## 三种 source 用法

### path（本机绝对路径）

用户说「改一下 `D:/contract.docx`」时使用：

```json
{
  "source": { "type": "path", "value": "D:/contract.docx" }
}
```

- 扩展名必须是 `.docx` 或 `.xlsx`
- 走 `PathSecurityChecker.check`（读场景），同一套白/黑名单与 `file.read` 共享
- 同一 session 对同一 path 调多次 patch 会复用同一 documentId（`session_documents` 上有唯一约束）

### attachment（前端上传附件）

用户拖拽上传文件后对话说「改这份」：

```json
{
  "source": { "type": "attachment", "id": "<attachmentId>" }
}
```

- 要求附件 `fileName` 扩展名或 `mimeType` 匹配 docx / xlsx
- 首次 checkout 后产生一份 session 私有工作副本，此后 commit 只能走 `saveAs`（没有 sourcePath 可 overwrite）

### document（本会话内已有 documentId）

同会话内继续编辑已生成或已签出过的文档：

```json
{
  "source": { "type": "document", "id": "<documentId>" }
}
```

- 适用于 AI 刚 `document.create` 生成完 docx，用户立刻要求再改的场景
- 适用于上一轮 patch 已经拿到 documentId，本轮用相同 id 继续累加版本
- 若目标文档 `latestVersion == 0`（还没进过 patch 链路），会自动把原始字节复制为 v0

---

## docx 4 种 op 详解

### 1. `replace_text` — 文本替换

```json
{
  "op": "replace_text",
  "before_context": "项目预计于 ",
  "target": "2024-Q4",
  "after_context": " 上线",
  "new_text": "2025-Q1",
  "reason": "交付节奏调整"
}
```

- 定位逻辑：在全文段落内查找 `before_context + target + after_context` 拼接串的**唯一**出现
- 改写范围：仅 `target` 对应文字，前后 context 保持原样
- 样式保留：首 run 样式（字体 / 字号 / 颜色 / 粗斜体）自动继承到新文本
- 空 `before_context` / `after_context` 合法但不推荐（易多处命中）
- `new_text=""` 等价于删除 target

### 2. `insert_paragraph_after` — 段落后插入

```json
{
  "op": "insert_paragraph_after",
  "anchor_paragraph_text": "风险分析",
  "new_paragraphs": [
    { "text": "补充说明", "style": "Heading2" },
    { "text": "本节重点说明以下三个风险项。", "style": "Normal" }
  ]
}
```

- `anchor_paragraph_text` 必须在**全文精确匹配**一个段落（整段 equals，不是 contains）
- `style` 枚举：`Normal` / `Heading1` / `Heading2` / `Heading3` / `ListBullet`，缺失默认 `Normal`
- 支持一次插入多段，按数组顺序追加在 anchor 之后

### 3. `delete_paragraph` — 整段删除

```json
{
  "op": "delete_paragraph",
  "paragraph_text": "待删除的整段原文"
}
```

- `paragraph_text` 必须在全文**精确匹配**一个段落（与 `insert_paragraph_after` 的 anchor 规则一致）
- 删除后通过 `doc.removeBodyElement(bodyPos)` 按 body-element 下标删除，段落 / 表格混合时安全

### 4. `add_table_row` — 表格追加行

```json
{
  "op": "add_table_row",
  "table_anchor_text": "季度营收",
  "position": "end",
  "cells": ["Q4", "120 万", "同比 +12%"]
}
```

- `table_anchor_text` 在任一单元格文本中 `contains` 命中即可，但必须唯一命中**一个表格**（同一表格内多 cell 命中算一个表）
- `position`: `"start"` 或 `"end"`（注意：不是 `first`/`last`）
- `cells.size()` **必须等于表格列数**，否则返回 `cells_mismatch` 失败

---

## xlsx 4 种 op 详解

### 1. `update_cell` — 单元格多态写入

```json
{ "op": "update_cell", "sheet": "Sheet1", "cell": "B5", "new_value": 72.5 }
{ "op": "update_cell", "sheet": "Sheet1", "cell": "C5", "new_value": true }
{ "op": "update_cell", "sheet": "Sheet1", "cell": "D5", "new_value": "=SUM(B2:B10)" }
{ "op": "update_cell", "sheet": "Sheet1", "cell": "E5", "new_value": "备注文字" }
{ "op": "update_cell", "sheet": "Sheet1", "cell": "F5", "new_value": null }
```

值类型决定格的类型：
- `Number` → numeric cell
- `Boolean` → boolean cell
- `String` 以 `=` 开头 → 公式（剥前缀后 `setCellFormula`）
- `String` 其它 → 字符串字面量
- `null` → `setBlank`

Cell 地址解析：
- 大小写不敏感，会自动 upper-case
- 自动剥 `$` 绝对引用前缀（`$B$5` / `B$5` 都合法）
- **拒绝**含 `!` 的 sheet-prefix 写法（`Sheet1!B5` 非法），sheet 必须放独立字段

合并单元格限制：
- 只允许改 anchor（左上角）
- 目标格在合并区内非 anchor 位置会返回 `cell_inside_merged_region`，hint 会指明正确的 anchor 地址

### 2. `insert_row` — 插入行

```json
{
  "op": "insert_row",
  "sheet": "Sheet1",
  "before_row": 5,
  "values": ["标题", 42, true, "=B5*2"]
}
```

- `before_row` 1-based。合法范围是「任一已存在行，或最末行的下一行」—— 即 insert 到某行之前，或追加到表尾；越界返回 `invalid_row_number`
- `before_row` 超过最末行时直接 `createRow` 追加；否则 `shiftRows` 下移再填值
- values 长度自由（可短于列数，短则后续列不写）
- 公式引用自动 +1（POI `shiftRows` 默认行为）
- 新行 `CellStyle` 继承上一行（POI 默认行为）

### 3. `delete_row` — 删除行

```json
{ "op": "delete_row", "sheet": "Sheet1", "row": 10 }
```

- `row` 1-based，必须指向已存在的行；越界返回 `row_not_found`
- 若目标行横跨合并区域直接返回 `row_in_merged_region`，需要用户先解除合并
- 后续行自动上移，公式引用 -1

### 4. `set_range` — 批量写矩形区域

```json
{
  "op": "set_range",
  "sheet": "Sheet1",
  "range": "B2:D4",
  "values": [
    ["姓名", "年龄", "城市"],
    ["张三", 28, "北京"],
    ["李四", 32, "上海"]
  ]
}
```

- `range` A1 notation（`B2:D4`），自动处理大小写 / `$` 前缀
- `values` 是 2D 数组：**外层长度必须等于 range 行数，每行长度必须等于 range 列数**
- 与合并区域相交直接返回 `range_contains_merged_region`
- 每格按 `update_cell` 的多态规则写入

批量矩形建议用 `set_range` 而不是循环 `update_cell`：一次 patch 内 op 越少越容易事务成功。

---

## 锚点最佳实践

### docx：before_context / after_context 各 10-30 字

**为什么是 10-30 字**：

- **< 10 字**：容易和文档多处命中，`locator_not_unique_or_missing` 返回 `matchCount > 1`
- **> 30 字**：容易和文档真实文本对不上（用户记忆的上下文可能与 AI 读到的存在标点 / 空格差异），变成 `matchCount = 0`
- **10-30 字**：经验上的甜区，既保证唯一性又容错

**反例**：

```jsonc
// ❌ 太短：可能命中多处
{ "before_context": "日期：", "target": "2024", "after_context": "年" }

// ❌ 太长：前置 60 字的 context，稍有标点差异就对不上
{ "before_context": "根据上次会议讨论结果以及 PM 反馈，项目组一致认为应将原定的交付节奏从 ", "target": "Q4", "after_context": " 调整" }

// ✅ 甜区
{ "before_context": "项目预计于 ", "target": "2024-Q4", "after_context": " 上线" }
```

### xlsx：精确 sheet + A1

xlsx 锚点没有「上下文」概念，只有 sheet 名（**严格区分大小写**）和 A1 地址：

```jsonc
// ❌ sheet 名大小写不符：sheet_not_found
{ "sheet": "sheet1", "cell": "B5" }

// ❌ sheet-prefix 写法：invalid_cell_address
{ "sheet": "Sheet1", "cell": "Sheet1!B5" }

// ✅
{ "sheet": "Sheet1", "cell": "B5" }
```

---

## 错误处理：failedOps + documentOutline

patch 失败时工具返回的数据结构：

```jsonc
{
  "success": false,
  "documentId": "9a3f8c1b-…",
  "failedOps": [
    {
      "opIndex": 0,
      "opType": "replace_text",
      "reason": "locator_not_unique_or_missing",
      "matchCount": 0,
      "hint": "before_context+target+after_context 在文档中未唯一出现，建议加长锚点"
    }
  ],
  "failureCount": 1,
  "maxAllowedFailures": 3,
  "documentOutline": [
    { "paragraphIndex": 0, "preview": "项目周报 - 2026 年 4 月第 3 周", "style": "Heading1" },
    { "paragraphIndex": 3, "preview": "本周进展：…", "style": "Normal" }
  ],
  "hint": "failedOps 无法匹配，请对照 documentOutline 里每段的 preview 重写 locator 的 before_context/target/after_context；若累计失败接近 3 次请停下和用户确认。"
}
```

### `failedOps[].reason` 枚举

**docx**：
- `locator_not_unique_or_missing` — replace_text 的 `before+target+after` 拼接串 0 次或多次出现
- `anchor_paragraph_not_unique_or_missing` — insert_paragraph_after 的 anchor 未精确匹配单一段落
- `paragraph_text_not_unique_or_missing` — delete_paragraph 的 paragraph_text 未精确匹配单一段落
- `table_anchor_not_unique_or_missing` — add_table_row 的 anchor 未命中单个表格
- `cells_mismatch` — add_table_row 的 cells 长度与表格列数不符

**xlsx**：
- `sheet_not_found` — sheet 名不存在（注意区分大小写）
- `invalid_cell_address` / `invalid_range` — A1 格式非法 / 含 `!` / 列字母非法
- `cell_inside_merged_region` — 目标格在合并区非 anchor 位置
- `row_in_merged_region` — delete_row 目标行横跨合并区域
- `range_contains_merged_region` — set_range 与合并区相交
- `invalid_row_number` — insert_row 的 before_row 越界
- `row_not_found` — delete_row 的 row 越界
- `range_size_mismatch` — set_range 的 values 尺寸不匹配

**共用**：
- `execution_error` — POI 抛其它 RuntimeException，`hint` 带原始异常消息

### `documentOutline` 的使用

- **仅 docx 文档有效**，xlsx 不回 outline（xlsx 的失败提示已在 `FailedOp.hint` 里给出具体单元格 / 行号）
- 按段落顺序列出，每段 `preview` ≤ 80 字（长则后缀 `…`），空段落跳过
- 用于 LLM 对照真实文本重新选择锚点 —— 把 `before_context` / `after_context` 改成 outline 里真实出现的字串

**重试策略**：

1. 读 `failedOps[0].reason` 和 `hint`
2. 若 `reason` 是 `*_not_unique_or_missing` 且 `documentOutline` 非空：对照 outline 重写锚点
3. 若 `failureCount` 接近 `maxAllowedFailures`（≥ 2）：**停下来问用户**，不要盲试

---

## 熔断机制

`DocumentEditActionDispatchExecutor` 对每个 `sessionId:documentId` 维护连续失败计数。达到 `MAX_PATCH_FAILURES = 3` 时直接返回 error：

```json
{
  "error": "同一文档 patch 已连续失败 3 次，熔断以防死循环。请停下向用户说明：当前文档的实际文本结构与你的锚点预期不一致，请用户提供更精确的锚点描述或重新贴一次文档；不要再继续自动重试。"
}
```

**计数清理时机**：
- patch 成功 → 清计数
- 达到上限熔断 → 清计数（允许用户反馈后重试）
- rollback 成功 → 清同 documentId 下所有计数（rollback 本质上是一次「成功恢复」，之前累积的计数失去语义）

**运维告警**：计数器容量超过 `FAIL_COUNTER_WARN_THRESHOLD = 1024` 会 WARN，正常场景下远用不到这个量级；出现告警说明有 session 泄漏没清，需要排查。

---

## 反模式清单

### ❌ 反模式 1：用 `document.create` 重建已存在的文档

```
用户：把 report.docx 改一下
AI：（发现上一轮没保留 documentId）那我重新生成一份吧 → document.create(...)
```

**后果**：产生新 documentId，丢失原文件的版本链和回滚能力；用户的 `.bak` 备份策略完全绕过。

**正确做法**：
- 没有 documentId 但有本地路径 → `document.edit source.type=path`
- 都没有 → 问用户要原文件路径或让其重新上传附件

### ❌ 反模式 2：跨 MIME 混用 op

```json
{
  "operations": [
    { "op": "replace_text", ... },      // docx op
    { "op": "update_cell", ... }        // xlsx op
  ]
}
```

**后果**：`DocumentVersionService.castDocxOps` / `castXlsxOps` 运行时 instanceof 校验会拦截，抛 `IllegalArgumentException("op #N 不是 xxx 操作，目标文档是 xxx 类型")`。

**正确做法**：分两次 patch 调用，分别改 docx 和 xlsx；或者单次 patch 只对一份文档、一种 MIME 操作。

### ❌ 反模式 3：LLM 主动 commit

```
AI：已改完，我帮你覆盖原文件 → document.edit(action=commit, commitTarget=overwrite)
```

**后果**：LLM 越权执行用户级动作；如果 AI 判断错改错地方，用户想 discard 已经来不及。

**正确做法**：LLM 只调 `patch`，把返回的 `downloadUrl` 告诉用户（或者直接信任前端会渲染 DiffCard），由用户自己在 UI 上点「应用到原路径」。SKILL.md 已明确这条约束。

### ❌ 反模式 4：锚点过短导致命中多处

```jsonc
{ "before_context": "：", "target": "张三", "after_context": "，" }
```

**后果**：`locator_not_unique_or_missing` 且 `matchCount` 可能是 5、10 等。

**正确做法**：before / after 各取 10-30 字上下文。

### ❌ 反模式 5：用 `file.read` 读 docx / xlsx 正文

docx / xlsx / pptx 是 ZIP + XML 二进制格式，`file.read` 读出来是 PK 开头的乱码。

**正确做法**：
- 刚由 `document.create` 生成：用自己写入的 markdown 原文做锚点 target
- 其他来源：直接尝试 patch，失败时用返回的 `documentOutline` 调整

### ❌ 反模式 6：在同一份 xlsx 里循环 `update_cell` 改一整块区域

```json
[
  { "op": "update_cell", "sheet": "Sheet1", "cell": "B2", "new_value": "..." },
  { "op": "update_cell", "sheet": "Sheet1", "cell": "C2", "new_value": "..." },
  ...（9 个格）
]
```

**后果**：ops 数量大，事务失败概率上升，熔断阈值更容易触发。

**正确做法**：用 `set_range`，2D values 一次写完。

---

## REST 与 Agent 工具通道的边界

有两套对外接口，**用途不同、不要混用**：

### Agent 工具通道（LLM 用）

- `document.edit` — 5 个 action：`patch` / `diff` / `commit` / `rollback` / `list_versions`
- `document.create` — 3 个 action：`docx` / `xlsx` / `pptx`
- 走 `ReactAgentLoop` 的工具调用链路，触发 Guardrail 权限审批（commit 是 MEDIUM）

### REST 通道（前端 Vue 用）

Base Path `/api/documents`，7 个端点：

- `GET /{id}` — 元数据
- `GET /{id}/download?version=N` — 下载二进制
- `GET /{id}/versions` — 版本链
- `GET /{id}/diff?from=&to=` — diff JSON
- `POST /{id}/commit` — 覆盖 / 另存
- `POST /{id}/rollback` — 回滚
- `DELETE /{id}/working-copy` — 丢弃

前端 DiffCard（`DocumentDiffCard.vue` / `DocumentXlsxDiffCard.vue`）通过 REST 拉 metadata / diff、通过 REST 执行 commit / rollback / discard。这些动作**不经过 LLM 工具通道**，不触发权限审批（用户已经在 UI 上点了按钮，就是最强授权）。

**约束**：
- LLM 不应直接调用 REST 端点（它没有 HTTP client 工具）
- 前端也不走 `document.edit` 工具调用；真要测试工具行为，用 `curl` 或集成测试

---

## 相关源码

### Java 后端

- `src/main/java/com/lifepilot/document/tool/DocumentEditToolProvider.java` — `document.edit` 工具 schema + description（LLM 运行时可见）
- `src/main/java/com/lifepilot/document/tool/DocumentEditActionDispatchExecutor.java` — 5 action 路由 + 熔断计数器
- `src/main/java/com/lifepilot/document/tool/DocumentToolProvider.java` — `document.create` 工具 schema
- `src/main/java/com/lifepilot/document/tool/DocumentCreateActionDispatchExecutor.java` — 3 action 路由
- `src/main/java/com/lifepilot/document/version/DocumentVersionService.java` — checkout / applyPatch / commit / rollback / discard 主编排
- `src/main/java/com/lifepilot/document/version/SourceRef.java` — sealed interface 三种源
- `src/main/java/com/lifepilot/document/patch/DocumentPatchOperation.java` — 顶层 sealed 协议
- `src/main/java/com/lifepilot/document/patch/DocxPatchOperation.java` — docx 子 sealed（4 op record）
- `src/main/java/com/lifepilot/document/patch/XlsxPatchOperation.java` — xlsx 子 sealed（4 op record）
- `src/main/java/com/lifepilot/document/patch/docx/DocxPatchEngine.java` — docx 4 op 实现 + `TextAnchorLocator`
- `src/main/java/com/lifepilot/document/patch/docx/TextAnchorLocator.java` — 文本锚点定位器
- `src/main/java/com/lifepilot/document/patch/docx/DocxDiffBuilder.java` — docx diff JSON 构造
- `src/main/java/com/lifepilot/document/patch/xlsx/XlsxPatchEngine.java` — xlsx 4 op 实现 + `CellAddressResolver`
- `src/main/java/com/lifepilot/document/patch/xlsx/CellAddressResolver.java` — A1 地址解析
- `src/main/java/com/lifepilot/document/patch/xlsx/XlsxDiffBuilder.java` — xlsx diff JSON 构造
- `src/main/java/com/lifepilot/document/repository/SessionDocumentRepository.java` — `session_documents` 表访问
- `src/main/java/com/lifepilot/document/repository/DocumentVersionRepository.java` — `document_versions` 表访问
- `src/main/java/com/lifepilot/document/config/DocumentAutoConfiguration.java` — 装配入口
- `src/main/java/com/lifepilot/document/config/DocumentProperties.java` — 配置属性
- `src/main/java/com/lifepilot/interaction/web/controller/DocumentController.java` — 7 个 REST 端点

### Skill 与迁移

- `src/main/resources/skills/document-workspace/SKILL.md` — LLM 侧能读到的使用指南（本文档开发者版的精简子集）
- `src/main/resources/db/migration/V12__add_session_documents_table.sql` — Phase 2A 建表
- `src/main/resources/db/migration/V13__document_patch_and_versions.sql` — Phase 3A 扩展 + document_versions
- `src/main/resources/db/migration/V14__session_documents_source_path_unique.sql` — Phase 3A 补丁：session+source_path 唯一约束

### 前端

- `zhiwei-web/src/components/chat/DocumentDiffCard.vue` — docx 差异渲染
- `zhiwei-web/src/components/chat/DocumentXlsxDiffCard.vue` — xlsx 差异渲染
- `zhiwei-web/src/components/chat/DocumentDiffHeader.vue` — 顶部条（共享）
- `zhiwei-web/src/components/chat/DocumentDiffActions.vue` — 底部三按钮（共享）
- `zhiwei-web/src/components/chat/DocumentVersionHistoryList.vue` — 版本历史列表
- `zhiwei-web/src/composables/useDocumentDiffCard.ts` — 卡片共享逻辑
- `zhiwei-web/src/composables/useDocumentMeta.ts` — 元数据拉取

### 相关文档

- [架构设计文档](../architecture/document-workspace.md)
- [特性说明](../features/document-workspace.md)
- [API 端点清单（Documents 节）](../API_ENDPOINTS.md#documents文档工作空间)
