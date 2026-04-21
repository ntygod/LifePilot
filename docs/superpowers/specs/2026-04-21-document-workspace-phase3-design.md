---
title: 文档工作空间 Phase 3 — 编辑与 Diff（面向 LLM）
status: draft
owner: zsg
date: 2026-04-21
scope: P3A 聚焦 docx 全链路打穿（patch 协议 + 版本管理 + 工作副本 + 前端 diff 展示），P3B 横向复制到 xlsx
blueprint: docs/superpowers/specs/2026-04-20-document-workspace-design.md（§0.5 Phase 3、§5.3、§8 Phase 3）
---

# 文档工作空间 Phase 3 — 编辑与 Diff（面向 LLM）

> 本文档是 Phase 3 的**设计规格**（spec），正式实施前按本 spec 走 `superpowers:writing-plans` 拆出逐任务可执行的 plan。

---

## 1. 定位

### 1.1 面向 LLM 不面向用户编辑

P3 的核心链路是：

```
用户 "把第 2 段改成 XXX"
  ↓
LLM 调 document.edit(action=patch, operations=[...])
  ↓
服务端 POI 执行，保留原样式 → 生成新版本
  ↓
前端渲染 diff（只读） + 给"应用到原路径 / 丢弃"按钮
```

**不做**：用户在浏览器里直接点单元格改数字、拖段落、调字号。这类"在线 Office 编辑器"需要 Univer / TipTap 之类重量级前端，学习成本高且和"本地 AI 助手"定位冲突，延到 Phase 4 Artifact 双栏工作台按需再议（也可能不做）。

### 1.2 与其他 Phase 的边界

| Phase | 与 P3 的关系 |
|---|---|
| Phase 0 / 1B | 读懂能力（`DocumentParserService`）是 P3 patch 前置——LLM 先 parse 看到文本才能吐准 locator |
| Phase 2A / 2B | 生成能力（`document.create`）是 P3 的对称——共用 `session_documents` 表 / `AttachmentRepository` 回填 / `DocumentController` 下载端点 |
| Phase 4（不做） | 双栏 Artifact 工作台。P3 前端挂在对话气泡内，不做双栏 |
| Phase 5 | 模板填空是另一个心智，P3 不触及 |

### 1.3 P3A / P3B 切分（按格式纵向切）

**P3A — docx 全链路打穿（本 spec 主体）**

- `document.edit` 工具 + 5 种 action
- 文本锚点 patch 协议（4 种 op：`replace_text` / `insert_paragraph_after` / `delete_paragraph` / `add_table_row`）
- `session_documents` 扩字段 + `document_versions` 新表（V13）
- 工作副本生命周期（checkout / patch / commit / rollback）
- `DocumentController` 扩展 5 个端点
- 前端 `DocumentDiffCard.vue`（对话气泡内折叠卡）
- 整批接受 / 回滚

**P3B — xlsx 横向复制（本 spec 不展开）**

- xlsx 特有 op：`update_cell / insert_row / delete_row / set_range`
- cell 级 diff 粒度适配
- 版本存储复用 P3A 同一套 `document_versions`
- 前端 `DocumentDiffCard.vue` 按 MIME 分支渲染 xlsx diff（或做一个 `DocumentXlsxDiffCard.vue`）
- P3B 与 P3A 共用协议骨架，改 engine 即可

---

## 2. 协议设计

### 2.1 工具接口 —— `document.edit`

对齐 Phase 2B 的 `document.create` 单工具 + action 分发模式（见 `DocumentToolProvider`）。

```json
{
  "action": "patch | diff | commit | rollback | list_versions",
  "source": { "type": "path|attachment|document", "value": "...", "id": "..." },
  "operations": [ ... ],
  "version": 3,
  "commitTarget": "overwrite|saveAs",
  "saveAsPath": "D:/..."
}
```

action 语义：

| action | 必填字段 | 语义 | Risk |
|---|---|---|---|
| `patch` | `source`, `operations` | 对工作副本施加 operations（事务性，整批成败） | LOW |
| `diff` | `source`, `from`, `to` | 返回两版本之间 diff JSON（前端渲染用） | LOW |
| `commit` | `source`, `commitTarget` | 覆盖回原路径 / 另存到 `saveAsPath`，覆盖自动 `.bak` 备份 | MEDIUM |
| `rollback` | `source`, `version` | 回滚到指定版本号（生成新版本，不删历史） | LOW |
| `list_versions` | `source` | 列版本号 / 时间 / patch 摘要 | LOW |

### 2.2 source_ref —— 三种来源统一

```json
// 本机路径
{"type": "path", "value": "D:/合同/甲方.docx"}

// 附件（Web 兜底上传）
{"type": "attachment", "id": "att-uuid"}

// 已在 documents 表的产物（Phase 2 生成的，或 P3 已 checkout 过的）
{"type": "document", "id": "doc-uuid"}
```

