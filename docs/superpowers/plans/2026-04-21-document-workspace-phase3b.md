# 文档工作空间 Phase 3B — xlsx 编辑与 Diff 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **配套 spec**：`docs/superpowers/specs/2026-04-21-document-workspace-phase3b-design.md`
> **前置依赖**：Phase 3A 已完成 —— `document.edit` 工具、`DocumentVersionService` docx 全链、V13 迁移、`DocumentController` 6 端点、前端 `DocumentDiffCard.vue`

**Goal：** 把 P3A 已打穿的 docx 编辑/版本/Diff 链路横向复制到 xlsx，新增 4 个 xlsx op（`update_cell` / `insert_row` / `delete_row` / `set_range`）和独立 `DocumentXlsxDiffCard.vue`；同时抽出共享版本历史子件 `DocumentVersionHistoryList.vue` 和两个共享 DiffCard 子件（Header/Actions），docx + xlsx 都挂。

**Architecture：**
1. **协议层分层重构**：`DocumentPatchOperation` 顶层仍 sealed，permits 收窄到 `DocxPatchOperation` / `XlsxPatchOperation` 两个子 sealed；P3A 既有 4 record 迁入 `DocxPatchOperation`，新增 4 xlsx op record 挂 `XlsxPatchOperation`。
2. **执行层**：`XlsxPatchEngine` 镜像 `DocxPatchEngine` 签名；`CellAddressResolver` 解析 A1 notation；`XlsxDiffBuilder` 产 xlsx 变体 diff JSON（含 `mime` 顶层字段 + `AppliedXlsxOp` 携带 before snapshot）
3. **版本层**：`DocumentVersionService` 内部按 MIME 分支选 engine；工作副本扩展名参数化；checkout 扩展名白名单扩到 `.xlsx`
4. **工具层**：`DocumentEditActionDispatchExecutor.parseOneOp` 扩 4 个 xlsx case；`DocumentEditToolProvider` description/schema 扩 xlsx 段
5. **装配**：`DocumentAutoConfiguration` 新增 `XlsxPatchEngine` + `XlsxDiffBuilder` Bean，扩 `DocumentVersionService` Bean 签名
6. **前端**：`DocumentXlsxDiffCard.vue` 独立 xlsx 卡；`DocumentDiffHeader.vue` / `DocumentDiffActions.vue` / `DocumentVersionHistoryList.vue` 三个共享子件；`DocumentDiffCard.vue` 重构接入共享子件；`MessageBubble.vue` 按 MIME 分支

**Tech Stack：** Apache POI 5.5.1（XSSF / CellReference / CellRangeAddress / shiftRows）、Java 22（sealed interface 分层、pattern matching、record）、Spring Boot 3、Vue 3 + Reka UI 2.x + Tailwind 命名尺度、JUnit 5 + AssertJ + Mockito + ApplicationContextRunner + Vitest

---

## File Structure

### 后端新建

| 路径 | 责任 |
|---|---|
| `src/main/java/com/lifepilot/document/patch/DocxPatchOperation.java` | sealed interface，permits P3A 4 个 docx op record |
| `src/main/java/com/lifepilot/document/patch/XlsxPatchOperation.java` | sealed interface，permits 4 个 xlsx op record |
| `src/main/java/com/lifepilot/document/patch/UpdateCellOp.java` | record：sheet / cell / newValue(Object) / reason |
| `src/main/java/com/lifepilot/document/patch/InsertRowOp.java` | record：sheet / beforeRow / values(List<Object>) / reason |
| `src/main/java/com/lifepilot/document/patch/DeleteRowOp.java` | record：sheet / row / reason |
| `src/main/java/com/lifepilot/document/patch/SetRangeOp.java` | record：sheet / range / values(List<List<Object>>) / reason |
| `src/main/java/com/lifepilot/document/patch/xlsx/CellAddressResolver.java` | A1 / range 解析（CellReference / CellRangeAddress） |
| `src/main/java/com/lifepilot/document/patch/xlsx/AppliedXlsxOp.java` | record：op + beforeSnapshot + rowsAffected + colsAffected |
| `src/main/java/com/lifepilot/document/patch/xlsx/XlsxPatchEngine.java` | XSSFWorkbook 内存操作，4 种 op |
| `src/main/java/com/lifepilot/document/patch/xlsx/XlsxDiffBuilder.java` | xlsx diff JSON 构造（mime: "xlsx"） |

### 后端修改

| 路径 | 改动 |
|---|---|
| `src/main/java/com/lifepilot/document/patch/DocumentPatchOperation.java` | 从 permits 4 record 收窄到 permits 2 子接口（DocxPatchOperation / XlsxPatchOperation），保持 sealed |
| `src/main/java/com/lifepilot/document/patch/ReplaceTextOp.java` | `implements DocumentPatchOperation` → `implements DocxPatchOperation` |
| `src/main/java/com/lifepilot/document/patch/InsertParagraphAfterOp.java` | 同上 |
| `src/main/java/com/lifepilot/document/patch/DeleteParagraphOp.java` | 同上 |
| `src/main/java/com/lifepilot/document/patch/AddTableRowOp.java` | 同上 |
| `src/main/java/com/lifepilot/document/patch/docx/DocxPatchEngine.java` | `apply` 签名入参类型从 `List<DocumentPatchOperation>` 收窄到 `List<DocxPatchOperation>` |
| `src/main/java/com/lifepilot/document/version/DocumentVersionService.java` | 扩展名参数化；XLSX_MIME 常量；checkoutFromPath/Attachment 接纳 xlsx；applyPatch 按 MIME 分支；注入 XlsxPatchEngine / XlsxDiffBuilder |
| `src/main/java/com/lifepilot/document/tool/DocumentEditActionDispatchExecutor.java` | `parseOneOp` 扩 4 个 xlsx op case |
| `src/main/java/com/lifepilot/document/tool/DocumentEditToolProvider.java` | description + schema 扩 xlsx 段 |
| `src/main/java/com/lifepilot/document/config/DocumentAutoConfiguration.java` | 新增 XlsxPatchEngine / XlsxDiffBuilder Bean；DocumentVersionService Bean 签名加两个依赖 |

### 前端新建

| 路径 | 职责 |
|---|---|
| `zhiwei-web/src/components/chat/DocumentXlsxDiffCard.vue` | xlsx 场景对话气泡卡 |
| `zhiwei-web/src/components/chat/DocumentVersionHistoryList.vue` | 共享版本历史列表（docx + xlsx 都挂） |
| `zhiwei-web/src/components/chat/DocumentDiffHeader.vue` | 共享标题栏（文件名 / 版本号 / 收展按钮） |
| `zhiwei-web/src/components/chat/DocumentDiffActions.vue` | 共享三按钮（应用到原路径 / 另存为 / 丢弃） |

### 前端修改

| 路径 | 改动 |
|---|---|
| `zhiwei-web/src/components/chat/DocumentDiffCard.vue` | 接入 Header/Actions/VersionHistoryList，去冗余代码 |
| `zhiwei-web/src/components/chat/MessageBubble.vue` | 新增 `isEditedXlsx` 判断 + 模板分支挂 `DocumentXlsxDiffCard` |
| `zhiwei-web/src/api/documents.ts` | `DiffChange` / `DiffPayload` 加可选字段 `sheet` / `cell` / `range` / `row` / `rows` / `cols` / `mime` |

### 测试新建

| 路径 | 操作 |
|---|---|
| `src/test/resources/fixtures/document/sample-sheet.xlsx` | 新建（XlsxFixtureGenerator 产出） |
| `src/test/resources/fixtures/document/sample-with-formulas.xlsx` | 新建（同上） |
| `src/test/java/com/lifepilot/document/testsupport/XlsxFixtureGenerator.java` | 新建：一次性生成 fixture |
| `src/test/java/com/lifepilot/document/patch/xlsx/CellAddressResolver_解析测试.java` | 新建 |
| `src/test/java/com/lifepilot/document/patch/xlsx/XlsxPatchEngine_四种操作测试.java` | 新建 |
| `src/test/java/com/lifepilot/document/patch/xlsx/XlsxDiffBuilder_构造测试.java` | 新建 |
| `src/test/java/com/lifepilot/document/version/DocumentVersionService_xlsx生命周期测试.java` | 新建 |
| `src/test/java/com/lifepilot/document/tool/DocumentEditActionDispatchExecutor_xlsx路由测试.java` | 新建 |
| `zhiwei-web/src/components/chat/__tests__/DocumentXlsxDiffCard.spec.ts` | 新建 |
| `zhiwei-web/src/components/chat/__tests__/DocumentVersionHistoryList.spec.ts` | 新建 |

### 测试修改

| 路径 | 改动 |
|---|---|
| `src/test/java/com/lifepilot/document/config/DocumentAutoConfiguration_装配测试.java` | 追加 xlsx engine / diffBuilder 装配断言 |
| `src/test/java/com/lifepilot/document/patch/docx/DocxPatchEngine_四种操作测试.java` | 若签名收窄导致 `List<DocumentPatchOperation>` 需改成 `List<DocxPatchOperation>`，跟随调整 |

### 不动清单

- Flyway 迁移（P3B 不新增；复用 V13）
- `DocumentController`（所有端点对 xlsx 同样有效，不改）
- `DocumentVersionRepository` / `SessionDocumentRepository`（schema 不变）
- `AttachmentRepository`
- `MarkdownToDocxGenerator` / `StructuredDataToXlsxGenerator` / `OutlineToPptxGenerator`（Phase 2 既有生成器）
- P3A `TextAnchorLocator` / `DocxDiffBuilder` / `ParagraphRunRange` / `AppliedOp`

---

## Task 1：DocumentPatchOperation sealed 分层重构

**目的**：把 P3A 的 `DocumentPatchOperation` 顶层 sealed 从"permits 4 个 docx record"改为"permits 2 个子接口"，新建 `DocxPatchOperation` 接管 P3A 4 个 record。不引入行为变化，但为 P3B 引入 `XlsxPatchOperation` 做好类型骨架。改完 P3A 所有测试必须全绿。

**Files:**
- Modify: `src/main/java/com/lifepilot/document/patch/DocumentPatchOperation.java`
- Create: `src/main/java/com/lifepilot/document/patch/DocxPatchOperation.java`
- Modify: `src/main/java/com/lifepilot/document/patch/ReplaceTextOp.java`
- Modify: `src/main/java/com/lifepilot/document/patch/InsertParagraphAfterOp.java`
- Modify: `src/main/java/com/lifepilot/document/patch/DeleteParagraphOp.java`
- Modify: `src/main/java/com/lifepilot/document/patch/AddTableRowOp.java`
- Modify: `src/main/java/com/lifepilot/document/patch/docx/DocxPatchEngine.java`

**前置阅读：**
- `src/main/java/com/lifepilot/document/patch/DocumentPatchOperation.java`（P3A 原型）
- `src/main/java/com/lifepilot/document/patch/docx/DocxPatchEngine.java`（apply 的 switch）
- spec §5.1（分层理由）

- [ ] **Step 1：改 DocumentPatchOperation 顶层 permits**

用下面的内容替换 `DocumentPatchOperation.java`：

```java
package com.lifepilot.document.patch;

/**
 * 文档 patch 操作 —— 面向 LLM 的顶层 sealed 协议。
 *
 * <p>P3B 起分层：本接口 permits 限定为格式子 sealed 接口（docx / xlsx），
 * 具体 op record 改挂在对应子接口下。</p>
 * <ul>
 *   <li>{@link DocxPatchOperation} — 4 个 docx op record</li>
 *   <li>{@link XlsxPatchOperation} — 4 个 xlsx op record</li>
 * </ul>
 *
 * <p>Engine 在自己那一侧按子接口 pattern matching 保持穷尽；Service 层按 MIME
 * 决定投喂哪个子接口列表给哪个 engine，不在同一批混用。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public sealed interface DocumentPatchOperation
        permits DocxPatchOperation, XlsxPatchOperation {

    /** LLM 可选的解释，会透传到 diff JSON 给用户看。 */
    String reason();
}
```

- [ ] **Step 2：新建 DocxPatchOperation**

新建 `src/main/java/com/lifepilot/document/patch/DocxPatchOperation.java`：

```java
package com.lifepilot.document.patch;

/**
 * docx 独占的 patch 操作 —— P3A 原有 4 个 op record 挂在此接口下。
 *
 * <p>{@link com.lifepilot.document.patch.docx.DocxPatchEngine} 按本接口
 * pattern matching，保持 switch 穷尽。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public sealed interface DocxPatchOperation extends DocumentPatchOperation
        permits ReplaceTextOp, InsertParagraphAfterOp, DeleteParagraphOp, AddTableRowOp {
}
```

- [ ] **Step 3：改 4 个 docx record 的 implements**

对 `ReplaceTextOp.java` / `InsertParagraphAfterOp.java` / `DeleteParagraphOp.java` / `AddTableRowOp.java` 做同款修改：把 `implements DocumentPatchOperation` 改为 `implements DocxPatchOperation`。

示例（ReplaceTextOp.java 第 20-26 行附近）：

```java
public record ReplaceTextOp(
        String beforeContext,
        String target,
        String afterContext,
        String newText,
        @Nullable String reason
) implements DocxPatchOperation {   // ← 只改这行
```

另外 3 个 record 同款（只改 `implements` 右侧）。

- [ ] **Step 4：收窄 DocxPatchEngine.apply 签名**

改 `DocxPatchEngine.java`：

- 把 `public EngineResult apply(XWPFDocument document, List<DocumentPatchOperation> ops)` 的入参类型收窄为 `List<DocxPatchOperation>`
- 把 `for` 循环变量 `DocumentPatchOperation op` 改为 `DocxPatchOperation op`
- `opType(DocumentPatchOperation op)` 的辅助方法入参同款收窄为 `DocxPatchOperation op`（`switch` 穷尽性保持）
- 相关 import：移除 `com.lifepilot.document.patch.DocumentPatchOperation`（不再直接引用），加 `com.lifepilot.document.patch.DocxPatchOperation`

注：如果项目别处（如 `DocxDiffBuilder.opType(...)` 调用）以 `DocumentPatchOperation` 类型引用 op，保持不动（子接口也是 DocumentPatchOperation 的子类型）。`AppliedOp` 的 `op()` 返回类型若是 `DocumentPatchOperation` 保持；若已是 `DocxPatchOperation` 更好。

检查现有 `AppliedOp.java`：

```
Read src/main/java/com/lifepilot/document/patch/docx/AppliedOp.java
```

若 `AppliedOp.op()` 返回 `DocumentPatchOperation`，一并改为 `DocxPatchOperation` 以消除多余的强转风险。

- [ ] **Step 5：跑 P3A 全量回归**

Run:
```bash
mvn test -q -Dtest='DocxPatchEngine* Text* DocxDiffBuilder* DocumentEditActionDispatchExecutor* DocumentVersionService* DocumentController_P3端点测试'
```

Expected: 所有 P3A 测试通过，无编译失败。

- [ ] **Step 6：commit**

```bash
git add src/main/java/com/lifepilot/document/patch/DocumentPatchOperation.java \
        src/main/java/com/lifepilot/document/patch/DocxPatchOperation.java \
        src/main/java/com/lifepilot/document/patch/ReplaceTextOp.java \
        src/main/java/com/lifepilot/document/patch/InsertParagraphAfterOp.java \
        src/main/java/com/lifepilot/document/patch/DeleteParagraphOp.java \
        src/main/java/com/lifepilot/document/patch/AddTableRowOp.java \
        src/main/java/com/lifepilot/document/patch/docx/DocxPatchEngine.java \
        src/main/java/com/lifepilot/document/patch/docx/AppliedOp.java
git commit -m "refactor(document): Phase 3B Task 1 — DocumentPatchOperation 分层 sealed + DocxPatchOperation 接管 4 个 docx op"
```

---

## Task 2：XlsxPatchOperation sealed + 4 个 xlsx op record

**目的**：新建 `XlsxPatchOperation` sealed 接口 + 4 个 xlsx op record（`UpdateCellOp` / `InsertRowOp` / `DeleteRowOp` / `SetRangeOp`）。字段按 spec §2.2 定义，`newValue` / `values` 用 `Object` 保持多态。

**Files:**
- Create: `src/main/java/com/lifepilot/document/patch/XlsxPatchOperation.java`
- Create: `src/main/java/com/lifepilot/document/patch/UpdateCellOp.java`
- Create: `src/main/java/com/lifepilot/document/patch/InsertRowOp.java`
- Create: `src/main/java/com/lifepilot/document/patch/DeleteRowOp.java`
- Create: `src/main/java/com/lifepilot/document/patch/SetRangeOp.java`

**前置阅读：**
- `src/main/java/com/lifepilot/document/patch/ReplaceTextOp.java`（record + `@Nullable reason` 模式）
- spec §2.2（op schema）+ §5.2（record 字段表）

- [ ] **Step 1：XlsxPatchOperation 接口**

```java
package com.lifepilot.document.patch;

/**
 * xlsx 独占的 patch 操作 —— P3B 新增 4 个 op record 挂在此接口下。
 *
 * <p>{@link com.lifepilot.document.patch.xlsx.XlsxPatchEngine} 按本接口
 * pattern matching，保持 switch 穷尽。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public sealed interface XlsxPatchOperation extends DocumentPatchOperation
        permits UpdateCellOp, InsertRowOp, DeleteRowOp, SetRangeOp {
}
```

- [ ] **Step 2：UpdateCellOp**

```java
package com.lifepilot.document.patch;

import org.springframework.lang.Nullable;

/**
 * 单元格值替换 —— P3B 主力 op。
 *
 * <p>定位：`sheet`（精确匹配，区分大小写）+ `cell`（A1 notation，大小写不敏感）。
 * {@code newValue} 为多态对象，保留 JSON 原始类型：</p>
 * <ul>
 *   <li>{@link Number} → numeric cell</li>
 *   <li>{@link Boolean} → boolean cell</li>
 *   <li>{@link String} 以 {@code =} 开头 → 公式（剥掉前缀后送 POI setCellFormula）</li>
 *   <li>{@link String} 其它 → 字符串字面量</li>
 *   <li>{@code null} → 清空 cell（setBlank）</li>
 * </ul>
 *
 * @param sheet    工作表名（精确匹配）
 * @param cell     单元格地址（A1 notation，如 "B5" / "AA12"）
 * @param newValue 新值（多态）
 * @param reason   可选，LLM 给用户看的解释
 * @author zsg
 * @since 2026-04-21
 */
public record UpdateCellOp(
        String sheet,
        String cell,
        @Nullable Object newValue,
        @Nullable String reason
) implements XlsxPatchOperation {

    public UpdateCellOp {
        if (sheet == null || sheet.isBlank()) {
            throw new IllegalArgumentException("UpdateCellOp.sheet 不能为空");
        }
        if (cell == null || cell.isBlank()) {
            throw new IllegalArgumentException("UpdateCellOp.cell 不能为空");
        }
    }
}
```

- [ ] **Step 3：InsertRowOp**

