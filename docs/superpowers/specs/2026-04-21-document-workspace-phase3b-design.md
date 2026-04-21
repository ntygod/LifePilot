---
title: 文档工作空间 Phase 3B — xlsx 编辑与 Diff（面向 LLM）
status: draft
owner: zsg
date: 2026-04-21
scope: P3A 的协议骨架横向复制到 xlsx，补齐版本历史 UI
parent: docs/superpowers/specs/2026-04-21-document-workspace-phase3-design.md（§1.3、§12）
predecessor: docs/superpowers/plans/2026-04-21-document-workspace-phase3a.md（已完成 16 Task）
---

# 文档工作空间 Phase 3B — xlsx 编辑与 Diff（面向 LLM）

> 本文档是 Phase 3B 的**设计规格**（spec），实施前按 `superpowers:writing-plans` 拆出可执行的 plan。
> Phase 3A 已打穿 docx 全链路；P3B 基于同一套 `session_documents` / `document_versions` /
> `DocumentVersionService` / `DocumentController` / `document.edit` 工具与 V13 迁移，**不引入新表**。

---

## 1. 定位

### 1.1 横向复制策略

P3B 的核心原则：**复用 P3A 已建的协议骨架，只为 xlsx 增加引擎和前端 diff 渲染**。

- 沿用 `document.edit` 单一工具 + 5 个 action（`patch / diff / commit / rollback / list_versions`）
- 沿用 `source_ref` 三选一（`path` / `attachment` / `document`）
- 沿用 checkout → patch → commit / rollback / discard 生命周期
- 沿用 `document_versions.diff_json` 缓存 + 前端按需拉取
- 不新增 REST 端点、不新增数据库表、不新增 Flyway 迁移

### 1.2 P3B 交付范围

**包含（4 个 op + 共享 UI 子件）**：

1. xlsx patch ops：`update_cell / insert_row / delete_row / set_range`（共 4 个 op）
2. cell 级 diff 粒度 + `DocumentXlsxDiffCard.vue`（对话气泡内折叠卡，对齐 docx DiffCard 交互）
3. 公式保留：`update_cell` new_value 以 `=` 开头视为公式，否则视为字面量（折入 update_cell，不单列 `add_formula`）
4. 合并单元格与样式保留：refuse 改动合并区域内部单元格（仅允许改 anchor）；style 走 POI 默认保留
5. 版本历史 UI：共享子件 `DocumentVersionHistoryList.vue`，docx + xlsx DiffCard 都嵌入

**排除**（延到后续）：

- `add_formula` 独立 op（与 update_cell 重叠）
- sheet 级操作（新建 / 删除 / 重命名 sheet）
- 合并单元格本身的解除 / 建立
- 图表 / 透视表 / 图片 / VBA / 命名区域 / 数据验证规则
- 列宽 / 行高 / 冻结窗口调整
- 单条 patch 粒度的 cherry-pick（P3A spec §12 已列延期，P3B 仍不做）

### 1.3 与 P3A 的差异摘要

| 维度 | P3A（docx） | P3B（xlsx） |
|---|---|---|
| 定位语义 | 文本锚点（before/target/after 唯一匹配） | A1 地址锚点（Sheet1!B5 / Sheet1!B2:D4） |
| POI 对象 | `XWPFDocument` | `XSSFWorkbook` |
| diff 粒度 | 段落 + inline segment | 单元格 + 双列 before/after |
| op 数 | 4（replace_text / insert_paragraph_after / delete_paragraph / add_table_row） | 4（update_cell / insert_row / delete_row / set_range） |
| DiffCard | `DocumentDiffCard.vue` | `DocumentXlsxDiffCard.vue` |
| 历史 UI | P3A 未做，P3B 一并补 | P3B 新增 |
| sealed interface | 保留（但会被重构为子接口） | 新增 `XlsxPatchOperation` 子接口 |
| 工作副本文件名 | `v{n}.docx` | `v{n}.xlsx` |

---

## 2. 协议设计

### 2.1 扩展 `document.edit` 工具描述

`DocumentEditToolProvider` 的 `buildDescription` 和 schema 的 `operations.description` 需要扩展，把 xlsx 的 4 个 op 也列进去。单工具 + 扁平 schema 保持不变；LLM 根据源文件扩展名 / MIME 自行选用哪套 op。

`RiskLevel` 仍为 MEDIUM；action 级 risk 沿用 P3A（`patch / diff / rollback / list_versions` 内部 LOW；`commit` MEDIUM）。

### 2.2 patch operations —— P3B 最小集

**`update_cell`（主力 60% 场景）**

```json
{
  "op": "update_cell",
  "sheet": "Sheet1",
  "cell": "B5",
  "new_value": "15",
  "reason": "缩短付款期限"
}
```

- `sheet` 必填：工作表名称（区分大小写，与 xlsx 内的 sheet name 精确匹配）
- `cell`：A1 notation 单元格地址（`B5` / `AA12`），大小写不敏感
- `new_value`：**多态**（JSON number / boolean / string / null），保留原始 JSON 类型：
  - `Number` → numeric cell（POI `cell.setCellValue(double)`）
  - `Boolean` → boolean cell
  - `String` 以 `=` 开头 → 公式（`cell.setCellFormula`，剥掉 `=` 前缀后送入 POI）
  - `String` 其它 → 字符串字面量
  - `null` / 缺失 → 清空 cell（`cell.setBlank()`）