**首次引用处理**：LLM 第一次用 `path` / `attachment` 引用源文件时，服务端自动 checkout 到工作副本、插入 `session_documents` 行、返回 `documentId`。后续调用可用 `document` 类型复用，也可继续用 `path`（系统按 `sessionId + normalized source_path` 去重）。

**origin 语义扩展**：

| 新值 | 场景 |
|---|---|
| `user_local_file` | 首次 patch `{"type":"path"}` 时写入，标记源是本机路径 |
| `user_attachment_edited` | 首次 patch `{"type":"attachment"}` 时写入，源来自 Web 上传 |

既有 `agent_generated` / `user_upload` / `template_rendered` 不变——"原本如何产生"是稳定属性，编辑只涨 `latest_version`。

### 2.3 patch operations —— P3A 最小集

**`replace_text`（主力 90% 场景）**

```json
{
  "op": "replace_text",
  "before_context": "风险如下：",
  "target": "付款期限 30 天",
  "after_context": "，若超期",
  "new_text": "付款期限 15 天",
  "reason": "用户要求缩短付款期限"
}
```

- 定位逻辑：`before_context + target + after_context` 三段拼接后的字符串在工作副本全文中必须**恰好出现 1 次**；0 次或 >1 次均视为定位失败
- `before_context` / `after_context` 可为空字符串（适用于 target 本身已足够唯一）
- `reason` 可选，LLM 给用户看的解释，透传到 diff JSON

**`insert_paragraph_after`**

```json
{
  "op": "insert_paragraph_after",
  "anchor_paragraph_text": "第三章 违约责任",
  "new_paragraphs": [
    {"text": "新段正文", "style": "Normal"},
    {"text": "新段正文续", "style": "Normal"}
  ],
  "reason": "补充违约条款"
}
```

- 定位：`anchor_paragraph_text` 需在段落文本中**唯一匹配**（按段落整句精确匹配，不支持跨段）
- `style` 枚举：`Normal` / `Heading1` / `Heading2` / `Heading3` / `ListBullet`（对齐 `MarkdownToDocxGenerator` 可用样式集）

**`delete_paragraph`**

```json
{
  "op": "delete_paragraph",
  "paragraph_text": "完整段落文字",
  "reason": "冗余内容"
}
```

- 定位：`paragraph_text` 需唯一匹配一个段落

**`add_table_row`**

```json
{
  "op": "add_table_row",
  "table_anchor_text": "产品名称",
  "position": "end",
  "cells": ["新产品", "2026-04-21", "1000"],
  "reason": "新增一行销售记录"
}
```

- 定位：`table_anchor_text` 需在某个表格的首行单元格里唯一匹配（定位到表格本身）
- `position`: `start`（表格开头插新行）/ `end`（表格末尾追加）
- `cells` 长度必须等于表格列数，否则整批失败

**P3A 不做的 op**（延到 P3B 或后续）：`delete_table` / `insert_image` / `change_style` / 页眉页脚 / 目录 / 页码 / xlsx 全部 op。

### 2.4 冲突处理 —— 严格事务（内存回滚）

执行模型本质上是"在内存 POI 对象上依次应用 ops，全部成功才写盘"。流程：

```
1. 读工作副本当前 v{latest}.docx → 内存 XWPFDocument X
2. 按 op 顺序对 X 应用每个 op：
     - 每次 op 先在当前 X 上定位 locator
     - 定位唯一 → 改 X → 下一个 op
     - 定位 0 次或 >1 次 → 立即中止，整批失败，X 直接丢弃（未写盘等于天然回滚）
3. 所有 op 成功 → 写盘到 v{latest+1}.docx → latest_version++ → 写 document_versions 行 → 缓存 diff_json
```

**关键点**：op 之间**依次而非快照定位**——op1 改过的内容对 op2 可见（若 op2 的锚点恰是 op1 产出的文字，可正确定位；若 op1 删掉了 op2 原本要改的内容，op2 定位失败）。LLM 负责规划无冲突的 op 序列。

失败时不写盘、不涨版本、不写 document_versions 行——原子性天然保证。

**失败返回结构**：

```json
{
  "success": false,
  "error": "patch_dry_run_failed",
  "failed_ops": [
    {
      "op_index": 2,
      "op_type": "replace_text",
      "reason": "locator_not_unique",
      "match_count": 3,
      "hint": "before_context + target + after_context 在文档中出现 3 次，请缩小锚点"
    }
  ]
}
```

LLM 收到这样的错误可以看懂并重试。ReAct 框架的 retry 策略已经支持。

### 2.5 diff JSON 结构