```java
package com.lifepilot.document.patch;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 在指定 1-based 行号**之前**插入一行（原第 {@code beforeRow} 行及以下下移）。
 *
 * <p>values 元素类型规则同 {@link UpdateCellOp#newValue()}。POI `sheet.shiftRows`
 * 会自动刷新下方行中对该区域的公式引用（相对地址自动 +1）。新行默认继承上一行的
 * {@link org.apache.poi.ss.usermodel.CellStyle}（POI 默认行为）。</p>
 *
 * @param sheet     工作表名
 * @param beforeRow 1-based 行号，合法 [1, 最大行+1]；等于最大行+1 时等价于追加
 * @param values    要写入的新行值列表（长度自由，从第 0 列起依次写入）
 * @param reason    可选
 * @author zsg
 * @since 2026-04-21
 */
public record InsertRowOp(
        String sheet,
        int beforeRow,
        List<Object> values,
        @Nullable String reason
) implements XlsxPatchOperation {

    public InsertRowOp {
        if (sheet == null || sheet.isBlank()) {
            throw new IllegalArgumentException("InsertRowOp.sheet 不能为空");
        }
        if (beforeRow < 1) {
            throw new IllegalArgumentException("InsertRowOp.beforeRow 必须 ≥ 1，当前 " + beforeRow);
        }
        if (values == null) {
            values = List.of();
        }
    }
}
```

- [ ] **Step 4：DeleteRowOp**

```java
package com.lifepilot.document.patch;

import org.springframework.lang.Nullable;

/**
 * 删除指定 1-based 行号的行（后续行上移）。
 *
 * <p>POI `sheet.shiftRows` 自动刷新公式引用。若目标行横跨合并区域，engine 拒绝执行。</p>
 *
 * @param sheet  工作表名
 * @param row    1-based 行号
 * @param reason 可选
 * @author zsg
 * @since 2026-04-21
 */
public record DeleteRowOp(
        String sheet,
        int row,
        @Nullable String reason
) implements XlsxPatchOperation {

    public DeleteRowOp {
        if (sheet == null || sheet.isBlank()) {
            throw new IllegalArgumentException("DeleteRowOp.sheet 不能为空");
        }
        if (row < 1) {
            throw new IllegalArgumentException("DeleteRowOp.row 必须 ≥ 1，当前 " + row);
        }
    }
}
```

- [ ] **Step 5：SetRangeOp**

```java
package com.lifepilot.document.patch;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 批量写入矩形区域 —— A1 range notation 锚定，2D values 与 range 尺寸必须吻合。
 *
 * <p>区域内若含合并单元格（非 anchor 的内部单元格），engine 拒绝整批。每格按
 * {@link UpdateCellOp#newValue()} 规则写入。</p>
 *
 * @param sheet  工作表名
 * @param range  A1 range（如 "B2:D4"）
 * @param values 2D 值（外层长度 = range 行数，每行长度 = range 列数）
 * @param reason 可选
 * @author zsg
 * @since 2026-04-21
 */
public record SetRangeOp(
        String sheet,
        String range,
        List<List<Object>> values,
        @Nullable String reason
) implements XlsxPatchOperation {

    public SetRangeOp {
        if (sheet == null || sheet.isBlank()) {
            throw new IllegalArgumentException("SetRangeOp.sheet 不能为空");
        }
        if (range == null || range.isBlank()) {
            throw new IllegalArgumentException("SetRangeOp.range 不能为空");
        }
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("SetRangeOp.values 不能为空");
        }
    }
}
```

- [ ] **Step 6：编译确认**

Run: `mvn compile -q`
Expected: 通过，无符号错误。sealed permits 声明的 record 全部实现 `XlsxPatchOperation`。

- [ ] **Step 7：commit**

```bash
git add src/main/java/com/lifepilot/document/patch/XlsxPatchOperation.java \
        src/main/java/com/lifepilot/document/patch/UpdateCellOp.java \
        src/main/java/com/lifepilot/document/patch/InsertRowOp.java \
        src/main/java/com/lifepilot/document/patch/DeleteRowOp.java \
        src/main/java/com/lifepilot/document/patch/SetRangeOp.java
git commit -m "feat(document): Phase 3B Task 2 — XlsxPatchOperation sealed + 4 个 xlsx op record"
```

---

## Task 3：测试 fixture —— XlsxFixtureGenerator + 两个样本 xlsx

**目的**：用一次性 `XlsxFixtureGenerator` 程序生成 `sample-sheet.xlsx`（简单数据）+ `sample-with-formulas.xlsx`（含公式 / 合并 / 样式）。后续 xlsx 相关测试都 load 这两个 fixture。对齐 P3A `DocxFixtureGenerator` 的做法。

**Files:**
- Create: `src/test/java/com/lifepilot/document/testsupport/XlsxFixtureGenerator.java`
- Create: `src/test/resources/fixtures/document/sample-sheet.xlsx`（由 Generator 产出，checked in）
- Create: `src/test/resources/fixtures/document/sample-with-formulas.xlsx`（同上）

**前置阅读：**
- `src/test/java/com/lifepilot/document/testsupport/DocxFixtureGenerator.java`（P3A 同款做法，参考其 @Test 触发模式）
- `src/main/java/com/lifepilot/document/generator/StructuredDataToXlsxGenerator.java`（POI XSSF 基本用法）

- [ ] **Step 1：XlsxFixtureGenerator 骨架**

```java
package com.lifepilot.document.testsupport;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 一次性 fixture 生成器 —— 手动触发生成 {@code sample-sheet.xlsx} /
 * {@code sample-with-formulas.xlsx} 到 {@code src/test/resources/fixtures/document/}。
 *
 * <p>类上 {@link Disabled} 避免 CI 自动执行；需要重建 fixture 时临时注释掉再跑。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
@Disabled("仅在需要重建 fixture 时手工运行")
class XlsxFixtureGenerator {

    private static final Path FIXTURES_DIR = Paths.get("src/test/resources/fixtures/document");

    @Test
    void 生成sample_sheet() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             FileOutputStream out = new FileOutputStream(
                     FIXTURES_DIR.resolve("sample-sheet.xlsx").toFile())) {

            Sheet s1 = wb.createSheet("Sheet1");
            writeRow(s1, 0, "产品", "数量", "单价");
            writeRow(s1, 1, "钢笔", 10, 5.5);
            writeRow(s1, 2, "笔记本", 20, 12.0);
            writeRow(s1, 3, "橡皮", 50, 0.5);
            writeRow(s1, 4, "订书机", 3, 35.0);

            Sheet s2 = wb.createSheet("Sheet2");
            writeRow(s2, 0, "季度", "销售");
            writeRow(s2, 1, "Q1", 100);
            writeRow(s2, 2, "Q2", 150);
            writeRow(s2, 3, "Q3", 200);
            writeRow(s2, 4, "Q4", 180);

            wb.write(out);
        }
    }

    @Test
    void 生成sample_with_formulas() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             FileOutputStream out = new FileOutputStream(
                     FIXTURES_DIR.resolve("sample-with-formulas.xlsx").toFile())) {

            Sheet sheet = wb.createSheet("Data");

            // 带样式的表头：粗体 + 浅黄底色 + 边框
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.THIN);

            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("项目");
            header.createCell(1).setCellValue("Q1");
            header.createCell(2).setCellValue("Q2");
            header.createCell(3).setCellValue("合计");
            for (int c = 0; c < 4; c++) header.getCell(c).setCellStyle(headerStyle);

            // 合并单元格 B3:C3（用于测试 "update_cell 落在合并区域中间" 的拒绝路径）
            sheet.addMergedRegion(new CellRangeAddress(2, 2, 1, 2));

            writeRow(sheet, 1, "产品 A", 100, 150, null);
            writeRow(sheet, 2, "合并标题", null, null, null); // 第 3 行，B3:C3 合并
            writeRow(sheet, 3, "产品 B", 80, 120, null);
            writeRow(sheet, 4, "产品 C", 60, 90, null);

            // 在 D 列写公式 =B2+C2 / =B4+C4 / =B5+C5
            sheet.getRow(1).createCell(3).setCellFormula("B2+C2");
            sheet.getRow(3).createCell(3).setCellFormula("B4+C4");
            sheet.getRow(4).createCell(3).setCellFormula("B5+C5");

            // 数字格式：C 列保留一位小数
            CellStyle decimalStyle = wb.createCellStyle();
            decimalStyle.setDataFormat(wb.createDataFormat().getFormat("0.0"));
            for (int r = 1; r <= 4; r++) {
                Cell c = sheet.getRow(r).getCell(2);
                if (c != null) c.setCellStyle(decimalStyle);
            }

            wb.write(out);
        }
    }

    private static void writeRow(Sheet sheet, int rowIdx, Object... cells) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) row = sheet.createRow(rowIdx);
        for (int c = 0; c < cells.length; c++) {
            Cell cell = row.createCell(c);
            Object v = cells[c];
            if (v == null) continue;
            if (v instanceof Number n) cell.setCellValue(n.doubleValue());
            else if (v instanceof Boolean b) cell.setCellValue(b);
            else cell.setCellValue(v.toString());
        }
    }
}
```

- [ ] **Step 2：手工跑一次生成 fixture**

临时把 `@Disabled` 注释掉（或用 IDE 右键 "Run Method"）运行两个 @Test，确认：

```
src/test/resources/fixtures/document/sample-sheet.xlsx         （约 5KB）
src/test/resources/fixtures/document/sample-with-formulas.xlsx （约 5KB）
```

两个文件生成成功后，恢复 `@Disabled` 注解。用 Excel / LibreOffice / POI 打开校验：
- `sample-sheet.xlsx` 有 Sheet1 + Sheet2，5 行 x 3 列数据
- `sample-with-formulas.xlsx` 的 Data sheet 含表头样式、合并 B3:C3、D 列公式、C 列数字格式

- [ ] **Step 3：commit fixture 文件 + generator**

```bash
git add src/test/java/com/lifepilot/document/testsupport/XlsxFixtureGenerator.java \
        src/test/resources/fixtures/document/sample-sheet.xlsx \
        src/test/resources/fixtures/document/sample-with-formulas.xlsx
git commit -m "test(document): Phase 3B Task 3 — 新增 xlsx fixture（简单表 + 公式/合并/样式）"
```

---

## Task 4：CellAddressResolver —— A1 / range 解析

**目的**：提供静态工具把 `"B5"` / `"AA12"` / `"B2:D4"` 解析为 POI 的 `CellReference` / `CellRangeAddress`；统一剥掉 `$` 绝对引用前缀、upper-case 列字母；越界 / 非法格式返回 `Optional.empty()`。供 `XlsxPatchEngine` 所有 op 复用。

**Files:**
- Create: `src/main/java/com/lifepilot/document/patch/xlsx/CellAddressResolver.java`
- Test: `src/test/java/com/lifepilot/document/patch/xlsx/CellAddressResolver_解析测试.java`

**前置阅读：**
- `src/main/java/com/lifepilot/document/patch/docx/TextAnchorLocator.java`（风格参考）
- POI `CellReference` / `CellRangeAddress` API（无需读源码，下文已给出用法）

- [ ] **Step 1：写 CellAddressResolver**

```java
package com.lifepilot.document.patch.xlsx;

import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A1 notation 地址解析工具。
 *
 * <p>统一处理：剥掉 {@code $} 绝对引用前缀、upper-case 列字母、拒绝带 {@code !} 的
 * sheet-prefixed 写法（本项目把 sheet 放在独立字段里，不塞 {@code Sheet1!B5}）。</p>
 *
 * <p>解析失败返回 {@link Optional#empty()}；调用方转成 {@code invalid_cell_address} /
 * {@code invalid_range} 错误。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public final class CellAddressResolver {

    private static final Pattern CELL_PATTERN = Pattern.compile("^\\$?[A-Z]+\\$?[0-9]+$");
    private static final Pattern RANGE_PATTERN = Pattern.compile(
            "^\\$?[A-Z]+\\$?[0-9]+:\\$?[A-Z]+\\$?[0-9]+$");

    private CellAddressResolver() {}

    /** 解析 "B5" / "$B$5" / "aa12" 等；含 "!" 或格式非法返回空。 */
    public static Optional<CellReference> parseCell(String address) {
        if (address == null) return Optional.empty();
        String cleaned = address.trim().toUpperCase(Locale.ROOT).replace("$", "");
        if (!CELL_PATTERN.matcher(cleaned).matches()) return Optional.empty();
        try {
            CellReference ref = new CellReference(cleaned);
            // POI CellReference 允许 row=0xFFFFFF 等非法大值，这里加简单范围校验
            if (ref.getRow() < 0 || ref.getCol() < 0) return Optional.empty();
            return Optional.of(ref);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 解析 "B2:D4" / "$B$2:$D$4"；左右颠倒（如 "D4:B2"）按 POI 规则自动规范化。 */
    public static Optional<CellRangeAddress> parseRange(String range) {
        if (range == null) return Optional.empty();
        String cleaned = range.trim().toUpperCase(Locale.ROOT).replace("$", "");
        if (!RANGE_PATTERN.matcher(cleaned).matches()) return Optional.empty();
        try {
            CellRangeAddress addr = CellRangeAddress.valueOf(cleaned);
            if (addr.getFirstRow() < 0 || addr.getFirstColumn() < 0) return Optional.empty();
            if (addr.getLastRow() < addr.getFirstRow()
                    || addr.getLastColumn() < addr.getFirstColumn()) {
                return Optional.empty();
            }
            return Optional.of(addr);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 2：写测试**

```java
package com.lifepilot.document.patch.xlsx;

import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CellAddressResolver 解析路径测试 —— A1 / range / 非法输入。
 *
 * @author zsg
 * @since 2026-04-21
 */
class CellAddressResolver_解析测试 {

    @Test
    void 单元格_标准A1解析成功() {
        Optional<CellReference> ref = CellAddressResolver.parseCell("B5");
        assertThat(ref).isPresent();
        assertThat(ref.get().getRow()).isEqualTo(4);
        assertThat(ref.get().getCol()).isEqualTo(1);
    }

    @Test
    void 单元格_小写和绝对引用被规范化() {
        assertThat(CellAddressResolver.parseCell("b5")).isPresent()
                .hasValueSatisfying(r -> {
                    assertThat(r.getRow()).isEqualTo(4);
                    assertThat(r.getCol()).isEqualTo(1);
                });
        assertThat(CellAddressResolver.parseCell("$B$5")).isPresent();
        assertThat(CellAddressResolver.parseCell("$AA$12")).isPresent()
                .hasValueSatisfying(r -> {
                    assertThat(r.getRow()).isEqualTo(11);
                    assertThat(r.getCol()).isEqualTo(26);
                });
    }

    @Test
    void 单元格_非法格式返回空() {
        assertThat(CellAddressResolver.parseCell(null)).isEmpty();
        assertThat(CellAddressResolver.parseCell("")).isEmpty();
        assertThat(CellAddressResolver.parseCell("5B")).isEmpty();
        assertThat(CellAddressResolver.parseCell("B")).isEmpty();
        assertThat(CellAddressResolver.parseCell("123")).isEmpty();
        assertThat(CellAddressResolver.parseCell("Sheet1!B5")).isEmpty();  // 拒绝 sheet-prefix
        assertThat(CellAddressResolver.parseCell("B 5")).isEmpty();
    }

    @Test
    void 区域_标准解析成功() {
        Optional<CellRangeAddress> r = CellAddressResolver.parseRange("B2:D4");
        assertThat(r).isPresent();
        assertThat(r.get().getFirstRow()).isEqualTo(1);
        assertThat(r.get().getFirstColumn()).isEqualTo(1);
        assertThat(r.get().getLastRow()).isEqualTo(3);
        assertThat(r.get().getLastColumn()).isEqualTo(3);
    }

    @Test
    void 区域_绝对引用和小写被规范化() {
        assertThat(CellAddressResolver.parseRange("$b$2:$d$4")).isPresent();
    }

    @Test
    void 区域_非法格式返回空() {
        assertThat(CellAddressResolver.parseRange(null)).isEmpty();
        assertThat(CellAddressResolver.parseRange("B2")).isEmpty();          // 缺冒号
        assertThat(CellAddressResolver.parseRange("B2:")).isEmpty();
        assertThat(CellAddressResolver.parseRange("B2-D4")).isEmpty();
        assertThat(CellAddressResolver.parseRange("Sheet1!B2:D4")).isEmpty();
    }
}
```

- [ ] **Step 3：跑测试**

Run: `mvn test -q -Dtest='CellAddressResolver_解析测试'`
Expected: 6 个 @Test 全绿。

- [ ] **Step 4：commit**

```bash
git add src/main/java/com/lifepilot/document/patch/xlsx/CellAddressResolver.java \
        src/test/java/com/lifepilot/document/patch/xlsx/CellAddressResolver_解析测试.java
git commit -m "feat(document): Phase 3B Task 4 — CellAddressResolver 解析 A1/range"
```

---

## Task 5：XlsxPatchEngine 骨架 + update_cell 实现

**目的**：搭 `XlsxPatchEngine.apply` 主流程（switch 4 种 op + 失败短路 + before snapshot 回传）+ 实现 `update_cell`（公式前缀、多态类型、合并区域拒绝、样式保留）。其余 3 个 op 下一 Task 补齐。

**Files:**
- Create: `src/main/java/com/lifepilot/document/patch/xlsx/XlsxPatchEngine.java`
- Create: `src/main/java/com/lifepilot/document/patch/xlsx/AppliedXlsxOp.java`
- Test: `src/test/java/com/lifepilot/document/patch/xlsx/XlsxPatchEngine_四种操作测试.java`（只先写 update_cell 相关用例，Task 6 补齐其余）

**前置阅读：**
- `src/main/java/com/lifepilot/document/patch/docx/DocxPatchEngine.java`（结构镜像）
- `src/main/java/com/lifepilot/document/patch/FailedOp.java`（复用 reason 返回结构）
- spec §2.2 update_cell + §2.3 失败枚举

- [ ] **Step 1：AppliedXlsxOp record**

```java
package com.lifepilot.document.patch.xlsx;

import com.lifepilot.document.patch.XlsxPatchOperation;

/**
 * 已成功应用的 xlsx op —— 带 before snapshot 供 {@link XlsxDiffBuilder} 生成 diff segments。
 *
 * <p>字段含义按 op 类型取舍：</p>
 * <ul>
 *   <li>{@code update_cell}：{@code beforeSnapshot} = 原 cell 字符串表示；{@code rowsAffected} / {@code colsAffected} 为 1</li>
 *   <li>{@code insert_row}：{@code beforeSnapshot} 无意义（传 {@code ""}）；{@code rowsAffected} = 1</li>
 *   <li>{@code delete_row}：{@code beforeSnapshot} = 被删整行的 "cellA | cellB | ..." 预览</li>
 *   <li>{@code set_range}：{@code beforeSnapshot} = "rowsXcols 批量（预览略）"；{@code rowsAffected} / {@code colsAffected} 反映 range 尺寸</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-21
 */
public record AppliedXlsxOp(
        XlsxPatchOperation op,
        String beforeSnapshot,
        int rowsAffected,
        int colsAffected
) {}
```

- [ ] **Step 2：XlsxPatchEngine 骨架 + update_cell**

```java
package com.lifepilot.document.patch.xlsx;

