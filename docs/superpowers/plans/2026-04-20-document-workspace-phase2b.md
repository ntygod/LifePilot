# 文档工作空间 Phase 2B — xlsx / pptx 生成横向复用

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **配套蓝图**：`docs/superpowers/specs/2026-04-20-document-workspace-design.md`（第 0.5 节 Phase 2）
> **前置依赖**：Phase 2A 已完成（commits `0a022364..7f16ab06`）—— `documents` 表、`DocumentRepository`、`DocumentProperties`、orphan 回填机制、`DocumentToolProvider`、`DocumentAutoConfiguration`、`DocumentController` 下载端点

**Goal：** 把 Phase 2A 建好的"Agent 生成文档产物"pattern 横向复制到 xlsx 和 pptx —— 新增 `document.create_xlsx` 和 `document.create_pptx` 两个工具，完整复用 documents 表 + orphan 回填 + 前端附件卡片 + 下载链路。

**Architecture：**
1. **输入格式与 docx 不同**：docx 用 markdown 字符串，xlsx 用 `sheets[]`（工作表 + 行列），pptx 用 `slides[]`（幻灯片 + 标题/要点/备注）—— 每个生成器独立接口 + 独立输入 record，不强行泛化
2. **落盘 + 持久化完全复用 Phase 2A 链路**：DocumentRepository + AttachmentRepository orphan 回填 + DocumentController 已泛化可处理任何 MIME，无改动
3. **Tool Provider 从 1 tool 扩到 3 tools**：`DocumentToolProvider` 构造器加两个新 executor，`DocumentAutoConfiguration` 新增 Bean 链，`core-tool-ids` 追加两条
4. **Phase 2A Task 5 的 `tools.size() != 1` warn 顺手修**（现在期望 3，不再是 1）

**Tech Stack：** Apache POI 5.5.1（XSSFWorkbook / XMLSlideShow，Phase 1B 已验证在 poi-ooxml artifact 内）、Spring Boot 3 + Java 22、JUnit 5 + AssertJ + Mockito

---

## File Structure

| 文件 | 操作 | 责任 |
|---|---|---|
| `src/main/java/com/lifepilot/document/generator/SheetData.java` | 新建 | record：`(name, headers, rows)`，Excel 工作表中间模型 |
| `src/main/java/com/lifepilot/document/generator/ExcelGenerator.java` | 新建 | 接口：`generate(List<SheetData>) -> byte[]` |
| `src/main/java/com/lifepilot/document/generator/StructuredDataToXlsxGenerator.java` | 新建 | POI XSSF 逐工作表 / 行 / 列生成 xlsx |
| `src/main/java/com/lifepilot/document/generator/SlideData.java` | 新建 | record：`(title, bullets, notes)`，PowerPoint 幻灯片中间模型 |
| `src/main/java/com/lifepilot/document/generator/PowerpointGenerator.java` | 新建 | 接口：`generate(List<SlideData>) -> byte[]` |
| `src/main/java/com/lifepilot/document/generator/OutlineToPptxGenerator.java` | 新建 | POI XSLF 逐幻灯片生成 pptx |
| `src/main/java/com/lifepilot/document/tool/DocumentCreateXlsxToolExecutor.java` | 新建 | 工具执行体（落盘 + 入 2 张表 + 返回 ToolResult） |
| `src/main/java/com/lifepilot/document/tool/DocumentCreatePptxToolExecutor.java` | 新建 | 同上 pptx |
| `src/main/java/com/lifepilot/document/tool/DocumentToolProvider.java` | 修改 | 构造器加 2 executor；buildDocumentTools 返回 3 tools |
| `src/main/java/com/lifepilot/document/config/DocumentAutoConfiguration.java` | 修改 | 新增 xlsx / pptx 的 Bean；tool Bean 拆成 3 个；修 `tools.size() != 1` 断言 |
| `src/main/resources/application.yml` | 修改 | `core-tool-ids` 追加 `document.create_xlsx` + `document.create_pptx` |
| `src/test/java/com/lifepilot/document/generator/StructuredDataToXlsxGenerator_基础生成测试.java` | 新建 | 5+ 测试 |
| `src/test/java/com/lifepilot/document/generator/OutlineToPptxGenerator_基础生成测试.java` | 新建 | 5+ 测试 |
| `src/test/java/com/lifepilot/document/tool/DocumentCreateXlsxToolExecutor_工具调用测试.java` | 新建 | 复用 docx executor 测试模式 |
| `src/test/java/com/lifepilot/document/tool/DocumentCreatePptxToolExecutor_工具调用测试.java` | 新建 | 同上 |

不动：Phase 2A 的 Repository、Properties、AttachmentRepository、DocumentController、MarkdownToDocxGenerator 等。

---

## Task 1：StructuredDataToXlsxGenerator — POI XSSF 生成 xlsx

**目的**：把结构化数据（工作表 + 行列）通过 POI XSSF 渲染成 xlsx 字节。

**Files:**
- Create: `src/main/java/com/lifepilot/document/generator/SheetData.java`
- Create: `src/main/java/com/lifepilot/document/generator/ExcelGenerator.java`
- Create: `src/main/java/com/lifepilot/document/generator/StructuredDataToXlsxGenerator.java`
- Test: `src/test/java/com/lifepilot/document/generator/StructuredDataToXlsxGenerator_基础生成测试.java`

**前置阅读：**
- `src/main/java/com/lifepilot/knowledge/parser/ExcelParser.java`（反向参照 — 读 xlsx，写就是相反流程）
- `src/main/java/com/lifepilot/document/generator/MarkdownToDocxGenerator.java`（Phase 2A 建，参照生成器风格）

### Step 1：SheetData record

新建 `src/main/java/com/lifepilot/document/generator/SheetData.java`：

```java
package com.lifepilot.document.generator;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * Excel 工作表中间模型。
 *
 * <p>Tool 层从 ToolInput 反序列化为 SheetData 列表传给 ExcelGenerator，
 * 内部结构 LLM 可见（工具 schema 直接描述此 record 字段）。</p>
 *
 * @param name    工作表名称（非空）
 * @param headers 表头行（可为 null / 空列表 → 不输出表头行）
 * @param rows    数据行，每行是单元格值列表。单元格可为 String / Number / Boolean / null
 *                （POI DataFormatter 按类型输出显示值）
 * @author zsg
 * @since 2026-04-20
 */
public record SheetData(
        String name,
        @Nullable List<String> headers,
        List<List<Object>> rows
) {

    /**
     * 简化构造：无表头工作表。
     */
    public static SheetData of(String name, List<List<Object>> rows) {
        return new SheetData(name, null, rows);
    }

    /**
     * 简化构造：有表头工作表。
     */
    public static SheetData withHeaders(String name, List<String> headers, List<List<Object>> rows) {
        return new SheetData(name, headers, rows);
    }
}
```

### Step 2：ExcelGenerator 接口

新建 `src/main/java/com/lifepilot/document/generator/ExcelGenerator.java`：

```java
package com.lifepilot.document.generator;

import java.util.List;

/**
 * Excel（xlsx）生成器契约。
 *
 * <p>和 {@code DocumentGenerator}（markdown → docx）并列 —— xlsx 输入是结构化数据，
 * 不是 markdown，不共享 DocumentGenerator 接口。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public interface ExcelGenerator {

    /**
     * 从工作表数据生成 xlsx 字节。
     *
     * @param sheets 工作表列表（不能为空，至少含 1 个 SheetData）
     * @return 生成的 xlsx 文件字节
     */
    byte[] generate(List<SheetData> sheets);

    /** 返回 MIME 类型。 */
    String mimeType();
}
```

### Step 3：写失败测试