```json
{
  "documentId": "doc-uuid",
  "fromVersion": 0,
  "toVersion": 1,
  "summary": "共 3 处修改",
  "changes": [
    {
      "patch_id": "uuid-1",
      "op": "replace_text",
      "paragraph_index": 3,
      "paragraph_preview": "第 3 段：关于付款条款...",
      "segments": [
        {"type": "keep",   "text": "付款期限"},
        {"type": "delete", "text": "30 天"},
        {"type": "insert", "text": "15 天"},
        {"type": "keep",   "text": "，若超期..."}
      ],
      "reason": "用户要求缩短付款期限"
    },
    {
      "patch_id": "uuid-2",
      "op": "insert_paragraph_after",
      "paragraph_index": 7,
      "paragraph_preview": "（新增）补充违约条款",
      "segments": [{"type": "insert", "text": "补充违约条款"}],
      "reason": "补充违约条款"
    }
  ]
}
```

diff JSON 在 `patch` action 执行完即时算出并缓存到 `document_versions.diff_json`，前端通过 `GET /api/documents/{id}/diff?from=0&to=1` 拉取，不会每次重算。

---

## 3. 保存策略

### 3.1 三种保存动作

| 动作 | 后端行为 | 前端触发 |
|---|---|---|
| **patch** | 只动工作副本，生成新版本 | LLM 调用 `document.edit(action=patch)` |
| **commit overwrite** | 把工作副本 `latest_version` 文件 copy 到 `source_path`，覆盖前自动 `.bak` 备份 | 用户在气泡卡片点"应用到原路径" |
| **commit saveAs** | 把工作副本 `latest_version` 文件 copy 到 `saveAsPath`（需过 `PathSecurityChecker`） | 用户在气泡卡片点"另存为..."（文件选择器） |

**`.bak` 命名**：`{source_path}.{紧凑时间戳}.bak`，时间戳格式 `yyyyMMddHHmmss`（无分隔符，Windows 文件名禁止 `:`），例 `D:/合同/甲方.docx.20260421153015.bak`。`.bak` 不入表、不入 message_attachments，纯文件系统副本，用户需要恢复自行 rename。

**commit 失败**：原路径 I/O 异常 / 权限拒绝 / 跨盘符失败 → 工作副本保留，返回 5xx，`.bak` 若已生成则保留。

### 3.2 rollback 语义

用户在气泡卡片点"回滚"触发 `POST /api/documents/{id}/rollback?version=N`（N 默认 0 即最初）。

- 把 `document_versions` 表里 `version_no = N` 的文件 copy 为新版本 `v{latest+1}.docx`
- `session_documents.latest_version = latest + 1`
- 写一条 `document_versions` 行，source=`rollback`，patch_summary=`"回滚到版本 N"`
- 不物理删除任何历史版本

### 3.3 丢弃语义

用户点"丢弃"触发 `DELETE /api/documents/{id}/working-copy`：

- 删除工作副本目录下所有 `v{n}.docx` 文件
- 删除 `document_versions` 里该 document 的所有行
- 删除 `session_documents` 该行
- 删除对应 `message_attachments` 行

丢弃只对"P3 引入的工作副本"适用——AI 产物（origin=agent_generated）的丢弃走原 Phase 2 逻辑，不在 P3 改动范围。

---

## 4. 数据模型

### 4.1 Flyway V13 迁移

```sql
-- Phase 3: 文档编辑与版本管理
ALTER TABLE session_documents ADD COLUMN source_path TEXT;
ALTER TABLE session_documents ADD COLUMN latest_version INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS document_versions (
    id TEXT PRIMARY KEY,
    document_id TEXT NOT NULL,
    version_no INTEGER NOT NULL,
    file_path TEXT NOT NULL,
    source TEXT NOT NULL,         -- initial | patch | rollback
    patch_summary TEXT,           -- "共 3 处修改：replace_text x2, insert_paragraph_after x1"
    diff_json TEXT,               -- 缓存前端渲染用的 diff 结构
    created_at TEXT NOT NULL,
    FOREIGN KEY (document_id) REFERENCES session_documents(id) ON DELETE CASCADE,
    UNIQUE (document_id, version_no)
);

CREATE INDEX IF NOT EXISTS idx_document_versions_document_id ON document_versions(document_id);
CREATE INDEX IF NOT EXISTS idx_document_versions_source ON document_versions(source);
```

**字段语义**：

- `session_documents.source_path`：原始本机路径。`null` 表示源非路径（附件 / AI 产物）
- `session_documents.latest_version`：当前工作副本最新版本号。0 = 未被 patch 过（对齐 Phase 2 AI 产物 default 0）
- `document_versions.source`：版本生成原因。`initial` = checkout 时的 v0；`patch` = 一次 LLM 批次；`rollback` = 用户回滚产生
- `document_versions.diff_json`：`version - 1 → version` 的 diff 缓存，前端按需读

