package com.lifepilot.document.patch.docx;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.document.patch.AddTableRowOp;
import com.lifepilot.document.patch.DeleteParagraphOp;
import com.lifepilot.document.patch.InsertParagraphAfterOp;
import com.lifepilot.document.patch.NewParagraph;
import com.lifepilot.document.patch.ReplaceTextOp;
import com.lifepilot.document.patch.SetParagraphStyleOp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 按已应用的 op + 定位结果构造 diff JSON。
 *
 * <p>输出结构见 spec §2.5：</p>
 * <pre>{
 *   documentId, fromVersion, toVersion, summary,
 *   changes: [{patch_id, op, paragraph_index, paragraph_preview, segments, reason}]
 * }</pre>
 *
 * @author zsg
 * @since 2026-04-21
 */
public class DocxDiffBuilder {

    private final ObjectMapper mapper = new ObjectMapper();

    public String build(String documentId, int fromVersion, int toVersion,
                        List<AppliedOp> applied) throws JsonProcessingException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("documentId", documentId);
        root.put("fromVersion", fromVersion);
        root.put("toVersion", toVersion);
        root.put("summary", summarize(applied));

        List<Map<String, Object>> changes = new ArrayList<>();
        for (AppliedOp a : applied) {
            changes.add(changeEntry(a));
        }
        root.put("changes", changes);

        return mapper.writeValueAsString(root);
    }

    /** 一句话摘要，供 session_documents / version 记录里留档。 */
    public String summarize(List<AppliedOp> applied) {
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (AppliedOp a : applied) {
            String k = DocxPatchEngine.opType(a.op());
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

    private Map<String, Object> changeEntry(AppliedOp a) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("patch_id", UUID.randomUUID().toString());
        entry.put("op", DocxPatchEngine.opType(a.op()));
        int pIndex = a.range() == null ? -1 : a.range().paragraphIndex();
        entry.put("paragraph_index", pIndex);

        switch (a.op()) {
            case ReplaceTextOp r -> {
                entry.put("paragraph_preview", r.beforeContext() + "[" + r.target() + "]" + r.afterContext());
                entry.put("segments", List.of(
                        segment("keep", r.beforeContext()),
                        segment("delete", r.target()),
                        segment("insert", r.newText()),
                        segment("keep", r.afterContext())
                ));
                entry.put("reason", r.reason() == null ? "" : r.reason());
            }
            case InsertParagraphAfterOp ins -> {
                StringBuilder inserted = new StringBuilder();
                for (NewParagraph np : ins.newParagraphs()) {
                    if (inserted.length() > 0) inserted.append("\n");
                    inserted.append(np.text());
                }
                entry.put("paragraph_preview", "(在\"" + ins.anchorParagraphText() + "\"后插入)");
                entry.put("segments", List.of(segment("insert", inserted.toString())));
                entry.put("reason", ins.reason() == null ? "" : ins.reason());
            }
            case DeleteParagraphOp del -> {
                entry.put("paragraph_preview", "(删除段落)");
                entry.put("segments", List.of(segment("delete", del.paragraphText())));
                entry.put("reason", del.reason() == null ? "" : del.reason());
            }
            case AddTableRowOp row -> {
                entry.put("paragraph_preview", "(表格新增一行,锚点:" + row.tableAnchorText() + ")");
                entry.put("segments", List.of(segment("insert", String.join(" | ", row.cells()))));
                entry.put("reason", row.reason() == null ? "" : row.reason());
            }
            case SetParagraphStyleOp style -> {
                entry.put("paragraph_preview", "(设置段落样式 → " + style.newStyle() + ")");
                entry.put("segments", List.of(segment("keep", style.anchorText())));
                entry.put("reason", style.reason() == null ? "" : style.reason());
            }
        }
        return entry;
    }

    private static Map<String, Object> segment(String type, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("text", text == null ? "" : text);
        return m;
    }
}