新建 `src/test/java/com/lifepilot/document/generator/StructuredDataToXlsxGenerator_基础生成测试.java`：

```java
package com.lifepilot.document.generator;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredDataToXlsxGenerator_基础生成测试 {

    private final StructuredDataToXlsxGenerator generator = new StructuredDataToXlsxGenerator();

    @Test
    void mimeType_返回_spreadsheetml() {
        assertThat(generator.mimeType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @Test
    void 单工作表含表头与数据行能被回读() throws Exception {
        var sheet = SheetData.withHeaders("月报",
                List.of("产品", "销售额"),
                List.of(
                        List.of("海豚登月计划 A 款", 1299.5),
                        List.of("海豚登月计划 B 款", 2088.0)
                ));

        byte[] bytes = generator.generate(List.of(sheet));

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet s = wb.getSheet("月报");
            assertThat(s).isNotNull();
            Row header = s.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("产品");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("销售额");
            Row dataRow = s.getRow(1);
            assertThat(dataRow.getCell(0).getStringCellValue()).contains("海豚登月计划");
            assertThat(dataRow.getCell(1).getNumericCellValue()).isEqualTo(1299.5);
        }
    }

    @Test
    void 多工作表按顺序输出() throws Exception {
        byte[] bytes = generator.generate(List.of(
                SheetData.of("一季度", List.of(List.of("Q1 数据"))),
                SheetData.of("二季度", List.of(List.of("Q2 数据")))
        ));

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(2);
            assertThat(wb.getSheetName(0)).isEqualTo("一季度");
            assertThat(wb.getSheetName(1)).isEqualTo("二季度");
        }
    }

    @Test
    void 无表头的工作表首行直接是数据() throws Exception {
        var sheet = SheetData.of("无表头",
                List.of(List.of("第一行数据")));

        byte[] bytes = generator.generate(List.of(sheet));

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet s = wb.getSheet("无表头");
            assertThat(s.getRow(0).getCell(0).getStringCellValue()).isEqualTo("第一行数据");
        }
    }

    @Test
    void null_单元格产出空字符串不崩() throws Exception {
        var sheet = SheetData.of("含空",
                List.of(List.of("有值", null, "有值2")));

        byte[] bytes = generator.generate(List.of(sheet));

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet s = wb.getSheet("含空");
            Row row = s.getRow(0);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("有值");
            // cell 1 应为 blank 或 null —— 既然 POI 对空值处理各异，关键是不崩
            assertThat(row.getCell(2).getStringCellValue()).isEqualTo("有值2");
        }
    }

    @Test
    void 空_sheets_列表生成最小可读_xlsx() throws Exception {
        byte[] bytes = generator.generate(List.of());

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            // 空 sheets 时添加一个占位 "Sheet1"，保证 XSSFWorkbook 至少 1 张工作表
            assertThat(wb.getNumberOfSheets()).isGreaterThanOrEqualTo(1);
        }
    }
}
```

### Step 4：跑测试确认失败

```bash
mvn -q test -Dtest=StructuredDataToXlsxGenerator_基础生成测试
```

预期：编译失败（`StructuredDataToXlsxGenerator` 不存在）。

### Step 5：实现 StructuredDataToXlsxGenerator

新建 `src/main/java/com/lifepilot/document/generator/StructuredDataToXlsxGenerator.java`：

```java
package com.lifepilot.document.generator;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * 结构化数据 → xlsx 生成器，基于 Apache POI XSSF。
 *
 * <p>按 SheetData 列表顺序建工作表，先写 headers（如有）再按 rows 展开单元格。
 * 单元格类型：Number 走 numeric cell，Boolean 走 boolean cell，其它包括 null 转字符串，
 * null 输出空串。不做样式 / 公式 / 合并单元格 —— Phase 2A 基础深度，按需 Phase 2+ 扩展。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class StructuredDataToXlsxGenerator implements ExcelGenerator {

    private static final Logger log = LoggerFactory.getLogger(StructuredDataToXlsxGenerator.class);
    private static final String MIME_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Override
    public String mimeType() {
        return MIME_TYPE;
    }

    @Override
    public byte[] generate(List<SheetData> sheets) {
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            if (sheets == null || sheets.isEmpty()) {
                // XSSFWorkbook 必须至少 1 张工作表才可打开
                wb.createSheet("Sheet1");
                wb.write(out);
                return out.toByteArray();
            }

            for (SheetData data : sheets) {
                Sheet sheet = wb.createSheet(data.name());
                int rowIdx = 0;

                if (data.headers() != null && !data.headers().isEmpty()) {
                    Row header = sheet.createRow(rowIdx++);
                    for (int c = 0; c < data.headers().size(); c++) {
                        header.createCell(c).setCellValue(data.headers().get(c));
                    }
                }

                if (data.rows() != null) {
                    for (List<Object> rowValues : data.rows()) {
                        Row row = sheet.createRow(rowIdx++);
                        for (int c = 0; c < rowValues.size(); c++) {
                            Cell cell = row.createCell(c);
                            Object value = rowValues.get(c);
                            setCellValue(cell, value);
                        }
                    }
                }
            }

            wb.write(out);
            log.info("StructuredDataToXlsx 生成完成：sheets={}, outputBytes={}",
                    sheets.size(), out.size());
            return out.toByteArray();

        } catch (IOException e) {
            throw new DocumentGenerationException("xlsx 生成失败：" + e.getMessage(), e);
        }
    }

    private void setCellValue(Cell cell, Object value) {
        if (value == null) {
            cell.setCellValue("");
        } else if (value instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else if (value instanceof Boolean b) {
            cell.setCellValue(b);
        } else {
            cell.setCellValue(value.toString());
        }
    }
}
```

### Step 6：跑测试确认通过

```bash
mvn -q test -Dtest=StructuredDataToXlsxGenerator_基础生成测试
```

预期：6 个测试 PASS。

### Step 7：提交

```bash
git add src/main/java/com/lifepilot/document/generator/SheetData.java \
        src/main/java/com/lifepilot/document/generator/ExcelGenerator.java \
        src/main/java/com/lifepilot/document/generator/StructuredDataToXlsxGenerator.java \
        src/test/java/com/lifepilot/document/generator/StructuredDataToXlsxGenerator_基础生成测试.java
git commit -m "$(cat <<'EOF'
feat(document): StructuredDataToXlsxGenerator — Phase 2B xlsx 生成核心

SheetData record（name + headers? + rows）作为 Excel 工作表中间模型，
ExcelGenerator 接口独立于 docx 的 DocumentGenerator（输入结构不同，不强行泛化）。
POI XSSF 实现按工作表/行/列生成；单元格 Number/Boolean/String/null 分别处理；
空 sheets 列表生成含 Sheet1 占位的最小可打开 xlsx。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2：OutlineToPptxGenerator — POI XSLF 生成 pptx

**目的**：把幻灯片大纲（title + bullets + notes）通过 POI XSLF 渲染成 pptx。

**Files:**
- Create: `src/main/java/com/lifepilot/document/generator/SlideData.java`
- Create: `src/main/java/com/lifepilot/document/generator/PowerpointGenerator.java`
- Create: `src/main/java/com/lifepilot/document/generator/OutlineToPptxGenerator.java`
- Test: `src/test/java/com/lifepilot/document/generator/OutlineToPptxGenerator_基础生成测试.java`

**前置阅读：**
- `src/main/java/com/lifepilot/knowledge/parser/PowerpointParser.java`（反向参照）

### Step 1：SlideData record

新建 `src/main/java/com/lifepilot/document/generator/SlideData.java`：

```java
package com.lifepilot.document.generator;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * PowerPoint 幻灯片中间模型。
 *
 * @param title   标题（可为 null / 空字符串 → 不渲染标题框）
 * @param bullets 要点列表（每项一行）
 * @param notes   备注文本（可为 null）
 * @author zsg
 * @since 2026-04-20
 */
