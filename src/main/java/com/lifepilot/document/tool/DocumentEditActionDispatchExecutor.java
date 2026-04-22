package com.lifepilot.document.tool;

import com.lifepilot.document.model.DocumentVersionRecord;
import com.lifepilot.document.patch.AddTableRowOp;
import com.lifepilot.document.patch.DeleteParagraphOp;
import com.lifepilot.document.patch.DeleteRowOp;
import com.lifepilot.document.patch.DocumentPatchOperation;
import com.lifepilot.document.patch.DocumentPatchResult;
import com.lifepilot.document.patch.InsertParagraphAfterOp;
import com.lifepilot.document.patch.InsertRowOp;
import com.lifepilot.document.patch.NewParagraph;
import com.lifepilot.document.patch.ReplaceTextOp;
import com.lifepilot.document.patch.SetRangeOp;
import com.lifepilot.document.patch.UpdateCellOp;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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

    /** 同一 sessionId+documentId 上 patch 连续失败次数上限；达到上限强制熔断避免 LLM 死循环。 */
    private static final int MAX_PATCH_FAILURES = 3;

    /**
     * patchFailCounter 容量告警阈值：正常场景远用不到这个量级（每个 active session × 每个
     * in-flight patch 才占 1 条）；若日志出现此告警说明有 session 泄漏没清，需要排查。
     */
    private static final int FAIL_COUNTER_WARN_THRESHOLD = 1024;

    /**
     * patch 失败计数器：key=sessionId:documentId，value=连续失败次数。
     * patch 成功 / 熔断 / rollback 时清理对应条目；异常 session 断连导致的漏清由
     * {@link #FAIL_COUNTER_WARN_THRESHOLD} 告警暴露。实例字段而非静态，便于测试与 bean 生命周期对齐。
     */
    private final ConcurrentMap<String, Integer> patchFailCounter = new ConcurrentHashMap<>();

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
            String counterKey = sessionId + ":" + documentId;

            // B10 熔断：达到连续失败上限就不让 LLM 再试了，返回 error 让它停下和用户确认
            int prevFailures = patchFailCounter.getOrDefault(counterKey, 0);
            if (prevFailures >= MAX_PATCH_FAILURES) {
                patchFailCounter.remove(counterKey);
                return ToolResult.error(
                        "同一文档 patch 已连续失败 " + prevFailures + " 次，熔断以防死循环。" +
                        "请停下向用户说明：当前文档的实际文本结构与你的锚点预期不一致，" +
                        "请用户提供更精确的锚点描述或重新贴一次文档；不要再继续自动重试。"
                );
            }

            List<DocumentPatchOperation> ops = parseOperations(input);
            DocumentPatchResult result = versionService.applyPatch(documentId, ops);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("success", result.success());
            data.put("documentId", documentId);
            if (result.success()) {
                patchFailCounter.remove(counterKey);
                data.put("newVersion", result.newVersion());
                data.put("summary", result.patchSummary());
                data.put("diffJson", result.diffJson());
                data.put("downloadUrl", "/api/documents/" + documentId + "/download");
            } else {
                int count = patchFailCounter.merge(counterKey, 1, Integer::sum);
                if (patchFailCounter.size() >= FAIL_COUNTER_WARN_THRESHOLD) {
                    log.warn("patchFailCounter 条目数 {} 达到告警阈值 {}，可能存在未清理的遗留 session",
                            patchFailCounter.size(), FAIL_COUNTER_WARN_THRESHOLD);
                }
                data.put("failedOps", result.failedOps());
                data.put("failureCount", count);
                data.put("maxAllowedFailures", MAX_PATCH_FAILURES);
                // B9：返回当前文档段落预览给 LLM，避免它只凭错误消息瞎猜锚点
                var outline = versionService.getDocxOutline(documentId);
                if (!outline.isEmpty()) {
                    data.put("documentOutline", outline);
                    data.put("hint", "failedOps 无法匹配，请对照 documentOutline 里每段的 preview 重写 locator 的 before_context/target/after_context；" +
                            "若累计失败接近 " + MAX_PATCH_FAILURES + " 次请停下和用户确认。");
                }
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
            if (!"overwrite".equals(target) && !"saveAs".equals(target)) {
                return ToolResult.error("commitTarget 必须是 overwrite 或 saveAs");
            }
            // 硬化约束：commit 会写入用户本地文件系统（overwrite 覆盖原文件 / saveAs 落到新路径），
            // 是不可逆的破坏性操作。要求 LLM 必须先与用户对齐并取得明确同意后，才能带上精确等于
            // commitTarget 值的 userConfirmation 字段。这个字段本身不构成"绝对"硬控（LLM 依然
            // 能机械地塞值），但强制 LLM 的思考链里插入"先 reply 用户求确认"这一步，避免 patch
            // 成功后直接链式调用 commit 导致用户来不及审查。
            // 注意：用 getOptionalParam，字段缺失不抛"缺少必需参数"异常，由本方法给更精准的拒绝文案。
            String confirmation = input.getOptionalParam("userConfirmation", String.class).orElse(null);
            if (confirmation == null || !confirmation.equals(target)) {
                return ToolResult.error(
                        "commit 是写用户本地文件的破坏性操作（无法自动回滚），必须先取得用户明确同意。" +
                        "请停下向用户说明：准备 commit 的 target=" + target +
                        "（" + (target.equals("overwrite") ? "覆盖原文件，系统会自动生成 .bak 备份" : "另存到 saveAsPath") + "），" +
                        "让用户回复'确认/是的/可以'等同意后，再调用本工具并带上 userConfirmation=\"" + target + "\"。");
            }
            DocumentVersionService.CommitResult result;
            if ("overwrite".equals(target)) {
                result = versionService.commitOverwrite(documentId);
            } else {
                String saveAsPath = input.getParam("saveAsPath", String.class);
                result = versionService.commitSaveAs(documentId, saveAsPath);
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
            // rollback 本质上是一次"成功恢复"：之前 patch 连续失败累积的计数已不再有语义价值，
            // 清掉避免下一次 patch 继承旧计数被提前熔断（document 通常只属于 1 个 session，
            // 用 endsWith 做按 documentId 精确清理，不依赖 sessionId 入参）。
            patchFailCounter.keySet().removeIf(k -> k.endsWith(":" + documentId));
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
            case "update_cell" -> new UpdateCellOp(
                    String.valueOf(m.get("sheet")),
                    String.valueOf(m.get("cell")),
                    m.get("new_value"),  // 保留原始类型（Number / Boolean / String / null）
                    reason);
            case "insert_row" -> {
                int beforeRow = m.get("before_row") instanceof Number n ? n.intValue()
                        : Integer.parseInt(String.valueOf(m.get("before_row")));
                List<Object> values = (List<Object>) m.getOrDefault("values", List.of());
                yield new InsertRowOp(String.valueOf(m.get("sheet")), beforeRow, values, reason);
            }
            case "delete_row" -> {
                int row = m.get("row") instanceof Number n ? n.intValue()
                        : Integer.parseInt(String.valueOf(m.get("row")));
                yield new DeleteRowOp(String.valueOf(m.get("sheet")), row, reason);
            }
            case "set_range" -> {
                List<List<Object>> values = (List<List<Object>>) m.get("values");
                if (values == null) {
                    throw new IllegalArgumentException("set_range values 不能为空");
                }
                yield new SetRangeOp(
                        String.valueOf(m.get("sheet")),
                        String.valueOf(m.get("range")),
                        values,
                        reason);
            }
            default -> throw new IllegalArgumentException("未知 op：" + op);
        };
    }
}