- **合并单元格规则**：若 `cell` 落在合并区域内**但不是 anchor**（左上角），直接 reject：`failedOp.reason = "cell_inside_merged_region"`，hint 指引改用 anchor 地址；若 `cell` 恰是 anchor，允许修改（POI 行为：修改 anchor 在合并区域内仍生效）
- **样式保留**：`setCellValue` / `setCellFormula` 不清除 CellStyle，字体 / 填充 / 边框 / 数字格式原样保留

**`insert_row`**

```json
{
  "op": "insert_row",
  "sheet": "Sheet1",
  "before_row": 5,
  "values": ["新产品", 100, "2026-04-21"],
  "reason": "新增销售记录"
}
```

- `before_row`：1-based 行号；在该行**之前**插入一行，原第 `before_row` 行及之后的行下移
- `values` 长度自由（允许短于或等于当前 sheet 的有效列数），对齐到第 0 列起依次写入；值类型处理同 `update_cell`
- 下方行的**公式引用**会通过 POI `sheet.shiftRows` 自动刷新（`=A5 → =A6`）
- 样式：新插入的行默认无样式；若上一行有 CellStyle 则自动继承（POI 默认行为）
- **边界**：`before_row < 1` → `invalid_row_number`；`before_row > 已有最大行 + 1` → `invalid_row_number`（允许等于最大行+1，即追加一行）

**`delete_row`**

```json
{
  "op": "delete_row",
  "sheet": "Sheet1",
  "row": 8,
  "reason": "删除过期条款"
}
```

- `row`：1-based 行号；删除该行并将后续行上移（POI `sheet.shiftRows` 自动刷新公式引用）
- **边界**：`row` 不存在或 > 已有最大行 → `row_not_found`
- **合并单元格**：若目标行横跨合并区域，拒绝删除并返回 `row_in_merged_region`（避免合并区残留）

**`set_range`**

```json
{
  "op": "set_range",
  "sheet": "Sheet1",
  "range": "B2:D4",
  "values": [
    ["a1", "a2", "a3"],
    ["b1", "b2", "b3"],
    ["c1", "c2", "c3"]
  ],
  "reason": "批量填充季度数据"
}
```

- `range`：A1 notation 区域地址（`B2:D4`）；左上和右下都必填
- `values`：2D 数组；外层长度必须等于 `range` 行数，每行长度必须等于 `range` 列数；否则 `range_size_mismatch`
- 对区域内每个 cell 按 `update_cell` 规则写入（公式前缀 / 类型 / 样式保留）
- **合并单元格**：区域内若有合并单元格，按"合并区域内部非 anchor" 规则 reject 整批（`range_contains_merged_region`）

### 2.3 失败返回扩展

`DocumentPatchResult.failedOps` 结构沿用 P3A，新增 reason 枚举值（以下在 xlsx 场景可能返回）：

| reason | 场景 | hint（微调用）                                         |
|---|---|---|
| `sheet_not_found` | `sheet` 未命中                                            | "工作表 `{name}` 不存在，请确认名称精确（区分大小写）" |
| `invalid_cell_address` | `cell` 不是合法 A1 notation                           | "cell 地址格式错误：`{value}`" |
| `invalid_range` | `range` 无法解析 / 左右颠倒                                  | "range 地址非法：`{value}`" |
| `invalid_row_number` | `before_row` / `row` 越界                                | "行号 {n} 越界，合法范围 1..{max+1}" |
| `cell_inside_merged_region` | 目标 cell 在合并区域内部（非 anchor）               | "目标单元格在合并区域内，请改用 anchor 地址 `{anchorRef}` 或先解除合并" |
| `row_in_merged_region` | 要删的行横跨合并区域                                    | "目标行横跨合并区域 {ref}，无法删除" |
| `range_size_mismatch` | `values` 维度与 `range` 不匹配                          | "values 形状 {rxc} 与 range 尺寸 {rxc} 不符" |
| `range_contains_merged_region` | set_range 区域包含合并单元格                     | "range {ref} 包含合并区域 {mergedRefs}，请拆分操作" |
| `row_not_found` | 删除 / 引用的行不存在                                       | "行 {n} 不存在" |

### 2.4 diff JSON 结构（xlsx 变体）

沿用 P3A §2.5 顶层 schema，`changes[].segments` 语义与 P3A 一致（`keep` / `delete` / `insert`），增加 xlsx 特有的字段：

```json
{
  "documentId": "doc-uuid",
  "fromVersion": 0,
  "toVersion": 1,
  "summary": "共 4 处修改(update_cell x2, insert_row x1, set_range x1)",
  "mime": "xlsx",
  "changes": [
    {
      "patch_id": "uuid-1",
      "op": "update_cell",
      "sheet": "Sheet1",
      "cell": "B5",
      "segments": [
        {"type": "delete", "text": "30"},
        {"type": "insert", "text": "15"}
      ],
      "reason": "缩短付款期限"
    },
    {
      "patch_id": "uuid-2",
      "op": "insert_row",
      "sheet": "Sheet1",
      "row": 5,
      "segments": [{"type": "insert", "text": "新产品 | 100 | 2026-04-21"}],
      "reason": "新增销售记录"
    },
    {
      "patch_id": "uuid-3",
      "op": "delete_row",
      "sheet": "Sheet1",
      "row": 8,
      "segments": [{"type": "delete", "text": "过期条款行（原 A8:D8 预览）"}],
      "reason": "删除过期条款"
    },
    {
      "patch_id": "uuid-4",
      "op": "set_range",
      "sheet": "Sheet1",
      "range": "B2:D4",
      "rows": 3, "cols": 3,
      "segments": [{"type": "insert", "text": "3x3 批量填充（预览略）"}],
      "reason": "批量填充季度数据"
    }
  ]
}
```