public record SlideData(
        @Nullable String title,
        List<String> bullets,
        @Nullable String notes
) {

    /** 简化构造：仅标题 + 要点。 */
    public static SlideData of(String title, List<String> bullets) {
        return new SlideData(title, bullets, null);
    }

    /** 简化构造：标题 + 要点 + 备注。 */
    public static SlideData withNotes(String title, List<String> bullets, String notes) {
        return new SlideData(title, bullets, notes);
    }
}
```

### Step 2：PowerpointGenerator 接口

新建 `src/main/java/com/lifepilot/document/generator/PowerpointGenerator.java`：

```java
package com.lifepilot.document.generator;

import java.util.List;

/**
 * PowerPoint（pptx）生成器契约。
 *
 * @author zsg
 * @since 2026-04-20
 */
public interface PowerpointGenerator {

    /**
     * 从幻灯片大纲生成 pptx 字节。
     *
     * @param slides 幻灯片列表
     * @return 生成的 pptx 文件字节
     */
    byte[] generate(List<SlideData> slides);

    /** 返回 MIME 类型。 */
    String mimeType();
}
```

### Step 3：写失败测试

新建 `src/test/java/com/lifepilot/document/generator/OutlineToPptxGenerator_基础生成测试.java`：

```java
package com.lifepilot.document.generator;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutlineToPptxGenerator_基础生成测试 {

    private final OutlineToPptxGenerator generator = new OutlineToPptxGenerator();

    @Test
    void mimeType_返回_presentationml() {
        assertThat(generator.mimeType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.presentationml.presentation");
    }

    @Test
    void 单幻灯片含标题和要点能被回读() throws Exception {
        var slide = SlideData.of("首页", List.of("要点 A", "要点 B", "要点 C"));

        byte[] bytes = generator.generate(List.of(slide));

        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            assertThat(ppt.getSlides()).hasSize(1);
            String allText = collectSlideText(ppt, 0);
            assertThat(allText).contains("首页");
            assertThat(allText).contains("要点 A");
            assertThat(allText).contains("要点 B");
            assertThat(allText).contains("要点 C");
        }
    }

    @Test
    void 多幻灯片按顺序输出() throws Exception {
        byte[] bytes = generator.generate(List.of(
                SlideData.of("第一张", List.of("内容 1")),
                SlideData.of("第二张", List.of("内容 2"))
        ));

        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            assertThat(ppt.getSlides()).hasSize(2);
            assertThat(collectSlideText(ppt, 0)).contains("第一张").contains("内容 1");
            assertThat(collectSlideText(ppt, 1)).contains("第二张").contains("内容 2");
        }
    }

    @Test
    void 备注文本独立存在幻灯片备注区() throws Exception {
        var slide = SlideData.withNotes("方案", List.of("要点"), "讲稿：这里展开说明");

        byte[] bytes = generator.generate(List.of(slide));

        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            var s = ppt.getSlides().get(0);
            var notes = s.getNotes();
            assertThat(notes).isNotNull();
            String notesText = collectShapeText(notes.getShapes());
            assertThat(notesText).contains("讲稿：这里展开说明");
        }
    }

    @Test
    void 无标题无要点也能生成合法幻灯片() throws Exception {
        var slide = new SlideData(null, List.of(), null);

        byte[] bytes = generator.generate(List.of(slide));

        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            assertThat(ppt.getSlides()).hasSize(1);
        }
    }

    @Test
    void 空_slides_列表生成零幻灯片的最小可读_pptx() throws Exception {
        byte[] bytes = generator.generate(List.of());

        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            // 空 slides 是合法 pptx（没有 slide）
            assertThat(ppt.getSlides()).isEmpty();
        }
    }

    private String collectSlideText(XMLSlideShow ppt, int slideIdx) {
        return collectShapeText(ppt.getSlides().get(slideIdx).getShapes());
    }

    @SuppressWarnings("rawtypes")
    private String collectShapeText(java.util.List shapes) {
        StringBuilder sb = new StringBuilder();
        for (Object shape : shapes) {
            if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape ts) {
                sb.append(ts.getText()).append("\n");
            }
        }
        return sb.toString();
    }
}
```

### Step 4：跑测试确认失败

```bash
mvn -q test -Dtest=OutlineToPptxGenerator_基础生成测试
```

预期：编译失败。

### Step 5：实现 OutlineToPptxGenerator

新建 `src/main/java/com/lifepilot/document/generator/OutlineToPptxGenerator.java`：

```java
package com.lifepilot.document.generator;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * 幻灯片大纲 → pptx 生成器，基于 Apache POI XSLF。
 *
 * <p>每个 SlideData 渲染为一张幻灯片：顶部文本框放标题（如有），下方文本框放要点列表
 * （每个要点一行），备注写入幻灯片的 notes 区域。不做主题 / 动画 / 图片 / 图表 —
 * Phase 2B 基础深度，按需 Phase 2+ 扩展。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class OutlineToPptxGenerator implements PowerpointGenerator {

    private static final Logger log = LoggerFactory.getLogger(OutlineToPptxGenerator.class);
    private static final String MIME_TYPE =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    @Override
    public String mimeType() {
        return MIME_TYPE;
    }

    @Override
    public byte[] generate(List<SlideData> slides) {
        try (XMLSlideShow ppt = new XMLSlideShow();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            if (slides == null || slides.isEmpty()) {
                ppt.write(out);
                return out.toByteArray();
            }

            for (SlideData data : slides) {
                XSLFSlide slide = ppt.createSlide();

                if (data.title() != null && !data.title().isBlank()) {
                    XSLFTextBox titleBox = slide.createTextBox();
                    titleBox.setAnchor(new Rectangle(40, 30, 620, 60));
                    titleBox.setText(data.title());
                }

                if (data.bullets() != null && !data.bullets().isEmpty()) {
                    XSLFTextBox bulletBox = slide.createTextBox();
                    bulletBox.setAnchor(new Rectangle(40, 110, 620, 400));
                    boolean first = true;
                    for (String bullet : data.bullets()) {
                        var paragraph = first ? bulletBox.getTextParagraphs().get(0)
                                : bulletBox.addNewTextParagraph();
                        var run = paragraph.addNewTextRun();
                        run.setText("• " + bullet);
                        first = false;
                    }
                }

                if (data.notes() != null && !data.notes().isBlank()) {
                    XSLFNotes notes = ppt.getNotesSlide(slide);
                    for (var shape : notes.getShapes()) {
                        if (shape instanceof XSLFTextShape textShape) {
                            textShape.clearText();
                            textShape.setText(data.notes());
                            break;
                        }
                    }
                }
            }

            ppt.write(out);
            log.info("OutlineToPptx 生成完成：slides={}, outputBytes={}",
                    slides.size(), out.size());
            return out.toByteArray();

        } catch (IOException e) {
            throw new DocumentGenerationException("pptx 生成失败：" + e.getMessage(), e);
        }
    }
}
```

> ⚠️ **校验点：`ppt.getNotesSlide(slide)` API**：POI 5.x 应该直接可用（创建 notes slide 若不存在）；如果测试 3 "备注文本独立存在幻灯片备注区" 失败，可能需要改用 `slide.getNotes()` 并手动建 notes slide。实施时如需调整，保持测试期望不变即可。

### Step 6：跑测试确认通过

```bash
mvn -q test -Dtest=OutlineToPptxGenerator_基础生成测试
```

预期：6 个测试 PASS。

### Step 7：提交

```bash
git add src/main/java/com/lifepilot/document/generator/SlideData.java \
        src/main/java/com/lifepilot/document/generator/PowerpointGenerator.java \
        src/main/java/com/lifepilot/document/generator/OutlineToPptxGenerator.java \
        src/test/java/com/lifepilot/document/generator/OutlineToPptxGenerator_基础生成测试.java