import com.lifepilot.document.patch.DeleteRowOp;
import com.lifepilot.document.patch.FailedOp;
import com.lifepilot.document.patch.InsertRowOp;
import com.lifepilot.document.patch.SetRangeOp;
import com.lifepilot.document.patch.UpdateCellOp;
import com.lifepilot.document.patch.XlsxPatchOperation;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * xlsx patch 引擎 —— 纯内存操作 {@link XSSFWorkbook}。
 *
 * <p>职责：对给定 XSSFWorkbook 按顺序应用 {@link XlsxPatchOperation} 列表；
 * 任一 op 定位 / 校验失败立即中止，已改动的 workbook 由调用方丢弃（不写盘即天然回滚）。
 * 成功时返回 {@link AppliedXlsxOp} 列表供 {@link XlsxDiffBuilder} 使用。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public class XlsxPatchEngine {

    private static final Logger log = LoggerFactory.getLogger(XlsxPatchEngine.class);
    private static final DataFormatter FORMATTER = new DataFormatter();

    public record EngineResult(boolean success, List<AppliedXlsxOp> appliedOps, List<FailedOp> failedOps) {}

    public EngineResult apply(XSSFWorkbook wb, List<XlsxPatchOperation> ops) {
        List<AppliedXlsxOp> applied = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            XlsxPatchOperation op = ops.get(i);
            try {
                AppliedXlsxOp result = switch (op) {
                    case UpdateCellOp u -> applyUpdateCell(wb, u);
                    case InsertRowOp r -> applyInsertRow(wb, r);
                    case DeleteRowOp r -> applyDeleteRow(wb, r);
                    case SetRangeOp r -> applySetRange(wb, r);
                };
                applied.add(result);
            } catch (XlsxPatchException e) {
                return new EngineResult(false, List.of(),
                        List.of(new FailedOp(i, opType(op), e.reason, e.matchCount, e.hint)));
            } catch (RuntimeException e) {
                log.warn("xlsx patch op #{} 执行异常：op={}, err={}", i, op.getClass().getSimpleName(), e.getMessage());
                return new EngineResult(false, List.of(),
                        List.of(new FailedOp(i, opType(op), "execution_error", -1, e.getMessage())));
            }
        }
        return new EngineResult(true, applied, List.of());
    }

    // ===== update_cell =====

    private AppliedXlsxOp applyUpdateCell(XSSFWorkbook wb, UpdateCellOp op) {
        Sheet sheet = requireSheet(wb, op.sheet());
        CellReference ref = CellAddressResolver.parseCell(op.cell())
                .orElseThrow(() -> XlsxPatchException.invalidAddress(
                        "invalid_cell_address", "cell 地址格式错误：" + op.cell()));
        checkNotInsideMergedRegion(sheet, ref);

        Row row = sheet.getRow(ref.getRow());
        if (row == null) row = sheet.createRow(ref.getRow());
        Cell cell = row.getCell(ref.getCol());
        if (cell == null) cell = row.createCell(ref.getCol());

        String before = cellToString(cell);
        writeCellValue(cell, op.newValue());
        return new AppliedXlsxOp(op, before, 1, 1);
    }

    /** 把 cell 当前内容转人类可读字符串；空 / 空 blank 返回 ""。 */
    static String cellToString(Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.BLANK) return "";
        if (cell.getCellType() == CellType.FORMULA) return "=" + cell.getCellFormula();
        return FORMATTER.formatCellValue(cell);
    }

    /** 按 spec §2.2 的类型规则写入 cell；保留 CellStyle（POI setCellValue 默认行为）。 */
    static void writeCellValue(Cell cell, Object value) {
        if (value == null) {
            cell.setBlank();
            return;
        }
        if (value instanceof Number n) {
            cell.setCellValue(n.doubleValue());
            return;
        }
        if (value instanceof Boolean b) {
            cell.setCellValue(b);
            return;
        }
        String s = value.toString();
        if (s.startsWith("=")) {
            cell.setCellFormula(s.substring(1));
        } else {
            cell.setCellValue(s);
        }
    }

    /** 若 ref 落在某合并区域内且不是 anchor，抛 cell_inside_merged_region。 */
    static void checkNotInsideMergedRegion(Sheet sheet, CellReference ref) {
        int r = ref.getRow();
        int c = ref.getCol();
        for (CellRangeAddress mr : sheet.getMergedRegions()) {
            if (mr.isInRange(r, c)) {
                if (r == mr.getFirstRow() && c == mr.getFirstColumn()) return; // anchor 放行
                String anchorRef = new CellReference(mr.getFirstRow(), mr.getFirstColumn()).formatAsString();
                throw XlsxPatchException.inMerged(
                        "cell_inside_merged_region",
                        "目标单元格在合并区域 " + mr.formatAsString() + " 内，请改用 anchor 地址 " + anchorRef + " 或先解除合并");
            }
        }
    }

    // ===== 其余 3 op 暂时抛未实现（Task 6 补） =====

    private AppliedXlsxOp applyInsertRow(XSSFWorkbook wb, InsertRowOp op) {
        throw new UnsupportedOperationException("Task 6 补齐");
    }

    private AppliedXlsxOp applyDeleteRow(XSSFWorkbook wb, DeleteRowOp op) {
        throw new UnsupportedOperationException("Task 6 补齐");
    }

    private AppliedXlsxOp applySetRange(XSSFWorkbook wb, SetRangeOp op) {
        throw new UnsupportedOperationException("Task 6 补齐");
    }

    // ===== 辅助 =====

    static Sheet requireSheet(XSSFWorkbook wb, String name) {
        Sheet s = wb.getSheet(name);
        if (s == null) {
            throw XlsxPatchException.notFound(
                    "sheet_not_found",
                    "工作表 " + name + " 不存在（区分大小写）");
        }
        return s;
    }

    static String opType(XlsxPatchOperation op) {
        return switch (op) {
            case UpdateCellOp u -> "update_cell";
            case InsertRowOp r -> "insert_row";
            case DeleteRowOp r -> "delete_row";
            case SetRangeOp r -> "set_range";
        };
    }

    /** 内部受检异常，apply 捕获转成 FailedOp。 */
    static final class XlsxPatchException extends RuntimeException {
        final String reason;
        final int matchCount;
        final String hint;

        private XlsxPatchException(String reason, int matchCount, String hint) {
            super(reason + ": " + hint);
            this.reason = reason;
            this.matchCount = matchCount;
            this.hint = hint;
        }

        static XlsxPatchException notFound(String reason, String hint) {
            return new XlsxPatchException(reason, 0, hint);
        }

        static XlsxPatchException invalidAddress(String reason, String hint) {
            return new XlsxPatchException(reason, -1, hint);
        }

        static XlsxPatchException inMerged(String reason, String hint) {
            return new XlsxPatchException(reason, -1, hint);
        }

        static XlsxPatchException invalidRow(String reason, String hint) {
            return new XlsxPatchException(reason, -1, hint);
        }

        static XlsxPatchException rangeMismatch(String reason, String hint) {
            return new XlsxPatchException(reason, -1, hint);
        }
    }
}
```

- [ ] **Step 3：update_cell 单元测试**

```java
package com.lifepilot.document.patch.xlsx;

import com.lifepilot.document.patch.UpdateCellOp;
import com.lifepilot.document.patch.XlsxPatchOperation;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XlsxPatchEngine 4 种 op 行为测试。Task 5 先覆盖 update_cell；Task 6 补齐 insert_row /
 * delete_row / set_range。
 *
 * @author zsg
 * @since 2026-04-21
 */
class XlsxPatchEngine_四种操作测试 {

    private final XlsxPatchEngine engine = new XlsxPatchEngine();

    @Test
    void update_cell_写字面量字符串() throws Exception {
        try (XSSFWorkbook wb = loadFixture("sample-sheet.xlsx")) {
            var op = new UpdateCellOp("Sheet1", "A2", "活页笔记本", "改名");
            var r = engine.apply(wb, List.of(op));

            assertThat(r.success()).isTrue();
            Cell c = wb.getSheet("Sheet1").getRow(1).getCell(0);
            assertThat(c.getCellType()).isEqualTo(CellType.STRING);
            assertThat(c.getStringCellValue()).isEqualTo("活页笔记本");
        }
    }

    @Test
    void update_cell_数字类型保留() throws Exception {
        try (XSSFWorkbook wb = loadFixture("sample-sheet.xlsx")) {
            var r = engine.apply(wb, List.of(new UpdateCellOp("Sheet1", "B2", 99, null)));
            assertThat(r.success()).isTrue();
            Cell c = wb.getSheet("Sheet1").getRow(1).getCell(1);
            assertThat(c.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(c.getNumericCellValue()).isEqualTo(99.0);
        }
    }

    @Test
    void update_cell_字符串100保持字符串不误转数字() throws Exception {
        try (XSSFWorkbook wb = loadFixture("sample-sheet.xlsx")) {
            var r = engine.apply(wb, List.of(new UpdateCellOp("Sheet1", "A2", "100", null)));
            assertThat(r.success()).isTrue();
            Cell c = wb.getSheet("Sheet1").getRow(1).getCell(0);
            assertThat(c.getCellType()).isEqualTo(CellType.STRING);
            assertThat(c.getStringCellValue()).isEqualTo("100");
        }
    }

    @Test
    void update_cell_公式前缀写成formula_cell() throws Exception {
        try (XSSFWorkbook wb = loadFixture("sample-sheet.xlsx")) {
            var r = engine.apply(wb, List.of(new UpdateCellOp("Sheet1", "D2", "=B2*C2", null)));
            assertThat(r.success()).isTrue();
            Cell c = wb.getSheet("Sheet1").getRow(1).getCell(3);
            assertThat(c.getCellType()).isEqualTo(CellType.FORMULA);
            assertThat(c.getCellFormula()).isEqualTo("B2*C2");
        }
    }

    @Test
    void update_cell_null值清空() throws Exception {
        try (XSSFWorkbook wb = loadFixture("sample-sheet.xlsx")) {
            var r = engine.apply(wb, List.of(new UpdateCellOp("Sheet1", "A2", null, null)));
            assertThat(r.success()).isTrue();
            Cell c = wb.getSheet("Sheet1").getRow(1).getCell(0);
            assertThat(c.getCellType()).isEqualTo(CellType.BLANK);
        }
    }

    @Test
    void update_cell_未知sheet报sheet_not_found() throws Exception {
        try (XSSFWorkbook wb = loadFixture("sample-sheet.xlsx")) {
            var r = engine.apply(wb, List.of(new UpdateCellOp("Ghost", "A1", "x", null)));
            assertThat(r.success()).isFalse();
            assertThat(r.failedOps()).singleElement()
                    .satisfies(f -> {
                        assertThat(f.reason()).isEqualTo("sheet_not_found");
                        assertThat(f.opIndex()).isZero();
                    });
        }
    }

    @Test
    void update_cell_非法地址报invalid_cell_address() throws Exception {
        try (XSSFWorkbook wb = loadFixture("sample-sheet.xlsx")) {
            var r = engine.apply(wb, List.of(new UpdateCellOp("Sheet1", "5B", "x", null)));
            assertThat(r.success()).isFalse();
            assertThat(r.failedOps()).singleElement()
                    .satisfies(f -> assertThat(f.reason()).isEqualTo("invalid_cell_address"));
        }
    }

    @Test
    void update_cell_合并区域中间被拒() throws Exception {
        try (XSSFWorkbook wb = loadFixture("sample-with-formulas.xlsx")) {
            // fixture 合并 B3:C3；anchor 是 B3；非 anchor 的 C3 改动应被拒
            var r = engine.apply(wb, List.of(new UpdateCellOp("Data", "C3", "x", null)));
            assertThat(r.success()).isFalse();
            assertThat(r.failedOps()).singleElement()
                    .satisfies(f -> assertThat(f.reason()).isEqualTo("cell_inside_merged_region"));
        }
    }

    @Test
    void update_cell_合并区域anchor允许修改() throws Exception {
        try (XSSFWorkbook wb = loadFixture("sample-with-formulas.xlsx")) {
            var r = engine.apply(wb, List.of(new UpdateCellOp("Data", "B3", "合并新标题", null)));
            assertThat(r.success()).isTrue();
        }
    }

    @Test
    void update_cell_事务性_二op失败后首op回滚() throws Exception {
        try (XSSFWorkbook wb = loadFixture("sample-sheet.xlsx")) {
            var ops = List.<XlsxPatchOperation>of(
                    new UpdateCellOp("Sheet1", "A2", "新产品", null),
                    new UpdateCellOp("Ghost", "A1", "x", null)  // 故意失败
            );
            var r = engine.apply(wb, ops);
            assertThat(r.success()).isFalse();
            // 内存里 wb 虽然被第 1 个 op 改过，但调用方会丢弃——此测试只断言 engine 返回 failure
            assertThat(r.appliedOps()).isEmpty();
        }
    }

    // ===== 辅助 =====

    private static XSSFWorkbook loadFixture(String name) throws Exception {
        Path p = Paths.get("src/test/resources/fixtures/document", name);
        try (InputStream in = Files.newInputStream(p)) {
            return new XSSFWorkbook(in);
        }
    }
}
```

- [ ] **Step 4：跑测试**

Run: `mvn test -q -Dtest='XlsxPatchEngine_四种操作测试'`
Expected: 10 个 @Test 全绿（update_cell 相关）。

- [ ] **Step 5：commit**

```bash
git add src/main/java/com/lifepilot/document/patch/xlsx/XlsxPatchEngine.java \
        src/main/java/com/lifepilot/document/patch/xlsx/AppliedXlsxOp.java \
        src/test/java/com/lifepilot/document/patch/xlsx/XlsxPatchEngine_四种操作测试.java
git commit -m "feat(document): Phase 3B Task 5 — XlsxPatchEngine 骨架 + update_cell"
```

---

## Task 6：XlsxPatchEngine 剩余 3 op（insert_row / delete_row / set_range）

**目的**：补齐 `applyInsertRow` / `applyDeleteRow` / `applySetRange`，处理 POI `shiftRows` 细节、合并单元格守卫、range 尺寸校验。测试覆盖公式刷新、边界行号、合并行拒删、set_range 尺寸不符。

**Files:**
- Modify: `src/main/java/com/lifepilot/document/patch/xlsx/XlsxPatchEngine.java`
- Modify: `src/test/java/com/lifepilot/document/patch/xlsx/XlsxPatchEngine_四种操作测试.java`

**前置阅读：**
- spec §2.2 insert_row / delete_row / set_range + §2.3 失败枚举
- POI `Sheet.shiftRows(startRow, endRow, n)` 文档（1-based 语义：第 `startRow` 行为 0-based 起点；`n > 0` 下移、`n < 0` 上移）

- [ ] **Step 1：替换 3 个 UnsupportedOperation 为真实实现**

在 `XlsxPatchEngine.java` 里，把 Task 5 占位的 `applyInsertRow` / `applyDeleteRow` / `applySetRange` 三个方法替换为：

```java
// ===== insert_row =====

private AppliedXlsxOp applyInsertRow(XSSFWorkbook wb, InsertRowOp op) {
    Sheet sheet = requireSheet(wb, op.sheet());
    int targetIdx = op.beforeRow() - 1;  // 转 0-based
    int lastRow = sheet.getLastRowNum();

    // 合法范围：[0, lastRow + 1]；等于 lastRow + 1 时等价于追加（空 sheet lastRow = -1）
    if (targetIdx < 0 || targetIdx > lastRow + 1) {
        throw XlsxPatchException.invalidRow(
                "invalid_row_number",
                "行号 " + op.beforeRow() + " 越界，合法范围 1.." + (lastRow + 2));
    }

    // 空 sheet 或追加：无需 shift，直接 createRow
    if (targetIdx > lastRow) {
        Row newRow = sheet.createRow(targetIdx);
        fillRow(newRow, op.values());
        return new AppliedXlsxOp(op, "", 1, op.values().size());
    }

    // 需要 shift：把 [targetIdx, lastRow] 整体下移 1（POI shiftRows 参数是 0-based 且闭区间）
    sheet.shiftRows(targetIdx, lastRow, 1);
    Row newRow = sheet.createRow(targetIdx);
    fillRow(newRow, op.values());
    return new AppliedXlsxOp(op, "", 1, op.values().size());
}

private static void fillRow(Row row, List<Object> values) {
    for (int c = 0; c < values.size(); c++) {
        Cell cell = row.createCell(c);
        writeCellValue(cell, values.get(c));
    }
}

// ===== delete_row =====

private AppliedXlsxOp applyDeleteRow(XSSFWorkbook wb, DeleteRowOp op) {
    Sheet sheet = requireSheet(wb, op.sheet());
    int idx = op.row() - 1;
    int lastRow = sheet.getLastRowNum();
    if (idx < 0 || idx > lastRow) {
        throw XlsxPatchException.invalidRow(
                "row_not_found", "行 " + op.row() + " 不存在");
    }
    // 合并区域守卫：若该行横跨任何合并区域，拒删
    for (CellRangeAddress mr : sheet.getMergedRegions()) {
        if (mr.getFirstRow() <= idx && idx <= mr.getLastRow()) {
            throw XlsxPatchException.inMerged(
                    "row_in_merged_region",
                    "目标行横跨合并区域 " + mr.formatAsString() + "，无法删除");
        }
    }
    String beforeSnapshot = rowSnapshot(sheet.getRow(idx));

    // POI 规则：先 removeRow 再 shiftRows 空位
    Row row = sheet.getRow(idx);
    if (row != null) sheet.removeRow(row);
    if (idx < lastRow) {
        sheet.shiftRows(idx + 1, lastRow, -1);
    }
    return new AppliedXlsxOp(op, beforeSnapshot, 1, 0);
}

/** 把一行所有 cell 用 "|" 拼成人类可读预览（供 diff 里 delete segment 使用）。 */
private static String rowSnapshot(Row row) {
    if (row == null) return "(空行)";
    StringBuilder sb = new StringBuilder();
    short last = row.getLastCellNum();
    for (int c = 0; c < last; c++) {
        if (sb.length() > 0) sb.append(" | ");
        sb.append(cellToString(row.getCell(c)));
    }
    return sb.toString();
}

// ===== set_range =====

private AppliedXlsxOp applySetRange(XSSFWorkbook wb, SetRangeOp op) {
    Sheet sheet = requireSheet(wb, op.sheet());
    CellRangeAddress addr = CellAddressResolver.parseRange(op.range())
            .orElseThrow(() -> XlsxPatchException.invalidAddress(
                    "invalid_range", "range 地址非法：" + op.range()));
    int rows = addr.getLastRow() - addr.getFirstRow() + 1;
    int cols = addr.getLastColumn() - addr.getFirstColumn() + 1;

    // 校验 values 2D 尺寸
    if (op.values().size() != rows) {
        throw XlsxPatchException.rangeMismatch(
                "range_size_mismatch",
                "values 外层长度 " + op.values().size() + " 与 range 行数 " + rows + " 不符");
    }
    for (int r = 0; r < rows; r++) {
        if (op.values().get(r).size() != cols) {
            throw XlsxPatchException.rangeMismatch(
                    "range_size_mismatch",
                    "values[" + r + "] 长度 " + op.values().get(r).size()
                            + " 与 range 列数 " + cols + " 不符");
        }
    }
    // 合并区域守卫：range 内若包含任何合并区域（完全在内 / 部分相交），整批拒
    List<String> clashing = new ArrayList<>();
    for (CellRangeAddress mr : sheet.getMergedRegions()) {
        if (mr.intersects(addr)) clashing.add(mr.formatAsString());
    }
    if (!clashing.isEmpty()) {
        throw XlsxPatchException.inMerged(
                "range_contains_merged_region",
                "range " + addr.formatAsString() + " 包含合并区域 " + clashing + "，请拆分操作");
    }

    // 全部写入
    for (int r = 0; r < rows; r++) {
        int absRow = addr.getFirstRow() + r;
        Row row = sheet.getRow(absRow);
        if (row == null) row = sheet.createRow(absRow);
        for (int c = 0; c < cols; c++) {
            int absCol = addr.getFirstColumn() + c;
            Cell cell = row.getCell(absCol);
            if (cell == null) cell = row.createCell(absCol);
            writeCellValue(cell, op.values().get(r).get(c));
        }
    }
    return new AppliedXlsxOp(op, rows + "x" + cols + " 批量（预览略）", rows, cols);
}
```

**注意**：`CellRangeAddress.intersects` 是 POI 自带的区域相交判定。

- [ ] **Step 2：补测试（追加到 Task 5 已建的测试类）**

在 `XlsxPatchEngine_四种操作测试.java` 追加：

```java
@Test
void insert_row_中间插入_下方公式自动刷新() throws Exception {
    try (XSSFWorkbook wb = loadFixture("sample-with-formulas.xlsx")) {
        // 原 D5 = B5+C5；在第 5 行（索引 4）前插入 → 原 D5 变 D6，公式应变为 =B6+C6
        var r = engine.apply(wb, List.of(
                new InsertRowOp("Data", 5, List.of("新项目", 10, 20, null), null)));
        assertThat(r.success()).isTrue();

        Cell moved = wb.getSheet("Data").getRow(5).getCell(3);
        assertThat(moved.getCellType()).isEqualTo(CellType.FORMULA);
        assertThat(moved.getCellFormula()).isEqualTo("B6+C6");
    }
}

@Test
void insert_row_追加到末尾_无需shift() throws Exception {
    try (XSSFWorkbook wb = loadFixture("sample-sheet.xlsx")) {
        int lastBefore = wb.getSheet("Sheet1").getLastRowNum();
        var r = engine.apply(wb, List.of(
                new InsertRowOp("Sheet1", lastBefore + 2, List.of("追加", 1, 2), null)));
        assertThat(r.success()).isTrue();
        assertThat(wb.getSheet("Sheet1").getLastRowNum()).isEqualTo(lastBefore + 1);
    }
}

@Test
void insert_row_越界行号被拒() throws Exception {
    try (XSSFWorkbook wb = loadFixture("sample-sheet.xlsx")) {
        int lastBefore = wb.getSheet("Sheet1").getLastRowNum();
        var r = engine.apply(wb, List.of(
                new InsertRowOp("Sheet1", lastBefore + 10, List.of("x"), null)));
        assertThat(r.success()).isFalse();
        assertThat(r.failedOps()).singleElement()
                .satisfies(f -> assertThat(f.reason()).isEqualTo("invalid_row_number"));
    }
}

@Test
void delete_row_简单删除() throws Exception {
    try (XSSFWorkbook wb = loadFixture("sample-sheet.xlsx")) {
        var r = engine.apply(wb, List.of(new DeleteRowOp("Sheet1", 2, null)));
        assertThat(r.success()).isTrue();
        // 原第 3 行（橡皮 / 50 / 0.5）上移到第 2 行
        Cell c = wb.getSheet("Sheet1").getRow(1).getCell(0);
        assertThat(c.getStringCellValue()).isEqualTo("橡皮");
    }
}

@Test
void delete_row_跨合并区域被拒() throws Exception {
    try (XSSFWorkbook wb = loadFixture("sample-with-formulas.xlsx")) {
        // 第 3 行 B3:C3 是合并区域
        var r = engine.apply(wb, List.of(new DeleteRowOp("Data", 3, null)));
        assertThat(r.success()).isFalse();
        assertThat(r.failedOps()).singleElement()
                .satisfies(f -> assertThat(f.reason()).isEqualTo("row_in_merged_region"));
    }
}

@Test
void delete_row_不存在返回row_not_found() throws Exception {
    try (XSSFWorkbook wb = loadFixture("sample-sheet.xlsx")) {
        var r = engine.apply(wb, List.of(new DeleteRowOp("Sheet1", 999, null)));
        assertThat(r.success()).isFalse();
        assertThat(r.failedOps()).singleElement()
                .satisfies(f -> assertThat(f.reason()).isEqualTo("row_not_found"));
    }
}

@Test
void set_range_写入3x2矩形() throws Exception {
    try (XSSFWorkbook wb = loadFixture("sample-sheet.xlsx")) {
        List<List<Object>> values = List.of(
                List.of("A1", 1),
                List.of("B1", 2),
                List.of("C1", 3));
        var r = engine.apply(wb, List.of(new SetRangeOp("Sheet1", "A1:B3", values, null)));
        assertThat(r.success()).isTrue();
        assertThat(wb.getSheet("Sheet1").getRow(0).getCell(0).getStringCellValue()).isEqualTo("A1");
        assertThat(wb.getSheet("Sheet1").getRow(2).getCell(1).getNumericCellValue()).isEqualTo(3.0);
    }
}

@Test
void set_range_values形状不符被拒() throws Exception {
    try (XSSFWorkbook wb = loadFixture("sample-sheet.xlsx")) {
        List<List<Object>> bad = List.of(List.of("x"), List.of("y"));  // 2 行 1 列
        var r = engine.apply(wb, List.of(new SetRangeOp("Sheet1", "A1:B3", bad, null)));
        assertThat(r.success()).isFalse();
        assertThat(r.failedOps()).singleElement()
                .satisfies(f -> assertThat(f.reason()).isEqualTo("range_size_mismatch"));
    }
}

@Test
void set_range_触碰合并区域被拒() throws Exception {
    try (XSSFWorkbook wb = loadFixture("sample-with-formulas.xlsx")) {
        // 合并 B3:C3；让 range 与其相交
        List<List<Object>> vals = List.of(
                List.of(1, 2, 3),
                List.of(4, 5, 6),
                List.of(7, 8, 9));
        var r = engine.apply(wb, List.of(new SetRangeOp("Data", "A2:C4", vals, null)));
        assertThat(r.success()).isFalse();
        assertThat(r.failedOps()).singleElement()
                .satisfies(f -> assertThat(f.reason()).isEqualTo("range_contains_merged_region"));
    }
}
```

- [ ] **Step 3：跑测试**

Run: `mvn test -q -Dtest='XlsxPatchEngine_四种操作测试'`
Expected: 所有 @Test（含 Task 5 + Task 6 补）全绿。

- [ ] **Step 4：commit**

```bash
git add src/main/java/com/lifepilot/document/patch/xlsx/XlsxPatchEngine.java \
        src/test/java/com/lifepilot/document/patch/xlsx/XlsxPatchEngine_四种操作测试.java
git commit -m "feat(document): Phase 3B Task 6 — XlsxPatchEngine 补齐 insert_row / delete_row / set_range"
```

---

## Task 7：XlsxDiffBuilder —— xlsx diff JSON 构造

**目的**：按已应用的 `AppliedXlsxOp` 列表生成 xlsx 变体 diff JSON（顶层 `mime: "xlsx"`，change 里含 `sheet` / `cell` / `range` / `row` / `rows` / `cols`）。对齐 `DocxDiffBuilder` 的返回契约（`build(...) → String` / `summarize(...)`）。

**Files:**
- Create: `src/main/java/com/lifepilot/document/patch/xlsx/XlsxDiffBuilder.java`
- Test: `src/test/java/com/lifepilot/document/patch/xlsx/XlsxDiffBuilder_构造测试.java`

**前置阅读：**
- `src/main/java/com/lifepilot/document/patch/docx/DocxDiffBuilder.java`（build / summarize 模式）
- spec §2.4（xlsx diff JSON 结构示意）

- [ ] **Step 1：XlsxDiffBuilder 实现**

```java
package com.lifepilot.document.patch.xlsx;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.document.patch.DeleteRowOp;
import com.lifepilot.document.patch.InsertRowOp;
import com.lifepilot.document.patch.SetRangeOp;
import com.lifepilot.document.patch.UpdateCellOp;
import com.lifepilot.document.patch.XlsxPatchOperation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * xlsx diff JSON 构造器 —— 对齐 DocxDiffBuilder 的输出契约，额外顶层 mime 字段。
 *
 * <p>每个 op 一条 change；{@code update_cell} 双段（delete before / insert new）；其它
 * op 单段（insert 或 delete）。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public class XlsxDiffBuilder {

    private final ObjectMapper mapper = new ObjectMapper();

    public String build(String documentId, int fromVersion, int toVersion,
                        List<AppliedXlsxOp> applied) throws JsonProcessingException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("documentId", documentId);
        root.put("fromVersion", fromVersion);
        root.put("toVersion", toVersion);
        root.put("mime", "xlsx");
        root.put("summary", summarize(applied));

        List<Map<String, Object>> changes = new ArrayList<>();
        for (AppliedXlsxOp a : applied) {
            changes.add(changeEntry(a));
        }
        root.put("changes", changes);

        return mapper.writeValueAsString(root);
    }

