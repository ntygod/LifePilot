package com.lifepilot.document.tool;

import com.lifepilot.document.model.DocumentVersionRecord;
import com.lifepilot.document.patch.AddTableRowOp;
import com.lifepilot.document.patch.DeleteParagraphOp;
import com.lifepilot.document.patch.DocumentPatchOperation;
import com.lifepilot.document.patch.DocumentPatchResult;
import com.lifepilot.document.patch.InsertParagraphAfterOp;
import com.lifepilot.document.patch.NewParagraph;
import com.lifepilot.document.patch.ReplaceTextOp;
import com.lifepilot.document.version.DocumentVersionService;
import com.lifepilot.document.version.SourceRef;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.dispatch.ActionDispatchExecutor;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * document.edit action 路由 —— 5 个 action 分发到 {@link DocumentVersionService}。
 *
 * <p>对齐 Phase 2B {@link DocumentCreateActionDispatchExecutor} 模式,
 * LLM 看到单一 {@code document.edit} 工具 + action 枚举 + 对应参数, 减少 schema 噪声。</p>
 *
 * <p>action 语义见 Phase 3 spec §2.1:
 * <ul>
 *   <li>{@code patch} —— LOW / WRITE_FILE: 基于文本锚点 op 列表生成新版本</li>
 *   <li>{@code diff} —— LOW / READ_FILE: 取指定版本缓存的 diff JSON</li>
 *   <li>{@code commit} —— MEDIUM / WRITE_FILE: 落盘到 sourcePath (overwrite) 或 saveAsPath</li>
 *   <li>{@code rollback} —— LOW / WRITE_FILE: 以目标版本内容派生新版本</li>
 *   <li>{@code list_versions} —— LOW / READ_FILE: 列出历史版本</li>
 * </ul>
 * 所有 action 返回结构化 Map, 前端或 LLM 按需取字段。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
public class DocumentEditActionDispatchExecutor extends ActionDispatchExecutor {

    private static final Logger log = LoggerFactory.getLogger(DocumentEditActionDispatchExecutor.class);

    private final DocumentVersionService versionService;

    public DocumentEditActionDispatchExecutor(DocumentVersionService versionService) {
        this.versionService = versionService;

        register("patch", RiskLevel.LOW, writeFile(), this::patch);
        register("diff", RiskLevel.LOW, readFile(), this::diff);
        register("commit", RiskLevel.MEDIUM, writeFile(), this::commit);
        register("rollback", RiskLevel.LOW, writeFile(), this::rollback);
        register("list_versions", RiskLevel.LOW, readFile(), this::listVersions);
    }

    private ToolExecutionSemantics writeFile() {
        return ToolExecutionSemantics.of(
                PermissionActionType.WRITE_FILE,
                ToolSchedulingMode.SEQUENTIAL,
                ToolScopeResolvers.pathTrees());
    }

    private ToolExecutionSemantics readFile() {
        return ToolExecutionSemantics.of(
                PermissionActionType.READ_FILE,
                ToolSchedulingMode.SEQUENTIAL,
                ToolScopeResolvers.pathTrees());
    }

    // ===== action handlers =====

    ToolResult patch(ToolInput input) {
        try {
            String sessionId = requireSession(input);
            SourceRef source = parseSource(input);
            String documentId = versionService.checkout(sessionId, source);
            List<DocumentPatchOperation> ops = parseOperations(input);

            DocumentPatchResult result = versionService.applyPatch(documentId, ops);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("success", result.success());
            data.put("documentId", documentId);
            if (result.success()) {
                data.put("newVersion", result.newVersion());
                data.put("summary", result.patchSummary());
                data.put("diffJson", result.diffJson());
                data.put("downloadUrl", "/api/documents/" + documentId + "/download");
            } else {
                data.put("failedOps", result.failedOps());
            }
            return ToolResult.success(data);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误：" + e.getMessage());
        } catch (Exception e) {
            log.warn("document.edit patch 失败", e);
            return ToolResult.error("执行失败：" + e.getMessage());
        }
    }