git commit -m "$(cat <<'EOF'
feat(document): OutlineToPptxGenerator — Phase 2B pptx 生成核心

SlideData record（title? + bullets + notes?）作为幻灯片中间模型，
PowerpointGenerator 接口独立。POI XSLF 实现每张幻灯片渲染为：标题文本框 +
要点文本框 + 备注（写入 XSLFNotes）。空 slides 生成零幻灯片合法 pptx。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3：DocumentCreateXlsxToolExecutor

**目的**：`document.create_xlsx` 工具执行体 —— 接收 `sheets` JSON 数组，生成 xlsx，落盘 + 入表，返回下载 URL。完全复用 Phase 2A Task 4 的 docx executor 模式。

**Files:**
- Create: `src/main/java/com/lifepilot/document/tool/DocumentCreateXlsxToolExecutor.java`
- Test: `src/test/java/com/lifepilot/document/tool/DocumentCreateXlsxToolExecutor_工具调用测试.java`

**前置阅读：**
- `src/main/java/com/lifepilot/document/tool/DocumentCreateDocxToolExecutor.java`（Phase 2A 模式完全复用）

### Step 1：写失败测试

新建 `src/test/java/com/lifepilot/document/tool/DocumentCreateXlsxToolExecutor_工具调用测试.java`：

```java
package com.lifepilot.document.tool;

import com.lifepilot.document.generator.StructuredDataToXlsxGenerator;
import com.lifepilot.document.model.DocumentRecord;
import com.lifepilot.document.repository.DocumentRepository;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentCreateXlsxToolExecutor_工具调用测试 {

    @Mock DocumentRepository documentRepository;
    @Mock AttachmentRepository attachmentRepository;

    @Test
    void 成功生成_xlsx_落盘入两张表返回_downloadUrl(@TempDir Path tmp) {
        when(documentRepository.save(any())).thenReturn("doc-x");
        when(attachmentRepository.saveForEntry(any(), anyString(), anyString(), anyString(),
                any(Long.class), anyString(), anyString())).thenReturn("att-x");

        var executor = newExecutor(tmp);
        var input = newInput(Map.of(
                "fileName", "销售",
                "sheets", List.of(
                        Map.of(
                                "name", "Q1",
                                "headers", List.of("产品", "销售额"),
                                "rows", List.of(List.of("A", 100))
                        )
                ),
                "sessionId", "sess-1"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data()).containsEntry("documentId", "doc-x");
        assertThat((String) result.data().get("fileName")).endsWith(".xlsx");
        assertThat((String) result.data().get("downloadUrl")).isEqualTo("/api/documents/doc-x/download");

        ArgumentCaptor<DocumentRecord> capt = ArgumentCaptor.forClass(DocumentRecord.class);
        verify(documentRepository).save(capt.capture());
        DocumentRecord saved = capt.getValue();
        assertThat(Files.exists(Path.of(saved.filePath()))).isTrue();
        assertThat(saved.origin()).isEqualTo("agent_generated");
        assertThat(saved.mimeType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @Test
    void 缺少_fileName_参数报错(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        ToolResult result = executor.execute(newInput(Map.of(
                "sheets", List.of(),
                "sessionId", "sess-1"
        )));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("fileName");
    }

    @Test
    void 缺少_sheets_参数报错(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        ToolResult result = executor.execute(newInput(Map.of(
                "fileName", "报表",
                "sessionId", "sess-1"
        )));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("sheets");
    }

    @Test
    void 缺少_sessionId_参数报错(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        ToolResult result = executor.execute(newInput(Map.of(
                "fileName", "报表",
                "sheets", List.of()
        )));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("sessionId");
    }

    @Test
    void fileName_含路径分隔符被拒绝(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        ToolResult result = executor.execute(newInput(Map.of(
                "fileName", "../etc/passwd",
                "sheets", List.of(),
                "sessionId", "sess-1"
        )));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("非法字符");
    }

    @Test
    void fileName_自动追加_xlsx_扩展名(@TempDir Path tmp) {
        when(documentRepository.save(any())).thenReturn("doc-y");
        when(attachmentRepository.saveForEntry(any(), anyString(), anyString(), anyString(),
                any(Long.class), anyString(), anyString())).thenReturn("att-y");

        var executor = newExecutor(tmp);
        ToolResult result = executor.execute(newInput(Map.of(
                "fileName", "已带扩展名.xlsx",
                "sheets", List.of(),
                "sessionId", "sess-1"
        )));
        assertThat(result.isSuccess()).isTrue();
        assertThat((String) result.data().get("fileName")).isEqualTo("已带扩展名.xlsx");
    }

    private DocumentCreateXlsxToolExecutor newExecutor(Path storageDir) {
        return new DocumentCreateXlsxToolExecutor(
                new StructuredDataToXlsxGenerator(),
                documentRepository,
                attachmentRepository,
                storageDir.toString());
    }

    private ToolInput newInput(Map<String, Object> params) {
        return new ToolInput("document.create_xlsx", params,
                JsonSchema.of(Map.of("type", "object")), null, null);
    }
}
```

### Step 2：跑测试确认失败

```bash
mvn -q test -Dtest=DocumentCreateXlsxToolExecutor_工具调用测试
```

预期：编译失败。

### Step 3：实现 DocumentCreateXlsxToolExecutor

新建 `src/main/java/com/lifepilot/document/tool/DocumentCreateXlsxToolExecutor.java`：