### 4.2 工作副本文件系统布局

```
~/.zhiwei/documents/
  {sessionId}/
    {uuid}_{fileName}.docx         ← Phase 2 AI 原始产物（未被 patch 前）
    working/
      {documentId}/
        v0.docx                    ← checkout 时的初始副本
        v1.docx                    ← 第一次 patch 后
        v2.docx                    ← 第二次 patch 后
        ...
```

**路径规则**：

- 工作副本目录按 sessionId 隔离（对齐 Phase 2 约定）
- `session_documents.file_path` 在 P3 生命周期的不同阶段指向不同位置：
  - Phase 2 AI 产物尚未被 patch（`latest_version = 0`）→ 指向 `{uuid}_{fileName}.docx`
  - 一旦首次 patch 触发 checkout → 更新 `file_path` 指向 `working/{documentId}/v{latest}.docx`
- 这样前端下载 / DocumentController 下载端点无论何时读 `file_path` 都能拿到"当前最新可用文件"，无需感知是否被编辑过
- 用版本号文件名 + DB 维护 `latest_version`，不使用文件系统 symlink（Windows 不友好）
- 丢弃动作删整个 `working/{documentId}/` 目录（原始产物文件不动）

**checkout 时文件动作**：

| 源类型 | checkout 动作 |
|---|---|
| `path`（本机路径） | copy `source_path` → `working/{documentId}/v0.docx`；初始 `session_documents.file_path = v0.docx` |
| `attachment` | copy 附件物理文件 → `working/{documentId}/v0.docx`；同上 |
| `document`（Phase 2 AI 产物） | copy `{uuid}_{fileName}.docx` → `working/{documentId}/v0.docx`；同上；原产物文件保留不删（作为历史备份） |

### 4.3 与 `message_attachments` 的协同

沿用 Phase 2A 的"产物→附件"挂载模式：

- 首次 patch 完成时：在 `message_attachments` 新增一条，`file_path` = v1.docx，`file_size` = v1 字节数
- 后续 patch 完成：**更新现有 attachment 的 file_size / download URL**（downloadUrl 固定 `/api/documents/{id}/download` 不变，file_size 需 update），不新增 attachment 行
- `AgentPersistenceHandler.backfillOrphanEntryIds` 逻辑复用（entry_id 占位 + 回填）

**AttachmentRepository 新增方法**：`updateSizeByFilePath(filePath, newSize)`。

---

## 5. 后端组件分层

### 5.1 协议层

- `com.lifepilot.document.patch.DocumentPatchOperation`（sealed interface）
  - permits `ReplaceTextOp` / `InsertParagraphAfterOp` / `DeleteParagraphOp` / `AddTableRowOp`
  - 每个子类是 `record`，字段对应 2.3 的 JSON schema
- `com.lifepilot.document.patch.DocumentPatchRequest`（record）
  - `sessionId / documentId / operations`
- `com.lifepilot.document.patch.DocumentPatchResult`（record）
  - `success / newVersion / diff / failedOps`

### 5.2 执行层（docx patch engine）

- `com.lifepilot.document.patch.docx.DocxPatchEngine`
  - 主接口：`DocxPatchExecutionResult apply(XWPFDocument document, List<DocumentPatchOperation> ops)`
  - **纯内存操作**：Engine 不涉及磁盘 I/O，输入是已加载的 POI 对象，输出是改动后的同一个对象（in-place mutate）+ 应用成功的 ops 摘要 + 失败信息
  - 文件读写由 `DocumentVersionService` 负责（职责分层：Engine = 算法；Service = 状态）
  - 内部流程：依次对每个 op 定位 + 改动；任一失败立即返回 failure（调用方丢弃该 XWPFDocument）
  - 保样式关键：`replace_text` 找到横跨的 `XWPFRun` 序列后，保留首 run 的 font/bold/color/size，只替换文本内容；若跨多个 run，保留首 run 样式 + 清空后续 run 的文本
- `com.lifepilot.document.patch.docx.TextAnchorLocator`
  - 通用文本锚点定位辅助类，被 4 种 op 复用
  - 方法：`locate(XWPFDocument, before, target, after) → Optional<ParagraphRunRange>`（返回 empty 表示未唯一定位）
- `com.lifepilot.document.patch.docx.DocxDiffBuilder`
  - 输入：已应用的 ops 列表 + 定位结果
  - 输出：diff JSON（见 §2.5）
  - 优化路径：因为 ops 里已经有 locator + new_text，直接按 op 反推 diff segments，不用跑真正的 myers 算法

### 5.3 版本管理层