    public String summarize(List<AppliedXlsxOp> applied) {
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (AppliedXlsxOp a : applied) {
            String k = XlsxPatchEngine.opType(a.op());
            counter.merge(k, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder("共 ").append(applied.size()).append(" 处修改");
        if (!counter.isEmpty()) {
            sb.append("(");
            boolean first = true;
            for (var e : counter.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(e.getKey()).append(" x").append(e.getValue());
                first = false;
            }
            sb.append(")");
        }
        return sb.toString();
    }

    private Map<String, Object> changeEntry(AppliedXlsxOp a) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("patch_id", UUID.randomUUID().toString());
        entry.put("op", XlsxPatchEngine.opType(a.op()));

        switch (a.op()) {
            case UpdateCellOp u -> {
                entry.put("sheet", u.sheet());
                entry.put("cell", u.cell().toUpperCase());
                String afterPreview = valuePreview(u.newValue());
                entry.put("segments", List.of(
                        segment("delete", a.beforeSnapshot()),
                        segment("insert", afterPreview)
                ));
                entry.put("reason", u.reason() == null ? "" : u.reason());
            }
            case InsertRowOp ins -> {
                entry.put("sheet", ins.sheet());
                entry.put("row", ins.beforeRow());
                String preview = valueRowPreview(ins.values());
                entry.put("segments", List.of(segment("insert", preview)));
                entry.put("reason", ins.reason() == null ? "" : ins.reason());
            }
            case DeleteRowOp del -> {
                entry.put("sheet", del.sheet());
                entry.put("row", del.row());
                entry.put("segments", List.of(segment("delete", a.beforeSnapshot())));
                entry.put("reason", del.reason() == null ? "" : del.reason());
            }
            case SetRangeOp sr -> {
                entry.put("sheet", sr.sheet());
                entry.put("range", sr.range().toUpperCase());
                entry.put("rows", a.rowsAffected());
                entry.put("cols", a.colsAffected());
                entry.put("segments", List.of(segment("insert",
                        a.rowsAffected() + "x" + a.colsAffected() + " 批量填充（预览略）")));
                entry.put("reason", sr.reason() == null ? "" : sr.reason());
            }
        }
        return entry;
    }

    /** 把 cell 值（可能是 null / 数字 / 布尔 / 字符串 / 公式）转人类可读预览。 */
    static String valuePreview(Object value) {
        if (value == null) return "(空)";
        if (value instanceof String s && s.startsWith("=")) return "ƒx " + s;
        return value.toString();
    }

    /** 一行 values 用 "|" 拼预览。 */
    static String valueRowPreview(List<Object> values) {
        StringBuilder sb = new StringBuilder();
        for (Object v : values) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(valuePreview(v));
        }
        return sb.toString();
    }

    private static Map<String, Object> segment(String type, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("text", text == null ? "" : text);
        return m;
    }
}
```

- [ ] **Step 2：测试**

```java
package com.lifepilot.document.patch.xlsx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.document.patch.DeleteRowOp;
import com.lifepilot.document.patch.InsertRowOp;
import com.lifepilot.document.patch.SetRangeOp;
import com.lifepilot.document.patch.UpdateCellOp;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XlsxDiffBuilder 输出结构测试 —— 4 种 op 各覆盖一条 change，断言顶层 mime 和字段完整。
 *
 * @author zsg
 * @since 2026-04-21
 */
class XlsxDiffBuilder_构造测试 {

    private final XlsxDiffBuilder builder = new XlsxDiffBuilder();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void update_cell_双段delete_insert() throws Exception {
        var applied = List.of(new AppliedXlsxOp(
                new UpdateCellOp("Sheet1", "B5", "15", "缩短付款期限"),
                "30", 1, 1));
        JsonNode root = mapper.readTree(builder.build("d1", 0, 1, applied));

        assertThat(root.get("mime").asText()).isEqualTo("xlsx");
        assertThat(root.get("summary").asText()).contains("共 1 处修改");
        JsonNode change = root.get("changes").get(0);
        assertThat(change.get("op").asText()).isEqualTo("update_cell");
        assertThat(change.get("sheet").asText()).isEqualTo("Sheet1");
        assertThat(change.get("cell").asText()).isEqualTo("B5");
        JsonNode segs = change.get("segments");
        assertThat(segs.get(0).get("type").asText()).isEqualTo("delete");
        assertThat(segs.get(0).get("text").asText()).isEqualTo("30");
        assertThat(segs.get(1).get("type").asText()).isEqualTo("insert");
        assertThat(segs.get(1).get("text").asText()).isEqualTo("15");
        assertThat(change.get("reason").asText()).isEqualTo("缩短付款期限");
    }

    @Test
    void update_cell_公式预览带fx() throws Exception {
        var applied = List.of(new AppliedXlsxOp(
                new UpdateCellOp("Sheet1", "D1", "=SUM(A1:A4)", null),
                "0", 1, 1));
        JsonNode root = mapper.readTree(builder.build("d1", 0, 1, applied));
        String afterText = root.get("changes").get(0).get("segments").get(1).get("text").asText();
        assertThat(afterText).isEqualTo("ƒx =SUM(A1:A4)");
    }

    @Test
    void insert_row_记录row和segments_insert单段() throws Exception {
        var applied = List.of(new AppliedXlsxOp(
                new InsertRowOp("Sheet1", 5, List.of("新产品", 100, "2026-04-21"), "新增"),
                "", 1, 3));
        JsonNode root = mapper.readTree(builder.build("d1", 0, 1, applied));
        JsonNode change = root.get("changes").get(0);
        assertThat(change.get("op").asText()).isEqualTo("insert_row");
        assertThat(change.get("row").asInt()).isEqualTo(5);
        assertThat(change.get("segments").get(0).get("type").asText()).isEqualTo("insert");
        assertThat(change.get("segments").get(0).get("text").asText())
                .isEqualTo("新产品 | 100 | 2026-04-21");
    }

    @Test
    void delete_row_segments_delete_single() throws Exception {
        var applied = List.of(new AppliedXlsxOp(
                new DeleteRowOp("Sheet1", 8, "过期"),
                "A8val | B8val | C8val", 1, 0));
        JsonNode root = mapper.readTree(builder.build("d1", 0, 1, applied));
        JsonNode change = root.get("changes").get(0);
        assertThat(change.get("op").asText()).isEqualTo("delete_row");
        assertThat(change.get("row").asInt()).isEqualTo(8);
        assertThat(change.get("segments").get(0).get("type").asText()).isEqualTo("delete");
        assertThat(change.get("segments").get(0).get("text").asText())
                .isEqualTo("A8val | B8val | C8val");
    }

    @Test
    void set_range_带rows_cols和insert摘要() throws Exception {
        var applied = List.of(new AppliedXlsxOp(
                new SetRangeOp("Sheet1", "B2:D4",
                        List.of(List.of(1, 2, 3), List.of(4, 5, 6), List.of(7, 8, 9)), null),
                "3x3 批量（预览略）", 3, 3));
        JsonNode root = mapper.readTree(builder.build("d1", 0, 1, applied));
        JsonNode change = root.get("changes").get(0);
        assertThat(change.get("op").asText()).isEqualTo("set_range");
        assertThat(change.get("range").asText()).isEqualTo("B2:D4");
        assertThat(change.get("rows").asInt()).isEqualTo(3);
        assertThat(change.get("cols").asInt()).isEqualTo(3);
        assertThat(change.get("segments").get(0).get("text").asText()).contains("3x3 批量");
    }

    @Test
    void summarize_含op计数() {
        var applied = List.of(
                new AppliedXlsxOp(new UpdateCellOp("Sheet1", "A1", 1, null), "0", 1, 1),
                new AppliedXlsxOp(new UpdateCellOp("Sheet1", "A2", 2, null), "0", 1, 1),
                new AppliedXlsxOp(new InsertRowOp("Sheet1", 5, List.of("x"), null), "", 1, 1));
        assertThat(builder.summarize(applied))
                .isEqualTo("共 3 处修改(update_cell x2, insert_row x1)");
    }
}
```

- [ ] **Step 3：跑测试**

Run: `mvn test -q -Dtest='XlsxDiffBuilder_构造测试'`
Expected: 6 个 @Test 全绿。

- [ ] **Step 4：commit**

```bash
git add src/main/java/com/lifepilot/document/patch/xlsx/XlsxDiffBuilder.java \
        src/test/java/com/lifepilot/document/patch/xlsx/XlsxDiffBuilder_构造测试.java
git commit -m "feat(document): Phase 3B Task 7 — XlsxDiffBuilder 构造 xlsx diff JSON"
```

---

## Task 8：DocumentVersionService 扩展 —— MIME 分支 + xlsx checkout + 扩展名参数化

**目的**：把 `DocumentVersionService` 改造成同时支持 docx / xlsx：工作副本文件扩展名按 mimeType 决定；`checkoutFromPath` / `checkoutFromAttachment` 的白名单扩到 `.xlsx`；`applyPatch` 按 mimeType 分支投喂对应 engine / diffBuilder；引入 `XLSX_MIME` 常量。

**Files:**
- Modify: `src/main/java/com/lifepilot/document/version/DocumentVersionService.java`
- Create: `src/test/java/com/lifepilot/document/version/DocumentVersionService_xlsx生命周期测试.java`

**前置阅读：**
- `src/main/java/com/lifepilot/document/version/DocumentVersionService.java`（Phase 3A 全文，特别是 `DOCX_MIME` 常量、`workingPath`、`checkoutFromPath`、`checkoutFromAttachment`、`applyPatch`）
- `src/test/java/com/lifepilot/document/version/DocumentVersionService_生命周期测试.java`（docx 生命周期测试，xlsx 测试可照猫画虎）
- spec §5.4（service 改造要点）

- [ ] **Step 1：引入 MIME 常量 + 扩展名工具**

在 `DocumentVersionService.java` 顶部常量区：

```java
public static final String DOCX_MIME =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
public static final String XLSX_MIME =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
```

（原先的 `private static final String DOCX_MIME` 改为 `public static final`，并新增 `XLSX_MIME`。）

在类尾部加：

```java
/** 按 mimeType 返回工作副本的文件扩展名（含点）。 */
private static String workingExtension(String mimeType) {
    return switch (mimeType) {
        case DOCX_MIME -> ".docx";
        case XLSX_MIME -> ".xlsx";
        default -> throw new IllegalArgumentException("不支持的 MIME：" + mimeType);
    };
}
```

- [ ] **Step 2：把 workingPath 参数化**

替换原 `workingPath` 方法为：

```java
private Path workingPath(SessionDocumentRecord record, int version) {
    return workingPath(record.sessionId(), record.id(), version, record.mimeType());
}