```java
package com.lifepilot.document.tool;

import com.lifepilot.document.generator.DocumentGenerationException;
import com.lifepilot.document.generator.ExcelGenerator;
import com.lifepilot.document.generator.SheetData;
import com.lifepilot.document.model.DocumentRecord;
import com.lifepilot.document.repository.DocumentRepository;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * document.create_xlsx 工具执行体。
 *
 * <p>流程与 {@link DocumentCreateDocxToolExecutor} 对称：
 * 接收 fileName + sheets + sessionId → ExcelGenerator 生成字节 → 落盘 →
 * 入 documents + message_attachments（entry_id=null）→ 返回 downloadUrl。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class DocumentCreateXlsxToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(DocumentCreateXlsxToolExecutor.class);
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String XLSX_EXT = ".xlsx";
    private static final String DOWNLOAD_URL_TEMPLATE = "/api/documents/%s/download";
    private static final int MAX_FILE_NAME_LENGTH = 240;

    private final ExcelGenerator generator;
    private final DocumentRepository documentRepository;
    private final AttachmentRepository attachmentRepository;
    private final String storageDir;

    public DocumentCreateXlsxToolExecutor(ExcelGenerator generator,
                                          DocumentRepository documentRepository,
                                          AttachmentRepository attachmentRepository,
                                          String storageDir) {
        this.generator = generator;
        this.documentRepository = documentRepository;
        this.attachmentRepository = attachmentRepository;
        this.storageDir = storageDir;
    }

    public ToolResult execute(ToolInput input) {
        String fileName;
        List<?> sheetsRaw;
        String sessionId;
        try {
            fileName = input.getParam("fileName", String.class);
            sheetsRaw = input.getParam("sheets", List.class);
            sessionId = input.getParam("sessionId", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数校验失败：" + e.getMessage());
        }

        // fileName 安全校验（与 docx executor 一致）
        if (fileName.contains("/") || fileName.contains("\\")
                || fileName.contains("\0") || fileName.contains("..")) {
            return ToolResult.error("fileName 含非法字符（禁止路径分隔符、.. 与 NUL）");
        }
        if (fileName.length() > MAX_FILE_NAME_LENGTH) {
            return ToolResult.error("fileName 超长（>" + MAX_FILE_NAME_LENGTH + " 字符）");
        }

        String normalizedFileName = fileName.toLowerCase().endsWith(XLSX_EXT)
                ? fileName : fileName + XLSX_EXT;

        // sheets 反序列化
        List<SheetData> sheets;
        try {
            sheets = parseSheets(sheetsRaw);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("sheets 参数格式非法：" + e.getMessage());
        }

        // 生成字节
        byte[] bytes;
        try {
            bytes = generator.generate(sheets);
        } catch (DocumentGenerationException e) {
            log.warn("xlsx 生成失败：fileName={}, error={}", normalizedFileName, e.getMessage());
            return ToolResult.error("文档生成失败：" + e.getMessage());
        }

        // 落盘
        Path storageRoot = Paths.get(storageDir);
        String documentId = UUID.randomUUID().toString();
        String storedName = documentId + "_" + normalizedFileName;
        Path filePath = storageRoot.resolve(storedName);
        try {
            Files.createDirectories(storageRoot);
            Files.write(filePath, bytes);
        } catch (IOException e) {
            log.error("xlsx 落盘失败：filePath={}", filePath, e);
            return ToolResult.error("文档落盘失败：" + e.getMessage());
        }

        // 入 documents 表
        var record = new DocumentRecord(
                documentId, sessionId, null, normalizedFileName, filePath.toString(),
                bytes.length, XLSX_MIME, DocumentRecord.ORIGIN_AGENT_GENERATED, Instant.now());
        String savedId = documentRepository.save(record);

        // 入 message_attachments 表（entry_id=null，回填由 AgentPersistenceHandler 处理）
        String downloadUrl = String.format(DOWNLOAD_URL_TEMPLATE, savedId);
        attachmentRepository.saveForEntry(
                null, sessionId, normalizedFileName, filePath.toString(),
                (long) bytes.length, XLSX_MIME, downloadUrl);

        log.info("document.create_xlsx 成功：documentId={}, fileName={}, size={}",
                savedId, normalizedFileName, bytes.length);

        var data = new LinkedHashMap<String, Object>();
        data.put("documentId", savedId);
        data.put("fileName", normalizedFileName);
        data.put("fileSize", (long) bytes.length);
        data.put("downloadUrl", downloadUrl);
        return ToolResult.success(Map.copyOf(data));
    }

    /**
     * 把 Map 反序列化为 SheetData。期望格式：
     * <pre>
     * [{ "name": "工作表名", "headers": ["列A", "列B"], "rows": [[val1, val2], ...] }]
     * </pre>
     */
    @SuppressWarnings("unchecked")
    private List<SheetData> parseSheets(List<?> raw) {
        List<SheetData> result = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("sheet 项必须是 object，收到：" + item);
            }
            Object nameObj = map.get("name");
            if (!(nameObj instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException("sheet.name 必须是非空字符串");
            }

            List<String> headers = null;
            Object headersObj = map.get("headers");
            if (headersObj instanceof List<?> headersRaw && !headersRaw.isEmpty()) {
                headers = new ArrayList<>();
                for (Object h : headersRaw) {
                    headers.add(h == null ? "" : h.toString());
                }
            }

            List<List<Object>> rows = new ArrayList<>();
            Object rowsObj = map.get("rows");
            if (rowsObj instanceof List<?> rowsRaw) {
                for (Object rowObj : rowsRaw) {
                    if (!(rowObj instanceof List<?> rowRaw)) {
                        throw new IllegalArgumentException("sheet.rows 项必须是 array");
                    }
                    rows.add(new ArrayList<>((List<Object>) rowRaw));
                }
            }

            result.add(new SheetData(name, headers, rows));
        }
        return result;
    }
}
```

### Step 4：跑测试确认通过

```bash
mvn -q test -Dtest=DocumentCreateXlsxToolExecutor_工具调用测试
```

预期：6 个测试 PASS。

### Step 5：提交

```bash
git add src/main/java/com/lifepilot/document/tool/DocumentCreateXlsxToolExecutor.java \
        src/test/java/com/lifepilot/document/tool/DocumentCreateXlsxToolExecutor_工具调用测试.java
git commit -m "$(cat <<'EOF'
feat(document): DocumentCreateXlsxToolExecutor — document.create_xlsx 执行体

接收 fileName + sheets + sessionId，parseSheets 把 Map 反序列化为 SheetData 列表，
StructuredDataToXlsxGenerator 生成 xlsx → 落盘 → 入 documents + message_attachments
（entry_id=null，由 AgentPersistenceHandler 回填）→ 返回 downloadUrl。
fileName 复用 docx executor 的路径注入防御。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4：DocumentCreatePptxToolExecutor

**目的**：`document.create_pptx` 工具执行体，对称 Task 3 但 pptx 路径。

**Files:**
- Create: `src/main/java/com/lifepilot/document/tool/DocumentCreatePptxToolExecutor.java`
- Test: `src/test/java/com/lifepilot/document/tool/DocumentCreatePptxToolExecutor_工具调用测试.java`

### Step 1：写失败测试

新建 `src/test/java/com/lifepilot/document/tool/DocumentCreatePptxToolExecutor_工具调用测试.java`：

```java
package com.lifepilot.document.tool;

