# 文档工作空间 Phase 1B — xlsx/pptx parser 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **配套蓝图**：`docs/superpowers/specs/2026-04-20-document-workspace-design.md`（第 0.5 节 Phase 1）

**Goal：** 让 `file.read` 能读懂 xlsx / pptx 文件 —— 新增 `ExcelParser` 和 `PowerpointParser` 加入 `DocumentParserService`，前后端的 MIME/扩展名白名单同步扩展。

**Architecture：**
1. 基于 Apache POI 5.5.1（已在依赖）的 XSSF（Excel）和 XSLF（PowerPoint）实现两个新 parser，实现 `DocumentParser` sealed interface
2. 扩展 `DocumentParser` 的 `permits` 列表（sealed 接口变更）
3. 扩展 `DocumentParserService` 的 parsers 装配（在 `FileToolProvider` 内部 new）
4. 扩展 `FileReadToolExecutor.FORMATTED_DOCUMENT_EXTENSIONS` 白名单加 `xlsx` / `pptx`
5. 前后端 MIME 白名单同步（后端 `BrowserIngressService.DOCUMENT_MIME_PREFIXES`、前端 `MessageBubble.isParseableDocument` / `documentIcon`）
6. 每个 parser 走 TDD：先写失败测试 → POI 构造 fixture → 实现解析逻辑 → 断言内容/元数据

**Tech Stack：** Apache POI 5.5.1（poi-ooxml artifact，已含 XSSF + XSLF）、JUnit 5 + AssertJ、Vitest（前端）

---

## File Structure

| 文件 | 操作 | 责任 |
|---|---|---|
| `src/main/java/com/lifepilot/knowledge/parser/DocumentParser.java` | 修改 | permits 列表加 `ExcelParser, PowerpointParser` |
| `src/main/java/com/lifepilot/knowledge/parser/ExcelParser.java` | 新建 | 基于 POI XSSF 解析 xlsx：工作表 → 单元格 → 结构化文本 + 元数据 |
| `src/main/java/com/lifepilot/knowledge/parser/PowerpointParser.java` | 新建 | 基于 POI XSLF 解析 pptx：幻灯片 → 文本占位符 / 表格 / 备注 |
| `src/main/java/com/lifepilot/meta/infra/file/FileToolProvider.java` | 修改 | 内部 new `DocumentParserService` 时追加 `ExcelParser` / `PowerpointParser` |
| `src/main/java/com/lifepilot/meta/infra/file/FileReadToolExecutor.java` | 修改 | `FORMATTED_DOCUMENT_EXTENSIONS` 加 `xlsx` / `pptx` |
| `src/main/java/com/lifepilot/interaction/web/service/BrowserIngressService.java` | 修改 | `DOCUMENT_MIME_PREFIXES` 加 xlsx / pptx MIME |
| `zhiwei-web/src/components/chat/MessageBubble.vue` | 修改 | `isParseableDocument` 加 spreadsheetml / presentationml 分支 + 扩展名 fallback；`documentIcon` 加 xlsx/pptx 图标映射 |
| `src/test/java/com/lifepilot/knowledge/parser/ExcelParser_基础解析测试.java` | 新建 | xlsx 解析单元测试（@TempDir + POI 动态构造 fixture） |
| `src/test/java/com/lifepilot/knowledge/parser/PowerpointParser_基础解析测试.java` | 新建 | pptx 解析单元测试（同上） |
| `src/test/java/com/lifepilot/meta/infra/file/FileReadToolExecutor_多格式解析测试.java` | 修改 | 新增 xlsx / pptx 读取 case |
| `zhiwei-web/src/components/chat/MessageBubble.spec.ts` | 修改 | 新增 xlsx / pptx 点亮 "AI 可读取" 徽标断言 |

不动：`knowledge/parser/` 下 WordParser/PdfParser/MarkdownParser/PlainTextParser、`DocumentParserService`、`AttachmentRepository`、`ChatController.ALLOWED_EXTENSIONS`（已含 `.xlsx` / `.pptx`）。

---

## Task 1：新增 ExcelParser（含 sealed interface permits 扩展）

**目的**：基于 POI XSSF 解析 xlsx，按"工作表 → 行 → 单元格"遍历，输出结构化文本 + 元数据。

**Files:**
- Modify: `src/main/java/com/lifepilot/knowledge/parser/DocumentParser.java`（permits 加 `ExcelParser`，为 Task 2 的 `PowerpointParser` 先不加）
- Create: `src/main/java/com/lifepilot/knowledge/parser/ExcelParser.java`
- Test: `src/test/java/com/lifepilot/knowledge/parser/ExcelParser_基础解析测试.java`

**前置阅读：**
- `src/main/java/com/lifepilot/knowledge/parser/WordParser.java`（参照 POI 使用模式 + Javadoc 风格 + 异常处理）
- `src/main/java/com/lifepilot/knowledge/parser/DocumentParser.java`（sealed interface 契约）
- `src/main/java/com/lifepilot/knowledge/parser/ParseResult.java`（record 结构：`text()` 不是 `content()`）
- `src/main/java/com/lifepilot/knowledge/parser/DocumentMetadata.java`（字段：title/author/createdAt/modifiedAt/pageCount/wordCount/language/extraProperties）
- `src/main/java/com/lifepilot/knowledge/parser/DocumentParseException.java`（构造器签名 `(message, Phase, String filePath)`）

- [ ] **Step 1：写失败测试**

新建 `src/test/java/com/lifepilot/knowledge/parser/ExcelParser_基础解析测试.java`：