private Path workingPath(String sessionId, String documentId, int version, String mimeType) {
    return Paths.get(storageDir, sessionId, "working", documentId,
            "v" + version + workingExtension(mimeType));
}
```

原先 `private Path workingPath(String sessionId, String documentId, int version)` 签名删除。所有调用点改为传 `record` 或 `(sessionId, documentId, version, mimeType)`。

- [ ] **Step 3：扩展 checkoutFromPath / checkoutFromAttachment 白名单**

把 `checkoutFromPath` 的扩展名检查改为：

```java
String lower = sourcePath.toLowerCase();
boolean supportedExt = lower.endsWith(".docx") || lower.endsWith(".xlsx");
if (!supportedExt) {
    throw new IllegalArgumentException("只支持 .docx / .xlsx 源文件：" + sourcePath);
}
String inferredMime = lower.endsWith(".xlsx") ? XLSX_MIME : DOCX_MIME;
```

把原先写死 `DOCX_MIME` 的 `SessionDocumentRecord` 构造改为 `inferredMime`。`workingPath` 调用改为传 `inferredMime`：

```java
Path workingV0 = workingPath(sessionId, documentId, 0, inferredMime);
```

`checkoutFromAttachment` 同款扩展：

```java
String name = att.fileName() == null ? "" : att.fileName().toLowerCase();
boolean extDocx = name.endsWith(".docx");
boolean extXlsx = name.endsWith(".xlsx");
boolean mimeDocx = DOCX_MIME.equals(att.mimeType());
boolean mimeXlsx = XLSX_MIME.equals(att.mimeType());
if (!(extDocx || extXlsx || mimeDocx || mimeXlsx)) {
    throw new IllegalArgumentException(
            "只支持 .docx / .xlsx 附件：fileName=" + att.fileName() + ", mimeType=" + att.mimeType());
}
String inferredMime = (extXlsx || mimeXlsx) ? XLSX_MIME : DOCX_MIME;
// workingPath 用 inferredMime；SessionDocumentRecord 的 mimeType 也用 inferredMime
```

`checkoutFromDocument` 不需要改（mimeType 从已有 record 继承）。但 `workingPath` 调用要改为传 mime：

```java
Path workingV0 = workingPath(existing.sessionId(), documentId, 0, existing.mimeType());
```

- [ ] **Step 4：applyPatch 按 MIME 分支**

改造 `applyPatch` 签名为：

```java
public DocumentPatchResult applyPatch(String documentId, List<DocumentPatchOperation> ops)
        throws IOException, JsonProcessingException {
    var record = requireDocument(documentId);
    return switch (record.mimeType()) {
        case DOCX_MIME -> applyDocxPatch(record, castDocxOps(ops));
        case XLSX_MIME -> applyXlsxPatch(record, castXlsxOps(ops));
        default -> throw new IllegalStateException("不支持的 MIME：" + record.mimeType());
    };
}
```

构造器加依赖：

```java
private final XlsxPatchEngine xlsxEngine;
private final XlsxDiffBuilder xlsxDiffBuilder;

public DocumentVersionService(SessionDocumentRepository documentRepository,
                              DocumentVersionRepository versionRepository,
                              AttachmentRepository attachmentRepository,
                              DocxPatchEngine engine,
                              DocxDiffBuilder diffBuilder,
                              XlsxPatchEngine xlsxEngine,
                              XlsxDiffBuilder xlsxDiffBuilder,
                              String storageDir,
                              PathSecurityChecker pathSecurityChecker) {
    this.documentRepository = documentRepository;
    this.versionRepository = versionRepository;
    this.attachmentRepository = attachmentRepository;
    this.engine = engine;
    this.diffBuilder = diffBuilder;
    this.xlsxEngine = xlsxEngine;
    this.xlsxDiffBuilder = xlsxDiffBuilder;
    this.storageDir = storageDir;
    this.pathSecurityChecker = pathSecurityChecker;
}
```

把**原来的 applyPatch 主体**重命名为 `applyDocxPatch(record, castDocxOps)`，把里面 `workingPath(...)` 调用改为 `workingPath(record, nextVersion)`。`List<DocumentPatchOperation> ops` 参数改为 `List<DocxPatchOperation> ops`，engine 调用 `engine.apply(doc, ops)` 不变。

新增 `applyXlsxPatch`：

```java
private DocumentPatchResult applyXlsxPatch(SessionDocumentRecord record,
                                           List<XlsxPatchOperation> ops)
        throws IOException, JsonProcessingException {
    int nextVersion = record.latestVersion() + 1;
    Path currentFile = Paths.get(record.filePath());
    Path nextFile = workingPath(record, nextVersion);
    Files.createDirectories(nextFile.getParent());

    try (InputStream in = Files.newInputStream(currentFile);
         XSSFWorkbook wb = new XSSFWorkbook(in)) {
        var engineResult = xlsxEngine.apply(wb, ops);
        if (!engineResult.success()) {
            return DocumentPatchResult.failure(engineResult.failedOps());
        }
        byte[] bytes = serializeXlsx(wb);
        Files.write(nextFile, bytes);

        String diffJson = xlsxDiffBuilder.build(record.id(), record.latestVersion(), nextVersion,
                engineResult.appliedOps());
        String summary = xlsxDiffBuilder.summarize(engineResult.appliedOps());

        versionRepository.save(new DocumentVersionRecord(
                UUID.randomUUID().toString(), record.id(), nextVersion, nextFile.toString(),
                DocumentVersionRecord.SOURCE_PATCH, summary, diffJson, Instant.now()));
        documentRepository.updateLatestVersion(record.id(), nextVersion);
        documentRepository.updateFilePath(record.id(), nextFile.toString(), bytes.length);
        attachmentRepository.updateSizeByFilePath(nextFile.toString(), (long) bytes.length);

        log.info("xlsx patch 成功：documentId={}, version={}→{}, {}",
                record.id(), record.latestVersion(), nextVersion, summary);
        return DocumentPatchResult.success(nextVersion, diffJson, summary);
    }
}

private byte[] serializeXlsx(XSSFWorkbook wb) throws IOException {
    try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
        wb.write(bos);
        return bos.toByteArray();
    }
}

/** 断言所有 op 都是 DocxPatchOperation；否则抛清晰错误（跨 MIME 混用保护）。 */
private static List<DocxPatchOperation> castDocxOps(List<DocumentPatchOperation> ops) {
    List<DocxPatchOperation> result = new ArrayList<>(ops.size());
    for (int i = 0; i < ops.size(); i++) {
        DocumentPatchOperation op = ops.get(i);
        if (!(op instanceof DocxPatchOperation d)) {
            throw new IllegalArgumentException(
                    "op #" + i + " 不是 docx 操作（" + op.getClass().getSimpleName()
                            + "），目标文档是 docx 类型");
        }
        result.add(d);
    }
    return result;
}

private static List<XlsxPatchOperation> castXlsxOps(List<DocumentPatchOperation> ops) {
    List<XlsxPatchOperation> result = new ArrayList<>(ops.size());
    for (int i = 0; i < ops.size(); i++) {
        DocumentPatchOperation op = ops.get(i);
        if (!(op instanceof XlsxPatchOperation x)) {
            throw new IllegalArgumentException(
                    "op #" + i + " 不是 xlsx 操作（" + op.getClass().getSimpleName()
                            + "），目标文档是 xlsx 类型");
        }
        result.add(x);
    }
    return result;
}
```

相应 import：`org.apache.poi.xssf.usermodel.XSSFWorkbook`、`com.lifepilot.document.patch.XlsxPatchOperation`、`com.lifepilot.document.patch.xlsx.XlsxPatchEngine`、`com.lifepilot.document.patch.xlsx.XlsxDiffBuilder`。

- [ ] **Step 5：rollback / discard 扩展名参数化**

`rollback` 方法里原 `workingPath(record.sessionId(), documentId, nextVersion)` 改为 `workingPath(record, nextVersion)`。

`discard` 方法里 working 目录路径不需要按扩展名分（它删的是整个 `working/{documentId}/` 目录），不改。

- [ ] **Step 6：写 xlsx 生命周期测试**

```java
package com.lifepilot.document.version;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.document.model.SessionDocumentRecord;
import com.lifepilot.document.patch.UpdateCellOp;
import com.lifepilot.document.patch.DocumentPatchOperation;
import com.lifepilot.document.patch.ReplaceTextOp;
import com.lifepilot.document.patch.docx.DocxDiffBuilder;
import com.lifepilot.document.patch.docx.DocxPatchEngine;
import com.lifepilot.document.patch.docx.TextAnchorLocator;
import com.lifepilot.document.patch.xlsx.XlsxDiffBuilder;
import com.lifepilot.document.patch.xlsx.XlsxPatchEngine;
import com.lifepilot.document.repository.DocumentVersionRepository;
import com.lifepilot.document.repository.SessionDocumentRepository;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.meta.infra.file.PathSecurityChecker;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DocumentVersionService xlsx 生命周期集成测试 —— checkout / patch / rollback / commit / discard
 * 对 xlsx mimeType 的文档同样适用；跨 MIME 混用的 op 被 cast 辅助方法拦截。
 *
 * @author zsg
 * @since 2026-04-21
 */
class DocumentVersionService_xlsx生命周期测试 {

    @TempDir
    Path storageDir;

    @Test
    void checkout_xlsx路径_写入session_documents并落v0() throws Exception {
        var svc = newService(storageDir);
        Path sample = copyFixtureTo("sample-sheet.xlsx", storageDir.resolve("source.xlsx"));

        String documentId = svc.checkout("sess-1",
                new com.lifepilot.document.version.SourceRef.PathSource(sample.toString()));

        assertThat(documentId).isNotBlank();
        Path v0 = storageDir.resolve("sess-1/working/" + documentId + "/v0.xlsx");
        assertThat(Files.exists(v0)).isTrue();
    }

    @Test
    void apply_xlsx_patch_成功生成v1且新版本文件可读() throws Exception {
        var svc = newService(storageDir);
        Path sample = copyFixtureTo("sample-sheet.xlsx", storageDir.resolve("source.xlsx"));
        String documentId = svc.checkout("sess-2",
                new com.lifepilot.document.version.SourceRef.PathSource(sample.toString()));

        var result = svc.applyPatch(documentId, List.<DocumentPatchOperation>of(
                new UpdateCellOp("Sheet1", "A2", "活页笔记本", null)));

        assertThat(result.success()).isTrue();
        assertThat(result.newVersion()).isEqualTo(1);

        // 物理读 v1.xlsx 断言改动落地
        Path v1 = storageDir.resolve("sess-2/working/" + documentId + "/v1.xlsx");
        try (InputStream in = Files.newInputStream(v1);
             XSSFWorkbook wb = new XSSFWorkbook(in)) {
            Cell c = wb.getSheet("Sheet1").getRow(1).getCell(0);
            assertThat(c.getStringCellValue()).isEqualTo("活页笔记本");
        }
    }

    @Test
    void apply_xlsx_patch_diff_JSON包含mime_xlsx() throws Exception {
        var svc = newService(storageDir);
        Path sample = copyFixtureTo("sample-sheet.xlsx", storageDir.resolve("src.xlsx"));
        String documentId = svc.checkout("sess-3",
                new com.lifepilot.document.version.SourceRef.PathSource(sample.toString()));

        var r = svc.applyPatch(documentId, List.<DocumentPatchOperation>of(
                new UpdateCellOp("Sheet1", "A2", "X", null)));
        JsonNode root = new ObjectMapper().readTree(r.diffJson());
        assertThat(root.get("mime").asText()).isEqualTo("xlsx");
    }