- `com.lifepilot.document.version.DocumentVersionService`
  - `checkout(source_ref) → DocumentId`（若未 checkout 过则复制到 working 目录 + 入表，已 checkout 过则直接返回既有 id）
  - `applyPatch(documentId, ops) → DocumentPatchResult`（调 engine + 写新版本 + 更新 latest_version）
  - `commit(documentId, target) → CommitResult`（overwrite 原路径 / saveAs 用户指定）
  - `rollback(documentId, versionNo) → DocumentPatchResult`
  - `discard(documentId)`
  - `listVersions(documentId) → List<VersionInfo>`
- `com.lifepilot.document.version.DocumentVersionRepository`
  - JdbcTemplate 风格，对齐 `SessionDocumentRepository`
  - `save / findByDocumentId / findByVersion / deleteByDocumentId`

### 5.4 工具层

- `com.lifepilot.document.tool.DocumentEditActionDispatchExecutor`
  - 对齐 `DocumentCreateActionDispatchExecutor`
  - 5 个 action 分发到 `DocxPatchEngine` / `DocumentVersionService`
- `com.lifepilot.document.tool.DocumentEditToolProvider`
  - 构建单一 `document.edit` BuiltinTool，schema 扁平
- `com.lifepilot.document.config.DocumentAutoConfiguration` 扩展
  - 新增 Bean：`DocumentVersionRepository` / `DocumentVersionService` / `DocxPatchEngine` / `DocumentEditActionDispatchExecutor` / `DocumentEditToolProvider` / `documentEditTool`（BuiltinTool）

### 5.5 端点层

`DocumentController` 新增：

| 端点 | 返回 |
|---|---|
| `GET /api/documents/{id}` | 元数据（含 latest_version / source_path / origin） |
| `GET /api/documents/{id}/versions` | `[{version_no, source, patch_summary, created_at}]` |
| `GET /api/documents/{id}/diff?from={a}&to={b}` | diff JSON（见 §2.5） |
| `GET /api/documents/{id}/download?version={v}` | 扩展既有 download 支持版本号，缺省返回 latest |
| `POST /api/documents/{id}/commit` | body: `{target: overwrite | saveAs, saveAsPath?}` |
| `POST /api/documents/{id}/rollback` | body: `{version: N}` |
| `DELETE /api/documents/{id}/working-copy` | 丢弃工作副本 |

commit / rollback / DELETE 均走 `PathSecurityChecker` 校验目标路径。

### 5.6 Guardrail 集成

- `document.edit` 工具整体 RiskLevel = MEDIUM（保守）
- 子 action 运行时级细化：
  - `patch / diff / rollback / list_versions` → 内部等价 LOW（只动 working 目录）
  - `commit overwrite` → 内部 MEDIUM，过 `PathSecurityChecker` + 记审计日志
  - `commit saveAs` → 同上
- GuardrailEngine 在 schema 决策层保持 MEDIUM 即可，不需要按 action 粒度重写 Guardrail 决策链（精度换成本不划算）

---

## 6. 前端组件

### 6.1 `DocumentDiffCard.vue`（新增）

位置：`zhiwei-web/src/components/chat/DocumentDiffCard.vue`

**展示形态**：对话气泡下折叠卡，默认收起标题"AI 改动了 {fileName}（共 X 处）"，点开展开 `changes` 列表。

**卡片结构**（展开状态）：

```
┌─────────────────────────────────────┐
│ 甲方合同.docx                       │
│ 共 3 处修改 · v0 → v1   [收起]      │
├─────────────────────────────────────┤
│ > 修改 1：付款条款                   │
│   第 3 段：关于付款条款...           │
│   付款期限 [30 天] → [15 天]，若超期  │
│   原因：用户要求缩短付款期限           │
│                                      │
│ > 修改 2：...                        │
├─────────────────────────────────────┤
│ [应用到原路径]  [另存为...]  [丢弃]  │
└─────────────────────────────────────┘
```

- `keep` segment 正常文字
- `delete` segment 红底删除线
- `insert` segment 绿底
- "应用到原路径" 仅在 `source_path != null` 时显示

### 6.2 `MessageBubble.vue`（修改）

- 识别附件 MIME + 查 `GET /api/documents/{id}` 看 `latest_version > 0 && source ∈ {patch, rollback}` → 渲染 `DocumentDiffCard`
- 非 patch 附件（如 Phase 2 生成的 agent_generated）保持原渲染

### 6.3 API 封装

`zhiwei-web/src/api/documents.ts`（新文件）：

```ts
export async function getDocument(id: string)
export async function getDocumentVersions(id: string)
export async function getDocumentDiff(id: string, from: number, to: number)
export async function commitDocument(id: string, target: 'overwrite' | 'saveAs', saveAsPath?: string)
export async function rollbackDocument(id: string, version: number)
export async function discardDocument(id: string)
```

### 6.4 P3A 不做的前端

