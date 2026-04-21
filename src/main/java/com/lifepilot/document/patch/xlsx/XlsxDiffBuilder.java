package com.lifepilot.document.patch.xlsx;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.document.patch.DeleteRowOp;
import com.lifepilot.document.patch.InsertRowOp;
import com.lifepilot.document.patch.SetRangeOp;
import com.lifepilot.document.patch.UpdateCellOp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * xlsx diff JSON 构造器 —— 对齐 {@link com.lifepilot.document.patch.docx.DocxDiffBuilder} 的输出契约,额外顶层 {@code mime=xlsx}。
 *
 * <p>每个 op 一条 change;{@code update_cell} 双段（delete before / insert new）;其它
 * op 单段（insert 或 delete）。字段映射见 spec §2.4。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public class XlsxDiffBuilder {

    private final ObjectMapper mapper = new ObjectMapper();

    public String build(String documentId, int fromVersion, int toVersion,
                        List<AppliedXlsxOp> applied) throws JsonProcessingException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("documentId", documentId);
        root.put("fromVersion", fromVersion);
        root.put("toVersion", toVersion);
        root.put("mime", "xlsx");
        root.put("summary", summarize(applied));

        List<Map<String, Object>> changes = new ArrayList<>();
        for (AppliedXlsxOp a : applied) {
            changes.add(changeEntry(a));
        }
        root.put("changes", changes);

        return mapper.writeValueAsString(root);
    }

    /** 一句话摘要,供 session_documents / version 记录里留档。 */
    public String summarize(List<AppliedXlsxOp> applied) {
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (AppliedXlsxOp a : applied) {
            String k = XlsxPatchEngine.opType(a.op());
            counter.merge(k, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder("共 ").append(applied.size()).append(" 处修改");
        if (!counter.isEmpty()) {
            sb.append("(");
            boolean first = true;
            for (var e : counter.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(e.getKey()).append(" x").append(e.getValue());
                first = false;
            }
            sb.append(")");
        }
        return sb.toString();
    }

    private Map<String, Object> changeEntry(AppliedXlsxOp a) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("patch_id", UUID.randomUUID().toString());
        entry.put("op", XlsxPatchEngine.opType(a.op()));

        switch (a.op()) {
            case UpdateCellOp u -> {
                entry.put("sheet", u.sheet());
                entry.put("cell", u.cell().toUpperCase());
                String afterPreview = valuePreview(u.newValue());
                entry.put("segments", List.of(
                        segment("delete", a.beforeSnapshot()),
                        segment("insert", afterPreview)
                ));
                entry.put("reason", u.reason() == null ? "" : u.reason());
            }
            case InsertRowOp ins -> {
                entry.put("sheet", ins.sheet());
                entry.put("row", ins.beforeRow());
                String preview = valueRowPreview(ins.values());
                entry.put("segments", List.of(segment("insert", preview)));
                entry.put("reason", ins.reason() == null ? "" : ins.reason());
            }
            case DeleteRowOp del -> {
                entry.put("sheet", del.sheet());
                entry.put("row", del.row());
                entry.put("segments", List.of(segment("delete", a.beforeSnapshot())));
                entry.put("reason", del.reason() == null ? "" : del.reason());
            }
            case SetRangeOp sr -> {
                entry.put("sheet", sr.sheet());
                entry.put("range", sr.range().toUpperCase());
                entry.put("rows", a.rowsAffected());
                entry.put("cols", a.colsAffected());
                entry.put("segments", List.of(segment("insert",
                        a.rowsAffected() + "x" + a.colsAffected() + " 批量填充（预览略）")));
                entry.put("reason", sr.reason() == null ? "" : sr.reason());
            }
        }
        return entry;
    }

    /** 把 cell 值（可能是 null / 数字 / 布尔 / 字符串 / 公式）转人类可读预览。 */
    static String valuePreview(Object value) {
        if (value == null) return "(空)";
        if (value instanceof String s && s.startsWith("=")) return "ƒx " + s;
        return value.toString();
    }

    /** 一行 values 用 " | " 拼预览。 */
    static String valueRowPreview(List<Object> values) {
        StringBuilder sb = new StringBuilder();
        for (Object v : values) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(valuePreview(v));
        }
        return sb.toString();
    }

    private static Map<String, Object> segment(String type, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("text", text == null ? "" : text);
        return m;
    }
}