    @Test
    void 跨MIME混用被拒_docx_op发往xlsx文档() throws Exception {
        var svc = newService(storageDir);
        Path sample = copyFixtureTo("sample-sheet.xlsx", storageDir.resolve("src.xlsx"));
        String documentId = svc.checkout("sess-4",
                new com.lifepilot.document.version.SourceRef.PathSource(sample.toString()));

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> svc.applyPatch(documentId,
                        List.<DocumentPatchOperation>of(
                                new ReplaceTextOp("", "X", "", "Y", null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是 xlsx 操作");
    }

    // ===== 辅助：装配真实 SQLite + 依赖链 =====

    private static DocumentVersionService newService(Path storageDir) throws Exception {
        DataSource ds = inMemorySqlite();
        runFlyway(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        var docRepo = new SessionDocumentRepository(jdbc);
        var verRepo = new DocumentVersionRepository(jdbc);
        var attRepo = new AttachmentRepository(jdbc);

        var locator = new TextAnchorLocator();
        var docxEngine = new DocxPatchEngine(locator);
        var docxDiff = new DocxDiffBuilder();
        var xlsxEngine = new XlsxPatchEngine();
        var xlsxDiff = new XlsxDiffBuilder();

        var meta = new MetaProperties();  // 使用默认 file 白名单配置
        var pathChecker = new PathSecurityChecker(meta.getInfra().getFile());

        return new DocumentVersionService(
                docRepo, verRepo, attRepo,
                docxEngine, docxDiff, xlsxEngine, xlsxDiff,
                storageDir.toString(), pathChecker);
    }

    private static DataSource inMemorySqlite() throws Exception {
        SQLiteDataSource raw = new SQLiteDataSource();
        raw.setUrl("jdbc:sqlite::memory:");
        // 用 SingleConnectionDataSource 确保多次 getConnection 拿同一 in-memory 连接
        return new SingleConnectionDataSource(raw.getConnection(), true);
    }

    private static void runFlyway(DataSource ds) {
        org.flywaydb.core.Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private static Path copyFixtureTo(String name, Path target) throws Exception {
        Path src = Paths.get("src/test/resources/fixtures/document", name);
        Files.copy(src, target);
        return target;
    }
}
```

**注意 1**：如果项目既有 P3A `DocumentVersionService_生命周期测试` 已有 `inMemorySqlite` + `runFlyway` 模板，直接 copy 其风格；若它抽到了共享 `testsupport` 工具，本测试直接复用即可（可能需要调整 import 路径，按实际情况而定）。

**注意 2（P3A 测试必须同步修复）**：本 Task 把 `DocumentVersionService` 构造器从 7 参扩到 9 参，P3A 的 `DocumentVersionService_生命周期测试.java` 里的 `new DocumentVersionService(...)` 调用会编译失败。打开该测试文件，在构造调用处把原来的 7 参：

```java
return new DocumentVersionService(
        docRepo, verRepo, attRepo,
        docxEngine, docxDiff,
        storageDir.toString(), pathChecker);
```

改为 9 参（新增 xlsxEngine / xlsxDiff 在 docxDiff 之后、storageDir 之前）：

```java
return new DocumentVersionService(
        docRepo, verRepo, attRepo,
        docxEngine, docxDiff,
        new com.lifepilot.document.patch.xlsx.XlsxPatchEngine(),
        new com.lifepilot.document.patch.xlsx.XlsxDiffBuilder(),
        storageDir.toString(), pathChecker);
```

xlsx engine / diff builder 都是无状态的，直接 `new` 即可。

- [ ] **Step 7：跑测试 + P3A 全量回归**

Run:
```bash
mvn test -q -Dtest='DocumentVersionService_xlsx生命周期测试 DocumentVersionService_生命周期测试'
```

Expected: P3A 生命周期测试（docx）和新加的 xlsx 生命周期测试全绿。

- [ ] **Step 8：commit**

```bash
git add src/main/java/com/lifepilot/document/version/DocumentVersionService.java \
        src/test/java/com/lifepilot/document/version/DocumentVersionService_xlsx生命周期测试.java
git commit -m "feat(document): Phase 3B Task 8 — DocumentVersionService 按 MIME 分支 + xlsx checkout/applyPatch"
```

---

## Task 9：DocumentEditActionDispatchExecutor parseOneOp 扩 4 个 xlsx op

**目的**：让 `document.edit` 工具的 `operations` 参数能识别 4 个 xlsx op（`update_cell` / `insert_row` / `delete_row` / `set_range`）。JSON → record 的解析规则对齐 spec §2.2。xlsx 场景共享同一套 patch action；engine 路由 `DocumentVersionService` 层已在 Task 8 完成。

**Files:**
- Modify: `src/main/java/com/lifepilot/document/tool/DocumentEditActionDispatchExecutor.java`
- Create: `src/test/java/com/lifepilot/document/tool/DocumentEditActionDispatchExecutor_xlsx路由测试.java`

**前置阅读：**
- `src/main/java/com/lifepilot/document/tool/DocumentEditActionDispatchExecutor.java`（现有 `parseOneOp` 的 switch 结构，位于 line ~230）
- spec §2.2（xlsx op schema）

- [ ] **Step 1：扩 parseOneOp 的 switch**

在 `parseOneOp` 方法里，把 switch 扩展 4 个新 case（放在现有 4 个 docx case 之后、`default` 之前）：

```java
case "update_cell" -> new UpdateCellOp(
        String.valueOf(m.get("sheet")),
        String.valueOf(m.get("cell")),
        m.get("new_value"),  // 保留原始类型（Number / Boolean / String / null）
        reason);
case "insert_row" -> {
    int beforeRow = m.get("before_row") instanceof Number n ? n.intValue()
            : Integer.parseInt(String.valueOf(m.get("before_row")));
    List<Object> values = (List<Object>) m.getOrDefault("values", List.of());
    yield new InsertRowOp(String.valueOf(m.get("sheet")), beforeRow, values, reason);
}
case "delete_row" -> {
    int row = m.get("row") instanceof Number n ? n.intValue()
            : Integer.parseInt(String.valueOf(m.get("row")));
    yield new DeleteRowOp(String.valueOf(m.get("sheet")), row, reason);
}
case "set_range" -> {
    List<List<Object>> values = (List<List<Object>>) m.get("values");
    if (values == null) {
        throw new IllegalArgumentException("set_range values 不能为空");
    }
    yield new SetRangeOp(
            String.valueOf(m.get("sheet")),
            String.valueOf(m.get("range")),
            values,
            reason);
}
```

相应 import：

```java
import com.lifepilot.document.patch.UpdateCellOp;
import com.lifepilot.document.patch.InsertRowOp;
import com.lifepilot.document.patch.DeleteRowOp;
import com.lifepilot.document.patch.SetRangeOp;
```

- [ ] **Step 2：xlsx 路由测试**

```java
package com.lifepilot.document.tool;

import com.lifepilot.document.patch.DeleteRowOp;
import com.lifepilot.document.patch.InsertRowOp;
import com.lifepilot.document.patch.SetRangeOp;
import com.lifepilot.document.patch.UpdateCellOp;
import com.lifepilot.document.version.DocumentVersionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * DocumentEditActionDispatchExecutor —— xlsx 四 op 的 parseOneOp 路由与类型保真。
 *
 * @author zsg
 * @since 2026-04-21
 */
class DocumentEditActionDispatchExecutor_xlsx路由测试 {

    @Test
    void parseOneOp_update_cell_保留Number类型() throws Exception {
        var svc = Mockito.mock(DocumentVersionService.class);
        when(svc.checkout(anyString(), any())).thenReturn("doc-1");
        when(svc.applyPatch(anyString(), any())).thenReturn(
                com.lifepilot.document.patch.DocumentPatchResult.success(1, "{}", "ok"));

        var exec = new DocumentEditActionDispatchExecutor(svc);

        Map<String, Object> opMap = Map.of(
                "op", "update_cell", "sheet", "Sheet1", "cell", "B5",
                "new_value", 99, "reason", "缩短");
        Map<String, Object> input = Map.of(
                "action", "patch",
                "source", Map.of("type", "path", "value", "/tmp/x.xlsx"),
                "operations", List.of(opMap));
        var tool = new com.lifepilot.tool.model.ToolInput(
                "document.edit", input, Map.of("sessionId", "s-1"));

        exec.execute(tool);

        ArgumentCaptor<List<com.lifepilot.document.patch.DocumentPatchOperation>> captor =
                ArgumentCaptor.forClass(List.class);
        Mockito.verify(svc).applyPatch(anyString(), captor.capture());
        assertThat(captor.getValue()).singleElement()
                .isInstanceOfSatisfying(UpdateCellOp.class, u -> {
                    assertThat(u.sheet()).isEqualTo("Sheet1");
                    assertThat(u.cell()).isEqualTo("B5");
                    assertThat(u.newValue()).isEqualTo(99);  // 保留 Integer/Number，不是 "99"
                });
    }

    @Test
    void parseOneOp_insert_row_整数类型() throws Exception {
        var svc = Mockito.mock(DocumentVersionService.class);
        when(svc.checkout(anyString(), any())).thenReturn("doc-2");
        when(svc.applyPatch(anyString(), any())).thenReturn(
                com.lifepilot.document.patch.DocumentPatchResult.success(1, "{}", "ok"));

        var exec = new DocumentEditActionDispatchExecutor(svc);
        Map<String, Object> opMap = Map.of(
                "op", "insert_row", "sheet", "Sheet1", "before_row", 5,
                "values", List.of("a", 1, 2));
        var input = Map.<String, Object>of(
                "action", "patch",
                "source", Map.of("type", "path", "value", "/tmp/x.xlsx"),
                "operations", List.of(opMap));
        exec.execute(new com.lifepilot.tool.model.ToolInput(
                "document.edit", input, Map.of("sessionId", "s-2")));

        ArgumentCaptor<List<com.lifepilot.document.patch.DocumentPatchOperation>> captor =
                ArgumentCaptor.forClass(List.class);
        Mockito.verify(svc).applyPatch(anyString(), captor.capture());
        assertThat(captor.getValue()).singleElement()
                .isInstanceOfSatisfying(InsertRowOp.class, r -> {
                    assertThat(r.sheet()).isEqualTo("Sheet1");
                    assertThat(r.beforeRow()).isEqualTo(5);
                    assertThat(r.values()).hasSize(3);
                });
    }

    @Test
    void parseOneOp_delete_row() throws Exception {
        var svc = Mockito.mock(DocumentVersionService.class);
        when(svc.checkout(anyString(), any())).thenReturn("doc-3");
        when(svc.applyPatch(anyString(), any())).thenReturn(
                com.lifepilot.document.patch.DocumentPatchResult.success(1, "{}", "ok"));

        var exec = new DocumentEditActionDispatchExecutor(svc);
        Map<String, Object> opMap = Map.of(
                "op", "delete_row", "sheet", "Sheet1", "row", 8);
        var input = Map.<String, Object>of(
                "action", "patch",
                "source", Map.of("type", "path", "value", "/tmp/x.xlsx"),
                "operations", List.of(opMap));
        exec.execute(new com.lifepilot.tool.model.ToolInput(
                "document.edit", input, Map.of("sessionId", "s-3")));

        ArgumentCaptor<List<com.lifepilot.document.patch.DocumentPatchOperation>> captor =
                ArgumentCaptor.forClass(List.class);
        Mockito.verify(svc).applyPatch(anyString(), captor.capture());
        assertThat(captor.getValue()).singleElement()
                .isInstanceOfSatisfying(DeleteRowOp.class, d -> {
                    assertThat(d.row()).isEqualTo(8);
                });
    }

    @Test
    void parseOneOp_set_range() throws Exception {
        var svc = Mockito.mock(DocumentVersionService.class);
        when(svc.checkout(anyString(), any())).thenReturn("doc-4");
        when(svc.applyPatch(anyString(), any())).thenReturn(
                com.lifepilot.document.patch.DocumentPatchResult.success(1, "{}", "ok"));

        var exec = new DocumentEditActionDispatchExecutor(svc);
        Map<String, Object> opMap = Map.of(
                "op", "set_range", "sheet", "Sheet1", "range", "B2:D4",
                "values", List.of(List.of(1, 2, 3), List.of(4, 5, 6), List.of(7, 8, 9)));
        var input = Map.<String, Object>of(
                "action", "patch",
                "source", Map.of("type", "path", "value", "/tmp/x.xlsx"),
                "operations", List.of(opMap));
        exec.execute(new com.lifepilot.tool.model.ToolInput(
                "document.edit", input, Map.of("sessionId", "s-4")));

        ArgumentCaptor<List<com.lifepilot.document.patch.DocumentPatchOperation>> captor =
                ArgumentCaptor.forClass(List.class);
        Mockito.verify(svc).applyPatch(anyString(), captor.capture());
        assertThat(captor.getValue()).singleElement()
                .isInstanceOfSatisfying(SetRangeOp.class, s -> {
                    assertThat(s.range()).isEqualTo("B2:D4");
                    assertThat(s.values()).hasSize(3);
                    assertThat(s.values().get(0)).hasSize(3);
                });
    }
}
```

注：测试假定 `ToolInput` 构造签名为 `(toolId, input, contextMap)`。如果现有 P3A 路由测试用的是其它签名（如 builder 或带额外字段），按实际签名调整；参考 `DocumentEditActionDispatchExecutor_路由测试.java`（P3A）。

- [ ] **Step 3：跑测试**

Run: `mvn test -q -Dtest='DocumentEditActionDispatchExecutor*'`
Expected: P3A 路由测试 + Task 9 新加 4 个 @Test 全绿。

- [ ] **Step 4：commit**

```bash
git add src/main/java/com/lifepilot/document/tool/DocumentEditActionDispatchExecutor.java \
        src/test/java/com/lifepilot/document/tool/DocumentEditActionDispatchExecutor_xlsx路由测试.java
git commit -m "feat(document): Phase 3B Task 9 — DocumentEditActionDispatchExecutor 扩 xlsx 4 op 解析"
```

---

## Task 10：DocumentEditToolProvider description / schema 扩 xlsx 段

**目的**：让 `document.edit` 工具对 LLM 透明暴露 xlsx 能力：`buildDescription` 追加 xlsx 段（op 枚举、A1 notation、公式语义、合并单元格规则、批量 set_range 优先），`buildSchema.operations.description` 扩展字段模板。

**Files:**
- Modify: `src/main/java/com/lifepilot/document/tool/DocumentEditToolProvider.java`

**前置阅读：**
- `src/main/java/com/lifepilot/document/tool/DocumentEditToolProvider.java`（现有 buildDescription / buildSchema）
- spec §7.3（LLM 描述要点 6-9）

- [ ] **Step 1：改 buildDescription**

把现有 `buildDescription()` 返回串追加 xlsx 段落。完整替换为：

```java
private String buildDescription() {
    return "对 docx / xlsx 文档施加锚点编辑并管理版本。" +
            "action=patch 提交一批 operations（事务性，整批成败一致），整批成功后生成新版本工作副本；" +
            "action=diff 拉取某次 patch 的 diff JSON；" +
            "action=commit 把工作副本覆盖回源路径（overwrite，自动生成 .bak 备份）或另存到新路径（saveAs）；" +
            "action=rollback 回滚到指定历史版本（生成新版本而非物理撤销历史）；" +
            "action=list_versions 列出文档的版本历史。" +
            "【docx op 集】（在 docx 文档上使用）" +
            "replace_text / insert_paragraph_after / delete_paragraph / add_table_row；" +
            "locator 是文本锚点（before_context + target + after_context 整体在文档中唯一匹配）；" +
            "保留原 run 样式（字体/字号/颜色/粗斜体）。" +
            "【xlsx op 集】（在 xlsx 文档上使用）" +
            "update_cell / insert_row / delete_row / set_range；" +
            "locator 是 A1 地址（sheet 名区分大小写，cell 如 B5，range 如 B2:D4）；" +
            "update_cell.new_value 多态：数字→数值格；布尔→布尔格；以 = 开头的字符串→公式；其它字符串→字面量；null→清空；" +
            "合并单元格只允许改 anchor（左上角），中间位置会被拒；" +
            "批量写矩形用 set_range（values 是 2D 数组，尺寸必须与 range 吻合）更高效。" +
            "LLM 用法要点：" +
            "(1) 先用 file.read 读文档内容再规划 locator，不要凭空猜测；" +
            "(2) docx locator 的 before/after context 建议各带 10-30 字；xlsx 用精确 sheet 名 + A1 地址；" +
            "(3) 单次 patch 内可传入多个 operations 事务执行；xlsx 与 docx op 不能跨 MIME 混用；" +
            "(4) commit 是用户动作，LLM 不应主动 commit，应把 downloadUrl 告知用户由其决定是否落盘；" +
            "(5) 所有 op 都保留原样式（字体 / 数字格式 / 边框 / 填充），" +
            "不要在 new_value / cells 里再自行拼装样式标记。";
}
```

- [ ] **Step 2：改 buildSchema —— operations.description 扩展**

把 schema 里 `operations` 字段的 description 替换为：

```java
properties.put("operations", Map.of(
        "type", "array",
        "description", "patch action 必填；每个 op 形如 {op, ...字段, reason?}。" +
                "【docx op】op ∈ {replace_text, insert_paragraph_after, delete_paragraph, add_table_row}；" +
                "replace_text 需 before_context + target + after_context + new_text；" +
                "insert_paragraph_after 需 anchor_paragraph_text + new_paragraphs（{text, style?}[]）；" +
                "delete_paragraph 需 paragraph_text；" +
                "add_table_row 需 table_anchor_text + position(first/last) + cells(string[])。" +
                "【xlsx op】op ∈ {update_cell, insert_row, delete_row, set_range}；" +
                "update_cell 需 sheet + cell + new_value（多态：number/boolean/string，= 开头为公式；null 清空）；" +
                "insert_row 需 sheet + before_row（1-based 行号） + values(array)；" +
                "delete_row 需 sheet + row（1-based）；" +
                "set_range 需 sheet + range（A1，如 B2:D4）+ values（2D array，外长=行数，内长=列数）。" +
                "同批 operations 不能跨 MIME 混用（即要么全是 docx op，要么全是 xlsx op）。",
        "items", Map.of("type", "object")
));
```

- [ ] **Step 3：跑一次既有的 provider 测试回归**

Run: `mvn test -q -Dtest='DocumentEditToolProvider*'`
Expected: 既有测试（如果有）全绿。若无直接测 provider 的 test，跑装配测试 `DocumentAutoConfiguration*` 间接验证 Bean 仍装配。

- [ ] **Step 4：commit**

```bash
git add src/main/java/com/lifepilot/document/tool/DocumentEditToolProvider.java
git commit -m "feat(document): Phase 3B Task 10 — document.edit 工具 description/schema 扩 xlsx 段"
```

---

## Task 11：DocumentAutoConfiguration + Controller xlsx 冒烟

**目的**：给 `DocumentAutoConfiguration` 新增 `XlsxPatchEngine` / `XlsxDiffBuilder` 两个 Bean；扩展 `DocumentVersionService` @Bean 签名。补充 `DocumentController` 对 xlsx 文档的端点冒烟测试（下载 / 元数据 / diff），证明既有端点无需改动即可服务 xlsx。

**Files:**
- Modify: `src/main/java/com/lifepilot/document/config/DocumentAutoConfiguration.java`
- Create: `src/test/java/com/lifepilot/interaction/web/controller/DocumentController_xlsx端点测试.java`

**前置阅读：**
- `src/main/java/com/lifepilot/document/config/DocumentAutoConfiguration.java`（Phase 3A 装配链）
- `src/test/java/com/lifepilot/interaction/web/controller/DocumentController_P3端点测试.java`（docx 端点测试的风格）
- spec §5.6 / §5.7

- [ ] **Step 1：新增 xlsx Bean**

在 `DocumentAutoConfiguration.java` 的 "Phase 3A 装配" 块末尾（`documentEditTool` Bean 之前）插入：

```java
@Bean
XlsxPatchEngine xlsxPatchEngine() {
    return new XlsxPatchEngine();
}

@Bean
XlsxDiffBuilder xlsxDiffBuilder() {
    return new XlsxDiffBuilder();
}
```

imports：

```java
import com.lifepilot.document.patch.xlsx.XlsxPatchEngine;
import com.lifepilot.document.patch.xlsx.XlsxDiffBuilder;
```

- [ ] **Step 2：扩展 DocumentVersionService Bean 签名**

替换 `documentVersionService` @Bean 为：

```java
@Bean
@ConditionalOnBean({
        SessionDocumentRepository.class,
        DocumentVersionRepository.class,
        AttachmentRepository.class,
        DocxPatchEngine.class,
        DocxDiffBuilder.class,
        XlsxPatchEngine.class,
        XlsxDiffBuilder.class
})
DocumentVersionService documentVersionService(
        SessionDocumentRepository documentRepository,
        DocumentVersionRepository versionRepository,
        AttachmentRepository attachmentRepository,
        DocxPatchEngine docxPatchEngine,
        DocxDiffBuilder docxDiffBuilder,
        XlsxPatchEngine xlsxPatchEngine,
        XlsxDiffBuilder xlsxDiffBuilder,
        DocumentProperties properties,
        MetaProperties metaProperties) {
    var pathSecurityChecker = new PathSecurityChecker(metaProperties.getInfra().getFile());
    return new DocumentVersionService(
            documentRepository,
            versionRepository,
            attachmentRepository,
            docxPatchEngine,
            docxDiffBuilder,
            xlsxPatchEngine,
            xlsxDiffBuilder,
            properties.getStorageDir(),
            pathSecurityChecker);
}
```

- [ ] **Step 3：写 Controller xlsx 端点冒烟测试**

```java
package com.lifepilot.interaction.web.controller;

import com.lifepilot.document.model.DocumentVersionRecord;
import com.lifepilot.document.model.SessionDocumentRecord;
import com.lifepilot.document.repository.DocumentVersionRepository;
import com.lifepilot.document.repository.SessionDocumentRepository;
import com.lifepilot.document.version.DocumentVersionService;
import com.lifepilot.document.version.DocumentVersionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * DocumentController xlsx 冒烟 —— 既有端点对 xlsx 文档应全部可用，无需代码改动。
 *
 * @author zsg
 * @since 2026-04-21
 */
class DocumentController_xlsx端点测试 {

    private SessionDocumentRepository documentRepo;
    private DocumentVersionRepository versionRepo;
    private DocumentVersionService versionService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        documentRepo = Mockito.mock(SessionDocumentRepository.class);
        versionRepo = Mockito.mock(DocumentVersionRepository.class);
        versionService = Mockito.mock(DocumentVersionService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new DocumentController(documentRepo, versionRepo, versionService)).build();
    }

    @Test
    void 元数据端点返回xlsx_mime() throws Exception {
        when(documentRepo.findById(eq("x-1"))).thenReturn(new SessionDocumentRecord(
                "x-1", "s-1", null, "报表.xlsx", "/tmp/v1.xlsx", 1024,
                DocumentVersionService.XLSX_MIME,
                SessionDocumentRecord.ORIGIN_USER_LOCAL_FILE,
                "D:/src/报表.xlsx", 1, Instant.now()));

        var body = mvc.perform(get("/api/documents/x-1"))
                .andReturn().getResponse().getContentAsString();
        JsonNode json = new ObjectMapper().readTree(body);
        assertThat(json.get("mimeType").asText()).isEqualTo(DocumentVersionService.XLSX_MIME);
        assertThat(json.get("fileName").asText()).isEqualTo("报表.xlsx");
        assertThat(json.get("latestVersion").asInt()).isEqualTo(1);
    }

    @Test
    void 下载端点返回xlsx内容类型() throws Exception {
        Path tmp = Files.createTempFile("xlsx-test-", ".xlsx");
        Files.write(tmp, new byte[]{1, 2, 3, 4});
        when(documentRepo.findById(eq("x-2"))).thenReturn(new SessionDocumentRecord(
                "x-2", "s-2", null, "a.xlsx", tmp.toString(), 4,
                DocumentVersionService.XLSX_MIME,
                SessionDocumentRecord.ORIGIN_USER_LOCAL_FILE, null, 0, Instant.now()));

        mvc.perform(get("/api/documents/x-2/download"))
                .andExpect(r -> assertThat(r.getResponse().getContentType())
                        .startsWith("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        Files.deleteIfExists(tmp);
    }

    @Test
    void diff端点返回xlsx mime字段() throws Exception {
        when(documentRepo.findById(eq("x-3"))).thenReturn(new SessionDocumentRecord(
                "x-3", "s-3", null, "a.xlsx", "/tmp/v1.xlsx", 1024,
                DocumentVersionService.XLSX_MIME,
                SessionDocumentRecord.ORIGIN_USER_LOCAL_FILE, null, 1, Instant.now()));
        when(versionRepo.findByDocumentIdAndVersion(eq("x-3"), eq(1))).thenReturn(
                new DocumentVersionRecord("v-1", "x-3", 1, "/tmp/v1.xlsx",
                        DocumentVersionRecord.SOURCE_PATCH, "共 1 处修改",
                        "{\"mime\":\"xlsx\",\"summary\":\"共 1 处修改\"}", Instant.now()));

        var body = mvc.perform(get("/api/documents/x-3/diff?from=0&to=1"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"mime\":\"xlsx\"");
    }
}
```

- [ ] **Step 4：跑测试 + 全量编译**

Run:
```bash
mvn test -q -Dtest='DocumentController_xlsx端点测试 DocumentController_P3端点测试 DocumentAutoConfiguration*'
```

Expected: xlsx 端点冒烟测试、既有 P3A 端点测试、AutoConfig 装配测试全绿。

- [ ] **Step 5：commit**

```bash
git add src/main/java/com/lifepilot/document/config/DocumentAutoConfiguration.java \
        src/test/java/com/lifepilot/interaction/web/controller/DocumentController_xlsx端点测试.java
git commit -m "feat(document): Phase 3B Task 11 — AutoConfig 装配 xlsx engine/diffBuilder + Controller xlsx 冒烟"
```

---

## Task 12：api/documents.ts 类型扩展（xlsx 字段）

**目的**：扩展 `DiffChange` / `DiffPayload` TypeScript 接口，增加 xlsx 场景的可选字段（`sheet` / `cell` / `range` / `row` / `rows` / `cols` / `mime`）。docx 路径的 `paragraph_index` / `paragraph_preview` 改为可选（兼容 xlsx diff 没有这两个字段）。无新增 API 函数，既有端点足够。

**Files:**
- Modify: `zhiwei-web/src/api/documents.ts`

**前置阅读：**
- `zhiwei-web/src/api/documents.ts` 现状
- spec §2.4（xlsx diff JSON 示意）

- [ ] **Step 1：扩 DiffSegment（新增 text 类型约束未变）**

无需改动 `DiffSegment`。

- [ ] **Step 2：扩 DiffChange 与 DiffPayload**

把 `DiffChange` 与 `DiffPayload` 替换为：

```ts
/** 单个改动：docx 用 paragraph_index/paragraph_preview；xlsx 用 sheet/cell/range/row 等字段 */
export interface DiffChange {
  patch_id: string
  op: string
  /** docx 场景：所在段落索引（xlsx 场景缺省） */
  paragraph_index?: number
  /** docx 场景：段落预览（xlsx 场景缺省） */
  paragraph_preview?: string
  /** xlsx 场景：工作表名 */
  sheet?: string
  /** xlsx update_cell：A1 地址 */
  cell?: string
  /** xlsx set_range：A1 区域 */
  range?: string
  /** xlsx insert_row / delete_row：1-based 行号 */
  row?: number
  /** xlsx set_range：区域行数 */
  rows?: number
  /** xlsx set_range：区域列数 */
  cols?: number
  segments: DiffSegment[]
  reason: string
}

/** diffJson 解析后的完整负载（docx / xlsx 共用，按 mime 字段区分） */
export interface DiffPayload {
  documentId: string
  fromVersion: number
  toVersion: number
  summary: string
  /** P3B 起：docx / xlsx；docx 后端可不写该字段，缺省视为 docx */
  mime?: 'docx' | 'xlsx'
  changes: DiffChange[]
}
```

- [ ] **Step 3：跑前端类型检查**

Run: `cd zhiwei-web && npx vue-tsc --noEmit`
Expected: 0 error（可选字段不会破坏既有 DocumentDiffCard.vue 里 `c.paragraph_preview` / `c.paragraph_index` 的使用——它们对 docx 场景有值，只是 TS 层现在允许 undefined）。

若 DocumentDiffCard.vue 现在对 `c.paragraph_index` 做了严格访问（非 null 断言），把访问处改成可选链 `c.paragraph_index ?? -1` / `c.paragraph_preview ?? ''`。具体改动（如适用）：

在 `DocumentDiffCard.vue` template 中：
```html
<div class="mb-xs text-xs text-muted-foreground">{{ c.paragraph_preview }}</div>
```
保持不变（Vue 模板 `{{ undefined }}` 渲染空串，无需改）。

- [ ] **Step 4：commit**

```bash
git add zhiwei-web/src/api/documents.ts
git commit -m "feat(document): Phase 3B Task 12 — api/documents.ts 扩展 DiffChange/DiffPayload 的 xlsx 字段"
```

---

## Task 13：DocumentVersionHistoryList.vue 共享子件 + 测试

**目的**：新建共享组件展示文档历史版本 + 回滚按钮。docx / xlsx DiffCard 都挂。

**Files:**
- Create: `zhiwei-web/src/components/chat/DocumentVersionHistoryList.vue`
- Create: `zhiwei-web/src/components/chat/__tests__/DocumentVersionHistoryList.spec.ts`

**前置阅读：**
- `zhiwei-web/src/components/chat/DocumentDiffCard.vue`（Tailwind 命名尺度 / class variant 风格参考）
- `zhiwei-web/src/api/documents.ts`（`listVersions` / `rollback` 已存在）
- `.claude/rules/frontend-conventions.md`

- [ ] **Step 1：写组件**

```vue
<script setup lang="ts">
/**
 * 文档版本历史列表 —— 共享子件，docx / xlsx DiffCard 都挂。
 *
 * 内置二次确认、成功后触发 rollback-complete 让父组件刷新 diff。
 *
 * @author zsg
 * @since 2026-04-21
 */
import { ref, onMounted, computed } from 'vue'
import { History, RotateCcw, ChevronDown, ChevronUp } from 'lucide-vue-next'
import {
  listVersions,
  rollback,
  type DocumentVersionInfo,
} from '@/api/documents'

interface Props {
  documentId: string
  /** 当前最新版本号 —— 该版本不显示"回滚"按钮（回滚到当前无意义） */
  currentVersion: number
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'rollback-complete', newVersion: number): void
}>()

const expanded = ref(false)
const loading = ref(false)
const versions = ref<DocumentVersionInfo[]>([])

async function toggleExpand() {
  expanded.value = !expanded.value
  if (expanded.value && versions.value.length === 0) await load()
}

async function load() {
  loading.value = true
  try {
    versions.value = await listVersions(props.documentId)
    // 倒序：最新在上
    versions.value.sort((a, b) => b.versionNo - a.versionNo)
  } catch (e) {
    console.warn('加载版本历史失败', e)
  } finally {
    loading.value = false
  }
}

async function onRollback(version: number) {
  if (!confirm(`确认回滚到版本 v${version}？将生成一个新版本，历史不会丢失。`)) return
  try {
    const r = await rollback(props.documentId, version)
    emit('rollback-complete', r.newVersion)
    // 刷新列表
    await load()
  } catch (e) {
    console.error('回滚失败', e)
    alert('回滚失败，请查看控制台日志。')
  }
}

const headerLabel = computed(() =>
  versions.value.length > 0
    ? `版本历史 (${versions.value.length} 个版本)`
    : '版本历史'
)
</script>

<template>
  <div class="document-version-history rounded-md border border-border bg-muted/40 p-md text-sm">
    <button
      type="button"
      class="flex w-full items-center justify-between gap-sm text-left"
      @click="toggleExpand"
    >
      <span class="flex items-center gap-xs">
        <History class="size-md text-muted-foreground" />
        <span>{{ headerLabel }}</span>
      </span>
      <component :is="expanded ? ChevronUp : ChevronDown" class="size-md" />
    </button>

    <div v-if="expanded" class="mt-md">
      <div v-if="loading" class="text-muted-foreground">加载中…</div>

      <ul v-else-if="versions.length > 0" class="space-y-xs">
        <li
          v-for="v in versions"
          :key="v.versionNo"
          class="flex items-center justify-between gap-sm rounded-md border border-border bg-background p-sm"
        >
          <div class="min-w-0">
            <div class="truncate">
              <span class="font-medium">v{{ v.versionNo }}</span>
              <span class="ml-xs text-muted-foreground">· {{ v.source }}</span>
              <span v-if="v.patchSummary" class="ml-xs text-muted-foreground">
                · {{ v.patchSummary }}
              </span>
            </div>
            <div class="text-xs text-muted-foreground">
              {{ new Date(v.createdAt).toLocaleString() }}
            </div>
          </div>
          <button
            v-if="v.versionNo !== currentVersion"
            type="button"
            class="shrink-0 inline-flex items-center gap-xs rounded-md border border-border px-md py-xs text-xs"
            @click="onRollback(v.versionNo)"
          >
            <RotateCcw class="size-md" />
            <span>回滚</span>
          </button>
          <span v-else class="shrink-0 text-xs text-muted-foreground">（当前）</span>
        </li>
      </ul>

      <div v-else class="text-muted-foreground">尚无历史版本</div>
    </div>
  </div>
</template>
```

- [ ] **Step 2：写测试**

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import DocumentVersionHistoryList from '../DocumentVersionHistoryList.vue'

vi.mock('@/api/documents', () => ({
  listVersions: vi.fn(),
  rollback: vi.fn(),
}))

import { listVersions, rollback } from '@/api/documents'

describe('DocumentVersionHistoryList', () => {
  beforeEach(() => {
    vi.mocked(listVersions).mockReset()
    vi.mocked(rollback).mockReset()
  })

  it('展开后加载版本列表，倒序展示', async () => {
    vi.mocked(listVersions).mockResolvedValue([
      { versionNo: 0, source: 'initial', patchSummary: null, createdAt: '2026-04-21T10:00:00Z' },
      { versionNo: 1, source: 'patch', patchSummary: '共 1 处修改', createdAt: '2026-04-21T10:05:00Z' },
      { versionNo: 2, source: 'patch', patchSummary: '共 2 处修改', createdAt: '2026-04-21T10:10:00Z' },
    ])
    const wrapper = mount(DocumentVersionHistoryList, {
      props: { documentId: 'doc-1', currentVersion: 2 },
    })
    await wrapper.find('button').trigger('click')
    await flushPromises()

    const items = wrapper.findAll('li')
    expect(items.length).toBe(3)
    expect(items[0].text()).toContain('v2')
    expect(items[2].text()).toContain('v0')
  })

  it('当前版本不渲染回滚按钮', async () => {
    vi.mocked(listVersions).mockResolvedValue([
      { versionNo: 0, source: 'initial', patchSummary: null, createdAt: '2026-04-21T10:00:00Z' },
      { versionNo: 1, source: 'patch', patchSummary: 'x', createdAt: '2026-04-21T10:05:00Z' },
    ])
    const wrapper = mount(DocumentVersionHistoryList, {
      props: { documentId: 'doc-1', currentVersion: 1 },
    })
    await wrapper.find('button').trigger('click')
    await flushPromises()

    const items = wrapper.findAll('li')
    // v1（currentVersion）项显示 "（当前）"，无回滚按钮
    expect(items[0].text()).toContain('（当前）')
    // v0 项有回滚按钮
    expect(items[1].findAll('button').length).toBe(1)
  })

  it('点回滚触发 API 并 emit 事件', async () => {
    vi.mocked(listVersions).mockResolvedValue([
      { versionNo: 0, source: 'initial', patchSummary: null, createdAt: '2026-04-21T10:00:00Z' },
      { versionNo: 1, source: 'patch', patchSummary: 'x', createdAt: '2026-04-21T10:05:00Z' },
    ])
    vi.mocked(rollback).mockResolvedValue({ newVersion: 2, summary: '回滚到版本 0' })
    window.confirm = vi.fn().mockReturnValue(true)

    const wrapper = mount(DocumentVersionHistoryList, {
      props: { documentId: 'doc-1', currentVersion: 1 },
    })
    await wrapper.find('button').trigger('click')
    await flushPromises()

    // 找 v0 行的回滚按钮（列表倒序，v0 在第 2 条，即 index 1）
    const rollbackBtn = wrapper.findAll('li')[1].findAll('button')[0]
    await rollbackBtn.trigger('click')
    await flushPromises()

    expect(rollback).toHaveBeenCalledWith('doc-1', 0)
    expect(wrapper.emitted('rollback-complete')).toEqual([[2]])
  })
})
```

- [ ] **Step 3：跑测试**

Run: `cd zhiwei-web && npm run test:run -- DocumentVersionHistoryList`
Expected: 3 个用例全绿。

- [ ] **Step 4：commit**

```bash
git add zhiwei-web/src/components/chat/DocumentVersionHistoryList.vue \
        zhiwei-web/src/components/chat/__tests__/DocumentVersionHistoryList.spec.ts
git commit -m "feat(document): Phase 3B Task 13 — 共享 DocumentVersionHistoryList.vue + 单测"
```

---

## Task 14：共享子件 Header/Actions 提炼 + DocumentDiffCard 重构

**目的**：把 P3A `DocumentDiffCard.vue` 里的标题栏 / 三按钮抽成 `DocumentDiffHeader.vue` / `DocumentDiffActions.vue` 两个共享子件（给 xlsx DiffCard 复用），并把 `DocumentVersionHistoryList` 挂到 `DocumentDiffCard` 里。docx 场景所有原有行为必须保持不变。

**Files:**
- Create: `zhiwei-web/src/components/chat/DocumentDiffHeader.vue`
- Create: `zhiwei-web/src/components/chat/DocumentDiffActions.vue`
- Modify: `zhiwei-web/src/components/chat/DocumentDiffCard.vue`

**前置阅读：**
- `zhiwei-web/src/components/chat/DocumentDiffCard.vue`（抽取的源文件）

- [ ] **Step 1：写 DocumentDiffHeader**

```vue
<script setup lang="ts">
/**
 * 共享卡片头 —— 文件名 + diff 摘要 + 版本号 + 展开箭头。
 *
 * @author zsg
 * @since 2026-04-21
 */
import { FileText, ChevronDown, ChevronUp } from 'lucide-vue-next'

interface Props {
  fileName: string | undefined
  summary?: string | undefined
  fromVersion?: number | undefined
  toVersion?: number | undefined
  expanded: boolean
}

defineProps<Props>()
defineEmits<{ (e: 'toggle'): void }>()
</script>

<template>
  <button
    type="button"
    class="flex w-full items-center justify-between gap-sm text-left"
    @click="$emit('toggle')"
  >
    <span class="flex min-w-0 items-center gap-xs">
      <FileText class="size-md shrink-0 text-muted-foreground" />
      <span class="truncate font-medium">{{ fileName || '加载中…' }}</span>
      <span v-if="summary" class="truncate text-muted-foreground">
        · {{ summary }}
        <template v-if="fromVersion !== undefined && toVersion !== undefined">
          · v{{ fromVersion }} → v{{ toVersion }}
        </template>
      </span>
    </span>
    <component :is="expanded ? ChevronUp : ChevronDown" class="size-md shrink-0" />
  </button>
</template>
```

- [ ] **Step 2：写 DocumentDiffActions**

```vue
<script setup lang="ts">
/**
 * 共享卡片底部三按钮 —— 应用到原路径 / 另存为 / 丢弃。
 * 按钮事件 emit 给父组件，真正的提交 / 二次确认由父组件处理。
 *
 * @author zsg
 * @since 2026-04-21
 */
import { Upload, Save, Trash2 } from 'lucide-vue-next'

interface Props {
  canOverwrite: boolean
}

defineProps<Props>()
defineEmits<{
  (e: 'overwrite'): void
  (e: 'save-as'): void
  (e: 'discard'): void
}>()
</script>

<template>
  <div class="flex items-center gap-sm">
    <button
      v-if="canOverwrite"
      type="button"
      class="action-btn action-btn-primary"
      @click="$emit('overwrite')"
    >
      <Upload class="size-md" />
      <span>应用到原路径</span>
    </button>
    <button
      type="button"
      class="action-btn action-btn-secondary"
      @click="$emit('save-as')"
    >
      <Save class="size-md" />
      <span>另存为…</span>
    </button>
    <button
      type="button"
      class="action-btn action-btn-secondary text-destructive"
      @click="$emit('discard')"
    >
      <Trash2 class="size-md" />
      <span>丢弃</span>
    </button>
  </div>
</template>

<style scoped>
.action-btn {
  @apply inline-flex items-center gap-xs rounded-md px-md py-xs text-sm;
}

.action-btn-primary {
  @apply bg-primary text-primary-foreground;
}

.action-btn-secondary {
  @apply border border-border bg-transparent;
}
</style>
```

- [ ] **Step 3：重构 DocumentDiffCard —— 接入 3 个共享子件**

替换 `DocumentDiffCard.vue` 的整体内容为：

```vue
<script setup lang="ts">
/**
 * 文档 Diff 卡片 —— Phase 3A（docx），Phase 3B 接入共享 Header/Actions/VersionHistoryList。
 *
 * @author zsg
 * @since 2026-04-21（P3B 重构）
 */
import { computed, ref, onMounted } from 'vue'
import DocumentDiffHeader from './DocumentDiffHeader.vue'
import DocumentDiffActions from './DocumentDiffActions.vue'
import DocumentVersionHistoryList from './DocumentVersionHistoryList.vue'
import {
  getDocument,
  getDiff,
  commit,
  discardWorkingCopy,
  parseDiffJson,
  type DocumentMetadata,
  type DiffPayload,
} from '@/api/documents'

interface Props {
  documentId: string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'committed', documentId: string, backupPath: string | undefined): void
  (e: 'discarded', documentId: string): void
}>()

const expanded = ref(false)
const loading = ref(false)
const metadata = ref<DocumentMetadata | null>(null)
const diff = ref<DiffPayload | null>(null)

const canOverwrite = computed(() => !!metadata.value?.sourcePath)
const hasChanges = computed(
  () => !!metadata.value && metadata.value.latestVersion > 0,
)

async function loadData() {
  loading.value = true
  try {
    metadata.value = await getDocument(props.documentId)
    if (metadata.value.latestVersion > 0) {
      const from = metadata.value.latestVersion - 1
      const to = metadata.value.latestVersion
      const payload = await getDiff(props.documentId, from, to)
      diff.value = parseDiffJson(payload.diffJson)
    }
  } catch (e) {
    console.warn('加载文档 diff 失败', e)
  } finally {
    loading.value = false
  }
}

async function onOverwrite() {
  if (!canOverwrite.value) return
  const sourcePath = metadata.value?.sourcePath ?? ''
  if (!confirm(`确认覆盖原文件 ${sourcePath} 吗？系统会自动生成 .bak 备份。`)) return
  try {
    const result = await commit(props.documentId, 'overwrite')
    emit('committed', props.documentId, result.backupPath)
    alert('已覆盖；备份：' + (result.backupPath || '无'))
  } catch (e) {
    console.error('覆盖失败', e)
    alert('覆盖失败，请查看控制台日志。')
  }
}

async function onSaveAs() {
  const path = prompt('另存为绝对路径：')
  if (!path || !path.trim()) return
  try {
    const result = await commit(props.documentId, 'saveAs', path.trim())
    emit('committed', props.documentId, undefined)
    alert('已另存到：' + result.committedPath)
  } catch (e) {
    console.error('另存失败', e)
    alert('另存失败，请查看控制台日志。')
  }
}

async function onDiscard() {
  if (!confirm('丢弃工作副本将不可恢复，继续？')) return
  try {
    await discardWorkingCopy(props.documentId)
    emit('discarded', props.documentId)
  } catch (e) {
    console.error('丢弃失败', e)
    alert('丢弃失败，请查看控制台日志。')
  }
}

/** 回滚完成后重载 metadata + diff */
async function onRollbackComplete() {
  diff.value = null
  await loadData()
}

onMounted(loadData)
</script>

<template>
  <div class="document-diff-card rounded-md border border-border bg-card p-md text-sm">
    <DocumentDiffHeader
      :file-name="metadata?.fileName"
      :summary="diff?.summary"
      :from-version="diff?.fromVersion"
      :to-version="diff?.toVersion"
      :expanded="expanded"
      @toggle="expanded = !expanded"
    />

    <div v-if="expanded" class="mt-md">
      <div v-if="loading" class="text-muted-foreground">加载中…</div>

      <div v-else-if="diff" class="space-y-md">
        <div
          v-for="(c, idx) in diff.changes"
          :key="c.patch_id"
          class="rounded-md border border-border p-md"
        >
          <div class="mb-xs text-xs text-muted-foreground">修改 {{ idx + 1 }}：{{ c.op }}</div>
          <div v-if="c.paragraph_preview" class="mb-xs text-xs text-muted-foreground">
            {{ c.paragraph_preview }}
          </div>
          <p class="leading-relaxed">
            <template v-for="(seg, i) in c.segments" :key="i">
              <span v-if="seg.type === 'keep'">{{ seg.text }}</span>
              <span
                v-else-if="seg.type === 'delete'"
                class="diff-delete rounded px-xs line-through"
              >
                {{ seg.text }}
              </span>
              <span v-else class="diff-insert rounded px-xs">{{ seg.text }}</span>
            </template>
          </p>
          <div v-if="c.reason" class="mt-xs text-xs text-muted-foreground">原因：{{ c.reason }}</div>
        </div>
      </div>

      <div v-else-if="hasChanges" class="text-muted-foreground">diff 数据暂不可用</div>
      <div v-else class="text-muted-foreground">尚无改动</div>

      <DocumentVersionHistoryList
        v-if="metadata"
        :document-id="documentId"
        :current-version="metadata.latestVersion"
        class="mt-md"
        @rollback-complete="onRollbackComplete"
      />

      <DocumentDiffActions
        v-if="hasChanges"
        :can-overwrite="canOverwrite"
        class="mt-md"
        @overwrite="onOverwrite"
        @save-as="onSaveAs"
        @discard="onDiscard"
      />
    </div>
  </div>
</template>

<style scoped>
.diff-delete {
  background-color: hsl(0 80% 94%);
  color: hsl(0 72% 38%);
}

.diff-insert {
  background-color: hsl(142 70% 92%);
  color: hsl(142 64% 30%);
}
</style>
```

- [ ] **Step 4：跑前端全量回归**

Run: `cd zhiwei-web && npm run test:run`
Expected: 全绿（P3A 已有的 `DocumentDiffCard.spec.ts` 若存在，需确认重构后仍能通过；若测试用 selector 依赖原结构，作小幅调整）。

- [ ] **Step 5：commit**

```bash
git add zhiwei-web/src/components/chat/DocumentDiffHeader.vue \
        zhiwei-web/src/components/chat/DocumentDiffActions.vue \
        zhiwei-web/src/components/chat/DocumentDiffCard.vue
git commit -m "refactor(document): Phase 3B Task 14 — 提炼 DocumentDiffHeader/Actions 共享子件 + DocumentDiffCard 挂 VersionHistoryList"
```

---

## Task 15：DocumentXlsxDiffCard.vue + 测试

**目的**：新建 xlsx 专用 Diff 卡片，复用 Task 14 的共享子件（Header / Actions / VersionHistoryList），按 xlsx diff 结构渲染：`update_cell` 双列 before/after；`insert_row` / `delete_row` 单向；`set_range` 规模摘要。

**Files:**
- Create: `zhiwei-web/src/components/chat/DocumentXlsxDiffCard.vue`
- Create: `zhiwei-web/src/components/chat/__tests__/DocumentXlsxDiffCard.spec.ts`

**前置阅读：**
- `zhiwei-web/src/components/chat/DocumentDiffCard.vue`（Task 14 版本）
- spec §6.1 + xlsx diff JSON 示意

- [ ] **Step 1：写组件**

```vue
<script setup lang="ts">
/**
 * xlsx 文档 Diff 卡片 —— Phase 3B 新增，按 cell / row / range 粒度渲染。
 *
 * 与 docx DiffCard 的差异：
 * - update_cell 双列 "[before] → [after]"，不走段内 inline segment
 * - insert_row / delete_row 以 "sheet!row N" 标注，单向 insert / delete
 * - set_range 显示 "rows × cols 批量（预览略）"，避免 2D 数据过载
 *
 * @author zsg
 * @since 2026-04-21
 */
import { computed, ref, onMounted } from 'vue'
import DocumentDiffHeader from './DocumentDiffHeader.vue'
import DocumentDiffActions from './DocumentDiffActions.vue'
import DocumentVersionHistoryList from './DocumentVersionHistoryList.vue'
import {
  getDocument,
  getDiff,
  commit,
  discardWorkingCopy,
  parseDiffJson,
  type DocumentMetadata,
  type DiffPayload,
  type DiffChange,
} from '@/api/documents'

interface Props {
  documentId: string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'committed', documentId: string, backupPath: string | undefined): void
  (e: 'discarded', documentId: string): void
}>()

const expanded = ref(false)
const loading = ref(false)
const metadata = ref<DocumentMetadata | null>(null)
const diff = ref<DiffPayload | null>(null)

const canOverwrite = computed(() => !!metadata.value?.sourcePath)
const hasChanges = computed(
  () => !!metadata.value && metadata.value.latestVersion > 0,
)

async function loadData() {
  loading.value = true
  try {
    metadata.value = await getDocument(props.documentId)
    if (metadata.value.latestVersion > 0) {
      const from = metadata.value.latestVersion - 1
      const to = metadata.value.latestVersion
      const payload = await getDiff(props.documentId, from, to)
      diff.value = parseDiffJson(payload.diffJson)
    }
  } catch (e) {
    console.warn('加载 xlsx diff 失败', e)
  } finally {
    loading.value = false
  }
}

async function onOverwrite() {
  if (!canOverwrite.value) return
  const sourcePath = metadata.value?.sourcePath ?? ''
  if (!confirm(`确认覆盖原文件 ${sourcePath} 吗？系统会自动生成 .bak 备份。`)) return
  try {
    const result = await commit(props.documentId, 'overwrite')
    emit('committed', props.documentId, result.backupPath)
    alert('已覆盖；备份：' + (result.backupPath || '无'))
  } catch (e) {
    console.error('覆盖失败', e)
    alert('覆盖失败，请查看控制台日志。')
  }
}

async function onSaveAs() {
  const path = prompt('另存为绝对路径：')
  if (!path || !path.trim()) return
  try {
    const result = await commit(props.documentId, 'saveAs', path.trim())
    emit('committed', props.documentId, undefined)
    alert('已另存到：' + result.committedPath)
  } catch (e) {
    console.error('另存失败', e)
    alert('另存失败，请查看控制台日志。')
  }
}

async function onDiscard() {
  if (!confirm('丢弃工作副本将不可恢复，继续？')) return
  try {
    await discardWorkingCopy(props.documentId)
    emit('discarded', props.documentId)
  } catch (e) {
    console.error('丢弃失败', e)
    alert('丢弃失败，请查看控制台日志。')
  }
}

async function onRollbackComplete() {
  diff.value = null
  await loadData()
}

/** 为 update_cell 的 diff 生成 before / after 预览文本 */
function cellBefore(c: DiffChange): string {
  const delSeg = c.segments.find(s => s.type === 'delete')
  return delSeg?.text || '(空)'
}

function cellAfter(c: DiffChange): string {
  const insSeg = c.segments.find(s => s.type === 'insert')
  return insSeg?.text || '(空)'
}

/** 为 insert_row / delete_row / set_range 的 change 生成单向文本 */
function rowSingleText(c: DiffChange): string {
  const seg = c.segments[0]
  return seg?.text ?? ''
}

/** change 头部的位置标签：update_cell → sheet!cell；insert_row/delete_row → sheet!row N；set_range → sheet!range (rows×cols) */
function locationLabel(c: DiffChange): string {
  const sheet = c.sheet ?? ''
  if (c.op === 'update_cell' && c.cell) return `${sheet}!${c.cell}`
  if ((c.op === 'insert_row' || c.op === 'delete_row') && c.row !== undefined) {
    return `${sheet}!row ${c.row}`
  }
  if (c.op === 'set_range' && c.range) {
    const size = c.rows && c.cols ? ` (${c.rows}×${c.cols})` : ''
    return `${sheet}!${c.range}${size}`
  }
  return sheet
}

onMounted(loadData)
</script>

<template>
  <div class="document-xlsx-diff-card rounded-md border border-border bg-card p-md text-sm">
    <DocumentDiffHeader
      :file-name="metadata?.fileName"
      :summary="diff?.summary"
      :from-version="diff?.fromVersion"
      :to-version="diff?.toVersion"
      :expanded="expanded"
      @toggle="expanded = !expanded"
    />

    <div v-if="expanded" class="mt-md">
      <div v-if="loading" class="text-muted-foreground">加载中…</div>

      <div v-else-if="diff" class="space-y-md">
        <div
          v-for="(c, idx) in diff.changes"
          :key="c.patch_id"
          class="rounded-md border border-border p-md"
        >
          <div class="mb-xs text-xs text-muted-foreground">
            修改 {{ idx + 1 }}：{{ c.op }} · {{ locationLabel(c) }}
          </div>

          <!-- update_cell：双列 before → after -->
          <div v-if="c.op === 'update_cell'" class="flex items-center gap-sm">
            <span class="diff-delete rounded px-xs line-through">{{ cellBefore(c) }}</span>
            <span class="text-muted-foreground">→</span>
            <span class="diff-insert rounded px-xs">{{ cellAfter(c) }}</span>
          </div>

          <!-- insert_row / set_range：单向 insert -->
          <div v-else-if="c.op === 'insert_row' || c.op === 'set_range'">
            <span class="diff-insert rounded px-xs">{{ rowSingleText(c) }}</span>
          </div>

          <!-- delete_row：单向 delete -->
          <div v-else-if="c.op === 'delete_row'">
            <span class="diff-delete rounded px-xs line-through">{{ rowSingleText(c) }}</span>
          </div>

          <!-- 兜底：未知 op 直接展示 segments -->
          <div v-else>
            <span v-for="(seg, i) in c.segments" :key="i">{{ seg.text }}</span>
          </div>

          <div v-if="c.reason" class="mt-xs text-xs text-muted-foreground">原因：{{ c.reason }}</div>
        </div>
      </div>

      <div v-else-if="hasChanges" class="text-muted-foreground">diff 数据暂不可用</div>
      <div v-else class="text-muted-foreground">尚无改动</div>

      <DocumentVersionHistoryList
        v-if="metadata"
        :document-id="documentId"
        :current-version="metadata.latestVersion"
        class="mt-md"
        @rollback-complete="onRollbackComplete"
      />

      <DocumentDiffActions
        v-if="hasChanges"
        :can-overwrite="canOverwrite"
        class="mt-md"
        @overwrite="onOverwrite"
        @save-as="onSaveAs"
        @discard="onDiscard"
      />
    </div>
  </div>
</template>

<style scoped>
.diff-delete {
  background-color: hsl(0 80% 94%);
  color: hsl(0 72% 38%);
}

.diff-insert {
  background-color: hsl(142 70% 92%);
  color: hsl(142 64% 30%);
}
</style>
```

- [ ] **Step 2：写测试**

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import DocumentXlsxDiffCard from '../DocumentXlsxDiffCard.vue'

vi.mock('@/api/documents', async (importActual) => {
  const actual = await importActual<typeof import('@/api/documents')>()
  return {
    ...actual,
    getDocument: vi.fn(),
    getDiff: vi.fn(),
    commit: vi.fn(),
    discardWorkingCopy: vi.fn(),
  }
})

import {
  getDocument,
  getDiff,
  commit,
  parseDiffJson,
} from '@/api/documents'

const mockMeta = {
  id: 'x-1',
  fileName: '报表.xlsx',
  mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  fileSize: 2048,
  origin: 'user_local_file',
  sourcePath: 'D:/src/报表.xlsx',
  latestVersion: 1,
  createdAt: '2026-04-21T10:00:00Z',
}

function makeDiffJson(changes: Array<Record<string, unknown>>) {
  return JSON.stringify({
    documentId: 'x-1',
    fromVersion: 0,
    toVersion: 1,
    mime: 'xlsx',
    summary: '共 ' + changes.length + ' 处修改',
    changes,
  })
}

describe('DocumentXlsxDiffCard', () => {
  beforeEach(() => {
    vi.mocked(getDocument).mockReset()
    vi.mocked(getDiff).mockReset()
    vi.mocked(commit).mockReset()
  })

  it('update_cell 显示 before → after 双列', async () => {
    vi.mocked(getDocument).mockResolvedValue(mockMeta)
    vi.mocked(getDiff).mockResolvedValue({
      diffJson: makeDiffJson([{
        patch_id: 'p1', op: 'update_cell', sheet: 'Sheet1', cell: 'B5',
        segments: [{ type: 'delete', text: '30' }, { type: 'insert', text: '15' }],
        reason: '缩短',
      }]),
    })
    const wrapper = mount(DocumentXlsxDiffCard, { props: { documentId: 'x-1' } })
    await flushPromises()
    await wrapper.find('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Sheet1!B5')
    expect(wrapper.text()).toContain('30')
    expect(wrapper.text()).toContain('15')
    expect(wrapper.text()).toContain('缩短')
  })

  it('insert_row 显示 sheet!row N 标签 + 单向 insert', async () => {
    vi.mocked(getDocument).mockResolvedValue(mockMeta)
    vi.mocked(getDiff).mockResolvedValue({
      diffJson: makeDiffJson([{
        patch_id: 'p2', op: 'insert_row', sheet: 'Sheet1', row: 5,
        segments: [{ type: 'insert', text: '新产品 | 100 | 2026-04-21' }],
        reason: '',
      }]),
    })
    const wrapper = mount(DocumentXlsxDiffCard, { props: { documentId: 'x-1' } })
    await flushPromises()
    await wrapper.find('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Sheet1!row 5')
    expect(wrapper.text()).toContain('新产品 | 100')
  })

  it('set_range 显示 rows×cols 规模', async () => {
    vi.mocked(getDocument).mockResolvedValue(mockMeta)
    vi.mocked(getDiff).mockResolvedValue({
      diffJson: makeDiffJson([{
        patch_id: 'p3', op: 'set_range', sheet: 'Sheet1', range: 'B2:D4',
        rows: 3, cols: 3,
        segments: [{ type: 'insert', text: '3x3 批量（预览略）' }],
        reason: '',
      }]),
    })
    const wrapper = mount(DocumentXlsxDiffCard, { props: { documentId: 'x-1' } })
    await flushPromises()
    await wrapper.find('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Sheet1!B2:D4')
    expect(wrapper.text()).toContain('(3×3)')
  })

  it('sourcePath 为 null 时不显示 "应用到原路径" 按钮', async () => {
    vi.mocked(getDocument).mockResolvedValue({ ...mockMeta, sourcePath: null })
    vi.mocked(getDiff).mockResolvedValue({
      diffJson: makeDiffJson([{
        patch_id: 'p1', op: 'update_cell', sheet: 'Sheet1', cell: 'A1',
        segments: [{ type: 'delete', text: 'x' }, { type: 'insert', text: 'y' }],
        reason: '',
      }]),
    })
    const wrapper = mount(DocumentXlsxDiffCard, { props: { documentId: 'x-1' } })
    await flushPromises()
    await wrapper.find('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).not.toContain('应用到原路径')
    expect(wrapper.text()).toContain('另存为')
  })
})
```

- [ ] **Step 3：跑测试**

Run: `cd zhiwei-web && npm run test:run -- DocumentXlsxDiffCard`
Expected: 4 个用例全绿。

- [ ] **Step 4：commit**

```bash
git add zhiwei-web/src/components/chat/DocumentXlsxDiffCard.vue \
        zhiwei-web/src/components/chat/__tests__/DocumentXlsxDiffCard.spec.ts
git commit -m "feat(document): Phase 3B Task 15 — DocumentXlsxDiffCard.vue + 单测（cell 级 diff 渲染）"
```

---

## Task 16：MessageBubble xlsx 分支 + AutoConfig 装配契约测试 + 全量回归

**目的**：在 `MessageBubble.vue` 里按 MIME 分支识别已编辑 xlsx，挂 `DocumentXlsxDiffCard`；扩展 `DocumentAutoConfiguration_装配测试` 加 xlsx Bean 断言；跑全栈回归，给 P3B 打收关。

**Files:**
- Modify: `zhiwei-web/src/components/chat/MessageBubble.vue`
- Modify: `src/test/java/com/lifepilot/document/config/DocumentAutoConfiguration_装配测试.java`

**前置阅读：**
- `zhiwei-web/src/components/chat/MessageBubble.vue` 现有 `isEditedDocx` 判断 + 模板分支（line ~170-540）
- `src/test/java/com/lifepilot/document/config/DocumentAutoConfiguration_装配测试.java`

- [ ] **Step 1：MessageBubble 新增 isEditedXlsx**

在 `<script setup>` 中（紧挨 `isEditedDocx` 之后）加：

```ts
/** 判断附件是否为「已编辑的 xlsx」—— 与 isEditedDocx 同款机制，MIME 检查换成 spreadsheetml.sheet */
function isEditedXlsx(att: { type?: string; url?: string }): boolean {
  if (!att.type?.includes('spreadsheetml.sheet')) return false
  const docId = extractDocumentId(att.url)
  if (!docId) return false
  void resolveDocMeta(docId)
  const meta = docxMetaCache.value[docId]
  return !!meta && meta.latestVersion > 0
}
```

同时加 import：

```ts
import DocumentXlsxDiffCard from './DocumentXlsxDiffCard.vue'
```

- [ ] **Step 2：模板分支插入 xlsx DiffCard**

在附件循环的 `<DocumentDiffCard v-if="isEditedDocx(attachment)" ... />` 之后、video 分支之前插入：

```html
<DocumentXlsxDiffCard
  v-else-if="isEditedXlsx(attachment)"
  :document-id="extractDocumentId(attachment.url)!"
  @committed="onDocumentCommitted"
  @discarded="onDocumentDiscarded"
/>
```

- [ ] **Step 3：扩展 AutoConfig 装配契约测试**

在 `DocumentAutoConfiguration_装配测试.java` 原有 P3A Bean 断言之后追加 xlsx Bean 断言。假定原测试用 `ApplicationContextRunner`，照抄风格加：

```java
@Test
void xlsx_engine_和_diffBuilder_Bean装配() {
    runner.run(ctx -> {
        assertThat(ctx).hasSingleBean(
                com.lifepilot.document.patch.xlsx.XlsxPatchEngine.class);
        assertThat(ctx).hasSingleBean(
                com.lifepilot.document.patch.xlsx.XlsxDiffBuilder.class);
    });
}

@Test
void documentVersionService_注入_xlsx_依赖() {
    runner.run(ctx -> {
        assertThat(ctx).hasSingleBean(
                com.lifepilot.document.version.DocumentVersionService.class);
        // 反射或行为断言：能处理 xlsx mime 的 applyPatch（见 DocumentVersionService_xlsx生命周期测试）
        // 这里仅断言 Bean 存在且构造成功
    });
}
```

（若原测试用不同风格，如直接 `ApplicationContext#getBean`，同款调整。）

- [ ] **Step 4：跑后端全量 + 前端全量**

Run:
```bash
mvn test -q
cd zhiwei-web && npm run test:run
```

Expected: 全绿。

- [ ] **Step 5：手工 smoke（可选，在 /ship 阶段统一跑）**

1. `mvn spring-boot:run` 启动后端
2. `cd zhiwei-web && npm run dev` 启动前端
3. 在浏览器对话里输入："把 D:/销售/Q1.xlsx 里 Sheet1 的 B5 改成 15"（假设该文件存在）
4. 看气泡下应出现 `DocumentXlsxDiffCard`，展开后 update_cell 卡显示 `30 → 15`，可点"应用到原路径"
5. 在文件系统验证 `D:/销售/Q1.xlsx.20260421xxxxxx.bak` 生成 + `Q1.xlsx` 中 B5 已变 15

- [ ] **Step 6：commit**

```bash
git add zhiwei-web/src/components/chat/MessageBubble.vue \
        src/test/java/com/lifepilot/document/config/DocumentAutoConfiguration_装配测试.java
git commit -m "feat(document): Phase 3B Task 16 — MessageBubble xlsx 分支 + AutoConfig xlsx 契约"
```

---

## 完成收尾

**最终验证清单：**

- [ ] `mvn test -q` 全绿（涵盖 P3A / P3B 所有测试）
- [ ] `cd zhiwei-web && npm run test:run` 全绿
- [ ] `mvn spring-boot:run` 启动无报错；`document.edit` 工具 description 能看到 xlsx 段
- [ ] 手工 smoke：对话"改 xlsx B5"→ 气泡出现 `DocumentXlsxDiffCard` → 点"应用到原路径"→ 验证原文件被改 + `.bak` 生成
- [ ] 版本历史 UI 冒烟：docx 场景和 xlsx 场景都能展开"版本历史"折叠项 + 点回滚产生新版本
- [ ] 所有 16 个 Task 的 commit 形成清晰 Phase 3B 提交链

**P3B 完成后需要**：
1. 跑 `/ship check`（项目既有 slash command）做端到端测试 + 文档同步 + code review
2. 合 PR，PR 描述要点：
   - `DocumentPatchOperation` sealed 接口分层重构（P3A → P3B 兼容性说明：外部 API 不变，仅内部类型收窄）
   - xlsx 4 op + 合并单元格守卫 + 公式前缀多态类型语义
   - 共享版本历史 UI 接入 docx + xlsx 两个 DiffCard
   - 无新增 Flyway 迁移 / 无新增 REST 端点

**后续 Phase 建议**：
1. 大 xlsx 流式处理（SXSSFWorkbook 读 → XSSF 编辑 → 回写）
2. sheet 级操作（新建 / 删除 / 重命名 sheet）
3. `set_range` 的 2D 完整预览（点击展开 vs 默认摘要）
4. 单条 patch cherry-pick 跨版本（UI + engine 支持）
5. pptx 编辑链路（Phase 4）