import com.lifepilot.document.generator.OutlineToPptxGenerator;
import com.lifepilot.document.model.DocumentRecord;
import com.lifepilot.document.repository.DocumentRepository;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentCreatePptxToolExecutor_工具调用测试 {

    @Mock DocumentRepository documentRepository;
    @Mock AttachmentRepository attachmentRepository;

    @Test
    void 成功生成_pptx_落盘入两张表返回_downloadUrl(@TempDir Path tmp) {
        when(documentRepository.save(any())).thenReturn("doc-p");
        when(attachmentRepository.saveForEntry(any(), anyString(), anyString(), anyString(),
                any(Long.class), anyString(), anyString())).thenReturn("att-p");

        var executor = newExecutor(tmp);
        var input = newInput(Map.of(
                "fileName", "方案",
                "slides", List.of(
                        Map.of(
                                "title", "首页",
                                "bullets", List.of("要点 A", "要点 B"),
                                "notes", "讲稿"
                        )
                ),
                "sessionId", "sess-1"
        ));

        ToolResult result = executor.execute(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data()).containsEntry("documentId", "doc-p");
        assertThat((String) result.data().get("fileName")).endsWith(".pptx");
        assertThat((String) result.data().get("downloadUrl")).isEqualTo("/api/documents/doc-p/download");

        ArgumentCaptor<DocumentRecord> capt = ArgumentCaptor.forClass(DocumentRecord.class);
        verify(documentRepository).save(capt.capture());
        DocumentRecord saved = capt.getValue();
        assertThat(Files.exists(Path.of(saved.filePath()))).isTrue();
        assertThat(saved.mimeType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.presentationml.presentation");
    }

    @Test
    void 缺少_fileName_参数报错(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        ToolResult result = executor.execute(newInput(Map.of(
                "slides", List.of(),
                "sessionId", "sess-1"
        )));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("fileName");
    }

    @Test
    void 缺少_slides_参数报错(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        ToolResult result = executor.execute(newInput(Map.of(
                "fileName", "方案",
                "sessionId", "sess-1"
        )));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("slides");
    }

    @Test
    void 缺少_sessionId_参数报错(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        ToolResult result = executor.execute(newInput(Map.of(
                "fileName", "方案",
                "slides", List.of()
        )));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("sessionId");
    }

    @Test
    void fileName_含路径分隔符被拒绝(@TempDir Path tmp) {
        var executor = newExecutor(tmp);
        ToolResult result = executor.execute(newInput(Map.of(
                "fileName", "../etc/passwd",
                "slides", List.of(),
                "sessionId", "sess-1"
        )));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("非法字符");
    }

    @Test
    void fileName_自动追加_pptx_扩展名(@TempDir Path tmp) {
        when(documentRepository.save(any())).thenReturn("doc-q");
        when(attachmentRepository.saveForEntry(any(), anyString(), anyString(), anyString(),
                any(Long.class), anyString(), anyString())).thenReturn("att-q");

        var executor = newExecutor(tmp);
        ToolResult result = executor.execute(newInput(Map.of(
                "fileName", "已带扩展名.pptx",
                "slides", List.of(),
                "sessionId", "sess-1"
        )));
        assertThat(result.isSuccess()).isTrue();
        assertThat((String) result.data().get("fileName")).isEqualTo("已带扩展名.pptx");
    }

    private DocumentCreatePptxToolExecutor newExecutor(Path storageDir) {
        return new DocumentCreatePptxToolExecutor(
                new OutlineToPptxGenerator(),
                documentRepository,
                attachmentRepository,
                storageDir.toString());
    }

    private ToolInput newInput(Map<String, Object> params) {
        return new ToolInput("document.create_pptx", params,
                JsonSchema.of(Map.of("type", "object")), null, null);
    }
}
```

### Step 2：跑测试确认失败

```bash
mvn -q test -Dtest=DocumentCreatePptxToolExecutor_工具调用测试
```

### Step 3：实现 DocumentCreatePptxToolExecutor

新建 `src/main/java/com/lifepilot/document/tool/DocumentCreatePptxToolExecutor.java`：

```java
package com.lifepilot.document.tool;

import com.lifepilot.document.generator.DocumentGenerationException;
import com.lifepilot.document.generator.PowerpointGenerator;
import com.lifepilot.document.generator.SlideData;
import com.lifepilot.document.model.DocumentRecord;
import com.lifepilot.document.repository.DocumentRepository;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * document.create_pptx 工具执行体。
 *
 * <p>对称 {@link DocumentCreateDocxToolExecutor} / {@link DocumentCreateXlsxToolExecutor}，
 * 输入 slides JSON 数组，生成 pptx 后落盘 + 入两张表。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class DocumentCreatePptxToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(DocumentCreatePptxToolExecutor.class);
    private static final String PPTX_MIME =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private static final String PPTX_EXT = ".pptx";
    private static final String DOWNLOAD_URL_TEMPLATE = "/api/documents/%s/download";
    private static final int MAX_FILE_NAME_LENGTH = 240;

    private final PowerpointGenerator generator;
    private final DocumentRepository documentRepository;
    private final AttachmentRepository attachmentRepository;
    private final String storageDir;

    public DocumentCreatePptxToolExecutor(PowerpointGenerator generator,
                                          DocumentRepository documentRepository,
                                          AttachmentRepository attachmentRepository,
                                          String storageDir) {
        this.generator = generator;
        this.documentRepository = documentRepository;
        this.attachmentRepository = attachmentRepository;
        this.storageDir = storageDir;
    }

    public ToolResult execute(ToolInput input) {
        String fileName;
        List<?> slidesRaw;
        String sessionId;
        try {
            fileName = input.getParam("fileName", String.class);
            slidesRaw = input.getParam("slides", List.class);
            sessionId = input.getParam("sessionId", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数校验失败：" + e.getMessage());
        }

        if (fileName.contains("/") || fileName.contains("\\")
                || fileName.contains("\0") || fileName.contains("..")) {
            return ToolResult.error("fileName 含非法字符（禁止路径分隔符、.. 与 NUL）");
        }
        if (fileName.length() > MAX_FILE_NAME_LENGTH) {
            return ToolResult.error("fileName 超长（>" + MAX_FILE_NAME_LENGTH + " 字符）");
        }

        String normalizedFileName = fileName.toLowerCase().endsWith(PPTX_EXT)
                ? fileName : fileName + PPTX_EXT;

        List<SlideData> slides;
        try {
            slides = parseSlides(slidesRaw);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("slides 参数格式非法：" + e.getMessage());
        }

        byte[] bytes;
        try {
            bytes = generator.generate(slides);
        } catch (DocumentGenerationException e) {
            log.warn("pptx 生成失败：fileName={}, error={}", normalizedFileName, e.getMessage());
            return ToolResult.error("文档生成失败：" + e.getMessage());
        }

        Path storageRoot = Paths.get(storageDir);
        String documentId = UUID.randomUUID().toString();
        String storedName = documentId + "_" + normalizedFileName;
        Path filePath = storageRoot.resolve(storedName);
        try {
            Files.createDirectories(storageRoot);
            Files.write(filePath, bytes);
        } catch (IOException e) {
            log.error("pptx 落盘失败：filePath={}", filePath, e);
            return ToolResult.error("文档落盘失败：" + e.getMessage());
        }

        var record = new DocumentRecord(
                documentId, sessionId, null, normalizedFileName, filePath.toString(),
                bytes.length, PPTX_MIME, DocumentRecord.ORIGIN_AGENT_GENERATED, Instant.now());
        String savedId = documentRepository.save(record);

        String downloadUrl = String.format(DOWNLOAD_URL_TEMPLATE, savedId);
        attachmentRepository.saveForEntry(
                null, sessionId, normalizedFileName, filePath.toString(),
                (long) bytes.length, PPTX_MIME, downloadUrl);

        log.info("document.create_pptx 成功：documentId={}, fileName={}, size={}",
                savedId, normalizedFileName, bytes.length);

        var data = new LinkedHashMap<String, Object>();
        data.put("documentId", savedId);
        data.put("fileName", normalizedFileName);
        data.put("fileSize", (long) bytes.length);
        data.put("downloadUrl", downloadUrl);
        return ToolResult.success(Map.copyOf(data));
    }

    /**
     * 反序列化期望格式：
     * <pre>
     * [{ "title": "首页", "bullets": ["要点"], "notes": "讲稿" }]
     * </pre>
     */
    @SuppressWarnings("unchecked")
    private List<SlideData> parseSlides(List<?> raw) {
        List<SlideData> result = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("slide 项必须是 object");
            }
            String title = map.get("title") instanceof String t ? t : null;
            String notes = map.get("notes") instanceof String n ? n : null;

            List<String> bullets = new ArrayList<>();
            Object bulletsObj = map.get("bullets");
            if (bulletsObj instanceof List<?> bulletsRaw) {
                for (Object b : bulletsRaw) {
                    bullets.add(b == null ? "" : b.toString());
                }
            }

            result.add(new SlideData(title, bullets, notes));
        }
        return result;
    }
}
```

### Step 4：跑测试确认通过

```bash
mvn -q test -Dtest=DocumentCreatePptxToolExecutor_工具调用测试
```

### Step 5：提交

```bash
git add src/main/java/com/lifepilot/document/tool/DocumentCreatePptxToolExecutor.java \
        src/test/java/com/lifepilot/document/tool/DocumentCreatePptxToolExecutor_工具调用测试.java
git commit -m "$(cat <<'EOF'
feat(document): DocumentCreatePptxToolExecutor — document.create_pptx 执行体

对称 docx/xlsx executor：接收 fileName + slides + sessionId，
parseSlides 把 Map 反序列化为 SlideData，OutlineToPptxGenerator 生成 pptx →
落盘 + 入两张表（entry_id=null）→ 返回 downloadUrl。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5：DocumentToolProvider + AutoConfiguration 扩展 + core-tool-ids