- **版本历史列表 UI**（查看所有历史版本 + 独立回滚按钮）→ P3B
- **在线预览**（docx-preview 集成）→ Phase 4 双栏 Artifact
- **跨 session 文档浏览入口**（"我的文档"页面）→ Phase 5+

---

## 7. LLM 侧使用流程

### 7.1 典型交互

```
用户：帮我把 D:/合同/甲方.docx 的付款期限改成 15 天

LLM 内部：
  1. file.read(path="D:/合同/甲方.docx")  [Phase 0 能力]
  2. 读到"付款期限 30 天"的上下文，规划 patch
  3. document.edit(
       action="patch",
       source={type:"path", value:"D:/合同/甲方.docx"},
       operations=[{
         op:"replace_text",
         before_context:"风险如下：",
         target:"付款期限 30 天",
         after_context:"，若超期",
         new_text:"付款期限 15 天",
         reason:"缩短付款期限"
       }]
     )
  4. 工具返回 {documentId, newVersion:1, downloadUrl, diffSummary}
  5. 回答用户："已改好，diff 卡片请在上方查看，确认后点'应用到原路径'"

用户看到气泡下的 DiffCard → 点"应用到原路径"
  ↓
POST /api/documents/{id}/commit {target:"overwrite"}
  ↓
后端 copy v1.docx → D:/合同/甲方.docx（自动 .bak）
```

### 7.2 冲突重试

```
LLM: document.edit(patch, ops=[locator 不唯一的一个])
  ↓
工具返回 {success:false, failed_ops:[{reason:"locator_not_unique", match_count:3}]}
  ↓
LLM 看到错误，重新规划 locator（加长 before_context / after_context）
  ↓
重试 document.edit(patch, ops=[更精确的 locator])
```

### 7.3 工具描述要点（LLM prompt 可见）

`document.edit` 的 tool description 必须让 LLM 知道：

1. **patch 协议是文本锚点**：locator 必须在文档中唯一，建议 `before_context` / `after_context` 各带 10-30 字提高精度
2. **事务性**：一批 ops 必须全部能定位才执行
3. **保样式**：LLM 只管文本内容，不要在 op 里传样式指令（样式自动保留）
4. **先 parse 后 patch**：建议先用 `file.read` / `document.parse` 读到内容再规划 locator
5. **commit 是用户动作**：LLM 不应自动 commit，产出 patch 后让用户在前端决定

---

## 8. 测试策略

### 8.1 测试分层

| 层 | 范围 | 关键点 |
|---|---|---|
| 单元 | `DocxPatchEngine` 4 种 op | 真实 POI XWPFDocument，不 mock。用 `src/test/resources/fixtures/document/sample.docx` 作 fixture |
| 单元 | `TextAnchorLocator` | 边界：0 匹配 / 1 匹配 / 多匹配 / 跨 run / 跨段落 |
| 单元 | `DocxDiffBuilder` | 每种 op 对应的 diff segments 正确 |
| 集成 | `DocumentVersionService` | 真实 H2 / SQLite 跑 V13 迁移，checkout → patch → rollback → commit 全链 |
| 集成 | `DocumentEditActionDispatchExecutor` | Mock VersionService，测试 action 路由 + 参数校验 |
| 集成 | `DocumentController` 新端点 | MockMvc，覆盖每个端点的 200 / 400 / 404 / 403 |
| 端到端 | ChatTurn 级 | 用真实 docx fixture 走"用户问 → LLM patch → commit"完整链（在 `BrowserIngressService_*` 风格下） |

### 8.2 测试 fixture

`src/test/resources/fixtures/document/`：

- `sample-contract.docx`：小型合同，含标题 / 段落 / 1 个表格（≤20KB），供所有 op 测试
- `sample-with-styles.docx`：含加粗 / 斜体 / 有序列表 / 表格等多种样式，专门测 `replace_text` 跨 run 保样式正确

生成方式：手工构造或跑一个一次性的 `MarkdownToDocxGenerator` + 手动加样式脚本。

### 8.3 关键断言

- **保样式正确性**：patch 前后对比 `XWPFRun.isBold() / getColor() / getFontFamily()`，断言非 patch 目标 run 的样式完全不变
- **跨 run 合并**：`replace_text` 的 target 横跨 3 个 run 时，新文本合并到首 run，后续 run 清空
- **事务性**：3 个 op 里第 2 个故意让它定位失败，断言文件字节与 patch 前 100% 一致（没有半执行）
- **版本单调性**：连续 3 次 patch，`version_no` 必须 1→2→3 递增无跳号
- **丢弃幂等性**：重复 discard 同一 documentId，第二次返回 404，不抛异常

---

## 9. 风险与缓解

