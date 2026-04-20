package com.lifepilot.knowledge.parser;

import com.lifepilot.knowledge.util.TextUtils;
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
import java.util.Map;
import java.util.Optional;

/**
 * Excel/XLSX 文档解析器 —— 基于 Apache POI XSSF。
 *
 * <p>按"工作表 → 行 → 单元格"顺序遍历,每个工作表作为独立一级标题章节输出,
 * 行内单元格用" | "拼接;空行会被跳过避免污染文本。</p>
 *
 * <p>单元格通过 {@link DataFormatter} 获取显示值:数字按工作表内格式化后的字符串输出、
 * 日期按本地化格式输出,与用户在 Excel 中看到的视图一致。</p>
 *
 * <p>元数据中 {@code pageCount} 字段语义化复用为工作表数量。</p>
 *
 * <p>仅支持 XLSX(Office 2007+),旧版 .xls 不支持。</p>
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

                // 工作表名作为一级标题
                int headingStart = fullText.length();
                fullText.append("# 工作表:").append(sheetName).append("\n");
                int headingEnd = fullText.length();
                elements.add(new DocumentElement.Heading(
                        1, sheetName, headingStart, headingEnd));

                for (Row row : sheet) {
                    int lastCell = row.getLastCellNum();
                    if (lastCell <= 0) {
                        continue;
                    }
                    StringBuilder rowText = new StringBuilder();
                    for (int c = 0; c < lastCell; c++) {
                        Cell cell = row.getCell(c);
                        String cellText = cell == null ? "" : formatter.formatCellValue(cell);
                        if (!rowText.isEmpty()) {
                            rowText.append(" | ");
                        }
                        rowText.append(cellText);
                    }
                    String rowLine = rowText.toString();
                    if (rowLine.isBlank()) {
                        continue;
                    }
                    int rowStart = fullText.length();
                    fullText.append(rowLine).append("\n");
                    elements.add(new DocumentElement.Paragraph(
                            rowLine, rowStart, fullText.length()));
                }

                // 工作表间空行分隔
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
                    Map.of()
            );

            log.info("XLSX 解析完成: file={}, sheets={}, elements={}, wordCount={}",
                    filePath, totalSheets, elements.size(), wordCount);

            return new ParseResult(text, List.copyOf(elements), metadata, List.of());

        } catch (DocumentParseException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new DocumentParseException(
                    "XLSX 文件解码失败: " + filePath + " — " + e.getMessage(),
                    DocumentParseException.Phase.FORMAT_DECODE,
                    filePath.toString(),
                    e);
        }
    }

    @Override
    public DocumentMetadata extractMetadata(Path filePath) {
        validateFormat(filePath);
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
                    Map.of()
            );
        } catch (IOException | RuntimeException e) {
            log.warn("提取 XLSX 元数据失败,返回默认值: file={}, error={}", filePath, e.getMessage());
            return DocumentMetadata.empty();
        }
    }

    /**
     * 验证文件格式,仅接受 .xlsx 扩展名;非法扩展名直接抛异常。
     */
    private void validateFormat(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();
        if (!fileName.endsWith(".xlsx")) {
            throw new DocumentParseException(
                    "仅支持 XLSX 格式,不支持: " + fileName,
                    DocumentParseException.Phase.FORMAT_DECODE,
                    filePath.toString());
        }
    }

    /**
     * 去除文件名扩展名,用作默认标题。
     */
    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
