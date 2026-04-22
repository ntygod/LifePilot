package com.lifepilot.document.version;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.DeltaType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任意两个 docx 版本之间的段落级 diff 计算 —— P1-7。
 *
 * <p>区别于 {@link com.lifepilot.document.patch.docx.DocxDiffBuilder}：
 * DocxDiffBuilder 是"基于已应用的 op 列表"重建 diff JSON，只能在 patch 完成后用；
 * 本类是"对比两个完整文件"，适用于"比较任意 vN ↔ vM" 场景（版本历史对比）。</p>
 *
 * <p>算法：抽段落文本列表 → java-diff-utils LCS → 输出 inserted / deleted / changed 段落。
 * 输出结构与 DocxDiffBuilder 对齐（{@code documentId / fromVersion / toVersion / summary / changes}），
 * 前端 DiffCard 可直接复用渲染。</p>
 *
 * @author zsg
 * @since 2026-04-22
 */
public class DocxVersionComparator {

    private static final ObjectMapper JSON = new ObjectMapper();

    public String compare(String documentId, int fromVersion, int toVersion,
                          Path fromFile, Path toFile) throws IOException {
        List<String> fromParagraphs = readParagraphs(fromFile);
        List<String> toParagraphs = readParagraphs(toFile);

        var patch = DiffUtils.diff(fromParagraphs, toParagraphs);
        List<Map<String, Object>> changes = new ArrayList<>();
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            changes.add(deltaToChange(delta));
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("documentId", documentId);
        root.put("fromVersion", fromVersion);
        root.put("toVersion", toVersion);
        root.put("summary", summarize(changes));
        root.put("changes", changes);

        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IOException("docx 版本对比序列化失败", e);
        }
    }

    private static List<String> readParagraphs(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file);
             XWPFDocument doc = new XWPFDocument(in)) {
            var result = new ArrayList<String>();
            for (var p : doc.getParagraphs()) {
                String text = p.getText() == null ? "" : p.getText();
                result.add(text);
            }
            return result;
        }
    }

    private static Map<String, Object> deltaToChange(AbstractDelta<String> delta) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("patch_id", UUID.randomUUID().toString());
        String op = switch (delta.getType()) {
            case INSERT -> "insert_paragraph";
            case DELETE -> "delete_paragraph";
            case CHANGE -> "change_paragraph";
            default -> "unknown";
        };
        change.put("op", op);
        change.put("paragraph_index", delta.getSource().getPosition());

        List<Map<String, Object>> segments = new ArrayList<>();
        if (delta.getType() == DeltaType.DELETE || delta.getType() == DeltaType.CHANGE) {
            for (String line : delta.getSource().getLines()) {
                segments.add(Map.of("type", "delete", "text", line));
            }
        }
        if (delta.getType() == DeltaType.INSERT || delta.getType() == DeltaType.CHANGE) {
            for (String line : delta.getTarget().getLines()) {
                segments.add(Map.of("type", "insert", "text", line));
            }
        }
        change.put("segments", segments);
        change.put("paragraph_preview", firstLine(delta));
        return change;
    }

    private static String firstLine(AbstractDelta<String> delta) {
        List<String> target = delta.getTarget().getLines();
        if (!target.isEmpty()) {
            return truncate(target.getFirst());
        }
        List<String> source = delta.getSource().getLines();
        if (!source.isEmpty()) {
            return truncate(source.getFirst());
        }
        return "";
    }

    private static String truncate(String text) {
        if (text == null) return "";
        return text.length() > 80 ? text.substring(0, 80) + "…" : text;
    }

    private static String summarize(List<Map<String, Object>> changes) {
        if (changes.isEmpty()) return "两版本内容相同";
        int ins = 0, del = 0, chg = 0;
        for (var c : changes) {
            String op = String.valueOf(c.get("op"));
            switch (op) {
                case "insert_paragraph" -> ins++;
                case "delete_paragraph" -> del++;
                case "change_paragraph" -> chg++;
                default -> { /* unknown */ }
            }
        }
        var sb = new StringBuilder("共 ").append(changes.size()).append(" 处差异");
        if (ins > 0) sb.append("，新增 ").append(ins);
        if (del > 0) sb.append("，删除 ").append(del);
        if (chg > 0) sb.append("，修改 ").append(chg);
        return sb.toString();
    }
}