**顶层新增字段 `mime`**：标识该 diff 是 docx 还是 xlsx，前端据此挑 DiffCard 组件（也用于 P3A 既有 diff 的向后兼容 —— 缺省当 docx）。

**变化记录优化：对 `update_cell` 额外保存 `before` 值**（供 UI 渲染 `delete` 段）。建器（`XlsxDiffBuilder`）在构造时拿到 ops 应用前的 snapshot，否则无法生成准确的 `delete.text`。P3A 的 docx 版无此需求（op 自带 `target` 即 before text），xlsx 需 engine 配合回传 before snapshot。

### 2.5 执行模型

事务性语义与 P3A 一致（op 依次应用，全部成功才写盘；任一失败立即中止并丢弃内存副本）：

```
1. 读工作副本 v{latest}.xlsx → 内存 XSSFWorkbook W
2. 按 op 顺序对 W 应用每个 op：
     - 校验 sheet / 地址 / 行号 / 合并区域
     - 记录 before snapshot（update_cell / set_range / delete_row 需要）
     - 改动 W → 下一个 op
     - 任一失败 → 整批中止，W 直接丢弃
3. 所有 op 成功 → 写盘到 v{latest+1}.xlsx → latest_version++ → 写 document_versions 行 → 缓存 diff_json
```

---

## 3. 保存策略

沿用 P3A §3（三种动作、`.bak` 规则、rollback / discard 语义），无改动。

**`.bak` 文件名模板**：`{source_path}.{yyyyMMddHHmmss}.bak` 适用于 docx 和 xlsx；xlsx 场景仅扩展名差异（如 `销售数据.xlsx.20260421153015.bak`）。

---

## 4. 数据模型

**不新增迁移**。沿用 V13（Phase 3A 已提供 `session_documents.source_path` / `latest_version` + `document_versions` 表）。

**`session_documents.mime_type`** 字段直接承载 xlsx 的 MIME（`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`），无需区分字段。

**工作副本目录布局**沿用，扩展名按实际文档类型：

```
~/.zhiwei/documents/
  {sessionId}/
    working/
      {documentId}/
        v0.xlsx                    ← xlsx 场景的初始副本
        v1.xlsx
        ...
        v0.docx                    ← docx 场景（互斥，一个 document 只有一种扩展名）
```

---

## 5. 后端组件分层

### 5.1 协议层重构（兼容 P3A）

**问题**：P3A 的 `DocumentPatchOperation` 是 sealed interface，permits 4 个 docx op。若直接扩展 permits 加入 xlsx 4 op，`DocxPatchEngine.apply` 的 switch 会变成非穷尽（因为多了 4 个 xlsx 分支），需要加 default 分支丢弃；xlsx engine 同理。这样每个 engine 都要处理"不属于自己的 op"，边界糊。

**方案**：`DocumentPatchOperation` 仍 sealed，但 permits **收窄到两个子 sealed 接口**，下探一层再 permits 具体 record：

```java
// 顶层：仍 sealed，permits 收到 2 个子接口
public sealed interface DocumentPatchOperation
    permits DocxPatchOperation, XlsxPatchOperation {
    String reason();
}

// docx 独占
public sealed interface DocxPatchOperation extends DocumentPatchOperation
    permits ReplaceTextOp, InsertParagraphAfterOp, DeleteParagraphOp, AddTableRowOp {}

// xlsx 独占
public sealed interface XlsxPatchOperation extends DocumentPatchOperation
    permits UpdateCellOp, InsertRowOp, DeleteRowOp, SetRangeOp {}
```

- P3A 既有 4 个 op record 的 `implements` 改成 `DocxPatchOperation`
- `DocxPatchEngine.apply(XWPFDocument, List<DocxPatchOperation>)` —— 类型参数收窄，switch 对 `DocxPatchOperation` 穷尽
- `XlsxPatchEngine.apply(XSSFWorkbook, List<XlsxPatchOperation>)` —— 镜像 docx engine 的签名
- 入参在 `DocumentVersionService` / Dispatcher 层按 MIME 决定投喂哪个 engine，不在同一批混用
- 顶层 `DocumentPatchOperation` 仍 sealed，Dispatcher 的 switch-on-DocumentPatchOperation 也保持穷尽（两个子接口分支）

### 5.2 新增 xlsx op record

| record | 字段 |
|---|---|
| `UpdateCellOp` | `String sheet, String cell, Object newValue, String reason` |
| `InsertRowOp` | `String sheet, int beforeRow, List<Object> values, String reason` |
| `DeleteRowOp` | `String sheet, int row, String reason` |
| `SetRangeOp` | `String sheet, String range, List<List<Object>> values, String reason` |