### 9.1 技术风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| POI 的 `XWPFRun` 拆分逻辑复杂（一段文本可能被拆成 N 个 run，`replace_text` 跨 run 时可能丢样式） | 样式保真失败 | 早做 PoC：对 `sample-with-styles.docx` 跑 10 种 `replace_text` case，验证跨 run 保样式正确率 ≥ 95%。Phase 0 分析显示 `WordParser` 已处理过 run 拼接，可反向参考 |
| 复杂 docx（含 SmartArt / 嵌入 Excel / 公式 / 目录） | 定位或写回崩 | P3A scope 明确"最小集"，这些内容在 dry-run 时只要不影响 4 种 op 定位就允许通过；写回失败返回清晰错误 |
| 大文件（100+ 页 docx，20MB+） | 慢 / OOM | P3A 设上限 10MB，超过返回 "文档过大，请分段处理"；POI 用 streaming 读（SXSSF 不适用 XWPF，但可流式段落迭代） |
| SQLite `ALTER TABLE ADD COLUMN NOT NULL` 需要 DEFAULT | V13 迁移失败 | 已在 schema 里加 `DEFAULT 0`，对齐 SQLite 方言规则 |
| `session_documents` 表并发写（多个 patch 同时改同一 doc） | latest_version 竞态 | 单用户单机场景极少并发；仍加 `WHERE latest_version = ?` 的乐观锁 update（失败则返回 409 重试） |
| 源路径跨盘符 / UNC 路径 / 权限问题 | commit 失败 | `PathSecurityChecker` 已有规则，追加"commit 目标必须是真实文件（非目录、非 link）" |

### 9.2 LLM 行为风险

| 风险 | 缓解 |
|---|---|
| LLM 不先 parse 直接 patch，locator 瞎猜 | tool description 写明"先 parse 再 patch"；文档锚点不存在时工具返回清晰错误提示"建议先读文档" |
| LLM 自动 commit（越权覆盖用户原文件） | commit 是独立 action，tool description 写明"commit 是用户动作，LLM 不应自动 commit"；RiskLevel MEDIUM 让 GuardrailEngine 审 |
| LLM 产出 locator 过短导致多次匹配 | dry-run 即刻报错，错误里附 `match_count` + 提示加长锚点 |
| LLM 错误应用 rollback 覆盖用户后续改动 | rollback 不物理删历史，生成新版本而不是重置——用户可再次回滚到最新 patch 版本 |

### 9.3 产品/体验风险

| 风险 | 缓解 |
|---|---|
| 用户不理解"工作副本"心智（以为改了原文件） | 气泡 DiffCard 明确标注"已改到工作副本 v1；未覆盖原路径，点按钮才覆盖" |
| .bak 文件堆积在用户目录 | P3A 不自动清理；未来（P5+）可加"清理历史备份"工具 |
| 丢弃工作副本后无法 undo | 丢弃前前端弹二次确认；文档化"丢弃不可恢复" |

---

## 10. P3A 文件清单（供 writing-plans 展开 task）

### 10.1 后端新建

| 路径 | 职责 |
|---|---|
| `src/main/resources/db/migration/V13__document_patch_and_versions.sql` | V13 迁移（见 §4.1） |
| `src/main/java/com/lifepilot/document/patch/DocumentPatchOperation.java` | sealed interface + 4 个 record 子类 |
| `src/main/java/com/lifepilot/document/patch/DocumentPatchRequest.java` | record |
| `src/main/java/com/lifepilot/document/patch/DocumentPatchResult.java` | record |
| `src/main/java/com/lifepilot/document/patch/docx/DocxPatchEngine.java` | POI patch 执行器（纯内存） |
| `src/main/java/com/lifepilot/document/patch/docx/TextAnchorLocator.java` | 文本锚点定位辅助 |
| `src/main/java/com/lifepilot/document/patch/docx/DocxDiffBuilder.java` | diff JSON 构造器 |
| `src/main/java/com/lifepilot/document/model/DocumentVersionRecord.java` | record（对齐 SessionDocumentRecord 风格） |
| `src/main/java/com/lifepilot/document/repository/DocumentVersionRepository.java` | JdbcTemplate Repository（对齐 SessionDocumentRepository 风格） |
| `src/main/java/com/lifepilot/document/version/DocumentVersionService.java` | checkout / patch / commit / rollback / discard / listVersions |
| `src/main/java/com/lifepilot/document/tool/DocumentEditActionDispatchExecutor.java` | 5 action 路由 |
| `src/main/java/com/lifepilot/document/tool/DocumentEditToolProvider.java` | `document.edit` BuiltinTool |

### 10.2 后端修改

