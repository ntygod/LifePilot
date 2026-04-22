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
 * XlsxDiffBuilder 输出结构测试 —— 4 种 op 各覆盖一条 change,断言顶层 mime 和字段完整。
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
