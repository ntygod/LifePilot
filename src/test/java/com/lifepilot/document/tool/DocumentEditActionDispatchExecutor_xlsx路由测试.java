package com.lifepilot.document.tool;

import com.lifepilot.document.patch.DeleteRowOp;
import com.lifepilot.document.patch.DocumentPatchOperation;
import com.lifepilot.document.patch.DocumentPatchResult;
import com.lifepilot.document.patch.InsertRowOp;
import com.lifepilot.document.patch.SetRangeOp;
import com.lifepilot.document.patch.UpdateCellOp;
import com.lifepilot.document.version.DocumentVersionService;
import com.lifepilot.document.version.SourceRef;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentEditActionDispatchExecutor —— xlsx 四 op 的 parseOneOp 路由与类型保真。
 *
 * <p>覆盖点：</p>
 * <ul>
 *   <li>update_cell: Number / Boolean / 字符串字面量 / 公式 原类型透传</li>
 *   <li>insert_row: before_row 整数解析 + values 透传</li>
 *   <li>delete_row: row 整数解析</li>
 *   <li>set_range: 2D values 形状保持</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-21
 */
@ExtendWith(MockitoExtension.class)
class DocumentEditActionDispatchExecutor_xlsx路由测试 {

    @Mock
    DocumentVersionService versionService;

    private DocumentEditActionDispatchExecutor dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        when(versionService.checkout(anyString(), any(SourceRef.class))).thenReturn("doc-x");
        when(versionService.applyPatch(anyString(), any())).thenReturn(
                DocumentPatchResult.success(1, "{}", "ok"));
        dispatcher = new DocumentEditActionDispatchExecutor(versionService);
    }

    @Test
    @DisplayName("update_cell —— 保留 Number 类型（不转成 String）")
    void parseOneOp_update_cell_保留Number类型() throws Exception {
        Map<String, Object> opMap = Map.of(
                "op", "update_cell", "sheet", "Sheet1", "cell", "B5",
                "new_value", 99, "reason", "缩短");
        ToolResult result = runPatch(opMap);

        assertThat(result.isSuccess()).isTrue();

        List<DocumentPatchOperation> captured = captureApplyPatch();
        assertThat(captured).singleElement()
                .isInstanceOfSatisfying(UpdateCellOp.class, u -> {
                    assertThat(u.sheet()).isEqualTo("Sheet1");
                    assertThat(u.cell()).isEqualTo("B5");
                    assertThat(u.newValue()).isEqualTo(99);
                    assertThat(u.reason()).isEqualTo("缩短");
                });
    }

    @Test
    @DisplayName("update_cell —— Boolean / 字符串 \"100\" / 公式 =SUM 三类型分别保留")
    void parseOneOp_update_cell_保留Boolean和String_公式() throws Exception {
        // 同一批含 Boolean、字符串 "100"（不应误转）、公式 "=SUM"
        Map<String, Object> b = Map.of(
                "op", "update_cell", "sheet", "Sheet1", "cell", "A1",
                "new_value", true);
        Map<String, Object> s = Map.of(
                "op", "update_cell", "sheet", "Sheet1", "cell", "A2",
                "new_value", "100");
        Map<String, Object> f = Map.of(
                "op", "update_cell", "sheet", "Sheet1", "cell", "A3",
                "new_value", "=SUM(A1:A2)");
        runPatch(List.of(b, s, f));

        List<DocumentPatchOperation> ops = captureApplyPatch();
        assertThat(ops).hasSize(3);
        assertThat(((UpdateCellOp) ops.get(0)).newValue()).isEqualTo(true);
        assertThat(((UpdateCellOp) ops.get(1)).newValue()).isEqualTo("100");
        assertThat(((UpdateCellOp) ops.get(2)).newValue()).isEqualTo("=SUM(A1:A2)");
    }

    @Test
    @DisplayName("insert_row —— before_row 整数解析 + values 透传")
    void parseOneOp_insert_row_整数beforeRow() throws Exception {
        Map<String, Object> opMap = Map.of(
                "op", "insert_row", "sheet", "Sheet1", "before_row", 5,
                "values", List.of("a", 1, 2));
        runPatch(opMap);
        List<DocumentPatchOperation> ops = captureApplyPatch();
        assertThat(ops).singleElement()
                .isInstanceOfSatisfying(InsertRowOp.class, r -> {
                    assertThat(r.sheet()).isEqualTo("Sheet1");
                    assertThat(r.beforeRow()).isEqualTo(5);
                    assertThat(r.values()).containsExactly("a", 1, 2);
                });
    }

    @Test
    @DisplayName("delete_row —— row 整数解析")
    void parseOneOp_delete_row() throws Exception {
        Map<String, Object> opMap = Map.of(
                "op", "delete_row", "sheet", "Sheet1", "row", 8);
        runPatch(opMap);
        List<DocumentPatchOperation> ops = captureApplyPatch();
        assertThat(ops).singleElement()
                .isInstanceOfSatisfying(DeleteRowOp.class, d ->
                        assertThat(d.row()).isEqualTo(8));
    }

    @Test
    @DisplayName("set_range —— 2D values 形状保持")
    void parseOneOp_set_range_保持2D形状() throws Exception {
        Map<String, Object> opMap = Map.of(
                "op", "set_range", "sheet", "Sheet1", "range", "B2:D4",
                "values", List.of(
                        List.of(1, 2, 3),
                        List.of(4, 5, 6),
                        List.of(7, 8, 9)));
        runPatch(opMap);
        List<DocumentPatchOperation> ops = captureApplyPatch();
        assertThat(ops).singleElement()
                .isInstanceOfSatisfying(SetRangeOp.class, s -> {
                    assertThat(s.range()).isEqualTo("B2:D4");
                    assertThat(s.values()).hasSize(3);
                    assertThat(s.values().get(0)).hasSize(3);
                });
    }

    // ===== 辅助 =====

    private ToolResult runPatch(Map<String, Object> op) {
        return runPatch(List.of(op));
    }

    private ToolResult runPatch(List<Map<String, Object>> ops) {
        Map<String, Object> input = Map.of(
                "action", "patch",
                "source", Map.of("type", "path", "value", "/tmp/x.xlsx"),
                "operations", ops);
        return dispatcher.execute(newInput(input));
    }

    private ToolInput newInput(Map<String, Object> params) {
        return new ToolInput("document.edit", params,
                JsonSchema.of(Map.of("type", "object")), null,
                Map.of("sessionId", "sess-1"));
    }

    @SuppressWarnings("unchecked")
    private List<DocumentPatchOperation> captureApplyPatch() throws Exception {
        ArgumentCaptor<List<DocumentPatchOperation>> captor = ArgumentCaptor.forClass(List.class);
        verify(versionService).applyPatch(anyString(), captor.capture());
        return captor.getValue();
    }
}