    ToolResult diff(ToolInput input) {
        try {
            String documentId = input.getParam("documentId", String.class);
            int from = input.getParam("from", Integer.class);
            int to = input.getParam("to", Integer.class);
            // 简化实现: 只取 "to" 那次 patch 缓存的 diff, 不支持任意双向对比
            var versions = versionService.listVersions(documentId);
            var toVer = versions.stream()
                    .filter(v -> v.versionNo() == to)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("版本不存在：" + to));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("documentId", documentId);
            data.put("from", from);
            data.put("to", to);
            data.put("diffJson", toVer.diffJson() == null ? "" : toVer.diffJson());
            return ToolResult.success(data);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误：" + e.getMessage());
        }
    }

    ToolResult commit(ToolInput input) {
        try {
            String documentId = input.getParam("documentId", String.class);
            String target = input.getParam("commitTarget", String.class);
            DocumentVersionService.CommitResult result;
            if ("overwrite".equals(target)) {
                result = versionService.commitOverwrite(documentId);
            } else if ("saveAs".equals(target)) {
                String saveAsPath = input.getParam("saveAsPath", String.class);
                result = versionService.commitSaveAs(documentId, saveAsPath);
            } else {
                return ToolResult.error("commitTarget 必须是 overwrite 或 saveAs");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("committedPath", result.committedPath());
            if (result.backupPath() != null) {
                data.put("backupPath", result.backupPath());
            }
            return ToolResult.success(data);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误：" + e.getMessage());
        } catch (Exception e) {
            log.warn("document.edit commit 失败", e);
            return ToolResult.error("执行失败：" + e.getMessage());
        }
    }

    ToolResult rollback(ToolInput input) {
        try {
            String documentId = input.getParam("documentId", String.class);
            int version = input.getParam("version", Integer.class);
            DocumentPatchResult result = versionService.rollback(documentId, version);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("success", result.success());
            data.put("newVersion", result.newVersion());
            data.put("summary", result.patchSummary());
            return ToolResult.success(data);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误：" + e.getMessage());
        } catch (Exception e) {
            log.warn("document.edit rollback 失败", e);
            return ToolResult.error("执行失败：" + e.getMessage());
        }
    }

    ToolResult listVersions(ToolInput input) {
        try {
            String documentId = input.getParam("documentId", String.class);
            List<DocumentVersionRecord> versions = versionService.listVersions(documentId);
            List<Map<String, Object>> list = new ArrayList<>();
            for (var v : versions) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("versionNo", v.versionNo());
                m.put("source", v.source());
                m.put("summary", v.patchSummary() == null ? "" : v.patchSummary());
                m.put("createdAt", v.createdAt().toString());
                list.add(m);
            }
            return ToolResult.success(Map.of("versions", list));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误：" + e.getMessage());
        }
    }

    // ===== 参数解析 =====

    private String requireSession(ToolInput input) {
        return input.getContextValue("sessionId", String.class)
                .orElseThrow(() -> new IllegalArgumentException("缺少执行上下文 sessionId"));
    }

    @SuppressWarnings("unchecked")
    private SourceRef parseSource(ToolInput input) {
        Map<String, Object> src = input.getParam("source", Map.class);
        if (src == null) {
            throw new IllegalArgumentException("source 不能为空");
        }
        String type = String.valueOf(src.get("type"));
        return switch (type) {
            case "path" -> new SourceRef.PathSource(String.valueOf(src.get("value")));
            case "attachment" -> new SourceRef.AttachmentSource(String.valueOf(src.get("id")));
            case "document" -> new SourceRef.DocumentSource(String.valueOf(src.get("id")));
            default -> throw new IllegalArgumentException("source.type 必须是 path/attachment/document");
        };
    }

    @SuppressWarnings("unchecked")
    private List<DocumentPatchOperation> parseOperations(ToolInput input) {
        List<Map<String, Object>> raw = input.getParam("operations", List.class);
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("operations 不能为空");
        }
        List<DocumentPatchOperation> ops = new ArrayList<>();
        for (Map<String, Object> m : raw) {
            ops.add(parseOneOp(m));
        }
        return ops;
    }

    @SuppressWarnings("unchecked")
    private DocumentPatchOperation parseOneOp(Map<String, Object> m) {
        String op = String.valueOf(m.get("op"));
        String reason = m.get("reason") == null ? null : String.valueOf(m.get("reason"));
        return switch (op) {
            case "replace_text" -> new ReplaceTextOp(
                    String.valueOf(m.getOrDefault("before_context", "")),
                    String.valueOf(m.get("target")),
                    String.valueOf(m.getOrDefault("after_context", "")),
                    String.valueOf(m.getOrDefault("new_text", "")),
                    reason);
            case "insert_paragraph_after" -> {
                List<Map<String, Object>> newParas =
                        (List<Map<String, Object>>) m.get("new_paragraphs");
                if (newParas == null || newParas.isEmpty()) {
                    throw new IllegalArgumentException("new_paragraphs 至少一项");
                }
                List<NewParagraph> ps = new ArrayList<>();
                for (Map<String, Object> np : newParas) {
                    ps.add(new NewParagraph(
                            String.valueOf(np.getOrDefault("text", "")),
                            np.get("style") == null ? null : String.valueOf(np.get("style"))));
                }
                yield new InsertParagraphAfterOp(
                        String.valueOf(m.get("anchor_paragraph_text")), ps, reason);
            }
            case "delete_paragraph" -> new DeleteParagraphOp(
                    String.valueOf(m.get("paragraph_text")), reason);
            case "add_table_row" -> {
                List<Object> rawCells = (List<Object>) m.get("cells");
                if (rawCells == null) {
                    throw new IllegalArgumentException("cells 不能为空");
                }
                List<String> cells = new ArrayList<>();
                for (Object c : rawCells) {
                    cells.add(c == null ? "" : String.valueOf(c));
                }
                yield new AddTableRowOp(
                        String.valueOf(m.get("table_anchor_text")),
                        String.valueOf(m.get("position")),
                        cells, reason);
            }
            default -> throw new IllegalArgumentException("未知 op：" + op);
        };
    }
}
