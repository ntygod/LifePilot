package com.lifepilot.document.generator;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
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
        // 注：Arrays.asList 允许 null 元素，List.of 不允许 —— 此处必须 asList
        var sheet = SheetData.of("含空",
                List.of(Arrays.asList("有值", null, "有值2")));

        byte[] bytes = generator.generate(List.of(sheet));

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet s = wb.getSheet("含空");
            Row row = s.getRow(0);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("有值");
            // cell.setCellValue("") 建出的是字符串 cell，回读是空字符串而非 null
            Cell c1 = row.getCell(1);
            assertThat(c1).isNotNull();
            assertThat(c1.getStringCellValue()).isEqualTo("");
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

    @Test
    void 工作表名含非法字符与重名自动安全化() throws Exception {
        byte[] bytes = generator.generate(List.of(
                SheetData.of("2026/Q1", List.of(List.of("a"))),   // 含 / 非法
                SheetData.of("2026/Q1", List.of(List.of("b")))    // 同名重复
        ));

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(2);
            // POI 替换非法字符的具体字符由 WorkbookUtil 决定，只断言不崩 + 两个 sheet 名不同
            assertThat(wb.getSheetName(0)).isNotEqualTo(wb.getSheetName(1));
        }
    }

    @Test
    void 数据行中夹杂_null_行不崩() throws Exception {
        // 注：Arrays.asList 允许 null 元素，此处必须 asList —— SheetData compact constructor
        // 保留 null 行语义供生成器 null 行守卫消费
        var sheet = SheetData.of("含空行",
                Arrays.asList(
                        Arrays.asList("第一行"),
                        null,  // null 行
                        Arrays.asList("第三行")
                ));

        byte[] bytes = generator.generate(List.of(sheet));

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet s = wb.getSheet("含空行");
            assertThat(s.getRow(0).getCell(0).getStringCellValue()).isEqualTo("第一行");
            // 第 1 行（null 行）存在但空
            assertThat(s.getRow(2).getCell(0).getStringCellValue()).isEqualTo("第三行");
        }
    }
}