```java
package com.lifepilot.knowledge.parser;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExcelParser_基础解析测试 {

    @Test
    void supportedExtensions_返回_xlsx() {
        assertThat(new ExcelParser().supportedExtensions()).containsExactly("xlsx");
    }

    @Test
    void 解析单工作表按行按列输出单元格文本(@TempDir Path tmp) throws IOException, DocumentParseException {
        Path file = tmp.resolve("sample.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("月报");
            var row0 = sheet.createRow(0);
            row0.createCell(0).setCellValue("产品");
            row0.createCell(1).setCellValue("销售额");
            var row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("海豚登月计划 A 款");
            row1.createCell(1).setCellValue(1299.5);
            try (OutputStream out = Files.newOutputStream(file)) {
                wb.write(out);
            }
        }

        ParseResult result = new ExcelParser().parse(file);

        assertThat(result.text()).contains("月报");
        assertThat(result.text()).contains("产品");
        assertThat(result.text()).contains("销售额");
        assertThat(result.text()).contains("海豚登月计划 A 款");
        assertThat(result.text()).contains("1299.5");
    }

    @Test
    void 多工作表每个工作表作为独立章节输出(@TempDir Path tmp) throws IOException, DocumentParseException {
        Path file = tmp.resolve("multi.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            wb.createSheet("一季度").createRow(0).createCell(0).setCellValue("Q1 数据");
            wb.createSheet("二季度").createRow(0).createCell(0).setCellValue("Q2 数据");
            try (OutputStream out = Files.newOutputStream(file)) {
                wb.write(out);
            }
        }

        ParseResult result = new ExcelParser().parse(file);

        assertThat(result.text()).contains("一季度");
        assertThat(result.text()).contains("二季度");
        assertThat(result.text()).contains("Q1 数据");
        assertThat(result.text()).contains("Q2 数据");
    }

    @Test
    void 空 xlsx 文件返回空文本但不抛异常(@TempDir Path tmp) throws IOException, DocumentParseException {
        Path file = tmp.resolve("empty.xlsx");
        try (Workbook wb = new XSSFWorkbook();
             OutputStream out = Files.newOutputStream(file)) {
            wb.write(out);
        }

        ParseResult result = new ExcelParser().parse(file);

        assertThat(result.text()).isNotNull();
        assertThat(result.metadata()).isNotNull();
    }

    @Test
    void 不是有效 xlsx 文件抛 DocumentParseException(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("fake.xlsx");
        Files.writeString(file, "not a real xlsx");

        assertThatThrownBy(() -> new ExcelParser().parse(file))
                .isInstanceOf(DocumentParseException.class);
    }

    @Test
    void canParse_按扩展名匹配(@TempDir Path tmp) throws IOException {
        ExcelParser parser = new ExcelParser();
        Path xlsx = tmp.resolve("a.xlsx");
        Path docx = tmp.resolve("a.docx");
        Files.createFile(xlsx);
        Files.createFile(docx);

        assertThat(parser.canParse(xlsx)).isTrue();
        assertThat(parser.canParse(docx)).isFalse();
    }
}
```

- [ ] **Step 2：跑测试确认失败**

```bash
mvn -q test -Dtest=ExcelParser_基础解析测试
```

预期：编译失败（`ExcelParser` 类不存在）。

- [ ] **Step 3：扩展 DocumentParser sealed permits**

打开 `src/main/java/com/lifepilot/knowledge/parser/DocumentParser.java`，修改：

```java
public sealed interface DocumentParser
        permits MarkdownParser, PlainTextParser, PdfParser, WordParser, ExcelParser {
```

同步更新 Javadoc：

```java
/**
 * 文档解析器 sealed interface — 定义文档解析的统一契约。
 *
 * <p>permit MarkdownParser、PlainTextParser、PdfParser、WordParser、ExcelParser。
 *
 * @author zsg
 * @since 2026-02-25
 */
```

- [ ] **Step 4：实现 ExcelParser**

新建 `src/main/java/com/lifepilot/knowledge/parser/ExcelParser.java`：