`newValue` / `values` 字段统一用 `Object`（兼容 JSON number / boolean / string / null），不做早期字符串拍扁；`DocumentEditActionDispatchExecutor` 解析时保留原类型。

### 5.3 执行层（xlsx patch engine）

新增包 `com.lifepilot.document.patch.xlsx`：

- `CellAddressResolver`：静态工具，把 `"Sheet1"` + `"B5"` / `"B2:D4"` 解析到 POI 的 `CellReference` / `CellRangeAddress`。校验合法性，大小写不敏感。
- `XlsxPatchEngine`：接口镜像 DocxPatchEngine。`EngineResult apply(XSSFWorkbook wb, List<XlsxPatchOperation> ops)`；内部 switch 4 种 op。
  - `update_cell`：查 sheet → 定位 cell → 合并区域校验 → 记录 before snapshot → 写值
  - `insert_row`：查 sheet → 校验行号 → `sheet.shiftRows(beforeRow-1, lastRow, 1)` → 在 `beforeRow-1` 处写 `values`
  - `delete_row`：查 sheet → 校验行号 / 合并区域 → 记录 before 行内容 → `sheet.shiftRows(row, lastRow, -1)`
  - `set_range`：解析 range → 合并区域校验 → 对每 cell 走 update_cell 内部路径
  - before snapshot 通过 `AppliedXlsxOp`（对齐 `AppliedOp` 风格）回传给 DiffBuilder
- `XlsxDiffBuilder`：与 `DocxDiffBuilder` 平级。`build(documentId, from, to, appliedOps) → diffJson`。在 changes 顶层带上 `mime: "xlsx"`。
- `AppliedXlsxOp`：record，持有原 op + before snapshot（update_cell / set_range 的 before 字符串；delete_row 的被删行 preview；insert_row 无 before）

### 5.4 版本管理层改造

`DocumentVersionService` 改造要点：

1. **工作副本扩展名参数化**：
   ```java
   private static String workingExtension(String mimeType) {
       return switch (mimeType) {
           case DOCX_MIME -> ".docx";
           case XLSX_MIME -> ".xlsx";
           default -> throw new IllegalArgumentException("不支持的 MIME：" + mimeType);
       };
   }
   private Path workingPath(SessionDocumentRecord record, int version) {
       return Paths.get(storageDir, record.sessionId(), "working", record.id(),
           "v" + version + workingExtension(record.mimeType()));
   }
   ```
2. **checkout 路径识别**：`checkoutFromPath` / `checkoutFromAttachment` 由 "只接受 .docx" 扩展成接受 `.docx` 和 `.xlsx`；根据扩展名推断 MIME。
3. **applyPatch 按 MIME 分支**：
   ```java
   var engineResult = switch (record.mimeType()) {
       case DOCX_MIME -> applyDocxPatch(record, castDocxOps(ops));
       case XLSX_MIME -> applyXlsxPatch(record, castXlsxOps(ops));
       default -> throw new IllegalStateException("不支持的 MIME：" + record.mimeType());
   };
   ```
   `castDocxOps` / `castXlsxOps` 做运行时 instanceof 检查，拒绝跨类型混用（返回结构化 error）。
4. 注入新增依赖：`XlsxPatchEngine` / `XlsxDiffBuilder`。构造函数签名扩大。
5. `DOCX_MIME` 常量提取为 public 静态（或挪到 `SessionDocumentRecord`），`XLSX_MIME` 新增。

### 5.5 工具层

`DocumentEditActionDispatchExecutor`：

- `parseOneOp` 扩展新 4 个 case（`update_cell / insert_row / delete_row / set_range`），返回 `XlsxPatchOperation` 子类型
- 扩展 `parseOperations` 允许混合（但会被 service 层 reject 跨 MIME 混用——由 service 层集中校验）
- 所有 op 统一回流到 `DocumentPatchOperation` 列表（标记接口），由 service 按 MIME 分发

`DocumentEditToolProvider`：

- `buildDescription` 扩展一段 xlsx 段：说明 4 个 op 的 locator（A1 notation）、公式语义（`=` 前缀）、合并单元格拒写、values 类型处理
- schema 的 `operations.description` 补全 xlsx 4 个 op 的字段模板

### 5.6 AutoConfiguration

`DocumentAutoConfiguration` 增 3 个 @Bean：`XlsxPatchEngine` / `XlsxDiffBuilder` / (`CellAddressResolver` 若需单独 Bean，否则 static 方法)。`DocumentVersionService` 的 @Bean 签名加两个依赖。

### 5.7 Controller

**`DocumentController` 不变**。所有新能力通过工具或前端直接读写的既有端点完成：
- 元数据、版本列表、diff、commit、rollback、download、discardWorkingCopy 对 xlsx 同样有效（都是 document-id 驱动，不区分 MIME）
- 前端通过 `getDocument(id).mimeType` 自行判断用哪个 DiffCard 组件

### 5.8 Guardrail

沿用 P3A：工具 level MEDIUM，action 级别 write/read 已在 dispatcher register；`PathSecurityChecker` 校验 commit 目标路径，xlsx 与 docx 共用规则。

---

## 6. 前端组件

### 6.1 新增 `DocumentXlsxDiffCard.vue`

位置：`zhiwei-web/src/components/chat/DocumentXlsxDiffCard.vue`

