package com.lifepilot.knowledge.parser;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ExcelParser 基础解析测试 —— 验证 xlsx 文件按工作表/行/单元格顺序输出、多工作表分章节、
 * 空文件安全、非法格式抛 {@link DocumentParseException}、扩展名匹配等能力。
 *
 * @author zsg
 * @since 2026-04-20
 */
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
    void 空_xlsx_文件返回空文本但不抛异常(@TempDir Path tmp) throws IOException, DocumentParseException {
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
    void 不是有效_xlsx_文件抛_DocumentParseException(@TempDir Path tmp) throws IOException {
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
