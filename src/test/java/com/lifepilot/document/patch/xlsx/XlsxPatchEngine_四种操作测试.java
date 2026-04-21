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
 * XlsxPatchEngine 4 种 op 行为测试。Task 5 先覆盖 update_cell;Task 6 补齐 insert_row /
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
                    new UpdateCellOp("Ghost", "A1", "x", null)
            );
            var r = engine.apply(wb, ops);
            assertThat(r.success()).isFalse();
            assertThat(r.appliedOps()).isEmpty();
        }
    }

    private static XSSFWorkbook loadFixture(String name) throws Exception {
        Path p = Paths.get("src/test/resources/fixtures/document", name);
        try (InputStream in = Files.newInputStream(p)) {
            return new XSSFWorkbook(in);
        }
    }
}