**展示形态**：对话气泡下折叠卡，对齐 docx DiffCard 交互。

**卡片结构**（展开态 ASCII 示意）：

```
┌─────────────────────────────────────────────┐
│ 销售数据.xlsx                                │
│ 共 4 处修改 · v0 → v1             [收起]   │
├─────────────────────────────────────────────┤
│ > 修改 1：update_cell  Sheet1!B5            │
│   [30]   →   [15]                           │
│   原因：缩短付款期限                         │
│                                             │
│ > 修改 2：insert_row  Sheet1!row 5          │
│   新增：新产品 | 100 | 2026-04-21           │
│                                             │
│ > 修改 3：delete_row  Sheet1!row 8          │
│   删除：过期条款行（原 A8:D8 预览）          │
│                                             │
│ > 修改 4：set_range  Sheet1!B2:D4 (3x3)    │
│   批量填充（预览略）                         │
├─────────────────────────────────────────────┤
│ ► 版本历史 (3 个版本)                       │
│   [展开时挂 DocumentVersionHistoryList]     │
├─────────────────────────────────────────────┤
│ [应用到原路径]  [另存为...]  [丢弃]         │
└─────────────────────────────────────────────┘
```

**差异点（vs docx DiffCard）**：

- 每条 change 显示 `sheet!地址` 而非段落索引
- `update_cell` 用双列 `[before] → [after]`，不走 inline 段落 segment
- `insert_row` / `delete_row` 文本保持单向（insert 绿 / delete 红）
- `set_range` 只显示 `rows x cols` 规模和摘要，不展开 2D 数据（避免信息过载）
- 嵌入共享的 `DocumentVersionHistoryList.vue`（`props = { documentId }`）
- 底部三按钮与 docx DiffCard 一致（应用到原路径 / 另存为 / 丢弃）

**复用策略**：两个 DiffCard 的按钮组 / 标题栏 / 折叠逻辑由 P3A 的 `DocumentDiffCard.vue` 提炼子件复用，避免 copy-paste drift。具体：
- 提炼 `DocumentDiffHeader.vue`（标题 + 版本号 + 收/展按钮）
- 提炼 `DocumentDiffActions.vue`（三按钮 + 确认弹窗）

docx DiffCard 同步接入这两个子件（轻度重构）。

### 6.2 新增 `DocumentVersionHistoryList.vue`（共享，docx + xlsx 都挂）

位置：`zhiwei-web/src/components/chat/DocumentVersionHistoryList.vue`

**职责**：展示某文档的历史版本，支持"回滚到此版本"操作。docx DiffCard 与 xlsx DiffCard 都通过 `<DocumentVersionHistoryList :document-id />` 嵌入，点击展开时再拉 `listVersions`。

**展示形态**（初始收起，点击展开）：

```
┌─────────────────────────────────────────────┐
│ v3 · patch · 共 2 处修改  · 2 分钟前  [回滚] │
│ v2 · patch · 共 1 处修改  · 5 分钟前  [回滚] │
│ v1 · patch · 共 3 处修改  · 8 分钟前  [回滚] │
│ v0 · initial ·           · 10 分钟前 [回滚] │
└─────────────────────────────────────────────┘
```

**实现**：

- onMounted 调用 `listVersions(documentId)`（api/documents.ts 已存在）
- 点击回滚 → `rollback(documentId, version)` → 成功后 emit `rollback-complete` 事件，父组件重载 diff
- 当前 `latestVersion` 不显示"回滚"按钮（回滚到当前无意义）

### 6.3 修改 `MessageBubble.vue`

识别 MIME 分支：

```ts
function isEditedXlsx(att): boolean {
  if (!att.type?.includes('spreadsheetml.sheet')) return false
  // 同 isEditedDocx 的 docId + latestVersion 检查
  ...
}
```

模板分支：

```html
<DocumentDiffCard v-if="isEditedDocx(att)" :document-id="..." />
<DocumentXlsxDiffCard v-else-if="isEditedXlsx(att)" :document-id="..." />
<!-- 其余走原附件卡片 -->
```

### 6.4 `api/documents.ts` 扩展

**结构调整**：

- `DiffChange` 增加可选字段 `sheet?: string`、`cell?: string`、`range?: string`、`row?: number`、`rows?: number`、`cols?: number`（TS interface 加可选字段，兼容 docx 的段落字段）
- `DiffPayload` 增加 `mime?: 'docx' | 'xlsx'`（默认 docx）

**新函数**：无。`listVersions` / `rollback` 已存在。

### 6.5 测试

- `DocumentXlsxDiffCard.spec.ts`：挂载组件 + mock `getDocument` / `getDiff` 返回 xlsx diff → 断言 DOM 渲染
- `DocumentVersionHistoryList.spec.ts`：mock `listVersions` + rollback → 点击断言

---

## 7. LLM 侧使用流程

### 7.1 典型交互

```
用户：帮我把 D:/销售/Q1.xlsx 里 Sheet1 的 B5 改成 15

LLM 内部：
  1. file.read(path="D:/销售/Q1.xlsx")  [Phase 0/1B 的 ExcelParser 返回结构化数据]
  2. 读到 Sheet1!B5 当前为 30
  3. document.edit(
       action="patch",
       source={type:"path", value:"D:/销售/Q1.xlsx"},
       operations=[{
         op:"update_cell",
         sheet:"Sheet1",
         cell:"B5",
         new_value:"15",
         reason:"用户要求"
       }]
     )
  4. 工具返回 {documentId, newVersion:1, downloadUrl, summary:"共 1 处修改"}
  5. 回答用户：已改好，diff 卡片见上方

用户点"应用到原路径" → commit overwrite → 自动 .bak
```