```java
package com.lifepilot.knowledge.parser;

import com.lifepilot.knowledge.util.TextUtils;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Excel/XLSX 文档解析器 —— 基于 Apache POI XSSF。
 *
 * <p>按"工作表 → 行 → 单元格"顺序遍历，每个工作表作为独立章节输出。
 * 单元格通过 {@link DataFormatter} 获取显示值（数字按格式化后的字符串、日期按本地化格式）。
 * 仅支持 XLSX（Office 2007+），旧版 .xls 不支持（见 spec D3 决策）。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public non-sealed class ExcelParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(ExcelParser.class);
    private static final List<String> EXTENSIONS = List.of("xlsx");

    @Override
    public List<String> supportedExtensions() {
        return EXTENSIONS;
    }

    @Override
    public ParseResult parse(Path filePath) throws DocumentParseException {
        validateFormat(filePath);

        try (InputStream is = Files.newInputStream(filePath);
             Workbook workbook = new XSSFWorkbook(is)) {

            StringBuilder fullText = new StringBuilder();
            List<DocumentElement> elements = new ArrayList<>();
            DataFormatter formatter = new DataFormatter();
            int totalSheets = workbook.getNumberOfSheets();

            for (int sheetIdx = 0; sheetIdx < totalSheets; sheetIdx++) {
                Sheet sheet = workbook.getSheetAt(sheetIdx);
                String sheetName = sheet.getSheetName();

                int headingOffset = fullText.length();
                fullText.append("# 工作表：").append(sheetName).append("\n");
                elements.add(new DocumentElement.Heading(
                        1, sheetName, headingOffset, fullText.length()));

                for (Row row : sheet) {
                    int rowOffset = fullText.length();
                    StringBuilder rowText = new StringBuilder();
                    int lastCell = row.getLastCellNum();
                    for (int c = 0; c < lastCell; c++) {
                        Cell cell = row.getCell(c);
                        String cellText = cell == null ? "" : formatter.formatCellValue(cell);
                        if (!rowText.isEmpty()) {
                            rowText.append(" | ");
                        }
                        rowText.append(cellText);
                    }
                    String rowLine = rowText.toString();
                    if (!rowLine.isBlank()) {
                        fullText.append(rowLine).append("\n");
                        elements.add(new DocumentElement.Paragraph(
                                rowLine, rowOffset, fullText.length()));
                    }
                }

                fullText.append("\n");
            }

            String text = fullText.toString();
            long wordCount = TextUtils.estimateWordCount(text);
            DocumentMetadata metadata = new DocumentMetadata(
                    Optional.of(stripExtension(filePath.getFileName().toString())),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    totalSheets,
                    wordCount,
                    Optional.empty(),
                    java.util.Map.of()
            );

            log.info("XLSX 解析完成：file={}, sheets={}, wordCount={}",
                    filePath, totalSheets, wordCount);
            return new ParseResult(text, elements, metadata, List.of());

        } catch (IOException | InvalidFormatException | RuntimeException e) {
            throw new DocumentParseException(
                    "XLSX 解析失败：" + e.getMessage(),
                    DocumentParseException.Phase.FORMAT_DECODE,
                    filePath.toString(),
                    e);
        }
    }

    @Override
    public DocumentMetadata extractMetadata(Path filePath) {
        try (InputStream is = Files.newInputStream(filePath);
             Workbook workbook = new XSSFWorkbook(is)) {
            int sheets = workbook.getNumberOfSheets();
            return new DocumentMetadata(
                    Optional.of(stripExtension(filePath.getFileName().toString())),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    sheets,
                    0,
                    Optional.empty(),
                    java.util.Map.of()
            );
        } catch (Exception e) {
            log.warn("提取 XLSX 元数据失败，返回默认值：file={}, error={}", filePath, e.getMessage());
            return DocumentMetadata.empty();
        }
    }

    private void validateFormat(Path filePath) throws DocumentParseException {
        String fileName = filePath.getFileName().toString().toLowerCase();
        if (!fileName.endsWith(".xlsx")) {
            throw new DocumentParseException(
                    "仅支持 XLSX 格式，不支持：" + fileName,
                    DocumentParseException.Phase.FORMAT_DECODE,
                    filePath.toString());
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
```

> ⚠️ **校验点**：`ParseResult` 的构造器参数顺序 / `DocumentElement.Heading` 和 `Paragraph` 的字段签名可能与上面假设不完全一致。写完后立刻编译，如果报错对照 `WordParser.java` 实际代码修正（WordParser 用了相同的调用模式）。
>
> ⚠️ **`DocumentParseException.Phase` 枚举值**：按 Phase 0 Task 1 的经验是 `Phase.FORMAT_DECODE`，若编译失败查 `DocumentParseException.java` 实际枚举名。
>
> ⚠️ **`TextUtils.estimateWordCount`**：存在但若签名变化，按 `WordParser.java` 的调用为准。

- [ ] **Step 5：跑测试确认通过**

```bash
mvn -q test -Dtest=ExcelParser_基础解析测试
```

预期：6 个测试全部 PASS。

- [ ] **Step 6：提交**

