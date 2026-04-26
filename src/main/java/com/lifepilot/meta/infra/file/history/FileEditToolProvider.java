package com.lifepilot.meta.infra.file.history;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.dispatch.ActionMetadata;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import com.lifepilot.tool.semantics.ToolScopeResolvers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件编辑历史工具提供者 — 单工具多 action（undo/redo/diff）。
 *
 * <p>统一为 {@code file.history} 工具，{@code action} 参数路由：
 * <ul>
 *   <li>{@code undo} — 撤销文件最近一次编辑，回滚到上一个快照</li>
 *   <li>{@code redo} — 重做上一次被撤销的编辑</li>
 *   <li>{@code diff} — 输出 unified diff（文件两个版本对比）</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-31
 */
public class FileEditToolProvider {

    private static final Logger log = LoggerFactory.getLogger(FileEditToolProvider.class);

    private final FileEditHistory editHistory;

    public FileEditToolProvider(FileEditHistory editHistory) {
        this.editHistory = editHistory;
    }

    /**
     * 构建文件编辑历史工具列表（仅 1 个：{@code file.history}）。
     */
    public List<BuiltinTool> buildEditHistoryTools() {
        return List.of(buildHistoryTool());
    }

    private BuiltinTool buildHistoryTool() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("action", Map.of(
                "type", "string",
                "enum", List.of("undo", "redo", "diff"),
                "description", "操作类型：undo=撤销最近一次编辑回滚快照；redo=重做被撤销的编辑；diff=输出 unified diff（path 可选，留空对比所有变更）。"));
        properties.put("path", Map.of(
                "type", "string",
                "description", "目标文件路径；undo / redo 必填，diff 可选（不传时对比所有变更）。"));

        var writeSemantics = ToolExecutionSemantics.of(
                PermissionActionType.WRITE_FILE,
                ToolSchedulingMode.RESOURCE_SERIALIZED,
                ToolScopeResolvers.pathTrees("path"));
        var readSemantics = ToolExecutionSemantics.of(
                PermissionActionType.READ_FILE,
                ToolSchedulingMode.PARALLEL_SAFE,
                ToolScopeResolvers.pathTrees("path"));

        var actionMetadata = new LinkedHashMap<String, ActionMetadata>();
        actionMetadata.put("undo", new ActionMetadata(RiskLevel.MEDIUM, writeSemantics));
        actionMetadata.put("redo", new ActionMetadata(RiskLevel.MEDIUM, writeSemantics));
        actionMetadata.put("diff", new ActionMetadata(RiskLevel.LOW, readSemantics));

        return BuiltinTool.builder()
                .id("file.history")
                .category(ToolCategory.ACTION)
                .name("文件编辑历史")
                .description("文件编辑历史：undo 撤销最近一次编辑回滚快照；redo 重做被撤销的编辑；diff 输出 unified diff 对比文件版本。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("action"),
                        "properties", properties
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .idempotent(false)
                .executionSemantics(writeSemantics)
                .tags(List.of("撤销", "重做", "回滚", "差异", "对比", "文件", "编辑", "历史",
                        "undo", "redo", "diff", "history", "file"))
                .actionMetadata(actionMetadata)
                .executor(this::dispatch)
                .build();
    }

    private ToolResult dispatch(ToolInput input) {
        String action;
        try {
            action = input.getParam("action", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("缺少必需参数 action：" + e.getMessage());
        }
        return switch (action) {
            case "undo" -> executeUndo(input);
            case "redo" -> executeRedo(input);
            case "diff" -> executeDiff(input);
            default -> ToolResult.error("不支持的 action: " + action + "（允许：undo / redo / diff）");
        };
    }

    private ToolResult executeUndo(ToolInput input) {
        String pathStr;
        try {
            pathStr = input.getParam("path", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("undo 缺少必需参数 path: " + e.getMessage());
        }

        Path filePath = Path.of(pathStr);
        try {
            var snapshotOpt = editHistory.undo(filePath);
            if (snapshotOpt.isEmpty()) {
                return ToolResult.error("没有可撤销的编辑记录: " + pathStr);
            }
            var snapshot = snapshotOpt.get();
            var data = new LinkedHashMap<String, Object>();
            data.put("path", snapshot.path().toString());
            data.put("restoredBytes", snapshot.content().length);
            data.put("undoDepth", editHistory.undoDepth(filePath));
            data.put("redoDepth", editHistory.redoDepth(filePath));
            return ToolResult.success(Map.copyOf(data));
        } catch (IOException e) {
            log.error("文件撤销失败: path={}, error={}", pathStr, e.getMessage(), e);
            return ToolResult.error("文件撤销失败: " + e.getMessage());
        }
    }

    private ToolResult executeRedo(ToolInput input) {
        String pathStr;
        try {
            pathStr = input.getParam("path", String.class);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("redo 缺少必需参数 path: " + e.getMessage());
        }

        Path filePath = Path.of(pathStr);
        try {
            var snapshotOpt = editHistory.redo(filePath);
            if (snapshotOpt.isEmpty()) {
                return ToolResult.error("没有可重做的编辑记录: " + pathStr);
            }
            var snapshot = snapshotOpt.get();
            var data = new LinkedHashMap<String, Object>();
            data.put("path", snapshot.path().toString());
            data.put("restoredBytes", snapshot.content().length);
            data.put("undoDepth", editHistory.undoDepth(filePath));
            data.put("redoDepth", editHistory.redoDepth(filePath));
            return ToolResult.success(Map.copyOf(data));
        } catch (IOException e) {
            log.error("文件重做失败: path={}, error={}", pathStr, e.getMessage(), e);
            return ToolResult.error("文件重做失败: " + e.getMessage());
        }
    }

    private ToolResult executeDiff(ToolInput input) {
        var pathOpt = input.getOptionalParam("path", String.class);
        try {
            String diffOutput;
            if (pathOpt.isPresent()) {
                diffOutput = editHistory.diff(Path.of(pathOpt.get()));
            } else {
                diffOutput = editHistory.diffAll();
            }
            if (diffOutput.isEmpty()) {
                return ToolResult.success(Map.of(
                        "diff", "",
                        "message", "没有检测到文件变更"
                ));
            }
            return ToolResult.success(Map.of("diff", diffOutput));
        } catch (IOException e) {
            log.error("计算文件差异失败: error={}", e.getMessage(), e);
            return ToolResult.error("计算文件差异失败: " + e.getMessage());
        }
    }
}