### 7.2 冲突 / 错误重试

```
LLM 写 update_cell sheet="sheet1"  （小写 s）
  ↓
服务端返回 {success:false, failed_ops:[{reason:"sheet_not_found",
   hint:"工作表 sheet1 不存在，请确认名称精确（区分大小写）"}]}
  ↓
LLM 改用 "Sheet1" 重试
```

### 7.3 LLM 描述要点补充

在 P3A 已有的 5 条要点基础上追加：

6. **xlsx 地址必须精确**：`sheet` 名称区分大小写、`cell` 用 A1 notation（`B5` / `AA12`）、`range` 用 `B2:D4`
7. **公式用 `=` 前缀**：`new_value="=SUM(A1:A4)"` 等价于公式；`new_value="100"` 等价于字面量；LLM 要根据意图选对
8. **合并单元格慎改**：若 parse 结果显示某区域是合并单元格，仅允许改动 anchor（左上角）；中间或右下的地址会被拒绝
9. **批量优先 set_range**：改一整行 / 一整列 / 一块矩形区域时用 `set_range` 而非多个 `update_cell`，节省 token 且定位更清晰

---

## 8. 测试策略

### 8.1 测试分层

| 层 | 范围 | 关键点 |
|---|---|---|
| 单元 | `CellAddressResolver` | A1 解析、`Sheet1!B5`、range、合法性、大小写 |
| 单元 | `XlsxPatchEngine` 4 种 op | 真实 POI `XSSFWorkbook`；fixture xlsx |
| 单元 | `XlsxDiffBuilder` | 4 种 op 的 diff JSON 结构、before snapshot 正确 |
| 集成 | `DocumentVersionService` | 真实 SQLite；docx + xlsx 交叉回归（同一份 Service 对两类 MIME 都生效） |
| 集成 | `DocumentEditActionDispatchExecutor` | parseOneOp 对 xlsx 4 个 op；跨 MIME 混用被拒 |
| 集成 | `DocumentController` | xlsx 场景下所有既有端点 200；`?version=` 下载历史 xlsx |
| 装配 | `DocumentAutoConfiguration` | xlsx engine + diffBuilder + Service 链路装配成功 |
| 端到端 | 对话级 | 真实 xlsx fixture 走"用户问 → LLM patch → diff → commit overwrite" |
| 前端 | `DocumentXlsxDiffCard.spec.ts` + `DocumentVersionHistoryList.spec.ts` | 挂载 + mock API + 断言 |

### 8.2 测试 fixture

`src/test/resources/fixtures/document/`：

- `sample-sheet.xlsx`：最小 xlsx，2 个 sheet（Sheet1 / Sheet2），每个 sheet 5 行 3 列字面量，供所有 op 冒烟
- `sample-with-formulas.xlsx`：含公式（`=SUM(A1:A4)`）、合并单元格（`B2:C2`）、数字格式（货币 / 日期）、样式（粗体 / 底色），专门测公式 / 合并 / 样式保留
- fixture 由一个一次性的 `XlsxFixtureBuilder`（test 目录工具类）生成到 `target/test-fixtures/`，或手工制作并 checked-in

### 8.3 关键断言

- **update_cell 公式**：`new_value="=SUM(A1:A4)"` 写入后 `cell.getCellType() == FORMULA` 且 `cell.getCellFormula()` 文本匹配
- **update_cell 类型**：`new_value=100`（JSON number） → `getNumericCellValue() == 100.0`；`new_value=true` → `getBooleanCellValue() == true`；`new_value="hello"` → 字符串 cell；`new_value="=SUM(A1:A4)"` → formula cell；`new_value=null` → blank cell
- **update_cell 字符串不回退数字**：`new_value="100"`（JSON string）应保持字符串 cell，不被自动转成 numeric（防止 LLM 传订单号 `"001"` 被误读为 1）
- **样式保留**：update_cell 前后对比 `CellStyle` 的 `getFillForegroundColor()` / `getFont()` 等——应完全一致
- **insert_row 公式刷新**：插入行后，下方本来引用 `=A5` 的 cell 应变为 `=A6`（POI `shiftRows` 的既有行为）
- **合并单元格拒绝**：update_cell 指向合并区域中间 → failedOp.reason = `cell_inside_merged_region`，文件字节与 patch 前 100% 一致
- **跨 MIME 混用拒绝**：往 xlsx 文档传 docx op → Service 层 reject，不进 engine
- **事务性**：3 个 op，第 2 个定位失败 → 文件字节与 patch 前 100% 一致
- **版本单调 + 历史完整**：连续 3 次 patch，listVersions 返回 v0..v3；回滚到 v1 后 listVersions 返回 v0..v4（v4 是 rollback 生成）

---

## 9. 风险与缓解