```bash
git add src/main/java/com/lifepilot/knowledge/parser/DocumentParser.java \
        src/main/java/com/lifepilot/knowledge/parser/ExcelParser.java \
        src/test/java/com/lifepilot/knowledge/parser/ExcelParser_基础解析测试.java
git commit -m "$(cat <<'EOF'
feat(parser): 新增 ExcelParser — 基于 POI XSSF 解析 xlsx

按工作表 → 行 → 单元格结构化输出，支持多工作表、单元格格式化显示值，
元数据里 pageCount 字段填写工作表数量。sealed interface permits 同步扩展。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2：新增 PowerpointParser

**目的**：基于 POI XSLF 解析 pptx，按"幻灯片 → 占位符文本 / 表格 / 备注"遍历。

**Files:**
- Modify: `src/main/java/com/lifepilot/knowledge/parser/DocumentParser.java`（permits 追加 `PowerpointParser`）
- Create: `src/main/java/com/lifepilot/knowledge/parser/PowerpointParser.java`
- Test: `src/test/java/com/lifepilot/knowledge/parser/PowerpointParser_基础解析测试.java`

**前置阅读：**
- `src/main/java/com/lifepilot/knowledge/parser/ExcelParser.java`（Task 1 刚建的参照）

- [ ] **Step 1：写失败测试**

新建 `src/test/java/com/lifepilot/knowledge/parser/PowerpointParser_基础解析测试.java`：

```java
package com.lifepilot.knowledge.parser;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Rectangle;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PowerpointParser_基础解析测试 {

    @Test
    void supportedExtensions_返回_pptx() {
        assertThat(new PowerpointParser().supportedExtensions()).containsExactly("pptx");
    }

    @Test
    void 解析每张幻灯片的文本占位符(@TempDir Path tmp) throws IOException, DocumentParseException {
        Path file = tmp.resolve("deck.pptx");
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            XSLFSlide slide1 = ppt.createSlide();
            XSLFTextBox tb1 = slide1.createTextBox();
            tb1.setAnchor(new Rectangle(50, 50, 400, 100));
            tb1.setText("海豚登月计划 — 首张幻灯片");

            XSLFSlide slide2 = ppt.createSlide();
            XSLFTextBox tb2 = slide2.createTextBox();
            tb2.setAnchor(new Rectangle(50, 50, 400, 100));
            tb2.setText("第二张：执行路线");

            try (OutputStream out = Files.newOutputStream(file)) {
                ppt.write(out);
            }
        }

        ParseResult result = new PowerpointParser().parse(file);

        assertThat(result.text()).contains("海豚登月计划");
        assertThat(result.text()).contains("首张幻灯片");
        assertThat(result.text()).contains("第二张");
        assertThat(result.text()).contains("执行路线");
    }

    @Test
    void 幻灯片索引作为章节标题输出(@TempDir Path tmp) throws IOException, DocumentParseException {
        Path file = tmp.resolve("titled.pptx");
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            XSLFSlide slide = ppt.createSlide();
            XSLFTextBox tb = slide.createTextBox();
            tb.setAnchor(new Rectangle(50, 50, 400, 100));
            tb.setText("内容 A");
            try (OutputStream out = Files.newOutputStream(file)) {
                ppt.write(out);
            }
        }

        ParseResult result = new PowerpointParser().parse(file);

        // 第 1 张幻灯片应该有一个 "幻灯片 1" 或类似章节标识
        assertThat(result.text()).containsAnyOf("幻灯片 1", "幻灯片1", "Slide 1");
    }

    @Test
    void 空 pptx 返回空文本但不抛异常(@TempDir Path tmp) throws IOException, DocumentParseException {
        Path file = tmp.resolve("empty.pptx");
        try (XMLSlideShow ppt = new XMLSlideShow();
             OutputStream out = Files.newOutputStream(file)) {
            ppt.write(out);
        }

        ParseResult result = new PowerpointParser().parse(file);

        assertThat(result.text()).isNotNull();
        assertThat(result.metadata()).isNotNull();
    }

    @Test
    void 元数据 pageCount 等于幻灯片数量(@TempDir Path tmp) throws IOException, DocumentParseException {
        Path file = tmp.resolve("threepages.pptx");
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            ppt.createSlide();
            ppt.createSlide();
            ppt.createSlide();
            try (OutputStream out = Files.newOutputStream(file)) {
                ppt.write(out);
            }
        }

        ParseResult result = new PowerpointParser().parse(file);

        assertThat(result.metadata().pageCount()).isEqualTo(3);
    }

    @Test
    void 不是有效 pptx 文件抛 DocumentParseException(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("fake.pptx");
        Files.writeString(file, "not a real pptx");

        assertThatThrownBy(() -> new PowerpointParser().parse(file))
                .isInstanceOf(DocumentParseException.class);
    }

    @Test
    void canParse_按扩展名匹配(@TempDir Path tmp) throws IOException {
        PowerpointParser parser = new PowerpointParser();
        Path pptx = tmp.resolve("a.pptx");
        Path xlsx = tmp.resolve("a.xlsx");
        Files.createFile(pptx);
        Files.createFile(xlsx);

        assertThat(parser.canParse(pptx)).isTrue();
        assertThat(parser.canParse(xlsx)).isFalse();
    }
}
```

- [ ] **Step 2：跑测试确认失败**

```bash
mvn -q test -Dtest=PowerpointParser_基础解析测试
```

预期：编译失败（`PowerpointParser` 不存在）。

- [ ] **Step 3：扩展 permits**

改 `src/main/java/com/lifepilot/knowledge/parser/DocumentParser.java` permits 增加 `PowerpointParser`：

```java
public sealed interface DocumentParser
        permits MarkdownParser, PlainTextParser, PdfParser, WordParser, ExcelParser, PowerpointParser {
```

同步更新 Javadoc。

- [ ] **Step 4：实现 PowerpointParser**

新建 `src/main/java/com/lifepilot/knowledge/parser/PowerpointParser.java`：

```java
package com.lifepilot.knowledge.parser;

import com.lifepilot.knowledge.util.TextUtils;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PowerPoint/PPTX 文档解析器 —— 基于 Apache POI XSLF。
 *
 * <p>按幻灯片顺序遍历，每张幻灯片作为一个章节输出；提取文本占位符（标题、正文）
 * 以及备注文本。表格和图表不做深度结构提取（POI XSLF 对这些形态的支持有限，
 * 按 {@link XSLFTextShape} 统一处理即可满足 LLM 读文字的需求）。
 * 仅支持 PPTX（Office 2007+），旧版 .ppt 不支持。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public non-sealed class PowerpointParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PowerpointParser.class);
    private static final List<String> EXTENSIONS = List.of("pptx");

    @Override
    public List<String> supportedExtensions() {
        return EXTENSIONS;
    }

    @Override
    public ParseResult parse(Path filePath) throws DocumentParseException {
        validateFormat(filePath);

        try (InputStream is = Files.newInputStream(filePath);
             XMLSlideShow ppt = new XMLSlideShow(is)) {

            StringBuilder fullText = new StringBuilder();
            List<DocumentElement> elements = new ArrayList<>();
            int slideIdx = 0;

            for (XSLFSlide slide : ppt.getSlides()) {
                slideIdx++;
                int headingOffset = fullText.length();
                String heading = "幻灯片 " + slideIdx;
                fullText.append("# ").append(heading).append("\n");
                elements.add(new DocumentElement.Heading(
                        1, heading, headingOffset, fullText.length()));

                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text != null && !text.isBlank()) {
                            int offset = fullText.length();
                            fullText.append(text.strip()).append("\n");
                            elements.add(new DocumentElement.Paragraph(
                                    text.strip(), offset, fullText.length()));
                        }
                    }
                }

                XSLFNotes notes = slide.getNotes();
                if (notes != null) {
                    StringBuilder notesText = new StringBuilder();
                    for (XSLFShape ns : notes.getShapes()) {
                        if (ns instanceof XSLFTextShape nts) {
                            String t = nts.getText();
                            if (t != null && !t.isBlank()) {
                                if (!notesText.isEmpty()) {
                                    notesText.append("\n");
                                }
                                notesText.append(t.strip());
                            }
                        }
                    }
                    if (!notesText.isEmpty()) {
                        int offset = fullText.length();
                        String notesBlock = "[备注] " + notesText;
                        fullText.append(notesBlock).append("\n");
                        elements.add(new DocumentElement.Paragraph(
                                notesBlock, offset, fullText.length()));
                    }
                }

                fullText.append("\n");
            }

            String text = fullText.toString();
            long wordCount = TextUtils.estimateWordCount(text);
            DocumentMetadata metadata = new DocumentMetadata(
                    Optional.of(stripExtension(filePath.getFileName().toString())),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    slideIdx,
                    wordCount,
                    Optional.empty(),
                    java.util.Map.of()
            );

            log.info("PPTX 解析完成：file={}, slides={}, wordCount={}",
                    filePath, slideIdx, wordCount);
            return new ParseResult(text, elements, metadata, List.of());

        } catch (IOException | RuntimeException e) {
            throw new DocumentParseException(
                    "PPTX 解析失败：" + e.getMessage(),
                    DocumentParseException.Phase.FORMAT_DECODE,
                    filePath.toString(),
                    e);
        }
    }

    @Override
    public DocumentMetadata extractMetadata(Path filePath) {
        try (InputStream is = Files.newInputStream(filePath);
             XMLSlideShow ppt = new XMLSlideShow(is)) {
            int slides = ppt.getSlides().size();
            return new DocumentMetadata(
                    Optional.of(stripExtension(filePath.getFileName().toString())),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    slides,
                    0,
                    Optional.empty(),
                    java.util.Map.of()
            );
        } catch (Exception e) {
            log.warn("提取 PPTX 元数据失败，返回默认值：file={}, error={}", filePath, e.getMessage());
            return DocumentMetadata.empty();
        }
    }

    private void validateFormat(Path filePath) throws DocumentParseException {
        String fileName = filePath.getFileName().toString().toLowerCase();
        if (!fileName.endsWith(".pptx")) {
            throw new DocumentParseException(
                    "仅支持 PPTX 格式，不支持：" + fileName,
                    DocumentParseException.Phase.FORMAT_DECODE,
                    filePath.toString());
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
```

> ⚠️ **校验点**：POI 5.5.1 的 `poi-ooxml` 是否包含 `XSLF*` 类？如果 `import org.apache.poi.xslf.usermodel.*` 找不到，需要在 `pom.xml` 额外加 `poi-ooxml-lite` 或 `poi-scratchpad` 依赖（后者通常用于旧版 .ppt，新版 XSLF 应该在 `poi-ooxml` 就有）。实际验证 `mvn compile` 时如果 import 红 → 报告 BLOCKED 并查实际 artifact。

- [ ] **Step 5：跑测试确认通过**

```bash
mvn -q test -Dtest=PowerpointParser_基础解析测试
```

预期：7 个测试全部 PASS。

- [ ] **Step 6：提交**

```bash
git add src/main/java/com/lifepilot/knowledge/parser/DocumentParser.java \
        src/main/java/com/lifepilot/knowledge/parser/PowerpointParser.java \
        src/test/java/com/lifepilot/knowledge/parser/PowerpointParser_基础解析测试.java
git commit -m "$(cat <<'EOF'
feat(parser): 新增 PowerpointParser — 基于 POI XSLF 解析 pptx

按幻灯片顺序遍历，提取文本占位符 + 备注；元数据 pageCount 填幻灯片数量。
sealed interface permits 同步扩展。表格与图表的结构化提取延期，
当前依赖 XSLFTextShape 的统一文本抽取已能满足 LLM 读字需求。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3：扩展 file.read 支持 xlsx / pptx

**目的**：把两个新 parser 接入 `DocumentParserService`，扩展 `FileReadToolExecutor` 的白名单。

**Files:**
- Modify: `src/main/java/com/lifepilot/meta/infra/file/FileToolProvider.java`（`DocumentParserService` 装配追加两个 parser）
- Modify: `src/main/java/com/lifepilot/meta/infra/file/FileReadToolExecutor.java`（`FORMATTED_DOCUMENT_EXTENSIONS` 加 `xlsx` / `pptx` + `file.read` 工具 description 扩展）
- Modify: `src/test/java/com/lifepilot/meta/infra/file/FileReadToolExecutor_多格式解析测试.java`（新增 xlsx/pptx 读取 case）

- [ ] **Step 1：读两个文件现状**

先读 `FileToolProvider.java` 找 `DocumentParserService` new 处（Phase 0 Task 1-4 合并后应该在 `buildFileTools()` 附近），读 `FileReadToolExecutor.java` 找 `FORMATTED_DOCUMENT_EXTENSIONS` 常量位置（约 L360 附近）。

- [ ] **Step 2：写新测试 case**

在 `FileReadToolExecutor_多格式解析测试.java` 现有测试之后追加 2 个：

```java
@Test
void xlsx_文件走_DocumentParserService_路径(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("sales.xlsx");
    try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
         var out = Files.newOutputStream(file)) {
        var sheet = wb.createSheet("Q1");
        sheet.createRow(0).createCell(0).setCellValue("雪豹-星轨-X7");
        wb.write(out);
    }

    var executor = newExecutor();
    var input = newInput(Map.of("path", file.toString()));
    ToolResult result = executor.execute(input);

    assertThat(result.isSuccess()).isTrue();
    assertThat((String) result.data().get("content")).contains("雪豹-星轨-X7");
    assertThat((String) result.data().get("content")).contains("Q1");
}

@Test
void pptx_文件走_DocumentParserService_路径(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("deck.pptx");
    try (var ppt = new org.apache.poi.xslf.usermodel.XMLSlideShow();
         var out = Files.newOutputStream(file)) {
        var slide = ppt.createSlide();
        var tb = slide.createTextBox();
        tb.setAnchor(new java.awt.Rectangle(50, 50, 400, 100));
        tb.setText("鲲鹏振翅 2077");
        ppt.write(out);
    }

    var executor = newExecutor();
    var input = newInput(Map.of("path", file.toString()));
    ToolResult result = executor.execute(input);

    assertThat(result.isSuccess()).isTrue();
    assertThat((String) result.data().get("content")).contains("鲲鹏振翅 2077");
    assertThat((String) result.data().get("content")).contains("幻灯片 1");
}
```

> ⚠️ 如果现有测试的 `newExecutor()` 辅助方法不直接注入 `ExcelParser` / `PowerpointParser`，需要扩展它（或直接用 `new DocumentParserService(List.of(new MarkdownParser(), new PlainTextParser(), new WordParser(), new PdfParser(), new ExcelParser(), new PowerpointParser()))` 构造）。沿用测试现有风格。

- [ ] **Step 3：跑测试确认失败**

```bash
mvn -q test -Dtest=FileReadToolExecutor_多格式解析测试
```

预期：xlsx / pptx 两个新测试 FAIL（ExecutorService 未注册对应 parser，或 `FORMATTED_DOCUMENT_EXTENSIONS` 不含）。

- [ ] **Step 4：改 FileToolProvider 追加 parser**

找到 `FileToolProvider.java` 内 new `DocumentParserService` 的地方（格式类似）：

```java
var documentParserService = new DocumentParserService(List.of(
        new MarkdownParser(), new PlainTextParser(),
        new WordParser(), new PdfParser()));
```

修改为：

```java
var documentParserService = new DocumentParserService(List.of(
        new MarkdownParser(), new PlainTextParser(),
        new WordParser(), new PdfParser(),
        new ExcelParser(), new PowerpointParser()));
```

确保新增 import：

```java
import com.lifepilot.knowledge.parser.ExcelParser;
import com.lifepilot.knowledge.parser.PowerpointParser;
```

- [ ] **Step 5：改 FileReadToolExecutor 白名单**

在 `FileReadToolExecutor.java` 的 `FORMATTED_DOCUMENT_EXTENSIONS` 常量（约 L360 附近）加 `"xlsx"` 和 `"pptx"`：

```java
private static final java.util.Set<String> FORMATTED_DOCUMENT_EXTENSIONS = java.util.Set.of(
        "md", "markdown", "mkd",
        "csv", "tsv",
        "docx", "pdf",
        "xlsx", "pptx"
);
```

（具体现有常量内容可能略不同，对齐 Phase 0 合并后的实际值，加这两个即可。）

- [ ] **Step 6：改 file.read 工具 description**

在 `FileToolProvider.buildFileReadTool()` 的 description 里，原描述含"docx / pdf"等字样的地方扩充为含 xlsx / pptx 的完整列表。找到类似：

```java
.description("读取文件或加载技能指南。支持格式：docx / pdf / md / csv / txt / 代码等。...")
```

改为：

```java
.description("读取文件或加载技能指南。支持格式：docx / xlsx / pptx / pdf / md / csv / txt / 代码等。...")
```

- [ ] **Step 7：跑测试确认通过**

```bash
mvn -q test -Dtest=FileReadToolExecutor_多格式解析测试,ExcelParser_基础解析测试,PowerpointParser_基础解析测试
```

预期：全部 PASS（原 8 + 新 2 + ExcelParser 6 + PowerpointParser 7）。

- [ ] **Step 8：编译全量**

```bash
mvn -q -DskipTests compile
```

确保 FileToolProvider / FileReadToolExecutor 改动没破坏其他模块。

- [ ] **Step 9：提交**

```bash
git add src/main/java/com/lifepilot/meta/infra/file/FileToolProvider.java \
        src/main/java/com/lifepilot/meta/infra/file/FileReadToolExecutor.java \
        src/test/java/com/lifepilot/meta/infra/file/FileReadToolExecutor_多格式解析测试.java
git commit -m "$(cat <<'EOF'
feat(file): file.read 支持 xlsx / pptx —— 接入 Excel/Powerpoint parser

扩展 DocumentParserService 装配集合新增 ExcelParser/PowerpointParser，
FileReadToolExecutor 的 FORMATTED_DOCUMENT_EXTENSIONS 白名单和工具 description
同步加 xlsx/pptx。多格式解析测试补 2 个 case。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4：后端 hint 注入支持 xlsx / pptx

**目的**：`BrowserIngressService.DOCUMENT_MIME_PREFIXES` 加 xlsx / pptx MIME，让用户上传 xlsx/pptx 附件时 LLM 收到 hint 引导调 file.read。

**Files:**
- Modify: `src/main/java/com/lifepilot/interaction/web/service/BrowserIngressService.java`
- Modify: `src/test/java/com/lifepilot/interaction/web/service/BrowserIngressService_文档附件提示测试.java`（新增 xlsx/pptx 触发 hint 测试）

- [ ] **Step 1：写新测试 case**

在 `BrowserIngressService_文档附件提示测试.java` 追加：

```java
@Test
void xlsx_附件触发_file_read_系统提示(@TempDir Path tmp) throws Exception {
    Path xlsx = tmp.resolve("sales.xlsx");
    Files.createFile(xlsx);

    when(chatTurnService.prepare(anyString(), any())).thenReturn(new ResolvedTurnRequest(
            "turn-xlsx", ChatTurnAction.SEND, "看这份销售表",
            List.of("att-xlsx"), null));
    when(attachmentRepository.findById("att-xlsx")).thenReturn(new AttachmentRecord(
            "att-xlsx", "session-1", "sales.xlsx", xlsx.toString(),
            Files.size(xlsx),
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "/api/attachments/att-xlsx"));

    var service = newService();
    var request = new ChatRequest("turn-xlsx", ChatTurnAction.SEND,
            "看这份销售表", "session-1", List.of("att-xlsx"), null);

    GatewayMessage msg = service.buildChatMessage(request, null,
            com.lifepilot.interaction.model.DeliveryMode.SYNC);

    var text = ((MessageContent.TextMessage) msg.content()).text();
    assertThat(text).contains("sales.xlsx");
    assertThat(text).contains("file.read");
    assertThat(text).contains("att-xlsx");
}

@Test
void pptx_附件触发_file_read_系统提示(@TempDir Path tmp) throws Exception {
    Path pptx = tmp.resolve("deck.pptx");
    Files.createFile(pptx);

    when(chatTurnService.prepare(anyString(), any())).thenReturn(new ResolvedTurnRequest(
            "turn-pptx", ChatTurnAction.SEND, "看这个方案",
            List.of("att-pptx"), null));
    when(attachmentRepository.findById("att-pptx")).thenReturn(new AttachmentRecord(
            "att-pptx", "session-1", "deck.pptx", pptx.toString(),
            Files.size(pptx),
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "/api/attachments/att-pptx"));

    var service = newService();
    var request = new ChatRequest("turn-pptx", ChatTurnAction.SEND,
            "看这个方案", "session-1", List.of("att-pptx"), null);

    GatewayMessage msg = service.buildChatMessage(request, null,
            com.lifepilot.interaction.model.DeliveryMode.SYNC);

    var text = ((MessageContent.TextMessage) msg.content()).text();
    assertThat(text).contains("deck.pptx");
    assertThat(text).contains("file.read");
}
```

- [ ] **Step 2：跑测试确认失败**

```bash
mvn -q test -Dtest=BrowserIngressService_文档附件提示测试
```

预期：2 个新测试 FAIL（xlsx/pptx MIME 不在 DOCUMENT_MIME_PREFIXES）。

- [ ] **Step 3：扩展 DOCUMENT_MIME_PREFIXES**

找到 `BrowserIngressService.java` 中 `DOCUMENT_MIME_PREFIXES` 常量（约 L280+），加两条：

```java
private static final List<String> DOCUMENT_MIME_PREFIXES = List.of(
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/markdown",
        "text/plain",
        "text/csv"
);
```

（现状基础上加 spreadsheetml + presentationml 两条。）

- [ ] **Step 4：跑测试确认通过**

```bash
mvn -q test -Dtest=BrowserIngressService_文档附件提示测试
```

预期：所有测试 PASS（原有 + 新加 2 个）。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/lifepilot/interaction/web/service/BrowserIngressService.java \
        src/test/java/com/lifepilot/interaction/web/service/BrowserIngressService_文档附件提示测试.java
git commit -m "$(cat <<'EOF'
feat(document): BrowserIngressService MIME 白名单加 xlsx / pptx

用户上传 xlsx / pptx 附件时触发 file.read(attachmentId=...) 系统提示，
与 Phase 1B 的 parser 扩展配套。测试覆盖两类 MIME 各自的 hint 注入行为。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5：前端附件卡片支持 xlsx / pptx 徽标

**目的**：MessageBubble 的 `isParseableDocument` / `documentIcon` 加回 xlsx/pptx 识别（Phase 0 fix `918ae896` 曾为对齐后端能力边界而移除，现在后端能解析了，前端可以点亮徽标了）。

**Files:**
- Modify: `zhiwei-web/src/components/chat/MessageBubble.vue`
- Modify: `zhiwei-web/src/components/chat/MessageBubble.spec.ts`

- [ ] **Step 1：写新测试**

在 `MessageBubble.spec.ts` 现有测试追加：

```ts
it('xlsx 附件显示「AI 可读取」徽标', () => {
  const wrapper = mount(MessageBubble, {
    props: {
      message: {
        id: 'm3',
        role: 'user',
        content: '看销售表',
        attachments: [{
          fileId: 'att-3',
          url: '/api/attachments/att-3',
          filename: '销售.xlsx',
          size: 51200,
          type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        }],
      },
    },
  })

  expect(wrapper.text()).toContain('销售.xlsx')
  expect(wrapper.text()).toContain('AI 可读取')
})

it('pptx 附件显示「AI 可读取」徽标', () => {
  const wrapper = mount(MessageBubble, {
    props: {
      message: {
        id: 'm4',
        role: 'user',
        content: '看方案',
        attachments: [{
          fileId: 'att-4',
          url: '/api/attachments/att-4',
          filename: '方案.pptx',
          size: 204800,
          type: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
        }],
      },
    },
  })

  expect(wrapper.text()).toContain('方案.pptx')
  expect(wrapper.text()).toContain('AI 可读取')
})
```

- [ ] **Step 2：跑测试确认失败**

```bash
cd zhiwei-web && npm run test:run -- MessageBubble.spec.ts
```

预期：新 2 个测试 FAIL（xlsx / pptx 不在 isParseableDocument 白名单）。

- [ ] **Step 3：修改 MessageBubble.vue 的 isParseableDocument / documentIcon**

在 `<script setup>` 段，修改 `isParseableDocument`：

```ts
function isParseableDocument(att: { type?: string; filename: string }): boolean {
  const t = att.type?.toLowerCase() ?? ''
  if (t === 'application/pdf') return true
  if (t.includes('wordprocessingml')) return true  // docx
  if (t.includes('spreadsheetml')) return true     // xlsx
  if (t.includes('presentationml')) return true    // pptx
  if (t === 'text/markdown' || t === 'text/plain' || t === 'text/csv') return true
  const ext = att.filename.split('.').pop()?.toLowerCase()
  return ['pdf', 'docx', 'xlsx', 'pptx', 'md', 'txt', 'csv'].includes(ext ?? '')
}
```

修改 `documentIcon` 引入之前移除的 `Sheet` / `Presentation` 图标（检查 lucide-vue-next import 语句补上）：

```ts
import { FileText, FileType, Sheet, Presentation, FileCode2 } from 'lucide-vue-next'

function documentIcon(att: { type?: string; filename: string }) {
  const ext = att.filename.split('.').pop()?.toLowerCase() ?? ''
  if (ext === 'pdf') return FileType
  if (ext === 'xlsx' || ext === 'csv') return Sheet
  if (ext === 'pptx') return Presentation
  if (ext === 'md') return FileCode2
  return FileText
}
```

- [ ] **Step 4：跑测试确认通过**

```bash
cd zhiwei-web && npm run test:run -- MessageBubble.spec.ts
```

预期：所有测试 PASS（含之前 `xlsx 附件不显示 AI 可读取徽标` 的反向测试 —— **这个测试需要反转**：现在 xlsx 应该 **点亮** 徽标而不是不显示，需要删掉或改断言为正向）。

- [ ] **Step 5：删掉或改写过时的反向测试**

原 Phase 0 Task 6 fix-up 加的 `xlsx 附件不显示 AI 可读取徽标` 测试需要删除（它的假设"xlsx Phase 0 不支持"已失效）。检查该测试是否还在 `MessageBubble.spec.ts`，有就删掉。

- [ ] **Step 6：跑前端构建**

```bash
cd zhiwei-web && npm run build
```

预期：BUILD SUCCESS。

- [ ] **Step 7：提交**

```bash
git add zhiwei-web/src/components/chat/MessageBubble.vue \
        zhiwei-web/src/components/chat/MessageBubble.spec.ts
git commit -m "$(cat <<'EOF'
feat(web): MessageBubble xlsx / pptx 附件显示 AI 可读取徽标 + 类型图标

Phase 1B 后端 parser 已覆盖 xlsx/pptx，前端徽标从 Phase 0 fix 时的保守收敛
放开为正向识别。documentIcon 恢复 Sheet / Presentation 图标映射，
删除过时的反向 spec 断言。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6：端到端手测验证

**目的**：启动完整环境，在浏览器里拖一份 xlsx 和一份 pptx 进对话，验证 LLM 调 `file.read(attachmentId=...)` 读到内容。

**Files:** 无代码改动；执行手测 + 记录结果。

- [ ] **Step 1：停掉老 backend + 启动新 backend**

```bash
netstat -ano | findstr :8080 | findstr LISTENING
# 如有进程，taskkill //PID <pid> //F
mvn -q spring-boot:run
```

等日志 `Started LifePilotApplication` + `工具注册统计: {JAVA_NATIVE=22}`（数量不变，file.read 仍然 1 个工具，能力扩展）。

- [ ] **Step 2：验证 parser 装配**

搜日志：

```
文档解析服务已就绪：parsers=6
```

`parsers=6` 而不是之前的 4（增加了 Excel + Powerpoint）。

- [ ] **Step 3：确认 frontend 跑着**

```bash
netstat -ano | findstr :5173 | findstr LISTENING
# 没有就 cd zhiwei-web && npm run dev
```

- [ ] **Step 4：浏览器手测**

浏览器开 `http://localhost:5173/conversations/new`：

1. 用 POI 准备 2 个小 xlsx / pptx 测试文件（或者直接用 JS 构造 Blob 上传 —— 类似 Phase 0 Task 7 的做法）
2. 拖 xlsx 进对话，发消息"这个表格里有什么特殊数据"
3. 验证：前端显示 Sheet 图标 + "AI 可读取" 徽标；LLM 回答含 xlsx 真实内容
4. 拖 pptx 进对话，发消息"这个 PPT 讲了什么"
5. 验证：前端显示 Presentation 图标 + "AI 可读取" 徽标；LLM 回答含 pptx 真实内容

**验证证据**：后端日志应出现 `file.read 成功：fileName=xxx.xlsx` 和 `file.read 成功：fileName=yyy.pptx`。

- [ ] **Step 5：记录与关停**

```bash
echo "Phase 1B e2e 验证通过 — $(date '+%Y-%m-%d %H:%M')"
# Ctrl+C 或 taskkill backend
```

---

## Self-Review 注记

**1. Spec 覆盖**：spec 0.5 节 Phase 1 目标是"xlsx / pptx parser 加入 DocumentParserService"。本 plan Task 1-3 直接落地（Task 1 ExcelParser + Task 2 PowerpointParser + Task 3 装配 + file.read 白名单）。Task 4/5 是保证前后端完整闭环（hint 注入 + 前端徽标）。Task 6 是 e2e 验收。

**2. 占位扫描**：无 TBD/TODO。两处 ⚠️ 标注的是实施时需要验证的点（`ParseResult` 字段签名、POI 5.5.1 对 XSLF 的 jar 覆盖），属必要灵活性而非占位。

**3. 类型一致性**：
- `DocumentParser` permits 在 Task 1 / 2 各扩展一次，顺序一致
- `ExcelParser` / `PowerpointParser` 构造器都是无参（与 WordParser / PdfParser 一致），在 Task 3 `new ExcelParser()` / `new PowerpointParser()` 无参调用对齐
- MIME 字符串 `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` / `...presentationml.presentation` 在 Task 4 后端 + Task 5 前端测试中一致使用
- `FORMATTED_DOCUMENT_EXTENSIONS` 中 `"xlsx"` / `"pptx"` 与 parser 的 `supportedExtensions()` 返回值一致

**4. 风险提示**：
- POI 5.5.1 的 XSLF 类是否都在 `poi-ooxml` artifact ——Task 2 Step 4 的 ⚠️ 提醒了，若失败需加 `poi-ooxml-full` 或 `poi-scratchpad`
- 前端 `MessageBubble.spec.ts` 可能有旧的反向断言（"xlsx 不显示徽标"），Task 5 Step 5 显式处理

---

**完结**。Phase 1B 完成后，后续 Phase 2（document.create_* 生成能力）再走 writing-plans。