**目的**：把 3 个 executor 装配起来，DocumentToolProvider 返回 3 个 BuiltinTool，AutoConfiguration 扩展 Bean 链，core-tool-ids 加两条 —— 同时修 Phase 2A Task 5 的 `tools.size() != 1` warn（改为按 tool id 选对应 Bean）。

**Files:**
- Modify: `src/main/java/com/lifepilot/document/tool/DocumentToolProvider.java`（构造器 3 参，buildDocumentTools 返回 3 tools）
- Modify: `src/main/java/com/lifepilot/document/config/DocumentAutoConfiguration.java`（新增 2 generator / 2 executor / 2 tool Bean）
- Modify: `src/main/resources/application.yml`（core-tool-ids 追加 2 条）

### Step 1：改 DocumentToolProvider

打开 `src/main/java/com/lifepilot/document/tool/DocumentToolProvider.java`，**整体替换**：

```java
package com.lifepilot.document.tool;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档工具提供者 —— Phase 2B 含 document.create_docx / create_xlsx / create_pptx。
 *
 * <p>Phase 0 曾因工具合并清理，Phase 2A 重建仅含 docx，Phase 2B 扩展至 3 种办公格式。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class DocumentToolProvider {

    private static final List<String> DOCUMENT_TAGS = List.of("infrastructure", "document");

    private final DocumentCreateDocxToolExecutor createDocxExecutor;
    private final DocumentCreateXlsxToolExecutor createXlsxExecutor;
    private final DocumentCreatePptxToolExecutor createPptxExecutor;

    public DocumentToolProvider(DocumentCreateDocxToolExecutor createDocxExecutor,
                                DocumentCreateXlsxToolExecutor createXlsxExecutor,
                                DocumentCreatePptxToolExecutor createPptxExecutor) {
        this.createDocxExecutor = createDocxExecutor;
        this.createXlsxExecutor = createXlsxExecutor;
        this.createPptxExecutor = createPptxExecutor;
    }

    public List<BuiltinTool> buildDocumentTools() {
        return List.of(buildCreateDocxTool(), buildCreateXlsxTool(), buildCreatePptxTool());
    }

    private BuiltinTool buildCreateDocxTool() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("fileName", Map.of("type", "string",
                "description", "产物文件名（不含扩展名会自动追加 .docx，禁止路径分隔符 / \\ 与 ..）"));
        properties.put("markdown", Map.of("type", "string",
                "description", "文档正文的 Markdown 源。支持一到三级标题（# ## ###）、" +
                        "无序列表（- / *）、有序列表（1. / 2.）和普通段落。" +
                        "当前不支持表格、代码块、内联格式与图片。"));

        return BuiltinTool.builder()
                .id("document.create_docx")
                .category(ToolCategory.ACTION)
                .name("生成 Word 文档")
                .description("从 markdown 生成 Word 文档（.docx）并保存到本地 documents 目录。" +
                        "适用于生成周报 / 报告 / 简短方案等不要求复杂排版的文档。" +
                        "产物样式为基础级（标题 + 段落 + 列表，无表格）。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("fileName", "markdown"),
                        "properties", properties
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.pathTrees()
                ))
                .tags(DOCUMENT_TAGS)
                .executor(createDocxExecutor::execute)
                .build();
    }

    private BuiltinTool buildCreateXlsxTool() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("fileName", Map.of("type", "string",
                "description", "产物文件名（不含扩展名会自动追加 .xlsx，禁止路径分隔符 / \\ 与 ..）"));
        properties.put("sheets", Map.of(
                "type", "array",
                "description", "工作表列表。每项含 name（工作表名，必需）、headers（表头列表，可选）、" +
                        "rows（数据行二维数组，每行是单元格值列表，单元格可为 string / number / boolean）。",
                "items", Map.of(
                        "type", "object",
                        "required", List.of("name"),
                        "properties", Map.of(
                                "name", Map.of("type", "string"),
                                "headers", Map.of("type", "array", "items", Map.of("type", "string")),
                                "rows", Map.of("type", "array", "items", Map.of("type", "array"))
                        )
                )
        ));

        return BuiltinTool.builder()
                .id("document.create_xlsx")
                .category(ToolCategory.ACTION)
                .name("生成 Excel 表格")
                .description("从结构化数据生成 Excel 表格（.xlsx）并保存到本地 documents 目录。" +
                        "适用于数据报表 / 台账 / 对账单。不支持样式 / 公式 / 合并单元格。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("fileName", "sheets"),
                        "properties", properties
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.pathTrees()
                ))
                .tags(DOCUMENT_TAGS)
                .executor(createXlsxExecutor::execute)
                .build();
    }

    private BuiltinTool buildCreatePptxTool() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("fileName", Map.of("type", "string",
                "description", "产物文件名（不含扩展名会自动追加 .pptx，禁止路径分隔符 / \\ 与 ..）"));
        properties.put("slides", Map.of(
                "type", "array",
                "description", "幻灯片列表。每项含 title（标题，可选）、bullets（要点列表，必需但可空）、" +
                        "notes（备注，可选）。",
                "items", Map.of(
                        "type", "object",
                        "required", List.of("bullets"),
                        "properties", Map.of(
                                "title", Map.of("type", "string"),
                                "bullets", Map.of("type", "array", "items", Map.of("type", "string")),
                                "notes", Map.of("type", "string")
                        )
                )
        ));

        return BuiltinTool.builder()
                .id("document.create_pptx")
                .category(ToolCategory.ACTION)
                .name("生成 PowerPoint 幻灯片")
                .description("从幻灯片大纲生成 PowerPoint（.pptx）并保存到本地 documents 目录。" +
                        "每张幻灯片含标题 + 要点列表 + 可选备注。不支持主题 / 动画 / 图片 / 图表。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("fileName", "slides"),
                        "properties", properties
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.WRITE_FILE,
                        ToolSchedulingMode.SEQUENTIAL,
                        ToolScopeResolvers.pathTrees()
                ))
                .tags(DOCUMENT_TAGS)
                .executor(createPptxExecutor::execute)
                .build();
    }
}
```

### Step 2：改 DocumentAutoConfiguration

打开 `src/main/java/com/lifepilot/document/config/DocumentAutoConfiguration.java`，**整体替换**：