### 9.1 技术风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| POI `shiftRows` 对含公式 / 合并单元格的 sheet 可能产生意外效果 | 插入 / 删除行后结构错乱 | 早做 PoC：对 `sample-with-formulas.xlsx` 跑 10 种 shift case；insert_row / delete_row 在合并区域场景直接拒绝（已在协议里卡住） |
| 大 xlsx（100k+ cell，多 sheet）读写慢 / OOM | 性能退化 | P3A 已有 10MB 上限，P3B 沿用。POI `XSSFWorkbook` 默认全量加载，若确需更大用 `SXSSFWorkbook` 的只读流模式（读取后转 XSSF 再编辑）—— P3B 不做，留延期 |
| CellStyle 在跨 workbook 场景被 POI 视为 immutable | 修改值后 style 丢失 | `setCellValue` 本身不碰 CellStyle；测试覆盖多样本（含 merged / styled）验证 |
| `CellReference` 对 `$` 绝对引用前缀 / 大写小写的处理 | LLM 输入 `$B$5` 被解析失败 | `CellAddressResolver` 统一剥离 `$` 前缀并 upper-case 列字母 |
| `SXSSFWorkbook` 不支持 `shiftRows` | 与上一条冲突 | 不采用 SXSSF，固守 XSSFWorkbook |
| 公式引用删除行后变成 `#REF!` | 公式损坏 | 由 POI shiftRows 自动处理，大多数情形可正确更新；无法更新的情况（跨 sheet 引用）在 diff 预览里用 warning 提示（P3B 最小版不做，留延期） |

### 9.2 LLM 行为风险

| 风险 | 缓解 |
|---|---|
| LLM 写错 sheet name（大小写不一致） | 工具 description 明确"区分大小写"；failedOp hint 指引正确名称 |
| LLM 把 `=` 前缀公式当字面量（或反之） | 工具 description 明确两种语义；diff card 显示公式时加 `fx:` 徽标让用户二次确认 |
| LLM 对合并单元格中间写值 | engine 拒绝 + hint 指引使用 anchor；保护数据正确性 |
| LLM 对空 sheet（无数据）做 insert_row before_row=1 | 允许（等价于追加）；engine 不走 shiftRows（没有可 shift 的行），直接 createRow |

### 9.3 产品/体验风险

| 风险 | 缓解 |
|---|---|
| 用户不理解"工作副本"仍适用（xlsx 与 docx 心智一致） | DiffCard 文案统一 "已改到工作副本 v{n}；未覆盖原路径，点按钮才覆盖" |
| 版本历史 UI 引入 rollback 误操作 | 列表项 confirm 弹窗二次确认；rollback 不物理删历史 |
| set_range 的 2D 数据预览对大区域信息过载 | 前端仅显示 `rows x cols` 规模和首行数据，点击可展开完整数据（P3B 先做规模摘要，详细展开 P4） |

---

## 10. P3B 文件清单（供 writing-plans 展开 task）

### 10.1 后端新建

| 路径 | 职责 |
|---|---|
| `src/main/java/com/lifepilot/document/patch/XlsxPatchOperation.java` | sealed interface + 4 permits |
| `src/main/java/com/lifepilot/document/patch/DocxPatchOperation.java` | sealed interface 接管 P3A 的 4 permits（重构） |
| `src/main/java/com/lifepilot/document/patch/UpdateCellOp.java` | record |
| `src/main/java/com/lifepilot/document/patch/InsertRowOp.java` | record |
| `src/main/java/com/lifepilot/document/patch/DeleteRowOp.java` | record |
| `src/main/java/com/lifepilot/document/patch/SetRangeOp.java` | record |
| `src/main/java/com/lifepilot/document/patch/xlsx/CellAddressResolver.java` | A1 / range 解析 |
| `src/main/java/com/lifepilot/document/patch/xlsx/XlsxPatchEngine.java` | XSSFWorkbook 内存操作 |
| `src/main/java/com/lifepilot/document/patch/xlsx/XlsxDiffBuilder.java` | xlsx diff JSON 构造 |
| `src/main/java/com/lifepilot/document/patch/xlsx/AppliedXlsxOp.java` | record（含 before snapshot） |

### 10.2 后端修改

| 路径 | 改动 |
|---|---|
| `src/main/java/com/lifepilot/document/patch/DocumentPatchOperation.java` | 降级为标记接口（移除 sealed + permits） |
| `src/main/java/com/lifepilot/document/patch/ReplaceTextOp.java` | `implements DocxPatchOperation`（从 DocumentPatchOperation 迁出） |
| `src/main/java/com/lifepilot/document/patch/InsertParagraphAfterOp.java` | 同上 |
| `src/main/java/com/lifepilot/document/patch/DeleteParagraphOp.java` | 同上 |
| `src/main/java/com/lifepilot/document/patch/AddTableRowOp.java` | 同上 |
| `src/main/java/com/lifepilot/document/patch/docx/DocxPatchEngine.java` | `apply` 签名收窄到 `List<DocxPatchOperation>`，switch 依然穷尽 |
| `src/main/java/com/lifepilot/document/version/DocumentVersionService.java` | 扩展名参数化、MIME 分支、跨类型 reject、注入 xlsx engine / diff builder；`DOCX_MIME` / `XLSX_MIME` 常量；checkout 接纳 xlsx 扩展名 |
| `src/main/java/com/lifepilot/document/tool/DocumentEditActionDispatchExecutor.java` | parseOneOp 扩展 4 个 xlsx case |
| `src/main/java/com/lifepilot/document/tool/DocumentEditToolProvider.java` | description + schema 扩展 xlsx 段 |
| `src/main/java/com/lifepilot/document/config/DocumentAutoConfiguration.java` | 新增 3 个 @Bean（XlsxPatchEngine / XlsxDiffBuilder / 可选 CellAddressResolver），更新 DocumentVersionService @Bean 签名 |

