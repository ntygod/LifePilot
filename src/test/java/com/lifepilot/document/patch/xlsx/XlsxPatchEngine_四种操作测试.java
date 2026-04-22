package com.lifepilot.document.patch.xlsx;

import com.lifepilot.document.patch.DeleteRowOp;
import com.lifepilot.document.patch.InsertRowOp;
import com.lifepilot.document.patch.SetRangeOp;
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
import java.util.Arrays;
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

    // ===== Task 6 reviewer 补齐的 update_cell 漏测 =====

    @Test
    void update_cell_布尔类型写成boolean_cell() throws Exception {
        try (XSSFWorkbook wb = loadFixture("sample-sheet.xlsx")) {
            var r = engine.apply(wb, List.of(new UpdateCellOp("Sheet1", "C2", true, null)));
            assertThat(r.success()).isTrue();
            Cell c = wb.getSheet("Sheet1").getRow(1).getCell(2);
            assertThat(c.getCellType()).isEqualTo(CellType.BOOLEAN);
            assertThat(c.getBooleanCellValue()).isTrue();
        }
    }

    @Test
    void update_cell_清空公式cell_从FORMULA变BLANK() throws Exception {
        try (XSSFWorkbook wb = loadFixture("sample-with-formulas.xlsx")) {
            // D2 原本是 FORMULA =B2+C2；清空后应变 BLANK
            assertThat(wb.getSheet("Data").getRow(1).getCell(3).getCellType()).isEqualTo(CellType.FORMULA);
            var r = engine.apply(wb, List.of(new UpdateCellOp("Data", "D2", null, null)));
            assertThat(r.success()).isTrue();
            Cell c = wb.getSheet("Data").getRow(1).getCell(3);
            assertThat(c.getCellType()).isEqualTo(CellType.BLANK);
        }
    }

    // ===== insert_row =====

    @Test
    void insert_row_中间插入_下方公式自动刷新() throws Exception {
        try (XSSFWorkbook wb = loadFixture("sample-with-formulas.xlsx")) {
            // 原 D5 = B5+C5;在第 5 行（索引 4）前插入 → 原 D5 变 D6,公式应变为 =B6+C6
            // 用 Arrays.asList 而非 List.of,因需要承载 null 表示新行 D 列不填
            var r = engine.apply(wb, List.of(
                    new InsertRowOp("Data", 5, Arrays.asList("新项目", 10, 20, null), null)));
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

    // ===== delete_row =====

    @Test
    void delete_row_简单删除() throws Exception {
        try (XSSFWorkbook wb = loadFixture("sample-sheet.xlsx")) {
            var r = engine.apply(wb, List.of(new DeleteRowOp("Sheet1", 2, null)));
            assertThat(r.success()).isTrue();
            // fixture Row 2=钢笔 / Row 3=笔记本;删除钢笔后笔记本上移到 Row 2
            Cell c = wb.getSheet("Sheet1").getRow(1).getCell(0);
            assertThat(c.getStringCellValue()).isEqualTo("笔记本");
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

    // ===== set_range =====

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
            // 合并 B3:C3;让 range 与其相交
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

    private static XSSFWorkbook loadFixture(String name) throws Exception {
        Path p = Paths.get("src/test/resources/fixtures/document", name);
        try (InputStream in = Files.newInputStream(p)) {
            return new XSSFWorkbook(in);
        }
    }
}