```java
package com.lifepilot.document.config;

import com.lifepilot.document.generator.DocumentGenerator;
import com.lifepilot.document.generator.ExcelGenerator;
import com.lifepilot.document.generator.MarkdownToDocxGenerator;
import com.lifepilot.document.generator.OutlineToPptxGenerator;
import com.lifepilot.document.generator.PowerpointGenerator;
import com.lifepilot.document.generator.StructuredDataToXlsxGenerator;
import com.lifepilot.document.repository.DocumentRepository;
import com.lifepilot.document.tool.DocumentCreateDocxToolExecutor;
import com.lifepilot.document.tool.DocumentCreatePptxToolExecutor;
import com.lifepilot.document.tool.DocumentCreateXlsxToolExecutor;
import com.lifepilot.document.tool.DocumentToolProvider;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.tool.BuiltinTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 文档工作空间自动配置 —— Phase 2B 装配 3 个 create_* 工具。
 *
 * @author zsg
 * @since 2026-04-20
 */
@AutoConfiguration
@ConditionalOnProperty(name = "lifepilot.document.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DocumentProperties.class)
public class DocumentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DocumentAutoConfiguration.class);

    // ===== 生成器 Bean（无依赖，无条件）=====

    @Bean
    DocumentGenerator markdownToDocxGenerator() {
        return new MarkdownToDocxGenerator();
    }

    @Bean
    ExcelGenerator structuredDataToXlsxGenerator() {
        return new StructuredDataToXlsxGenerator();
    }

    @Bean
    PowerpointGenerator outlineToPptxGenerator() {
        return new OutlineToPptxGenerator();
    }

    // ===== 工具执行体 Bean（依赖 Repository）=====

    @Bean
    @ConditionalOnBean({DocumentRepository.class, AttachmentRepository.class})
    DocumentCreateDocxToolExecutor documentCreateDocxToolExecutor(
            DocumentGenerator markdownToDocxGenerator,
            DocumentRepository documentRepository,
            AttachmentRepository attachmentRepository,
            DocumentProperties properties) {
        return new DocumentCreateDocxToolExecutor(
                markdownToDocxGenerator, documentRepository, attachmentRepository,
                properties.getStorageDir());
    }

    @Bean
    @ConditionalOnBean({DocumentRepository.class, AttachmentRepository.class})
    DocumentCreateXlsxToolExecutor documentCreateXlsxToolExecutor(
            ExcelGenerator structuredDataToXlsxGenerator,
            DocumentRepository documentRepository,
            AttachmentRepository attachmentRepository,
            DocumentProperties properties) {
        return new DocumentCreateXlsxToolExecutor(
                structuredDataToXlsxGenerator, documentRepository, attachmentRepository,
                properties.getStorageDir());
    }

    @Bean
    @ConditionalOnBean({DocumentRepository.class, AttachmentRepository.class})
    DocumentCreatePptxToolExecutor documentCreatePptxToolExecutor(
            PowerpointGenerator outlineToPptxGenerator,
            DocumentRepository documentRepository,
            AttachmentRepository attachmentRepository,
            DocumentProperties properties) {
        return new DocumentCreatePptxToolExecutor(
                outlineToPptxGenerator, documentRepository, attachmentRepository,
                properties.getStorageDir());
    }

    // ===== 工具提供者 + 3 个 BuiltinTool Bean =====

    @Bean
    @ConditionalOnBean({
            DocumentCreateDocxToolExecutor.class,
            DocumentCreateXlsxToolExecutor.class,
            DocumentCreatePptxToolExecutor.class
    })
    DocumentToolProvider documentToolProvider(
            DocumentCreateDocxToolExecutor docxExecutor,
            DocumentCreateXlsxToolExecutor xlsxExecutor,
            DocumentCreatePptxToolExecutor pptxExecutor) {
        return new DocumentToolProvider(docxExecutor, xlsxExecutor, pptxExecutor);
    }

    @Bean
    @ConditionalOnBean(DocumentToolProvider.class)
    BuiltinTool documentCreateDocxTool(DocumentToolProvider provider) {
        return selectTool(provider, "document.create_docx");
    }

    @Bean
    @ConditionalOnBean(DocumentToolProvider.class)
    BuiltinTool documentCreateXlsxTool(DocumentToolProvider provider) {
        return selectTool(provider, "document.create_xlsx");
    }

    @Bean
    @ConditionalOnBean(DocumentToolProvider.class)
    BuiltinTool documentCreatePptxTool(DocumentToolProvider provider) {
        return selectTool(provider, "document.create_pptx");
    }

    /**
     * 按 tool id 从 provider 的工具列表中选出对应 BuiltinTool。
     * 取代 Phase 2A 的 "tools.size() != 1 warn"（Phase 2B 3 tools 会误报）。
     */
    private BuiltinTool selectTool(DocumentToolProvider provider, String toolId) {
        var tool = provider.buildDocumentTools().stream()
                .filter(t -> toolId.equals(t.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "DocumentToolProvider 未返回 " + toolId + " 工具"));
        log.info("已装配 document 工具：id={}", tool.id());
        return tool;
    }
}
```

### Step 3：改 application.yml

打开 `src/main/resources/application.yml`，在 `core-tool-ids` 列表的 `document.create_docx` 下面追加两条：

```yaml
      - document.create_docx # Phase 2A：从 markdown 生成 docx 产物，落盘 + 挂到 assistant 消息
      - document.create_xlsx # Phase 2B：从结构化 sheets 生成 xlsx 产物
      - document.create_pptx # Phase 2B：从幻灯片大纲生成 pptx 产物
```

### Step 4：编译验证

```bash
mvn -q -DskipTests compile
```

预期：BUILD SUCCESS。

### Step 5：跑相关测试确保回归

```bash
mvn -q test -Dtest='Document*,*Attachment*,StructuredData*,OutlineTo*'
```

预期：所有 Phase 2A + 2B 测试 PASS（含 Phase 2A 原有 27 个 + Phase 2B 新增约 24 个）。

### Step 6：提交

```bash
git add src/main/java/com/lifepilot/document/tool/DocumentToolProvider.java \
        src/main/java/com/lifepilot/document/config/DocumentAutoConfiguration.java \
        src/main/resources/application.yml
git commit -m "$(cat <<'EOF'
feat(document): 装配 create_docx / create_xlsx / create_pptx 三工具 — Phase 2B 完成

DocumentToolProvider 构造器扩展为 3 executor；buildDocumentTools 返回 3 tools。
DocumentAutoConfiguration 新增 xlsx / pptx generator Bean + executor Bean +
3 个独立 BuiltinTool Bean（通过 tool id 按需选取，修 Phase 2A Task 5 的
"tools.size() != 1 warn" 在 Phase 2B 误报问题）。core-tool-ids 追加两条。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review 注记

**1. Spec 覆盖**：Phase 2 决策组合（**b1 垂直打穿 + 横向复用 + 基础深度 + 消息气泡产物卡片**）Phase 2B 完成：
- Phase 2A 完成 docx 垂直打穿（pattern 已立）
- Phase 2B 横向复用 xlsx + pptx（本 plan Task 1-5）
- 基础深度：xlsx 支持工作表 + 表头 + 行列，不做样式 / 公式；pptx 支持标题 + 要点 + 备注，不做主题 / 动画
- 消息气泡产物卡片：复用 Phase 2A 的 message_attachments 挂载 + orphan 回填，零前端改动

**2. 占位扫描**：无 TBD/TODO。两处 ⚠️：
- Task 2 Step 5：`ppt.getNotesSlide(slide)` API 可能需要实施时验证
- （其他：Phase 2A 已验证的 Javadoc 标点风格、@author zsg / @since 2026-04-20 全部沿用）

**3. 类型一致性**：
- `SheetData(name, headers, rows)` / `SlideData(title, bullets, notes)` record 字段签名在 Task 1/3 / Task 2/4 一致
- `ExcelGenerator.generate(List<SheetData>)` / `PowerpointGenerator.generate(List<SlideData>)` Task 1-3 / 2-4 一致
- 构造器参数顺序：`(Generator, DocumentRepository, AttachmentRepository, String storageDir)` 对三个 executor 完全对称
- 下载 URL 模板：`/api/documents/%s/download` 三处一致（Phase 2A 建立 → Phase 2B 复用）

**4. 风险点**：
- **Task 2 pptx notes 实现**：XSLFNotes.getShapes() 返回的是默认 placeholders，直接 setText 可能不工作。如果测试 3 "备注文本独立存在" 失败，可能需要 `slide.getNotes()` 然后手动添加 text shape
- **Tool Bean 依赖路径穿越**：3 个 executor Bean 都接 DocumentProperties.getStorageDir()，如果 storageDir 配置不当可能跨目录；已有 fileName 安全校验兜底

---

**完结**。Phase 2B 完成后：
1. Phase 2 统一 e2e 冒烟（按老板 Phase 2 整体做完再冒烟的指示）
2. ship 整个 Phase 0 + 1B + 2A + 2B
