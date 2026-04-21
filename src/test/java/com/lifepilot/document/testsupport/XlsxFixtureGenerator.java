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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Xlsx 测试 fixture 一次性生成器 —— 手动触发，产出文件提交进 resources/fixtures。
 *
 * <p>两个产出：</p>
 * <ul>
 *   <li>sample-sheet.xlsx：简单双 sheet 数据表（Sheet1 产品清单 + Sheet2 季度销售），
 *       供 4 种 xlsx op 基础测试</li>
 *   <li>sample-with-formulas.xlsx：含表头样式 / 合并单元格 / D 列公式 / C 列数字格式的单 sheet，
 *       专测 update_cell 落在合并区域拒绝路径、公式保留、样式保留</li>
 * </ul>
 *
 * <p>@Disabled 默认跳过，需要刷新 fixture 时人工解开并跑单个用例。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
@Disabled("手动触发生成 fixture — 产出文件会写到 src/test/resources/fixtures/document/")
class XlsxFixtureGenerator {

    private static final Path FIXTURE_DIR = Paths.get("src/test/resources/fixtures/document");

    @Test
    void 生成sample_sheet() throws Exception {
        Files.createDirectories(FIXTURE_DIR);
        try (XSSFWorkbook wb = new XSSFWorkbook();
             FileOutputStream out = new FileOutputStream(
                     FIXTURE_DIR.resolve("sample-sheet.xlsx").toFile())) {

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
        Files.createDirectories(FIXTURE_DIR);
        try (XSSFWorkbook wb = new XSSFWorkbook();
             FileOutputStream out = new FileOutputStream(
                     FIXTURE_DIR.resolve("sample-with-formulas.xlsx").toFile())) {

            Sheet sheet = wb.createSheet("Data");

            // 带样式的表头：粗体 + 浅黄底色 + 下边框
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
            for (int c = 0; c < 4; c++) {
                header.getCell(c).setCellStyle(headerStyle);
            }

            // 合并单元格 B3:C3（第 3 行行号=2，B 列列号=1，C 列列号=2）
            // 用于测试 "update_cell 落在合并区域中间" 的拒绝路径
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
                if (c != null) {
                    c.setCellStyle(decimalStyle);
                }
            }

            wb.write(out);
        }
    }

    private static void writeRow(Sheet sheet, int rowIdx, Object... cells) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) {
            row = sheet.createRow(rowIdx);
        }
        for (int c = 0; c < cells.length; c++) {
            Cell cell = row.createCell(c);
            Object v = cells[c];
            if (v == null) {
                continue;
            }
            if (v instanceof Number n) {
                cell.setCellValue(n.doubleValue());
            } else if (v instanceof Boolean b) {
                cell.setCellValue(b);
            } else {
                cell.setCellValue(v.toString());
            }
        }
    }
}