### 10.3 前端新建

| 路径 | 职责 |
|---|---|
| `zhiwei-web/src/components/chat/DocumentXlsxDiffCard.vue` | xlsx 场景气泡卡 |
| `zhiwei-web/src/components/chat/DocumentVersionHistoryList.vue` | 共享版本历史列表 |
| `zhiwei-web/src/components/chat/DocumentDiffHeader.vue` | 共享标题 / 版本号 / 折叠 |
| `zhiwei-web/src/components/chat/DocumentDiffActions.vue` | 共享底部三按钮 |

### 10.4 前端修改

| 路径 | 改动 |
|---|---|
| `zhiwei-web/src/components/chat/DocumentDiffCard.vue` | 接入 `DocumentDiffHeader` / `DocumentDiffActions` / `DocumentVersionHistoryList`，去冗余代码 |
| `zhiwei-web/src/components/chat/MessageBubble.vue` | 增 `isEditedXlsx` 判断 + 模板分支挂 `DocumentXlsxDiffCard` |
| `zhiwei-web/src/api/documents.ts` | 扩 `DiffChange` / `DiffPayload` 可选字段；无新 API |

### 10.5 测试新建

| 路径 |
|---|
| `src/test/resources/fixtures/document/sample-sheet.xlsx` |
| `src/test/resources/fixtures/document/sample-with-formulas.xlsx` |
| `src/test/java/com/lifepilot/document/patch/xlsx/CellAddressResolver_解析测试.java` |
| `src/test/java/com/lifepilot/document/patch/xlsx/XlsxPatchEngine_四种操作测试.java` |
| `src/test/java/com/lifepilot/document/patch/xlsx/XlsxDiffBuilder_diff构造测试.java` |
| `src/test/java/com/lifepilot/document/version/DocumentVersionService_xlsx生命周期测试.java` |
| `src/test/java/com/lifepilot/document/tool/DocumentEditActionDispatchExecutor_xlsx路由测试.java` |
| `src/test/java/com/lifepilot/interaction/web/controller/DocumentController_xlsx端点测试.java` |
| `zhiwei-web/src/components/chat/__tests__/DocumentXlsxDiffCard.spec.ts` |
| `zhiwei-web/src/components/chat/__tests__/DocumentVersionHistoryList.spec.ts` |

### 10.6 测试修改

| 路径 | 改动 |
|---|---|
| 已有 P3A 测试（Docx*） | 若重构 sealed interface 后签名变化，相应调整；不改逻辑 |
| `DocumentAutoConfigurationTest` | 装配契约扩展到 xlsx engine / diffBuilder 存在 |
| `zhiwei-web/src/components/chat/__tests__/DocumentDiffCard.spec.ts` | 若提炼子件后测试需微调 |

### 10.7 不动清单

- `knowledge/parser/` 任意文件
- `MarkdownToDocxGenerator` / `StructuredDataToXlsxGenerator` / `OutlineToPptxGenerator`（Phase 2 既有生成器）
- `AbstractDocumentCreateToolExecutor` / `DocumentCreate*ToolExecutor`（Phase 2B 既有 executor）
- Flyway 迁移（不新增）
- `DocumentController`（所有端点复用）

---

## 11. 与蓝图 / P3A spec 的对应

| 来源 | 对应 P3B 落地 |
|---|---|
| P3A spec §1.3 "P3B xlsx 横向复制" | 本 spec 全部 |
| P3A spec §12 "待 P3B 的延期项" — xlsx 5 op | 实现 4 op（`add_formula` 折进 `update_cell`，省一个 op） |
| P3A spec §12 — cell 级 diff 粒度 + DocumentXlsxDiffCard | §2.4 diff JSON + §6.1 独立组件 |
| P3A spec §12 — xlsx 公式 / 合并 / 样式保留 | §2.2 update_cell + §2.3 失败枚举 + §9.1 风险 |
| P3A spec §12 — 版本历史 UI | §6.2 DocumentVersionHistoryList 共享子件 |
| P3A spec §12 — cherry-pick | **仍不做**，留 P4+ |

---

## 12. 待后续阶段的延期项

- xlsx sheet 级操作：新建 / 删除 / 重命名 sheet
- 合并单元格本身的管理（建立 / 解除）
- 图表 / 透视表 / 图片 / VBA / 命名区域 / 数据验证
- 列宽 / 行高 / 冻结窗口
- 大 xlsx 流式读取（SXSSFWorkbook 的适配）
- `set_range` 的 2D 完整预览 / 可折叠
- 单条 patch cherry-pick（跨版本挑拣应用）
- 跨 sheet / 跨工作簿的公式失效检测告警
- pptx 编辑（Phase 4+）

---

**结束**。本 spec 为 Phase 3B 设计规格。按 `superpowers:writing-plans` 拆出 P3B 的逐任务 plan，逐任务用 `subagent-driven-development` 执行 + per-task code-review。