| 路径 | 改动 |
|---|---|
| `src/main/java/com/lifepilot/document/model/SessionDocumentRecord.java` | 加 `sourcePath`（可空） + `latestVersion` 字段；加 origin 常量 `ORIGIN_USER_LOCAL_FILE` / `ORIGIN_USER_ATTACHMENT_EDITED` |
| `src/main/java/com/lifepilot/document/repository/SessionDocumentRepository.java` | RowMapper + INSERT / UPDATE 带新字段；加 `updateLatestVersion(id, version)` |
| `src/main/java/com/lifepilot/document/config/DocumentAutoConfiguration.java` | 加 P3 的 Bean 链（Repository / Service / Engine / Dispatcher / Provider / BuiltinTool） |
| `src/main/java/com/lifepilot/interaction/web/controller/DocumentController.java` | 加 5 个端点（§5.5） |
| `src/main/java/com/lifepilot/interaction/web/repository/AttachmentRepository.java` | 加 `updateSizeByFilePath(filePath, newSize)` |
| `src/main/resources/application.yml` | `core-tool-ids` 追加 `document.edit` |

### 10.3 前端新建

| 路径 | 职责 |
|---|---|
| `zhiwei-web/src/components/chat/DocumentDiffCard.vue` | 对话气泡内 diff 卡片 |
| `zhiwei-web/src/api/documents.ts` | REST API 封装 |

### 10.4 前端修改

| 路径 | 改动 |
|---|---|
| `zhiwei-web/src/components/chat/MessageBubble.vue` | 识别 patch 类附件渲染 `DocumentDiffCard` |

### 10.5 测试新建

| 路径 |
|---|
| `src/test/resources/fixtures/document/sample-contract.docx` |
| `src/test/resources/fixtures/document/sample-with-styles.docx` |
| `src/test/java/com/lifepilot/document/patch/docx/DocxPatchEngine_四种操作测试.java` |
| `src/test/java/com/lifepilot/document/patch/docx/TextAnchorLocator_定位测试.java` |
| `src/test/java/com/lifepilot/document/patch/docx/DocxDiffBuilder_diff构造测试.java` |
| `src/test/java/com/lifepilot/document/version/DocumentVersionRepository_持久化测试.java` |
| `src/test/java/com/lifepilot/document/version/DocumentVersionService_生命周期测试.java` |
| `src/test/java/com/lifepilot/document/tool/DocumentEditActionDispatchExecutor_路由测试.java` |
| `src/test/java/com/lifepilot/interaction/web/controller/DocumentController_P3端点测试.java` |

### 10.6 不动清单

- `knowledge/parser/` 任意文件
- `MarkdownToDocxGenerator` / `StructuredDataToXlsxGenerator` / `OutlineToPptxGenerator`（Phase 2 既有生成器）
- `AbstractDocumentCreateToolExecutor`（create 专用模板方法，patch 不复用）
- `DocumentCreateActionDispatchExecutor` / `DocumentCreateDocxToolExecutor` / ...（Phase 2 既有 executor）

---

## 11. 与蓝图的对应

| 蓝图原文 | P3A 落地 |
|---|---|
| §5.3 "两种模式：A 内容替换 / B 结构化编辑" | 采用 B 的文本锚点变体，每个 op 含 locator + 新内容 |
| §5.3 "每次编辑生成新版本（document_versions 表）" | 同 V13 迁移 |
| §5.3 "支持 docx 的内容级 diff（按段落比较）" | 混合：段落粒度定位 + 段内 inline segments |
| §6 D4 "推荐 C 为主 + B 作为兜底" | P3A 用 B（文本锚点）落地；C（结构化 JSON Patch）不做 |
| §6 D5 "推荐 B 为目标" | P3A 聚焦内容正确与样式保留；专业排版留后续 |
| §6 D8 "推荐 A 单机版本管理" | `document_versions` 表落地 |
| §6 D10 "推荐 B + 前端 diff 预览" | patch MEDIUM、commit MEDIUM + 前端 DiffCard 作用户确认卡点 |
| §8 Phase 3 "默认'另存新版本'，用户确认后'覆盖'" | 工作副本 + 显式 commit + overwrite / saveAs 两支 |
| §9.1 "LLM patch 准确性" | tool description 要求 before/after context ≥ 10-30 字 + dry-run 事务性 + match_count 错误提示 |

---

## 12. 待 P3B 的延期项

- xlsx patch ops：`update_cell` / `insert_row` / `delete_row` / `set_range` / `add_formula`
- cell 级 diff 粒度适配 + `DocumentXlsxDiffCard.vue`
- xlsx 保留公式 / 合并单元格 / 样式的 POI 实现
- 版本历史列表 UI（列所有 versions + 独立回滚按钮）
- 单条 patch 粒度的 cherry-pick（暂不做，P3B 再议）

---

**结束**。本 spec 为 Phase 3 设计规格。正式实施前按 `superpowers:writing-plans` 拆出 P3A 的逐任务 plan，逐任务用 `subagent-driven-development` 执行 + per-task code-review。
